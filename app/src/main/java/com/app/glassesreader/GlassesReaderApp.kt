package com.app.glassesreader

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.app.glassesreader.overlay.ArRecordBroadcast
import com.app.glassesreader.overlay.ArShutterBroadcast

class GlassesReaderApp : Application() {

    private val arShutterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ArShutterBroadcast.ACTION -> {
                    val text = intent.getStringExtra(ArShutterBroadcast.EXTRA_SNAPSHOT_TEXT) ?: ""
                    MainActivity.handleArShutterBroadcast(text)
                }
                ArRecordBroadcast.ACTION_RECORD_START -> MainActivity.handleArRecordStart()
                ArRecordBroadcast.ACTION_RECORD_STOP -> MainActivity.handleArRecordStop()
                else -> Unit
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ArShutterBroadcast.ACTION)
            addAction(ArRecordBroadcast.ACTION_RECORD_START)
            addAction(ArRecordBroadcast.ACTION_RECORD_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(arShutterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(arShutterReceiver, filter)
        }
    }
}
