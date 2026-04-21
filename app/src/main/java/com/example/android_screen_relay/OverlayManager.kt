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

    fun showOverlay() {
        if (overlayView != null) return

        // Ultra-Compact Glass-Badge: Positioned high to avoid header text overlap
        val root = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(14, 6, 14, 6)
            gravity = Gravity.START
            
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f
                setColor(Color.parseColor("#99000000")) // 60% Transparent Black
                setStroke(1, Color.parseColor("#33FFFFFF"))
            }
            elevation = 14f
        }

        // Line 1: AI Model
        val header = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        tvStatusDot = TextView(context).apply {
            text = "●"
            setTextColor(Color.parseColor("#00E5FF")) // Bright Cyan
            textSize = 5.5f
            setPadding(0, 0, 6, 0)
        }
        header.addView(tvStatusDot)

        tvModel = TextView(context).apply {
            text = "AI: Initializing..."
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 8.5f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        header.addView(tvModel)
        root.addView(header)

        // Line 2: RAM
        tvRam = TextView(context).apply {
            text = "RAM: --/-- MB"
            setTextColor(Color.WHITE)
            textSize = 8f
            setPadding(0, 1, 0, 0)
        }
        root.addView(tvRam)

        // Line 3: CPU
        tvCpu = TextView(context).apply {
            text = "CPU: --%"
            setTextColor(Color.WHITE)
            textSize = 8f
            setPadding(0, 1, 0, 0)
        }
        root.addView(tvCpu)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else 
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 80 // Positioned higher up, near the status bar area to avoid titles
        }

        try {
            windowManager.addView(root, params)
            overlayView = root
        } catch (e: Exception) {
            android.util.Log.e("OverlayManager", "Failed to add overlay", e)
        }
    }

    fun updateMetrics(ramUsed: Long, ramTotal: Long, cpu: String, model: String? = null, status: String? = null) {
        val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
        uiHandler.post {
            if (model != null) {
                tvModel?.text = "AI: $model"
            }
            tvRam?.text = "RAM: $ramUsed / $ramTotal MB"
            tvCpu?.text = "CPU: $cpu"
            
            if (status != null && status.isNotEmpty()) {
                if (status.contains("Critical", ignoreCase = true)) {
                    tvStatusDot?.setTextColor(Color.RED)
                } else if (status.contains("Loaded", ignoreCase = true) || status.contains("Released", ignoreCase = true)) {
                    tvStatusDot?.setTextColor(Color.CYAN)
                } else {
                    tvStatusDot?.setTextColor(Color.parseColor("#00E5FF"))
                }
            }
            
            // Dynamic color selection for values
            val pct = if (ramTotal > 0) ramUsed.toDouble() / ramTotal.toDouble() else 0.0
            if (pct > 0.85) {
                tvRam?.setTextColor(Color.parseColor("#FF5252")) // Vibrant Red
            } else if (pct > 0.70) {
                tvRam?.setTextColor(Color.parseColor("#FFD740")) // Vibrant Amber
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
