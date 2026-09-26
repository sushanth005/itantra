# iTantra — Complete Setup Guide

> **Audience:** Team members setting up the iTantra prototype for the first time.  
> **Goal:** Get from a blank machine to a working two-phone demo.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Python Setup — Model Pipeline](#2-python-setup--model-pipeline)
   - 2.1 [Install Python dependencies](#21-install-python-dependencies)
   - 2.2 [Download model weights](#22-download-model-weights)
   - 2.3 [Convert to ONNX](#23-convert-to-onnx)
   - 2.4 [INT8 Quantize & copy to assets](#24-int8-quantize--copy-to-assets)
   - 2.5 [Verify the models](#25-verify-the-models)
3. [Font Setup — Outfit Typeface](#3-font-setup--outfit-typeface)
4. [Android Studio Setup](#4-android-studio-setup)
   - 4.1 [Install Android Studio](#41-install-android-studio)
   - 4.2 [Open the project](#42-open-the-project)
   - 4.3 [Sync Gradle](#43-sync-gradle)
   - 4.4 [Configure a physical device](#44-configure-a-physical-device)
   - 4.5 [Run on two devices simultaneously](#45-run-on-two-devices-simultaneously)
5. [Running the Full Demo](#5-running-the-full-demo)
6. [Troubleshooting](#6-troubleshooting)

---

## 1. Prerequisites

Before starting, make sure every item below is installed and working.

### Hardware
| Item | Requirement |
|---|---|
| Development laptop | Windows 10/11, 16 GB RAM recommended |
| Phone 1 (Sender) | Android 10+ (API 29+), working microphone, Bluetooth |
| Phone 2 (Receiver) | Android 10+ (API 29+), working speaker, Bluetooth |
| USB cables | One per phone (USB-C) for ADB debugging |

### Software — install before Step 2

| Tool | Version | Download |
|---|---|---|
| Python | 3.10 or 3.11 | https://www.python.org/downloads/ |
| Git | Latest | https://git-scm.com/download/win |
| Android Studio | Koala (2024.1) or newer | https://developer.android.com/studio |
| JDK 17 | Bundled with Android Studio | (no separate download needed) |

> **Tip:** During Python installation, check **"Add Python to PATH"**.

---

## 2. Python Setup — Model Pipeline

All commands in this section are run in **PowerShell** or **Command Prompt** on your laptop. The scripts live in the `scripts/` folder of the project.

### 2.1 Install Python dependencies

Open a terminal in the project root folder (`iTantra/`) and run:

```powershell
# Create a virtual environment (keeps dependencies isolated)
python -m venv venv

# Activate it (PowerShell)
.\venv\Scripts\Activate.ps1

# Or in Command Prompt:
# venv\Scripts\activate.bat
```

Then install all required packages:

```powershell
# Core tools — always required
pip install huggingface_hub onnxruntime onnx numpy

# NeMo toolkit — for exporting AI4Bharat models
# This is a large install (~2–3 GB including PyTorch); allow 10–20 minutes
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cpu
pip install nemo_toolkit[asr] nemo_toolkit[tts]
```

> **Note:** If `nemo_toolkit` installation fails, try installing with `--no-deps` first and then installing dependencies manually. NeMo can sometimes conflict with newer NumPy versions — use `pip install numpy==1.26.4` if you see NumPy errors.

---

### 2.2 Download model weights

The [`scripts/download_models.py`](scripts/download_models.py) script fetches:
- **Silero VAD** (~2 MB) — language-agnostic voice activity detection
- **AI4Bharat IndicConformer** — on-device STT for each language
- **AI4Bharat Indic-VITS** — on-device TTS for each language

**MVP (Hindi only — recommended for first run):**

```powershell
python scripts/download_models.py --langs hi
```

**Add more languages later:**

```powershell
python scripts/download_models.py --langs hi gu mr kn
```

**All 10 languages at once:**

```powershell
python scripts/download_models.py --all
```

After this step you will see new folders inside `models/`:
```
models/
  vad_silero.onnx           ← Silero VAD (already ONNX)
  stt_hi_raw/               ← Raw IndicConformer checkpoint
  tts_hi_raw/               ← Raw Indic-VITS checkpoint
```

> **Disk space:** Each language pair takes ~400 MB before quantization. Plan for ~500 MB per language downloaded.

> **Slow internet?** The script is resumable — if it fails mid-way, just re-run the same command. Files already downloaded are skipped automatically.

---

### 2.3 Convert to ONNX

The [`scripts/convert_to_onnx.py`](scripts/convert_to_onnx.py) script loads each PyTorch checkpoint and exports it to ONNX format with dynamic input axes.

**Convert Hindi:**

```powershell
python scripts/convert_to_onnx.py --lang hi
```

**Convert all downloaded languages:**

```powershell
python scripts/convert_to_onnx.py --all
```

Output:
```
models/onnx/
  stt_hi.onnx     (~180 MB — before quantization)
  tts_hi.onnx     (~180 MB — before quantization)
```

> **RAM requirement:** The export process loads the full model into RAM. 16 GB is recommended; 8 GB may work but will be slow.

> **GPU (optional):** If your laptop has an NVIDIA GPU with CUDA drivers, the export will run significantly faster. No code changes needed — PyTorch auto-detects the GPU.

---

### 2.4 INT8 Quantize & copy to assets

The [`scripts/quantize_models.py`](scripts/quantize_models.py) script:
1. Applies **INT8 dynamic quantization** (shrinks models ~4×)
2. **Automatically copies** the final `.onnx` files into `app/src/main/assets/`

**Quantize Hindi:**

```powershell
python scripts/quantize_models.py --lang hi
```

**Quantize all:**

```powershell
python scripts/quantize_models.py --all
```

Expected console output:
```
iTantra INT8 Quantizer

✓ Copied to assets: vad_silero.onnx (2.1MB)

── Hindi (hi) ──
  Quantizing STT hi (178.4MB) → INT8…
  ✓ Done in 42.1s: 178.4MB → 44.8MB (3.9× smaller)
  ✓ Copied to assets: stt_hi.onnx (44.8MB)

  Quantizing TTS hi (172.1MB) → INT8…
  ✓ Done in 38.7s: 172.1MB → 43.2MB (3.9× smaller)
  ✓ Copied to assets: tts_hi.onnx (43.2MB)

✓ Quantization complete.
  Models have been copied to app/src/main/assets/
```

After this step, verify the assets folder:
```
app/src/main/assets/
  vad_silero.onnx    (~2 MB)
  stt_hi.onnx        (~45 MB)
  tts_hi.onnx        (~45 MB)
```

> **Important:** These three files **must** be present before building the app. Android Studio will not throw an error if they are missing, but the app will silently fail to transcribe or synthesize speech at runtime.

---

### 2.5 Verify the models

Run a quick sanity check to confirm the quantized models accept valid inputs and produce correct output shapes:

```powershell
python scripts/quantize_models.py --verify hi
```

Expected output:
```
Verifying quantized models for Hindi (hi)…
  ✓ STT verify OK — output shape: (1, 42)
  ✓ TTS verify OK — output shape: (1, 1, 88200)
```

If either check fails, re-run the conversion and quantization steps for that language.

---

## 3. Font Setup — Outfit Typeface

The iTantra UI uses the **Outfit** font family. This must be added manually since font files cannot be fetched at build time.

### Step-by-step

1. Open your browser and go to:  
   **https://fonts.google.com/specimen/Outfit**

2. Click the **"Download family"** button (top right of the page). A ZIP file named `Outfit.zip` will download.

3. Open (extract) the ZIP. Inside you will find a folder called `Outfit/static/` containing individual TTF files.

4. Create the font resource directory in the project (if it doesn't exist):
   ```
   app\src\main\res\font\
   ```

5. Copy **exactly these four files** from the extracted ZIP into that folder, renaming them as shown:

   | Source file (from ZIP) | → Destination in project |
   |---|---|
   | `Outfit-Regular.ttf` | `app\src\main\res\font\outfit_regular.ttf` |
   | `Outfit-Medium.ttf` | `app\src\main\res\font\outfit_medium.ttf` |
   | `Outfit-SemiBold.ttf` | `app\src\main\res\font\outfit_semibold.ttf` |
   | `Outfit-Bold.ttf` | `app\src\main\res\font\outfit_bold.ttf` |

6. Verify the final structure:
   ```
   app/src/main/res/font/
     outfit_regular.ttf
     outfit_medium.ttf
     outfit_semibold.ttf
     outfit_bold.ttf
   ```

> **Why?** Android resource files must use **lowercase letters and underscores only** in their filenames. That is why `Outfit-Regular.ttf` is renamed to `outfit_regular.ttf`.

> **Alternative (temporary):** If you skip this step, the app will compile but crash at startup with a `FontInflateException`. To temporarily bypass this, open [`Type.kt`](app/src/main/java/com/itantra/ui/theme/Type.kt) and replace `OutfitFamily` with `FontFamily.Default` on every `fontFamily` line.

---

## 4. Android Studio Setup

### 4.1 Install Android Studio

1. Download Android Studio from: **https://developer.android.com/studio**
2. Run the installer and follow the setup wizard.
3. On the **"Install Type"** screen choose **"Standard"** — this installs the Android SDK, emulator, and build tools automatically.
4. Let the SDK components download finish (this takes 5–15 minutes).
5. At the end of the wizard, click **"Finish"**.

> **SDK location:** By default the SDK installs to `C:\Users\<YourName>\AppData\Local\Android\Sdk`. You can check or change this in Android Studio under **File → Settings → Appearance & Behavior → System Settings → Android SDK**.

---

### 4.2 Open the project

1. Launch **Android Studio**.
2. On the welcome screen click **"Open"** (not "New Project").
3. Navigate to:
   ```
   C:\Users\Sushanth Bandari\Desktop\iTantra
   ```
4. Select the **`iTantra`** folder (the root — the one that contains `settings.gradle.kts`) and click **"OK"**.
5. Android Studio will open the project and begin indexing files. Wait for the status bar at the bottom to finish (it says "Indexing…" while busy).

---

### 4.3 Sync Gradle

After opening the project, Android Studio will show a yellow banner:

> **"Gradle files have changed since last project sync. A project sync may be necessary."**

Click the **"Sync Now"** link in that banner.

Alternatively: go to **File → Sync Project with Gradle Files**.

**What happens during sync:**
- Gradle downloads all declared dependencies from Maven Central (ONNX Runtime, Protobuf, Compose, etc.)
- This requires internet and can take **5–10 minutes** on first sync
- The bottom status bar shows download progress

**Expected result:** The "BUILD SUCCESSFUL" message in the Build output panel, and no red underlines in any source files.

#### If sync fails:

| Error message | Fix |
|---|---|
| `Could not resolve com.microsoft.onnxruntime:onnxruntime-android` | Check internet connection; try **File → Invalidate Caches → Restart** |
| `Unresolved reference: libs` | Make sure `gradle/libs.versions.toml` exists in the project |
| `Plugin 'com.google.protobuf' not found` | Re-sync; the protobuf plugin downloads from Gradle Plugin Portal |
| `compileSdk 35 requires JDK 17` | Go to **File → Project Structure → SDK Location** and set JDK to 17 |

---

### 4.4 Configure a physical device

The app **cannot be fully tested on an emulator** because:
- Bluetooth is not available on emulators
- Real-time audio inference performance differs from physical hardware

You need to connect real Android phones via USB.

#### Enable Developer Options on each phone:

1. Open **Settings** on the phone
2. Scroll to **About phone**
3. Tap **Build number** exactly **7 times** rapidly
4. A toast will appear: _"You are now a developer!"_
5. Go back to **Settings → Developer options** (now visible)
6. Enable **USB debugging**
7. Connect the phone to your laptop via USB
8. On the phone, a dialog will appear: **"Allow USB debugging?"** — tap **"Allow"**

#### Verify ADB sees the device:

Open a terminal and run:

```powershell
# Android Studio ships adb at this path:
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
```

Expected output:
```
List of devices attached
R5CNA1234XY    device
R5CNB5678AB    device
```

Both phones should show `device` (not `unauthorized` or `offline`).

---

### 4.5 Run on two devices simultaneously

#### Sender phone:

1. In Android Studio, click the **device dropdown** in the toolbar (it shows the phone name or "No Device Selected")
2. Select **Phone 1 (Sender)** from the list
3. Click the green **▶ Run** button (or press `Shift+F10`)
4. Wait for the app to build and install — first build takes 2–3 minutes
5. The app will launch on the Sender phone automatically

#### Receiver phone:

1. Change the **device dropdown** to **Phone 2 (Receiver)**
2. Click **▶ Run** again
3. The same APK will install and launch on the Receiver phone

> **Tip:** You can use Android Studio's **"Run → Run configurations"** to create two separate run configurations named "Sender" and "Receiver" if you want to switch quickly.

#### Viewing logs from both phones simultaneously:

1. Open **Logcat** in Android Studio: **View → Tool Windows → Logcat** (or `Alt+6`)
2. Use the device dropdown inside Logcat to switch between the two phones
3. Filter by `iTantra` tag to see only relevant logs:
   ```
   tag:iTantra OR tag:TransceiverService OR tag:SttEngine OR tag:TtsEngine OR tag:VadManager
   ```

---

## 5. Running the Full Demo

Once the app is installed on both phones, follow these steps for a clean offline demo (ideal for evaluators):

### Step 1 — Pair the phones via Bluetooth

1. On **Phone 1** → Settings → Bluetooth → Enable
2. On **Phone 2** → Settings → Bluetooth → Enable → tap **"Pair new device"**
3. Both phones should discover each other — tap to pair and confirm on both

> Do this once; the pairing is remembered.

### Step 2 — Enable Airplane Mode (prove offline operation)

On **both phones**:
1. Pull down the notification shade
2. Tap **Airplane Mode** to turn it ON (disables Wi-Fi and cellular)
3. Then tap **Bluetooth** to turn Bluetooth back ON (Bluetooth works in airplane mode)

This visibly proves to evaluators that no internet connection is used at any point.

### Step 3 — Configure the Receiver phone

1. Open **iTantra** on the **Receiver** phone
2. Grant all permissions when prompted (Microphone, Bluetooth, Location, Notifications)
3. Set **Role → Receiver**
4. Set **Language → हिन्दी (Hindi)**
5. Tap **"Start Session"** — the app now listens for an incoming Bluetooth connection

### Step 4 — Configure the Sender phone

1. Open **iTantra** on the **Sender** phone
2. Grant all permissions when prompted
3. Set **Role → Sender**
4. Set **Language → हिन्दी (Hindi)**
5. Tap **"Select Device"** → choose the Receiver phone from the paired devices list
6. Tap **"Start Session"**

Both phones should now show **"Active"** status with a pulsing green indicator.

### Step 5 — Send a voice message (PTT mode)

1. On the **Sender** phone, press and **hold** the large microphone button
2. Speak clearly in Hindi — for example: _"मदद चाहिए, यहाँ आग लगी है"_
3. **Release** the button
4. Watch the transcript box on the Sender phone update with the recognized text
5. Within 2–3 seconds, the **Receiver** phone plays back the synthesized Hindi speech through its speaker

### Step 6 — Test Alert override

1. On the Sender phone, enable the **"Mark as Alert"** toggle (red switch)
2. Hold PTT and speak: _"आपातकाल! मदद चाहिए!"_
3. Release
4. On the Receiver phone: volume jumps to **maximum** and Do Not Disturb is overridden — the message plays regardless of any notification silencing settings

### Step 7 — View diagnostics

On either phone, tap the **◉** (analytics icon) in the top right of the screen to open the Live Diagnostics panel. You should see:

| Stat | Target |
|---|---|
| STT Latency | < 1500 ms |
| TTS Latency | < 1000 ms |
| RTF | < 1.00 |
| Payload | < 50 bytes |
| E2E Latency | < 3000 ms |

---

## 6. Troubleshooting

### Python / Model Pipeline

| Problem | Solution |
|---|---|
| `ModuleNotFoundError: nemo` | Run `pip install nemo_toolkit[asr]` inside the activated `venv` |
| `huggingface_hub` rate limit | Log in with `huggingface-cli login` using a free HF account token |
| ONNX export crashes with OOM | Reduce other open applications; ensure 16 GB RAM is available |
| `vad_silero.onnx` not found | Download manually from https://github.com/snakers4/silero-vad/releases |
| `quantize_models.py` says "Not found" | Run `convert_to_onnx.py` first — quantization needs the ONNX file |

### Android Studio

| Problem | Solution |
|---|---|
| Gradle sync fails: "Could not download onnxruntime-android" | Disable VPN; try a different network |
| `FontInflateException` at app launch | Font TTF files are missing from `res/font/` — see Section 3 |
| App crashes immediately: "Failed to load VAD model" | `vad_silero.onnx` is missing from `app/src/main/assets/` |
| App crashes: "Failed to load STT model stt_hi.onnx" | Run `quantize_models.py --lang hi` and rebuild |
| Device shows "unauthorized" in ADB | Tap "Allow" on the USB debugging dialog on the phone; replug USB |
| Bluetooth connection never establishes | Ensure both phones are paired in system settings first; check that the Receiver started its session before the Sender tries to connect |
| `BLUETOOTH_CONNECT` permission denied | On Android 12+ the permission dialog must be accepted at app launch — uninstall and reinstall to re-trigger it |

### On-device Performance

| Problem | Solution |
|---|---|
| STT latency > 3 seconds | Use a phone with a faster SoC (Snapdragon 7xx or better); INT8 quantization must be applied |
| RTF > 1.0 (TTS slower than real-time) | Same hardware advice; ensure no other apps are running in background |
| VAD never triggers | Speak loudly and close to the mic; increase `START_THRESHOLD` in `VadManager.kt` from 0.5 to 0.4 |
| No audio on Receiver | Confirm TTS ONNX model loaded (check Logcat for "TTS model loaded"); check phone volume is not muted |
