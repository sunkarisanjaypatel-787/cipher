import subprocess
import json

def speak(text):
    """Pipes text directly to the OnePlus native TTS engine."""
    subprocess.run(["termux-tts-speak", text])

def get_battery_status():
    """Executes the Termux API command and parses the JSON output."""
    result = subprocess.run(["termux-battery-status"], capture_output=True, text=True)
    try:
        data = json.loads(result.stdout)
        percentage = data.get("percentage", "Unknown")
        speak(f"Current power levels are at {percentage} percent.")
    except json.JSONDecodeError:
        speak("Error reading battery telemetry.")

# Execution
get_battery_status()

