package com.example.android_screen_relay

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class ConnectionRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make window transparent to show rounded corners correctly
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Main Card Layout with Rounded Corners
        val cardBackground = GradientDrawable().apply {
            setColor(Color.WHITE) // White card
            cornerRadius = 40f    // Rounded corners
        }

        // Parent container mimicking a CardView
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(70, 80, 70, 80)
            gravity = Gravity.CENTER_HORIZONTAL
            background = cardBackground
            elevation = 40f
        }

        // Icon
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_info) 
            setColorFilter(Color.parseColor("#007AFF")) // System Blue-ish
            layoutParams = LinearLayout.LayoutParams(140, 140).apply {
                bottomMargin = 40
            }
        }

        // Title
        val title = TextView(this).apply {
            text = "Connection Request"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#1C1C1E")) // Dark Grey/Black
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 24
            }
        }
        
        val requestId = intent.getStringExtra("REQUEST_ID")
        val clientIp = intent.getStringExtra("CLIENT_IP") ?: "Unknown"

        // Message Body
        val message = TextView(this).apply {
            text = "A Web Client at $clientIp\nis requesting to control this device."
            textSize = 16f
            setTextColor(Color.parseColor("#8E8E93")) // Secondary Text Color
            gravity = Gravity.CENTER
            setLineSpacing(10f, 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 70
            }
        }

        // Buttons Container
        val btnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            weightSum = 2f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Deny Button (Subtle/Ghost style)
        val denyBg = GradientDrawable().apply {
            setColor(Color.parseColor("#F2F2F7")) // System Gray 6
            cornerRadius = 24f
        }
        val btnDeny = Button(this).apply {
            text = "Deny"
            background = denyBg
            setTextColor(Color.parseColor("#FF3B30")) // System Red
            textSize = 16f
            stateListAnimator = null // Remove elevation shadow for flat iOS-style look
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                rightMargin = 20
            }
            setOnClickListener {
                if (requestId != null) {
                    RelayService.getInstance()?.denyConnection(requestId)
                    Toast.makeText(context, "Connection Denied", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }

        // Accept Button (Primary style)
        val acceptBg = GradientDrawable().apply {
            setColor(Color.parseColor("#34C759")) // System Green
            cornerRadius = 24f
        }
        val btnAccept = Button(this).apply {
            text = "Allow"
            background = acceptBg
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            stateListAnimator = null
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, 140, 1f).apply {
                leftMargin = 20
            }
            setOnClickListener {
                if (requestId != null) {
                    RelayService.getInstance()?.approveConnection(requestId)
                    Toast.makeText(context, "Connection Approved", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
        }

        btnLayout.addView(btnDeny)
        btnLayout.addView(btnAccept)

        layout.addView(icon)
        layout.addView(title)
        layout.addView(message)
        layout.addView(btnLayout)

        setContentView(layout)
        
        // Adjust Dialog Window Size
        val displayMetrics = resources.displayMetrics
        val dialogWidth = (displayMetrics.widthPixels * 0.85).toInt() // 85% width
        window.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
