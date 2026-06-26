package com.solo.cipher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {

    private val permissionRequestCode = 999

    // The Kill Switch: Listens for the Sherpa-ONNX Daemon's signal that the Mic is secured
    private val killReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.solo.cipher.KILL_PROXY") {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw a physical terminal UI to force the OS to recognize the Foreground state
        val layout = LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = "[+] SECURING HARDWARE TOKEN..."
                setTextColor(Color.GREEN)
                textSize = 16f
            })
        }
        setContentView(layout)

        // Register the Kill Switch
        val filter = IntentFilter("com.solo.cipher.KILL_PROXY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(killReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(killReceiver, filter, Context.RECEIVER_EXPORTED)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), permissionRequestCode)
        } else {
            igniteCipherCore()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            igniteCipherCore()
        } else {
            Toast.makeText(this, "Microphone access is mandatory.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun igniteCipherCore() {
        try {
            val serviceIntent = Intent(this, CipherService::class.java)

            // Tell the Service HOW we booted it (Power Button vs App Icon)
            if (intent?.action == Intent.ACTION_ASSIST) {
                serviceIntent.action = "ACTION_ASSIST_TRIGGER"
            } else {
                serviceIntent.action = "ACTION_BOOT_DAEMON"
            }

            // Using startForegroundService explicitly guarantees the service boots even if Athena killed it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(killReceiver)
    }
}