package com.solo.cipher

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.math.sqrt
import org.json.JSONObject

class CipherService : Service(), RecognitionListener {

    private val CHANNEL_ID = "CipherServiceChannel"
    private val SAMPLE_RATE = 16000
    private var silenceThreshold = 500.0
    private val SILENCE_DURATION_MS = 2000L
    private val MAX_RECORDING_MS = 15000L

    private var speechService: SpeechService? = null
    private var model: Model? = null
    
    private var isStreaming = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("Cipher", "Service onCreate triggered.")
        createNotificationChannel()
        initVosk()
    }

    private fun initVosk() {
        Log.d("Cipher", "Initializing VOSK Model...")
        StorageService.unpack(this, "model", "model",
            { loadedModel ->
                model = loadedModel
                startVoskListening()
            },
            { e ->
                Log.e("Cipher", "VOSK Unpack Failed: ${e.message}")
            }
        )
    }

    private fun startVoskListening() {
        if (isStreaming) return
        try {
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer.setWords(true)
            speechService = SpeechService(recognizer, SAMPLE_RATE.toFloat())
            speechService?.startListening(this)
            Log.d("Cipher", "Ring 0 (VOSK Native) Online. Listening for 'cipher'...")
        } catch (e: Exception) {
            Log.e("Cipher", "VOSK Listener Start Failed: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("Cipher", "Service onStartCommand triggered.")
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cipher")
            .setContentText("Neural Watch Active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        // Handle manual trigger from power button
        if (intent?.getStringExtra("TRIGGER_TYPE") == "MANUAL") {
            Log.d("Cipher", "Manual Trigger Intercepted. Igniting Bridge...")
            igniteNeuralBridge()
        }

        return START_STICKY
    }

    // --- VOSK Recognition Listener ---

    override fun onPartialResult(hypothesis: String?) {
        if (isStreaming) return
        
        try {
            val json = JSONObject(hypothesis ?: "{}")
            val partial = json.optString("partial", "").lowercase()
            if (partial.contains("cipher")) {
                Log.d("Cipher", "Wake Word Intercepted (Partial). Igniting Bridge...")
                igniteNeuralBridge()
            }
        } catch (e: Exception) {}
    }

    override fun onResult(hypothesis: String?) {
        if (isStreaming) return
        
        try {
            val json = JSONObject(hypothesis ?: "{}")
            val text = json.optString("text", "").lowercase()
            if (text.contains("cipher")) {
                Log.d("Cipher", "Wake Word Confirmed. Igniting Bridge...")
                igniteNeuralBridge()
            }
        } catch (e: Exception) {}
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) {
        Log.e("Cipher", "VOSK Error: ${exception?.message}")
    }
    override fun onTimeout() {}

    private fun igniteNeuralBridge() {
        if (isStreaming) return
        isStreaming = true
        
        // Pause VOSK while streaming to Termux to prevent feedback/resource collision
        speechService?.stop()
        
        thread {
            captureAndStreamToTermux()
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureAndStreamToTermux() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )
        } catch (e: SecurityException) {
            Log.e("Cipher", "Mic permission missing")
            resetToWatchMode()
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("Cipher", "AudioRecord not initialized")
            resetToWatchMode()
            return
        }

        var socket: Socket? = null
        var outputStream: OutputStream? = null

        try {
            socket = Socket("127.0.0.1", 9999)
            outputStream = socket.getOutputStream()
            Log.d("Cipher", "Neural Bridge Synchronized. Streaming Command...")
        } catch (e: Exception) {
            Log.e("Cipher", "Bridge Connection Failed: ${e.message}")
            audioRecord.release()
            resetToWatchMode()
            return
        }

        val buffer = ShortArray(minBufferSize)
        var silenceStartTime = 0L
        val startTime = System.currentTimeMillis()

        var lastSample = 0.0
        var filteredSample = 0.0

        audioRecord.startRecording()

        try {
            while (isStreaming) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - startTime > MAX_RECORDING_MS) break

                val readSize = audioRecord.read(buffer, 0, buffer.size)
                if (readSize > 0) {
                    val byteBuffer = ByteArray(readSize * 2)
                    var sum = 0.0

                    for (i in 0 until readSize) {
                        val sample = buffer[i]
                        filteredSample = sample - lastSample + 0.995 * filteredSample
                        lastSample = sample.toDouble()
                        
                        byteBuffer[i * 2] = (sample.toInt() and 0x00FF).toByte()
                        byteBuffer[i * 2 + 1] = (sample.toInt() shr 8).toByte()
                        sum += filteredSample * filteredSample
                    }

                    outputStream?.write(byteBuffer)

                    val rms = sqrt(sum / readSize)
                    if (rms < silenceThreshold) {
                        if (silenceStartTime == 0L) silenceStartTime = currentTime
                        else if (currentTime - silenceStartTime > SILENCE_DURATION_MS) break
                    } else {
                        silenceStartTime = 0L
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Cipher", "Streaming Error: ${e.message}")
        } finally {
            audioRecord.stop()
            audioRecord.release()
            try {
                outputStream?.flush()
                outputStream?.close()
                socket?.close()
            } catch (e: Exception) {}
            Log.d("Cipher", "Bridge Severed. Returning to Neural Watch.")
            resetToWatchMode()
        }
    }

    private fun resetToWatchMode() {
        isStreaming = false
        speechService?.startListening(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "Cipher Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
