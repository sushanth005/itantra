import argparse
import os
import sys
import json
from pathlib import Path

try:
    from huggingface_hub import hf_hub_download, snapshot_download
except ImportError:
    raise SystemExit(
        "huggingface_hub is not installed.\n"
        "Run:  pip install huggingface_hub"
    )

MODELS_DIR = Path(__file__).parent.parent / "app/src/main/assets"

PIPER_MODELS = {
    "ta": "ta/ta_IN/anurag/medium/ta_IN-anurag-medium",
    "te": "te/te_IN/maya/medium/te_IN-maya-medium",
    "kn": "kn/kn_IN/shruthi/medium/kn_IN-shruthi-medium",
    "ml": "ml/ml_IN/midhun/medium/ml_IN-midhun-medium",
    "mr": "mr/mr_IN/arohi/medium/mr_IN-arohi-medium",
    "gu": "gu/gu_IN/nirali/medium/gu_IN-nirali-medium",
    "bn": "bn/bn_IN/upahar/medium/bn_IN-upahar-medium",
    "en": "en/en_US/amy/low/en_US-amy-low",
    "hi": "hi/hi_IN/swara/medium/hi_IN-swara-medium",
}

def download_stt():
    print("⬇  Downloading Whisper-tiny STT model (Multilingual)...")
    repo = "csukuangfj/sherpa-onnx-whisper-tiny"
    out_dir = MODELS_DIR / "sherpa_stt"
    
    if (out_dir / "tiny-decoder.onnx").exists():
        print("   ✓ STT model already exists.")
        return

    try:
        snapshot_download(
            repo_id=repo,
            local_dir=str(out_dir),
            ignore_patterns=["*.tar.bz2", "*.md", ".git*"],
        )
        print(f"   ✓ Saved STT to: {out_dir}")
    except Exception as e:
        print(f"   ✗ STT download failed: {e}")

def download_tts(lang: str):
    if lang not in PIPER_MODELS:
        print(f"   ⚠ No Piper TTS model configured for {lang}.")
        return

    path_prefix = PIPER_MODELS[lang]
    print(f"⬇  Downloading Piper TTS model for {lang} ({path_prefix})...")
    repo = "rhasspy/piper-voices"
    out_dir = MODELS_DIR / f"sherpa_tts_{lang}"
    out_dir.mkdir(parents=True, exist_ok=True)

    onnx_file = f"{path_prefix}.onnx"
    json_file = f"{path_prefix}.onnx.json"

    if (out_dir / "model.onnx").exists() and (out_dir / "tokens.txt").exists():
        print("   ✓ TTS model already exists.")
        return

    try:
        onnx_path = hf_hub_download(repo_id=repo, filename=onnx_file)
        json_path = hf_hub_download(repo_id=repo, filename=json_file)
        
        # Copy to assets with standardized names
        import shutil
        shutil.copy(onnx_path, out_dir / "model.onnx")
        
        # Generate tokens.txt for sherpa-onnx from the JSON file
        with open(json_path, 'r', encoding='utf-8') as f:
            config = json.load(f)
            
        phoneme_map = config.get("phoneme_id_map", {})
        # Create a list big enough for all IDs
        # Some configs have list values, some have ints
        int_values = [v[0] if isinstance(v, list) else v for v in phoneme_map.values()]
        max_id = max(int_values) if int_values else 0
        tokens = [""] * (max_id + 1)
        for token, token_id in phoneme_map.items():
            # Handle list format in some config versions
            if isinstance(token_id, list):
                token_id = token_id[0]
            tokens[token_id] = token
            
        with open(out_dir / "tokens.txt", 'w', encoding='utf-8') as f:
            for t in tokens:
                f.write(f"{t}\n")
                
        # Also save the json just in case we need other config info (e.g. sample rate)
        shutil.copy(json_path, out_dir / "model.json")
        
        print(f"   ✓ Saved TTS to: {out_dir}")
    except Exception as e:
        print(f"   ✗ TTS download failed: {e}")

SHERPA_AAR_VERSION = "1.13.8"
SHERPA_AAR_URL = (
    f"https://github.com/k2-fsa/sherpa-onnx/releases/download/"
    f"v{SHERPA_AAR_VERSION}/sherpa-onnx-{SHERPA_AAR_VERSION}.aar"
)

def download_aar():
    """Download the Sherpa-ONNX AAR to app/libs/ if not already present."""
    import urllib.request, sys as _sys

    libs_dir = Path(__file__).parent.parent / "app/libs"
    libs_dir.mkdir(parents=True, exist_ok=True)
    out = libs_dir / f"sherpa-onnx-{SHERPA_AAR_VERSION}.aar"

    if out.exists() and out.stat().st_size > 1_000_000:
        print(f"   ✓ AAR already exists: {out}")
        return

    print(f"⬇  Downloading Sherpa-ONNX AAR v{SHERPA_AAR_VERSION}...")
    try:
        req = urllib.request.Request(SHERPA_AAR_URL, headers={"User-Agent": "Python/3.x"})
        opener = urllib.request.build_opener(urllib.request.HTTPRedirectHandler())
        with opener.open(req, timeout=360) as r:
            total = int(r.headers.get("Content-Length", 0))
            downloaded = 0
            with open(out, "wb") as f:
                while True:
                    chunk = r.read(2 * 1024 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
                    downloaded += len(chunk)
                    if total:
                        pct = 100 * downloaded // total
                        _sys.stdout.write(f"\r   {downloaded/1e6:.1f}/{total/1e6:.1f} MB ({pct}%)")
                        _sys.stdout.flush()
        print(f"\n   ✓ Saved AAR to: {out} ({out.stat().st_size/1e6:.1f} MB)")
    except Exception as e:
        print(f"\n   ✗ AAR download failed: {e}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Download Sherpa-ONNX models")
    parser.add_argument("--langs", nargs="+", help="Language codes to download TTS for")
    parser.add_argument("--download-aar", action="store_true",
                        help="Also download the Sherpa-ONNX AAR to app/libs/")
    args = parser.parse_args()

    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    if args.download_aar:
        download_aar()

    # Always download the multilingual STT model
    download_stt()

    if args.langs:
        for lang in args.langs:
            download_tts(lang)
