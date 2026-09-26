# iTantra
### Indian Multilingual TTS & STT Aided Neural Transceiver
**Radio Access for Low Bitrate Links**

**Problem Statement:** SIH26173 — Indian Space Research Organisation (ISRO), Smart India Hackathon 2026

---

## 1. Overview

iTantra is an Android application that turns two phones into an offline, multilingual "walkie-talkie" designed for alert and distress communication. Instead of transmitting raw voice audio — which is heavy and unreliable over weak or low-bitrate links — the app converts speech to text on the sender's device, transmits a tiny text packet, and regenerates natural-sounding speech on the receiver's device.

This makes critical communication possible even where bandwidth is extremely limited, and makes it inclusive — since the message always arrives as spoken audio, it reaches people regardless of literacy.

**Supported languages (10):** Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English.

**Core constraints:**
- Fully offline — no internet-hosted APIs, no cloud STT/TTS services
- Open-source frameworks only (no proprietary/commercial voice SDKs)
- Must run smoothly on low- and mid-range Android phones

---

## 2. What the App Does

- Runs in two modes:
  - **Push-to-Talk (Walkie-Talkie mode):** speech-to-text is active only while a button is held.
  - **Phone mode:** continuous background listening using voice activity detection.
- Converts speech to text locally, sends a compact payload over Bluetooth or Wi-Fi, and converts the received text back into speech.
- **Alert messages** are treated specially: they override Do Not Disturb, force the device to maximum volume, and play in a non-interruptible way.
- Two phones running the app — one in STT (sender) mode, one in TTS (receiver) mode — can be connected via Bluetooth or Wi-Fi to demonstrate the full loop.

---

## 3. End-to-End Workflow

The system operates as a linear pipeline split across the sender phone, a wireless bridge, and the receiver phone.

### Sender phone (STT side)
| Step | What happens |
|---|---|
| 1. Mic capture | `AudioRecord` captures raw 16kHz mono PCM audio continuously |
| 2. VAD (Silero) | A lightweight voice-activity model evaluates ~30ms frames and detects when speech starts and when it pauses (silence > 500ms) |
| 3. STT engine | While speech is active, a quantized AI4Bharat IndicConformer model runs on-device, decoding audio into text in the selected language |
| 4. Serialize | The final text, a language code, and an alert flag are packed into a Protocol Buffers payload — typically under 50 bytes |

### Transceiver link (the bridge)
The tiny serialized packet is transmitted over a **Bluetooth socket (RFCOMM)** or **Wi-Fi Direct** connection to the paired phone. Because the payload is just a few bytes of text rather than an audio stream, transmission is near-instantaneous even on very weak links — this is the central trick that lets "voice" travel over low-bitrate connections.

### Receiver phone (TTS side)
| Step | What happens |
|---|---|
| 5. Deserialize | The incoming packet is unpacked and the alert flag is checked |
| 6. TTS engine | The text is passed to a quantized AI4Bharat Indic-VITS model, which synthesizes speech faster than real time (target RTF < 1.0) |
| 7. Alert override | If flagged as an alert, `AudioManager` forces the volume to 100%, overrides Do Not Disturb, and requests audio focus using `AudioAttributes.USAGE_ALARM` so playback can't be interrupted |
| 8. AudioTrack playback | The generated PCM audio buffer is played back through `AudioTrack` as a voice note |

---

## 4. System Architecture — Four Subsystems

1. **Audio Ingestion & Activity Detection** ("the gatekeeper") — `AudioRecord` + Silero VAD prevent continuous raw audio from overwhelming a low-end CPU; only speech segments are routed into the STT pipeline.
2. **Edge STT Engine** ("the encoder") — runs in C++ via the Android NDK (ONNX Runtime Mobile / Sherpa-ONNX) rather than the JVM, since real-time acoustic inference needs native speed. Streaming inference lets text decode while the user is still speaking.
3. **Network Serialization & Transmission** ("the bridge") — only meaning is sent, not audio. Protobuf-encoded packets (text, language ID, alert flag) stay under ~50 bytes and move over a background Bluetooth/Wi-Fi Direct service.
4. **Edge TTS Engine & Playback** ("the decoder") — deserializes the payload, checks the alert flag, synthesizes speech with Indic-VITS, and plays it via `AudioTrack`, escalating to alarm-level audio priority for alerts.

---

## 5. Recommended Tech Stack

| Component | Technology | Why |
|---|---|---|
| ML runtime | Sherpa-ONNX / ONNX Runtime Mobile (via JNI) | Lightweight, built for offline on-device speech processing |
| Voice Activity Detection | Silero VAD (ONNX) | ~1ms latency, <1MB RAM, reliable pause detection |
| Speech-to-Text | AI4Bharat IndicConformer | Best open-source accuracy for the 10 target languages; exported to ONNX and INT8-quantized |
| Text-to-Speech | AI4Bharat Indic-VITS | Efficient, high-quality mobile TTS with fast RTF |
| Connectivity | Bluetooth Low Energy (BLE) / Wi-Fi Aware (NAN), Wi-Fi Direct | Low-bitrate text transmission without needing a router |
| App layer | Kotlin, Coroutines, Jetpack Compose | Async audio buffer handling without blocking the UI |

---

## 6. Implementation Roadmap

1. **Model optimization** — download AI4Bharat STT/TTS weights and Silero VAD, convert to ONNX, apply INT8 dynamic quantization (shrinks ~200MB models to ~40–50MB per language).
2. **NDK & C++ runtime setup** — configure CMake/NDK, integrate ONNX Runtime Mobile or Sherpa-ONNX as native `.so` libraries.
3. **Audio ingestion & VAD pipeline** — background service capturing 16kHz mono audio, feeding Silero VAD, triggering STT on speech and finalizing text on pause.
4. **Peer-to-peer networking layer** — Bluetooth/Wi-Fi Direct transceiver manager with a Protobuf payload schema.
5. **TTS synthesis & alert override** — deserialize payload, run Indic-VITS, and force max-volume/DND-override playback for alerts.
6. **UI & benchmarking** — Jetpack Compose interface with Push-to-Talk vs. Phone mode toggle, plus a live diagnostic overlay tracking RAM usage, WER, and end-to-end latency.

---

## 7. Evaluation Metrics

| Metric | Weight | What's measured |
|---|---|---|
| Efficiency | 20% | Model size, app size (RAM/Flash footprint), idle-listening CPU usage |
| Accuracy | 40% | Low Word Error Rate (STT), high intelligibility and natural flow (TTS) |
| Latency | 20% | Delay from speech to STT completion, delay from text received to audio played, and end-to-end delay from spoken sentence to audio on the receiving phone (RTF) |

*(Remaining 20% covers overall solution robustness and adherence to constraints.)*

---

## 8. MVP Demo Requirements

**Hardware**
- Two mid-range Android phones (4GB RAM, Snapdragon 6-series / MediaTek Helio G-series or better, Android 10+/API 29+, working mic and speaker) — one as Sender, one as Receiver
- Development laptop (16GB RAM recommended) for model quantization and live ADB debugging
- USB-C cables for side-by-side logcat demonstration of latency and payload size

**Software**
- Python 3.10+, ONNX & ONNX Runtime tools for model conversion/quantization
- Android Studio (latest stable), Android NDK + CMake, Android SDK (API 34 target)
- App dependencies: Sherpa-ONNX / ONNX Runtime Mobile, Kotlin Coroutines, Protocol Buffers, Jetpack Compose

**Demo tip:** Enable Airplane Mode on both phones (re-enabling only Bluetooth/Wi-Fi Direct) to visibly prove the "fully offline" requirement to evaluators — no cloud APIs are involved at any point.

---

## 9. Why It Matters

iTantra is effectively a walkie-talkie that speaks any of 10 Indian languages to anyone, using almost no bandwidth. In emergencies, disaster zones, or remote deployments where full audio streaming isn't feasible, it still gets a life-saving message through — spoken aloud, reaching people who may not be able to read it.
