# Project Cipher
**Autonomous Neural Daemon for Solo Operatives**

Project Cipher is a zero-latency, local Voice Activity Detection (VAD) assistant designed for absolute autonomy. It bridges a native Kotlin Android Foreground Service with a background Python cortex via raw TCP socket streaming, bypassing Android’s aggressive background process restrictions.

## Technical Architecture
- **Ring 1 (The Gatekeeper):** Native Kotlin `AudioRecord` implementation utilizing VOSK for local, offline wake-word detection. 
- **Ring 2 (The Cortex):** Python-based TCP socket daemon that ingests raw PCM audio streams in real-time.
- **Processing Pipeline:** Audio is buffered in-memory, packaged as virtual WAV data, and routed to the Groq/Whisper LPU for split-second transcription and Llama 3.1 execution.
- **Hardware Integration:** Custom high-pass filtering and adaptive noise-floor calibration allow the system to operate reliably in varying environments.

## Current Status: [Active Development]
- Engineering stable, low-latency socket handoffs to maintain system stability under Android 14+ FGS restrictions.
- Optimizing VAD thresholds for minimal battery impact.

## Stack
`Python` | `Kotlin` | `Android FGS` | `TCP Sockets` | `VOSK` | `Whisper API` | `Llama 3.1`
