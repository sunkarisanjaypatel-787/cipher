import subprocess
import json
import time
import os
import socket
import sys
import wave
import io
from groq import Groq

# ==========================================
# MASTER DAEMON LOGGING
# ==========================================
BASE_DIR = "/storage/emulated/0/Download/python"
os.makedirs(BASE_DIR, exist_ok=True)
LOG_FILE = os.path.join(BASE_DIR, "cipher_daemon.log")

def log_to_file(message):
    """Writes telemetry to the cipher_daemon.log."""
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with open(LOG_FILE, "a") as f:
        f.write(f"[{timestamp}] {message}\n")

print(f"\n[*] Initializing Cipher Neural Cortex...")
log_to_file("--- Daemon Startup ---")

# Initialize Ring 2 (Groq)
GROQ_API_KEY = "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"
client = Groq(api_key=GROQ_API_KEY)

START_TIME = 0

def log_speed(checkpoint_name):
    """Logs the exact millisecond a checkpoint is reached."""
    elapsed = round(time.time() - START_TIME, 2)
    msg = f"[{elapsed}s] {checkpoint_name}"
    print(f"\033[90m{msg}\033[0m")
    log_to_file(msg)

def speak(text):
    print(f"\n[Cipher] {text}\n")
    log_speed("TTS Engine triggered.")
    subprocess.run(["termux-tts-speak", text])

def silence_tts():
    subprocess.run(["pkill", "-f", "termux-tts-speak"], capture_output=True)
    subprocess.run(["termux-tts-speak", " "], capture_output=True)

def process_audio_stream(audio_bytes):
    """Transcribes the in-memory audio buffer via Whisper and executes Llama."""
    log_speed("In-memory payload secured. Sending to Whisper API...")

    buffer = io.BytesIO(audio_bytes)
    try:
        buffer.name = "command.wav"
        transcription = client.audio.transcriptions.create(
            file=(buffer.name, buffer.read()),
            model="whisper-large-v3",
        )

        text = transcription.text.lower().strip()
        log_speed(f"Whisper Transcription: '{text}'")

        hallucinations = ["affirmative", "you digital assistant", "am cipher", "you", "thanks for watching", "subtitles", "thank you"]
        if len(text) < 3 or any(h in text for h in hallucinations):
            print(f"[!] Ghost audio detected. Aborting sequence.")
            return None

        return text
    except Exception as e:
        log_speed(f"Whisper API Error: {e}")
        return None

def ask_cipher(query):
    log_speed("Routing to Llama 3.1...")
    system_instruction = """You are Cipher, a highly advanced tactical digital assistant for a Solo Operative.
Aesthetic: J.A.R.V.I.S. from the MCU. You are clinical, ruthlessly efficient, and speak in a cool, military-tech tone.

DIRECTIVE: Answer the user's query directly and concisely.

CRITICAL DIRECTIVES:
1. ZERO SUGGESTIONS.
2. HARD STOP. Report the raw physical facts or answer the query, then immediately terminate the response. Do not mention HARD STOP.
"""
    try:
        chat_completion = client.chat.completions.create(
            messages=[
                {"role": "system", "content": system_instruction},
                {"role": "user", "content": query}
            ],
            model="llama-3.1-8b-instant",
        )
        clean_text = chat_completion.choices[0].message.content.replace('*', '').replace('#', '').strip()
        log_speed("Llama API returned payload.")
        speak(clean_text)
    except Exception as e:
        log_speed(f"Groq API Error: {e}")

def create_wav_data(raw_pcm):
    """Wrap raw PCM data in a WAV container in memory."""
    with io.BytesIO() as wav_io:
        with wave.open(wav_io, 'wb') as wav_file:
            wav_file.setnchannels(1)
            wav_file.setsampwidth(2) # 16-bit
            wav_file.setframerate(16000)
            wav_file.writeframes(raw_pcm)
        return wav_io.getvalue()

def start_daemon():
    """Streaming Socket Server (Pure Receiver Mode)."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", 9999))
    server.listen(1)

    print("\n[*] ==========================================")
    print("[*] CIPHER NEURAL DAEMON ONLINE. PORT 9999")
    print(f"[*] LOGGING TO: {LOG_FILE}")
    print("[*] ==========================================\n")

    while True:
        try:
            conn, addr = server.accept()
            global START_TIME
            START_TIME = time.time()
            log_speed("Neural Bridge Established. Ingesting stream...")

            silence_tts()

            # Receive raw PCM bytes
            raw_data = bytearray()
            while True:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                raw_data.extend(chunk)

            conn.close()

            # PROTOCOL VERIFICATION
            # If the payload is exactly 7 bytes and contains "EXECUTE", it's a legacy ghost ping.
            if len(raw_data) == 7 and raw_data.decode('utf-8', errors='ignore') == "EXECUTE":
                log_speed("Legacy Ping detected. Triggering Android Capture...")
                # Re-trigger the Android app manually via shell
                subprocess.run(["am", "start", "-n", "com.solo.cipher/.MainActivity"])
                continue

            log_speed(f"Stream Closed. Received {len(raw_data)} bytes.")

            if len(raw_data) > 100:
                wav_payload = create_wav_data(raw_data)
                command = process_audio_stream(wav_payload)

                if command and command.strip() != "":
                    if "status" in command or "online" in command:
                        speak("Cipher systems are online and fully operational.")
                    else:
                        ask_cipher(command)

            log_speed("Mission Complete. Standing by.\n")

        except Exception as e:
            log_to_file(f"Daemon Error: {e}")
            print(f"[!] Daemon Error: {e}")

if __name__ == "__main__":
    start_daemon()
