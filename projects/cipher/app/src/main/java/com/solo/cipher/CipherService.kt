package com.solo.cipher

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.k2fsa.sherpa.onnx.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.min

class CipherService : Service() {

    private val channelId = "CipherCoreChannel"
    private var isListening = false
    private var isProcessingCommand = false

    // Core Sensory Variables
    private var audioRecord: AudioRecord? = null
    private var offlineRecognizer: OfflineRecognizer? = null

    // UX/UI Variables
    private lateinit var soundPool: SoundPool
    private var pingSoundId: Int = 0
    private var abortSoundId: Int = 0
    private lateinit var windowManager: WindowManager
    private var blobView: ReactiveBlobView? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initSherpaOnnx()
        initAcousticMatrix()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isListening && !isProcessingCommand) {
            startAudioSensor()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        offlineRecognizer?.release()
        soundPool.release()
        removeVisualOverlay()
    }

    // --- STAGE 1: SYSTEM INITIALIZATION ---

    private fun initSherpaOnnx() {
        try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "model/tiny.en-encoder.int8.onnx",
                        decoder = "model/tiny.en-decoder.int8.onnx"
                    ),
                    tokens = "model/tiny.en-tokens.txt",
                    numThreads = 4,
                    debug = false
                )
            )
            offlineRecognizer = OfflineRecognizer(assetManager = assets, config = config)
        } catch (e: Exception) {
            Log.e("Cipher", "[-] WHISPER INIT FAILED: ${e.message}")
        }
    }

    private fun initAcousticMatrix() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        // Load zero-latency pings
        pingSoundId = soundPool.load(this, R.raw.ping, 1)
        abortSoundId = soundPool.load(this, R.raw.abort, 1)
    }

    // --- STAGE 2: SENSORY CAPTURE & REACTIVE UI ---

    @SuppressLint("MissingPermission")
    private fun startAudioSensor() {
        isListening = true
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        audioRecord?.startRecording()

        // Ignite UX Matrix
        soundPool.play(pingSoundId, 1f, 1f, 0, 0, 1f)
        runOnUiThread { showVisualOverlay() }

        thread {
            val audioData = ShortArray(bufferSize)
            val floatBuffer = mutableListOf<Float>()

            var silenceFrames = 0
            var totalFrames = 0
            val silenceThreshold = 0.025f // Re-calibrated for fast speech
            val maxSilenceFrames = 14 // ~0.85s silence gate
            val absoluteMaxFrames = 80 // 5-second hard kill

            while (isListening) {
                val read = audioRecord?.read(audioData, 0, audioData.size) ?: 0
                if (read > 0) {
                    var frameEnergy = 0f
                    for (i in 0 until read) {
                        val f = audioData[i].toFloat() / 32768.0f
                        floatBuffer.add(f)
                        frameEnergy += f * f
                    }

                    val rms = Math.sqrt((frameEnergy / read).toDouble()).toFloat()
                    totalFrames++

                    // Feed RMS to the Visual Overlay
                    runOnUiThread { blobView?.updateRms(rms) }

                    if (rms < silenceThreshold) {
                        silenceFrames++
                    } else {
                        silenceFrames = 0
                    }

                    if ((silenceFrames > maxSilenceFrames && floatBuffer.size > 8000) || totalFrames >= absoluteMaxFrames) {
                        isListening = false
                    }
                }
            }

            audioRecord?.stop()
            runOnUiThread { removeVisualOverlay() } // Extinguish visual matrix

            // Decode Audio
            val stream = offlineRecognizer?.createStream() ?: return@thread
            stream.acceptWaveform(floatBuffer.toFloatArray(), sampleRate)
            offlineRecognizer?.decode(stream)

            val rawText = offlineRecognizer?.getResult(stream)?.text?.trim()?.lowercase() ?: ""
            stream.release()

            val text = rawText.replace(Regex("[.,!?]"), "").trim()
            val phantomHallucinations = listOf(
                "thank you", "you", "subscribe", "thanks for watching",
                "ammen", "okay", "yeah", "bye", "to be continued"
            )

            if (text.isNotBlank() && !phantomHallucinations.contains(text)) {
                isProcessingCommand = true
                thread { routeCognitivePayload(text) }
            } else {
                // Abort Sequence
                soundPool.play(abortSoundId, 1f, 1f, 0, 0, 1f)
            }
        }
    }

    // --- STAGE 3: DETERMINISTIC VOICE CLI ROUTER ---

    private fun routeCognitivePayload(cleanCommand: String) {
        Log.d("Cipher", "Routing CLI Command: \"$cleanCommand\"")

        // TIER 1: EXACT DETERMINISTIC MATCHING
        when {
            cleanCommand.contains("flashlight on") || cleanCommand.contains("torch on") -> {
                executePrivilegedHardwareAction("[EXEC:FLASHLIGHT_ON]")
                triggerHapticPulse(true)
                resetSystemLock()
                return
            }
            cleanCommand.contains("flashlight off") || cleanCommand.contains("torch off") -> {
                executePrivilegedHardwareAction("[EXEC:FLASHLIGHT_OFF]")
                triggerHapticPulse(true)
                resetSystemLock()
                return
            }
            // Add Future Bash/Python Termux script triggers here
        }

        // --- FALLBACK: LLM QUERY ---
        queryLocalNeuralCore(cleanCommand)
    }

    private fun queryLocalNeuralCore(cleanCommand: String) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("http://127.0.0.1:7000/completion")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 60000

            val systemPrompt = "Map intent to exact tag: [EXEC:FLASHLIGHT_ON], [EXEC:FLASHLIGHT_OFF], [EXEC:HOTSPOT_ON], [EXEC:HOTSPOT_OFF], or [EXEC:NULL]. Output only the tag."
            val jsonPayload = JSONObject().apply {
                put("prompt", "$systemPrompt\nUser: $cleanCommand\nCore:")
                put("n_predict", 10)
                put("temperature", 0.0)
            }

            OutputStreamWriter(connection.outputStream, "UTF-8").use {
                it.write(jsonPayload.toString())
                it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val rawOutput = JSONObject(responseText).optString("content", "").trim()

                val finalCommand = when {
                    rawOutput.contains("[EXEC:FLASHLIGHT_ON]") -> "[EXEC:FLASHLIGHT_ON]"
                    rawOutput.contains("[EXEC:FLASHLIGHT_OFF]") -> "[EXEC:FLASHLIGHT_OFF]"
                    else -> "[EXEC:NULL]"
                }

                if (finalCommand != "[EXEC:NULL]") {
                    executePrivilegedHardwareAction(finalCommand)
                    triggerHapticPulse(true)
                } else {
                    triggerHapticPulse(false)
                }
            }
        } catch (e: Exception) {
            triggerHapticPulse(false)
        } finally {
            connection?.disconnect()
            resetSystemLock()
        }
    }

    // --- STAGE 4: HARDWARE EXECUTION ---

    @SuppressLint("SdCardPath")
    private fun executePrivilegedHardwareAction(actionTag: String) {
        val intent = Intent()
        intent.setClassName("com.termux", "com.termux.app.RunCommandService")
        intent.action = "com.termux.RUN_COMMAND"
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)

        when (actionTag) {
            "[EXEC:FLASHLIGHT_ON]" -> {
                intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/termux-torch")
                intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("on"))
            }
            "[EXEC:FLASHLIGHT_OFF]" -> {
                intent.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/termux-torch")
                intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("off"))
            }
        }

        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.e("Cipher", "Termux Delivery Failed.")
        }
    }

    // --- UX HELPERS ---

    private fun triggerHapticPulse(success: Boolean) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (success) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            // Double pulse for failure
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 40), -1))
        }
    }

    private fun showVisualOverlay() {
        if (blobView != null) return

        blobView = ReactiveBlobView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // Locks blob to bottom center of screen
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 100 // Pixel offset from bottom

        windowManager.addView(blobView, params)
    }

    private fun removeVisualOverlay() {
        blobView?.let {
            windowManager.removeView(it)
            blobView = null
        }
    }

    private fun resetSystemLock() {
        isProcessingCommand = false
    }

    private fun runOnUiThread(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(channelId, "Cipher Core", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
    }

    // --- CUSTOM REACTIVE VIEW ---
    private inner class ReactiveBlobView(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private var currentRadius = 30f
        private val baseRadius = 30f
        private val maxRadius = 150f

        fun updateRms(rms: Float) {
            // Scale RMS (usually 0.0 - 0.1) to visually noticeable expansion
            val targetRadius = baseRadius + (rms * 1500f)
            currentRadius = min(targetRadius, maxRadius)
            invalidate() // Forces redraw
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension((maxRadius * 2).toInt(), (maxRadius * 2).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawCircle(width / 2f, height / 2f, currentRadius, paint)
        }
    }
}