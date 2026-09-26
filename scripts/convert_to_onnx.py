"""
iTantra — Model Conversion Script (PyTorch → ONNX)
====================================================
Converts AI4Bharat IndicConformer (STT) and Indic-VITS (TTS) checkpoints
to ONNX format with dynamic axes for variable-length audio inputs.

Requirements:
    pip install torch torchaudio onnx nemo_toolkit[asr] nemo_toolkit[tts]
    (Run on a machine with ~16GB RAM; GPU optional but recommended)

Usage:
    python scripts/convert_to_onnx.py --lang hi    # Convert Hindi models
    python scripts/convert_to_onnx.py --all        # Convert all downloaded languages
"""

import argparse
import os
import sys
from pathlib import Path

# Ensure UTF-8 output on Windows console
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

MODELS_DIR = Path(__file__).parent.parent / "models"
ONNX_DIR = MODELS_DIR / "onnx"

LANGUAGES = {
    "hi": "Hindi", "gu": "Gujarati", "mr": "Marathi", "kn": "Kannada",
    "ml": "Malayalam", "ta": "Tamil", "te": "Telugu", "or": "Odia",
    "bn": "Bengali", "en": "English",
}


def convert_stt(lang_code: str):
    """Export IndicConformer to ONNX with dynamic audio-length axis."""
    try:
        import torch
        import nemo.collections.asr as nemo_asr
    except ImportError:
        sys.exit("Install NeMo: pip install nemo_toolkit[asr]")

    raw_dir = MODELS_DIR / f"stt_{lang_code}_raw"
    if not raw_dir.exists():
        print(f"  ✗ STT raw checkpoint not found: {raw_dir}. Run download_models.py first.")
        return

    out_path = ONNX_DIR / f"stt_{lang_code}.onnx"
    if out_path.exists():
        print(f"  Already exists: {out_path}")
        return

    print(f"  Converting STT ({lang_code})…")
    try:
        # Load from NeMo checkpoint
        model = nemo_asr.models.EncDecCTCModelBPE.restore_from(
            restore_path=str(list(raw_dir.glob("*.nemo"))[0])
        )
        model.eval()

        # Dummy input: 5 seconds of 16kHz audio
        dummy_audio = torch.zeros(1, 80000)
        dummy_len = torch.tensor([80000])

        torch.onnx.export(
            model,
            (dummy_audio, dummy_len),
            str(out_path),
            input_names=["audio_signal", "length"],
            output_names=["log_probs", "encoded_len"],
            dynamic_axes={
                "audio_signal": {0: "batch", 1: "time"},
                "length": {0: "batch"},
                "log_probs": {0: "batch", 1: "time"},
            },
            opset_version=17,
            export_params=True,
        )
        print(f"  ✓ STT ONNX saved: {out_path} ({out_path.stat().st_size // 1_048_576}MB)")
    except Exception as e:
        print(f"  ✗ STT conversion failed for {lang_code}: {e}")


def convert_tts(lang_code: str):
    """Export Indic-VITS to ONNX with dynamic text-length axis."""
    try:
        import torch
        import nemo.collections.tts as nemo_tts
    except ImportError:
        sys.exit("Install NeMo: pip install nemo_toolkit[tts]")

    raw_dir = MODELS_DIR / f"tts_{lang_code}_raw"
    if not raw_dir.exists():
        print(f"  ✗ TTS raw checkpoint not found: {raw_dir}. Run download_models.py first.")
        return

    out_path = ONNX_DIR / f"tts_{lang_code}.onnx"
    if out_path.exists():
        print(f"  Already exists: {out_path}")
        return

    print(f"  Converting TTS ({lang_code})…")
    try:
        nemo_files = list(raw_dir.glob("*.nemo"))
        if not nemo_files:
            print(f"  ✗ No .nemo file found in {raw_dir}")
            return

        model = nemo_tts.models.VitsModel.restore_from(str(nemo_files[0]))
        model.eval()

        # Dummy phoneme IDs: 20 tokens
        dummy_ids = torch.zeros(1, 20, dtype=torch.long)
        dummy_len = torch.tensor([20])

        torch.onnx.export(
            model,
            (dummy_ids, dummy_len),
            str(out_path),
            input_names=["input_ids", "input_lengths"],
            output_names=["audio"],
            dynamic_axes={
                "input_ids": {0: "batch", 1: "text_len"},
                "input_lengths": {0: "batch"},
                "audio": {0: "batch", 2: "audio_len"},
            },
            opset_version=17,
            export_params=True,
        )
        print(f"  ✓ TTS ONNX saved: {out_path} ({out_path.stat().st_size // 1_048_576}MB)")
    except Exception as e:
        print(f"  ✗ TTS conversion failed for {lang_code}: {e}")


def main():
    parser = argparse.ArgumentParser(description="Convert iTantra models to ONNX")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--lang", choices=list(LANGUAGES.keys()), help="Single language code")
    group.add_argument("--all", action="store_true", help="Convert all downloaded languages")
    args = parser.parse_args()

    ONNX_DIR.mkdir(parents=True, exist_ok=True)
    langs = list(LANGUAGES.keys()) if args.all else [args.lang]

    print(f"\niTantra ONNX Exporter\nOutput: {ONNX_DIR}\n")
    for lang in langs:
        print(f"── {LANGUAGES[lang]} ({lang}) ──")
        convert_stt(lang)
        convert_tts(lang)

    print("\n✓ Conversion complete. Run quantize_models.py next.")


if __name__ == "__main__":
    main()
