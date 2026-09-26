"""
iTantra — Model Download Script
================================
Downloads AI4Bharat IndicConformer (STT), Indic-VITS (TTS), and Silero VAD
checkpoints from Hugging Face to the local `models/` directory.

Requirements:
    pip install huggingface_hub

Usage:
    python scripts/download_models.py --langs hi          # Hindi only (MVP)
    python scripts/download_models.py --langs hi gu mr    # Multiple languages
    python scripts/download_models.py --all               # All 10 languages
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

# Try importing huggingface_hub; guide the user if missing
try:
    from huggingface_hub import hf_hub_download, snapshot_download
except ImportError:
    raise SystemExit(
        "huggingface_hub is not installed.\n"
        "Run:  pip install huggingface_hub"
    )

# ─── Language configuration ───────────────────────────────────────────────────

LANGUAGES = {
    "hi": "Hindi",
    "gu": "Gujarati",
    "mr": "Marathi",
    "kn": "Kannada",
    "ml": "Malayalam",
    "ta": "Tamil",
    "te": "Telugu",
    "or": "Odia",
    "bn": "Bengali",
    "en": "English",
}

# AI4Bharat Hugging Face repository IDs
STT_REPO = "ai4bharat/indicconformer"
TTS_REPO = "ai4bharat/indic-tts-coqui-indo"

# Silero VAD — single model file, language-agnostic
SILERO_REPO = "snakers4/silero-vad"
SILERO_FILE = "files/silero_vad.onnx"

# Output directory
MODELS_DIR = Path(__file__).parent.parent / "models"


def download_silero_vad():
    print("⬇  Downloading Silero VAD ONNX model...")
    out_path = MODELS_DIR / "vad_silero.onnx"
    if out_path.exists():
        print(f"   Already exists: {out_path}")
        return

    try:
        path = hf_hub_download(
            repo_id=SILERO_REPO,
            filename=SILERO_FILE,
            local_dir=str(MODELS_DIR / "silero_tmp"),
        )
        import shutil
        shutil.move(path, out_path)
        print(f"   ✓ Saved: {out_path}")
    except Exception as e:
        print(f"   ✗ Failed to download Silero VAD: {e}")
        print(
            "   Manual fallback: Download silero_vad.onnx from\n"
            "   https://github.com/snakers4/silero-vad/releases\n"
            f"   and place it at {out_path}"
        )


def download_stt_model(lang_code: str):
    print(f"⬇  Downloading STT model for {LANGUAGES[lang_code]} ({lang_code})...")
    out_path = MODELS_DIR / f"stt_{lang_code}_raw"
    if out_path.exists():
        print(f"   Already exists: {out_path}")
        return

    try:
        # Download the full checkpoint — convert_to_onnx.py will export it
        snapshot_download(
            repo_id=STT_REPO,
            allow_patterns=[f"*{lang_code}*", "*.json", "*.py", "tokenizer*"],
            local_dir=str(out_path),
            ignore_patterns=["*.safetensors.index.json"],
        )
        print(f"   ✓ Saved: {out_path}")
    except Exception as e:
        print(f"   ✗ STT download failed for {lang_code}: {e}")
        print(
            f"   Manual: visit https://huggingface.co/{STT_REPO}\n"
            f"   and download the {lang_code} checkpoint to {out_path}"
        )


def download_tts_model(lang_code: str):
    print(f"⬇  Downloading TTS model for {LANGUAGES[lang_code]} ({lang_code})...")
    out_path = MODELS_DIR / f"tts_{lang_code}_raw"
    if out_path.exists():
        print(f"   Already exists: {out_path}")
        return

    try:
        snapshot_download(
            repo_id=TTS_REPO,
            allow_patterns=[f"*{lang_code}*"],
            local_dir=str(out_path),
        )
        print(f"   ✓ Saved: {out_path}")
    except Exception as e:
        print(f"   ✗ TTS download failed for {lang_code}: {e}")
        print(
            f"   Manual: visit https://huggingface.co/{TTS_REPO}\n"
            f"   and download the {lang_code} model to {out_path}"
        )


def main():
    parser = argparse.ArgumentParser(description="Download iTantra model weights")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--langs", nargs="+", choices=list(LANGUAGES.keys()), metavar="LANG",
                       help="Language codes to download (e.g. hi gu mr)")
    group.add_argument("--all", action="store_true", help="Download all 10 languages")
    args = parser.parse_args()

    langs = list(LANGUAGES.keys()) if args.all else args.langs

    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    print(f"\niTantra Model Downloader\nOutput dir: {MODELS_DIR}\n")

    # VAD — always required
    download_silero_vad()

    # Per-language STT and TTS
    for lang in langs:
        print()
        download_stt_model(lang)
        download_tts_model(lang)

    print("\n✓ Download complete. Run convert_to_onnx.py next.")


if __name__ == "__main__":
    main()
