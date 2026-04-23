package com.example.android_screen_relay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class OverlayManager(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    
    private var tvModel: TextView? = null
    private var tvRam: TextView? = null
    private var tvCpu: TextView? = null
    private var tvStatusDot: TextView? = null
    
    // New fields
    private var tvInputSize: TextView? = null
    private var tvFps: TextView? = null
    private var tvLatencies: TextView? = null
    private var tvStatus: TextView? = null

    fun showOverlay() {
        if (overlayView != null) return

        // Plain Text Monitor: Background is transparent
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(16, 4, 16, 4)
            gravity = Gravity.START
            
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT) // Transparent background
            }
        }

        // New Stats Lines
        tvInputSize = createStatTextView()
        root.addView(tvInputSize)
        
        tvFps = createStatTextView()
        root.addView(tvFps)
        
        tvLatencies = createStatTextView()
        root.addView(tvLatencies)

        // Line: RAM
        tvRam = createStatTextView("RAM: --/-- MB")
        root.addView(tvRam)

        // Line: CPU
        tvCpu = createStatTextView("CPU: --%")
        root.addView(tvCpu)
        
        // Status Line
        tvStatus = createStatTextView("Status: Searching...")
        tvStatus?.setTextColor(Color.parseColor("#4CAF50")) // Green
        root.addView(tvStatus)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or 
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 10
            y = 10 
        }

        try {
            windowManager.addView(root, params)
            overlayView = root
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Failed to add overlay", e)
        }
    }

    private fun createStatTextView(initialText: String = ""): TextView {
        return TextView(context).apply {
            text = initialText
            setTextColor(Color.WHITE)
            textSize = 9f
            setPadding(0, 1, 0, 0)
            // Add shadow for better readability on light backgrounds
            setShadowLayer(3f, 2f, 2f, Color.BLACK)
        }
    }

    fun updateMetrics(
        ramUsed: Long, 
        ramTotal: Long, 
        cpu: String, 
        model: String? = null, 
        status: String? = null,
        inputSize: String? = null,
        fps: Int? = null,
        frameLatency: Long? = null,
        detectorLatency: Long? = null
    ) {
        val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
        uiHandler.post {
            // Updated format based on user request
            if (inputSize != null) tvInputSize?.text = "InputImage size: $inputSize"
            
            if (fps != null && frameLatency != null) {
                tvFps?.text = "FPS: $fps, Frame latency: $frameLatency ms"
            } else if (fps != null) {
                tvFps?.text = "FPS: $fps"
            }
            
            if (detectorLatency != null) {
                tvLatencies?.text = "Detector latency: $detectorLatency ms"
            }
            
            tvRam?.text = "RAM: $ramUsed / $ramTotal MB"
            tvCpu?.text = "CPU: $cpu"
            
            if (status != null && status.isNotEmpty()) {
                tvStatus?.text = "Status snap: $status"
                // Keep the color logic for status dot if it was still there (though we removed the dot, 
                // we might want to color the text instead or just keep it simple)
                if (status.contains("success", ignoreCase = true)) {
                    tvStatus?.setTextColor(Color.GREEN)
                } else if (status.contains("Critical", ignoreCase = true)) {
                    tvStatus?.setTextColor(Color.RED)
                } else {
                    tvStatus?.setTextColor(Color.CYAN)
                }
            }
            
            // Dynamic color selection for RAM
            val pct = if (ramTotal > 0) ramUsed.toDouble() / ramTotal.toDouble() else 0.0
            if (pct > 0.85) {
                tvRam?.setTextColor(Color.parseColor("#FF5252")) 
            } else if (pct > 0.70) {
                tvRam?.setTextColor(Color.parseColor("#FFD740")) 
            } else {
                tvRam?.setTextColor(Color.WHITE)
            }
        }
    }

    fun removeOverlay() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {}
            overlayView = null
        }
    }
}
