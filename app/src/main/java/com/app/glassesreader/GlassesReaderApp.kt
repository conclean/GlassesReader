package com.app.glassesreader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.app.glassesreader.overlay.ArShutterBroadcast

class GlassesReaderApp : Application() {

    private val arShutterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ArShutterBroadcast.ACTION) return
            val text = intent.getStringExtra(ArShutterBroadcast.EXTRA_SNAPSHOT_TEXT) ?: ""
            MainActivity.handleArShutterBroadcast(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ArShutterBroadcast.ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(arShutterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(arShutterReceiver, filter)
        }
    }
}
