"""
iTantra — ONNX INT8 Quantization Script
========================================
Applies INT8 dynamic quantization to the ONNX STT and TTS models,
reducing STT from ~177MB → ~45MB each.

Two modes:
  Normal mode:  reads from models/onnx/ → writes to models/quantized/ → copies to assets/
  Assets mode:  reads directly from app/src/main/assets/ (for models already placed there)

Requirements:
    pip install onnxruntime onnx

Usage:
    python scripts/quantize_models.py --lang hi
    python scripts/quantize_models.py --all
    python scripts/quantize_models.py --from-assets --lang hi   # quantize already-placed assets
    python scripts/quantize_models.py --from-assets --all       # quantize all assets in-place
    python scripts/quantize_models.py --verify hi               # Verify quantized model output
"""

import argparse
import os
import sys
import time
from pathlib import Path

# Ensure UTF-8 output on Windows console
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

try:
    from onnxruntime.quantization import (
        quantize_dynamic,
        QuantType,
    )
    import onnxruntime as ort
    import numpy as np
except ImportError:
    raise SystemExit("Run: pip install onnxruntime onnx numpy")

# Op types that CAN be safely quantized on ORT Android.
# Conv is intentionally EXCLUDED:
#   QOperator format → ConvInteger node → ORT_NOT_IMPLEMENTED crash on Android.
#   QDQ format wraps Conv with QuantizeLinear/DequantizeLinear which ORT handles,
#   but this only works with static calibration, not dynamic quantization.
# Solution: quantize only MatMul/Gemm (the large weight matrices) dynamically.
# This gives 30-40% size reduction without any unsupported-op risk.
SAFE_ORT_ANDROID_OP_TYPES = ["MatMul", "Gemm"]

MODELS_DIR = Path(__file__).parent.parent / "models"
ONNX_DIR = MODELS_DIR / "onnx"
QUANT_DIR = MODELS_DIR / "quantized"
# Final destination for Android assets
ASSETS_DIR = Path(__file__).parent.parent / "app" / "src" / "main" / "assets"

LANGUAGES = {
    "hi": "Hindi", "gu": "Gujarati", "mr": "Marathi", "kn": "Kannada",
    "ml": "Malayalam", "ta": "Tamil", "te": "Telugu", "or": "Odia",
    "bn": "Bengali", "en": "English",
}


def quantize(model_path: Path, out_path: Path, label: str):
    if not model_path.exists():
        print(f"  ✗ Not found: {model_path}")
        return False
    if out_path.exists():
        mb = out_path.stat().st_size / 1_048_576
        print(f"  Already quantized: {out_path} ({mb:.1f}MB)")
        return True

    before_mb = model_path.stat().st_size / 1_048_576
    print(f"  Quantizing {label} ({before_mb:.1f}MB) → INT8…")
    t0 = time.time()

    quantize_dynamic(
        model_input=str(model_path),
        model_output=str(out_path),
        weight_type=QuantType.QInt8,
        # KEY: restrict to MatMul and Gemm ONLY.
        # By excluding Conv, no ConvInteger nodes are generated.
        # ConvInteger is the op that causes ORT_NOT_IMPLEMENTED on Android.
        # MatMul/Gemm cover the large attention weight matrices and give
        # meaningful size reduction without any Android compatibility risk.
        op_types_to_quantize=SAFE_ORT_ANDROID_OP_TYPES,
        per_channel=False,   # per-tensor: required for mobile ORT correctness
        reduce_range=False,
    )

    elapsed = time.time() - t0
    after_mb = out_path.stat().st_size / 1_048_576
    ratio = before_mb / after_mb if after_mb > 0 else 0
    print(f"  ✓ Done in {elapsed:.1f}s: {before_mb:.1f}MB → {after_mb:.1f}MB ({ratio:.1f}× smaller)")
    return True


def copy_to_assets(src: Path, name: str):
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)
    dst = ASSETS_DIR / name
    if dst.exists():
        existing_mb = dst.stat().st_size / 1_048_576
        src_mb = src.stat().st_size / 1_048_576
        if abs(existing_mb - src_mb) < 0.1:
            print(f"  Asset already present (same size): {name} ({existing_mb:.1f}MB)")
            return
        # Sizes differ — overwrite with quantized version
        print(f"  Replacing {name}: {existing_mb:.1f}MB → {src_mb:.1f}MB (quantized)")
    import shutil
    shutil.copy2(src, dst)
    mb = dst.stat().st_size / 1_048_576
    print(f"  ✓ Copied to assets: {name} ({mb:.1f}MB)")


def copy_vad_to_assets():
    """Copy the (already-ONNX) Silero VAD model directly to assets."""
    vad_src = MODELS_DIR / "vad_silero.onnx"
    if not vad_src.exists():
        # VAD may already be in assets — check there
        vad_asset = ASSETS_DIR / "vad_silero.onnx"
        if vad_asset.exists():
            print(f"  VAD already in assets ({vad_asset.stat().st_size / 1_048_576:.1f}MB)")
            return
        print("  ✗ vad_silero.onnx not found. Run download_models.py first.")
        return
    copy_to_assets(vad_src, "vad_silero.onnx")


def quantize_from_assets(lang_code: str):
    """
    Quantize models that are already placed in assets/ directly.
    Reads stt_{lang}.onnx from assets, writes quantized version back to assets.
    TTS models (~60MB) are skipped as they are already close to final size.
    """
    stt_src = ASSETS_DIR / f"stt_{lang_code}.onnx"
    tts_src = ASSETS_DIR / f"tts_{lang_code}.onnx"

    QUANT_DIR.mkdir(parents=True, exist_ok=True)

    # STT (IndicConformer): dynamic INT8 is NOT applicable
    # -------------------------------------------------------
    # IndicConformer uses depthwise Conv + fused attention — it has zero
    # standalone MatMul/Gemm nodes. Dynamic INT8 of MatMul/Gemm nodes gives
    # 0% size reduction (verified: 177MB in -> 177MB out).
    # Dynamic INT8 of Conv nodes produces ConvInteger ops which are NOT
    # registered in ORT Android's CPU provider (ORT_NOT_IMPLEMENTED crash).
    # Decision: ship FP32 model as-is. ORT Android 1.19.2 with 4 threads
    # achieves acceptable latency for walkie-talkie use.
    if stt_src.exists():
        stt_mb = stt_src.stat().st_size / 1_048_576
        print(f"  STT ({stt_mb:.1f}MB): no quantization applied (see comment above).")
        print(f"  Using FP32 model — safe and correct on ORT Android.")
    else:
        print(f"  ✗ STT asset not found: {stt_src}")

    # TTS: Piper VITS models are typically 60-65MB at full precision.
    # They do not quantize well with dynamic INT8 (audio quality degrades).
    # Skip quantization; 60MB is the expected production size.
    if tts_src.exists():
        tts_mb = tts_src.stat().st_size / 1_048_576
        if tts_mb > 80:
            tts_q = QUANT_DIR / f"tts_{lang_code}.onnx"
            print(f"  TTS ({tts_mb:.1f}MB) is unusually large — quantizing:")
            if quantize(tts_src, tts_q, f"TTS {lang_code}"):
                copy_to_assets(tts_q, f"tts_{lang_code}.onnx")
        else:
            print(f"  TTS at {tts_mb:.1f}MB — within expected range, skipping")
    else:
        print(f"  ✗ TTS asset not found: {tts_src}")


def verify_stt(lang_code: str):
    # Prefer assets version (final deployed model)
    model_path = ASSETS_DIR / f"stt_{lang_code}.onnx"
    if not model_path.exists():
        model_path = QUANT_DIR / f"stt_{lang_code}.onnx"
    if not model_path.exists():
        print(f"  ✗ STT model not found for {lang_code}")
        return

    mb = model_path.stat().st_size / 1_048_576
    print(f"  Loading STT ({mb:.1f}MB): {model_path.name}")
    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])

    # Print actual input/output shapes (resolves Q2 — what does the model expect?)
    print("  STT Inputs:")
    for inp in sess.get_inputs():
        print(f"    '{inp.name}'  shape={inp.shape}  dtype={inp.type}")
    print("  STT Outputs:")
    for out in sess.get_outputs():
        print(f"    '{out.name}'  shape={out.shape}  dtype={out.type}")

    # Dummy inference: 80 mel bands, 100 frames (~1 second of 16kHz audio)
    dummy_mel = np.zeros((1, 80, 100), dtype=np.float32)
    dummy_len = np.array([100], dtype=np.int64)
    out = sess.run(None, {"audio_signal": dummy_mel, "length": dummy_len})
    print(f"  ✓ STT verify OK — logprobs shape: {out[0].shape}  (expect [1, T, 257])")


def verify_tts(lang_code: str):
    model_path = ASSETS_DIR / f"tts_{lang_code}.onnx"
    if not model_path.exists():
        model_path = QUANT_DIR / f"tts_{lang_code}.onnx"
    if not model_path.exists():
        print(f"  ✗ TTS model not found for {lang_code}")
        return

    mb = model_path.stat().st_size / 1_048_576
    print(f"  Loading TTS ({mb:.1f}MB): {model_path.name}")
    sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])

    print("  TTS Inputs:")
    for inp in sess.get_inputs():
        print(f"    '{inp.name}'  shape={inp.shape}  dtype={inp.type}")
    print("  TTS Outputs:")
    for out in sess.get_outputs():
        print(f"    '{out.name}'  shape={out.shape}  dtype={out.type}")

    # Dummy: BOS(1) + a few phoneme IDs + EOS(2)
    dummy_ids = np.array([[1, 26, 142, 59, 25, 59, 31, 32, 142, 18, 2]], dtype=np.int64)
    dummy_len = np.array([11], dtype=np.int64)
    dummy_scales = np.array([0.667, 1.0, 0.8], dtype=np.float32)
    out = sess.run(None, {
        "input": dummy_ids,
        "input_lengths": dummy_len,
        "scales": dummy_scales,
    })
    print(f"  ✓ TTS verify OK — audio shape: {out[0].shape}  samples={out[0].size}")


def main():
    parser = argparse.ArgumentParser(description="Quantize iTantra ONNX models to INT8")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--lang", choices=list(LANGUAGES.keys()), help="Single language code")
    group.add_argument("--all", action="store_true", help="Quantize all available ONNX models")
    group.add_argument("--verify", choices=list(LANGUAGES.keys()), metavar="LANG",
                       help="Verify a model with dummy input (prints input/output shapes)")
    parser.add_argument("--from-assets", action="store_true",
                        help="Quantize models already present in app/src/main/assets/ "
                             "(use when models were placed there directly without going through models/onnx/)")
    args = parser.parse_args()

    if args.verify:
        lang = args.verify
        print(f"\nVerifying models for {LANGUAGES[lang]} ({lang})…")
        verify_stt(lang)
        print()
        verify_tts(lang)
        return

    QUANT_DIR.mkdir(parents=True, exist_ok=True)
    langs = list(LANGUAGES.keys()) if args.all else [args.lang]

    if args.from_assets:
        print(f"\niTantra INT8 Quantizer (--from-assets mode)\n"
              f"Reading from: {ASSETS_DIR}\n"
              f"Intermediates: {QUANT_DIR}\n")
        copy_vad_to_assets()
        for lang in langs:
            print(f"\n── {LANGUAGES[lang]} ({lang}) ──")
            quantize_from_assets(lang)
    else:
        print(f"\niTantra INT8 Quantizer\nQuantized output: {QUANT_DIR}\nAssets: {ASSETS_DIR}\n")
        copy_vad_to_assets()
        for lang in langs:
            print(f"\n── {LANGUAGES[lang]} ({lang}) ──")
            stt_src = ONNX_DIR / f"stt_{lang}.onnx"
            stt_dst = QUANT_DIR / f"stt_{lang}.onnx"
            if quantize(stt_src, stt_dst, f"STT {lang}"):
                copy_to_assets(stt_dst, f"stt_{lang}.onnx")

            tts_src = ONNX_DIR / f"tts_{lang}.onnx"
            tts_dst = QUANT_DIR / f"tts_{lang}.onnx"
            if quantize(tts_src, tts_dst, f"TTS {lang}"):
                copy_to_assets(tts_dst, f"tts_{lang}.onnx")

    print(
        "\n✓ Quantization complete.\n"
        "  Models have been copied to app/src/main/assets/\n"
        "  You can now build the Android app in Android Studio."
    )


if __name__ == "__main__":
    main()
