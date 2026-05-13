package com.example.android_screen_relay.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TextRecognitionProcessor : AIProcessor {
    override val name: String = "ML Kit Text Recognition"
    private var recognizer: TextRecognizer? = null

    override fun init(context: Context, config: AIConfig): Boolean {
        return try {
            // Use default options for Latin script (standard text recognition)
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun process(bitmap: Bitmap, options: Map<String, Any>): AIResult {
        val currentRecognizer = recognizer ?: return AIResult(false, emptyList(), 0, "Not initialized")
        val startTime = System.currentTimeMillis()

        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = currentRecognizer.process(image)
            
            // Wait for task completion (blocking call for AIProcessor interface)
            val visionText = Tasks.await(task)
            val duration = System.currentTimeMillis() - startTime

            val items = mutableListOf<AIDetectedItem>()
            
            for (block in visionText.textBlocks) {
                items.add(
                    AIDetectedItem(
                        label = block.text,
                        confidence = 1.0f, // ML Kit Text doesn't provide confidence per block directly in this API
                        boundingBox = RectF(block.boundingBox),
                        extra = mapOf(
                            "type" to "block",
                            "language" to (block.recognizedLanguage ?: "unknown"),
                            "lines_count" to block.lines.size
                        )
                    )
                )
            }

            AIResult(true, items, duration)
        } catch (e: Exception) {
            e.printStackTrace()
            AIResult(false, emptyList(), 0, e.message)
        }
    }

    override fun release() {
        recognizer?.close()
        recognizer = null
    }
}
