package com.example.android_screen_relay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

enum class AppState {
    FOREGROUND,
    BACKGROUND,
    SCREEN_OFF,
    SCREEN_ON
}

object AppStateManager : DefaultLifecycleObserver {
    private const val TAG = "AppStateManager"
    var currentState: AppState = AppState.BACKGROUND
        private set

    private var stateChangeListener: ((AppState) -> Unit)? = null
    private var isInitialized = false
    
    // BroadcastReceiver for screen state
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen went OFF")
                    updateState(AppState.SCREEN_OFF)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.d(TAG, "Screen went ON")
                    updateState(AppState.SCREEN_ON)
                }
            }
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        
        // Register Lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        
        // Register Screen state receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.applicationContext.registerReceiver(screenStateReceiver, filter)
        Log.d(TAG, "AppStateManager initialized")
    }

    fun setOnStateChangeListener(listener: (AppState) -> Unit) {
        stateChangeListener = listener
        // Send initial state immediately
        listener.invoke(currentState)
    }

    private fun updateState(newState: AppState) {
        if (currentState != newState) {
            currentState = newState
            Log.d(TAG, "App State changed to: $newState")
            stateChangeListener?.invoke(newState)
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        updateState(AppState.FOREGROUND)
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        updateState(AppState.BACKGROUND)
    }
}
