package com.solo.cipher

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val serviceIntent = Intent(this, CipherService::class.java)
        // Add an extra to signal manual trigger
        serviceIntent.putExtra("TRIGGER_TYPE", "MANUAL")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        finish()
    }
}
