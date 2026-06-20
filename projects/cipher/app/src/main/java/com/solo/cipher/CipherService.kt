package com.solo.cipher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class CipherService : VoiceInteractionService(), RecognitionListener {

    private val CHANNEL_ID = "CipherServiceChannel"
    private val SAMPLE_RATE = 16000
    private var speechService: SpeechService? = null
    private var model: Model? = null
    
    // State control: Is Cipher listening for the wake word, or actively transcribing a command?
    private var isProcessingCommand = false

    override fun onReady() {
        super.onReady()
        Log.d("Cipher", "VoiceInteractionService onReady triggered.")
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
        initVosk()
    }

    private fun initVosk() {
        Log.d("Cipher", "Initializing Local VOSK Model...")
        StorageService.unpack(this, "model", "model",
            { loadedModel ->
                model = loadedModel
                startWatchMode()
            },
            { e -> Log.e("Cipher", "VOSK Unpack Failed: ${e.message}") }
        )
    }

    // STATE 1: Continuous passive listening for "Hey Cipher"
    private fun startWatchMode() {
        isProcessingCommand = false
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer.setWords(true)
            speechService = SpeechService(recognizer, SAMPLE_RATE.toFloat())
            speechService?.startListening(this)
            Log.d("Cipher", "Ring 0 (Passive Watch) Active. Awaiting wake word...")
        } catch (e: Exception) {
            Log.e("Cipher", "Failed to start passive watch: ${e.message}")
        }
    }

    // STATE 2: Triggered. Switch VOSK context to ingest the actual user command
    private fun igniteCommandCapture() {
        if (isProcessingCommand) return
        isProcessingCommand = true
        
        Log.d("Cipher", "Cipher awakened. Capture mode engaged. Speak now...")
        speechService?.stop() // Reset listener for a clean stream capture
        
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            speechService = SpeechService(recognizer, SAMPLE_RATE.toFloat())
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e("Cipher", "Failed to ignite command capture: ${e.message}")
            startWatchMode()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle physical hardware triggers (Long-press power button / overlay)
        if (intent?.getStringExtra("TRIGGER_TYPE") == "MANUAL") {
            Log.d("Cipher", "Hardware/Manual Trigger Intercepted.")
            igniteCommandCapture()
        }
        return START_STICKY
    }

    // --- VOSK RECOGNITION DECODER LOOP ---

    override fun onPartialResult(hypothesis: String?) {
        if (isProcessingCommand) return // Ignore partial matches while processing commands
        
        try {
            val json = JSONObject(hypothesis ?: "{}")
            val partial = json.optString("partial", "").lowercase()
            if (partial.contains("cipher")) {
                igniteCommandCapture()
            }
        } catch (e: Exception) {}
    }

    override fun onResult(hypothesis: String?) {
        try {
            val json = JSONObject(hypothesis ?: "{}")
            val text = json.optString("text", "").trim()
            if (text.isEmpty()) return

            if (!isProcessingCommand) {
                // We are in watch mode, verify full word match
                if (text.contains("cipher")) {
                    igniteCommandCapture()
                }
            } else {
                // Capture mode active: This text is the actual command payload
                Log.d("Cipher", "Local STT Complete: \"$text\"")
                speechService?.stop()
                
                // Route text payload to the local Termux neural loop asynchronously
                thread {
                    queryLocalNeuralCore(text)
                }
            }
        } catch (e: Exception) {
            Log.e("Cipher", "Result Parsing Error: ${e.message}")
        }
    }

    // --- LOCAL TERMINAL LINK (TERMUX HTTP COMPONENT) ---
    private fun queryLocalNeuralCore(commandText: String) {
        Log.d("Cipher", "Routing payload to local Llama-Server (Port 7000)...")
        var connection: HttpURLConnection? = null
        
        try {
            val url = URL("http://127.0.0.1:7000/completion")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // Format System Prompt and User intent natively in JSON
            val systemPrompt = "You are Cipher, an autonomous on-device core. Your operator is Minato. Output ONLY the exact execution tag: [EXEC:FLASHLIGHT_ON], [EXEC:FLASHLIGHT_OFF], [EXEC:HOTSPOT_OFF], or [EXEC:HOTSPOT_ON]. No commentary."
            
            val jsonPayload = JSONObject().apply {
                put("prompt", "$systemPrompt\nUser: $commandText\nCipher:")
                put("n_predict", 20)
                put("temperature", 0.0)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val modelOutput = jsonResponse.optString("content", "").trim()
                
                Log.d("Cipher", "Local Intelligence Result: $modelOutput")
                
                // Fire privileged commands directly or send back to script loop
                executePrivilegedHardwareAction(modelOutput)
            } else {
                Log.e("Cipher", "Server Error Code: ${connection.responseCode}")
            }

        } catch (e: Exception) {
            Log.e("Cipher", "Failed to connect to local llama-server: ${e.message}")
        } finally {
            connection?.disconnect()
            // Reset back to passive ear mode immediately
            runOnUiThread { startWatchMode() }
        }
    }

    private fun executePrivilegedHardwareAction(actionTag: String) {
        // This is where you map your Shizuku/ADB loopback triggers or native Termux broadcasts
        Log.d("Cipher", "EXCECUTING PRIVILEGED HARDWARE ACTION: $actionTag")
    }

    // --- BOILERPLATE OVERRIDES ---
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { Log.e("Cipher", "VOSK Exception: ${exception?.message}") }
    override fun onTimeout() {}
    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher Core")
            .setContentText("Autonomous Monitoring Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Cipher Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
    
    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
}package com.solo.cipher

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class CipherService : VoiceInteractionService(), RecognitionListener {

    private val CHANNEL_ID = "CipherServiceChannel"
    private val SAMPLE_RATE = 16000
    private var speechService: SpeechService? = null
    private var model: Model? = null
    
    // State control: Is Cipher listening for the wake word, or actively transcribing a command?
    private var isProcessingCommand = false

    override fun onReady() {
        super.onReady()
        Log.d("Cipher", "VoiceInteractionService onReady triggered.")
        createNotificationChannel()
        startForeground(1, createForegroundNotification())
        initVosk()
    }

    private fun initVosk() {
        Log.d("Cipher", "Initializing Local VOSK Model...")
        StorageService.unpack(this, "model", "model",
            { loadedModel ->
                model = loadedModel
                startWatchMode()
            },
            { e -> Log.e("Cipher", "VOSK Unpack Failed: ${e.message}") }
        )
    }

    // STATE 1: Continuous passive listening for "Hey Cipher"
    private fun startWatchMode() {
        isProcessingCommand = false
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer.setWords(true)
            speechService = SpeechService(recognizer, SAMPLE_RATE.toFloat())
            speechService?.startListening(this)
            Log.d("Cipher", "Ring 0 (Passive Watch) Active. Awaiting wake word...")
        } catch (e: Exception) {
            Log.e("Cipher", "Failed to start passive watch: ${e.message}")
        }
    }

    // STATE 2: Triggered. Switch VOSK context to ingest the actual user command
    private fun igniteCommandCapture() {
        if (isProcessingCommand) return
        isProcessingCommand = true
        
        Log.d("Cipher", "Cipher awakened. Capture mode engaged. Speak now...")
        speechService?.stop() // Reset listener for a clean stream capture
        
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            speechService = SpeechService(recognizer, SAMPLE_RATE.toFloat())
            speechService?.startListening(this)
        } catch (e: Exception) {
            Log.e("Cipher", "Failed to ignite command capture: ${e.message}")
            startWatchMode()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle physical hardware triggers (Long-press power button / overlay)
        if (intent?.getStringExtra("TRIGGER_TYPE") == "MANUAL") {
            Log.d("Cipher", "Hardware/Manual Trigger Intercepted.")
            igniteCommandCapture()
        }
        return START_STICKY
    }

    // --- VOSK RECOGNITION DECODER LOOP ---

    override fun onPartialResult(hypothesis: String?) {
        if (isProcessingCommand) return // Ignore partial matches while processing commands
        
        try {
            val json = JSONObject(hypothesis ?: "{}")
            val partial = json.optString("partial", "").lowercase()
            if (partial.contains("cipher")) {
                igniteCommandCapture()
            }
        } catch (e: Exception) {}
    }

    override fun onResult(hypothesis: String?) {
        try {
            val json = JSONObject(hypothesis ?: "{}")
            val text = json.optString("text", "").trim()
            if (text.isEmpty()) return

            if (!isProcessingCommand) {
                // We are in watch mode, verify full word match
                if (text.contains("cipher")) {
                    igniteCommandCapture()
                }
            } else {
                // Capture mode active: This text is the actual command payload
                Log.d("Cipher", "Local STT Complete: \"$text\"")
                speechService?.stop()
                
                // Route text payload to the local Termux neural loop asynchronously
                thread {
                    queryLocalNeuralCore(text)
                }
            }
        } catch (e: Exception) {
            Log.e("Cipher", "Result Parsing Error: ${e.message}")
        }
    }

    // --- LOCAL TERMINAL LINK (TERMUX HTTP COMPONENT) ---
    private fun queryLocalNeuralCore(commandText: String) {
        Log.d("Cipher", "Routing payload to local Llama-Server (Port 7000)...")
        var connection: HttpURLConnection? = null
        
        try {
            val url = URL("http://127.0.0.1:7000/completion")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // Format System Prompt and User intent natively in JSON
            val systemPrompt = "You are Cipher, an autonomous on-device core. Your operator is Minato. Output ONLY the exact execution tag: [EXEC:FLASHLIGHT_ON], [EXEC:FLASHLIGHT_OFF], [EXEC:HOTSPOT_OFF], or [EXEC:HOTSPOT_ON]. No commentary."
            
            val jsonPayload = JSONObject().apply {
                put("prompt", "$systemPrompt\nUser: $commandText\nCipher:")
                put("n_predict", 20)
                put("temperature", 0.0)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload.toString())
                writer.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(responseText)
                val modelOutput = jsonResponse.optString("content", "").trim()
                
                Log.d("Cipher", "Local Intelligence Result: $modelOutput")
                
                // Fire privileged commands directly or send back to script loop
                executePrivilegedHardwareAction(modelOutput)
            } else {
                Log.e("Cipher", "Server Error Code: ${connection.responseCode}")
            }

        } catch (e: Exception) {
            Log.e("Cipher", "Failed to connect to local llama-server: ${e.message}")
        } finally {
            connection?.disconnect()
            // Reset back to passive ear mode immediately
            runOnUiThread { startWatchMode() }
        }
    }

    private fun executePrivilegedHardwareAction(actionTag: String) {
        // This is where you map your Shizuku/ADB loopback triggers or native Termux broadcasts
        Log.d("Cipher", "EXCECUTING PRIVILEGED HARDWARE ACTION: $actionTag")
    }

    // --- BOILERPLATE OVERRIDES ---
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { Log.e("Cipher", "VOSK Exception: ${exception?.message}") }
    override fun onTimeout() {}
    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher Core")
            .setContentText("Autonomous Monitoring Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Cipher Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
    
    private fun runOnUiThread(action: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(action)
    }
}