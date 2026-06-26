import subprocess
import json

def listen_and_echo():
    print("[*] Initializing audio intercept...")
    
    # Trigger Android's native speech recognition
    result = subprocess.run(["termux-dialog", "speech"], capture_output=True, text=True)
    
    try:
        # Parse the JSON response returned by the dialog
        data = json.loads(result.stdout)
        
        # 'code' -2 means the user canceled. 'code' 0 means success.
        if data.get("code") == 0:
            command = data.get("text", "").lower()
            print(f"[*] Intercepted Command: {command}")
            
            # Echo it back via TTS to verify the loop
            subprocess.run(["termux-tts-speak", f"You said: {command}"])
        else:
            print("[!] Audio intercept canceled or failed.")
            
    except json.JSONDecodeError:
        print("[!] Fatal error decoding speech API output.")

listen_and_echo()
