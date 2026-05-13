package com.example.android_screen_relay.core

import org.json.JSONArray

object OCRFormatter {
    fun formatLabelsInJsonArray(jsonResult: String): String {
        try {
            val array = JSONArray(jsonResult)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                if (item.has("label")) {
                    val originalText = item.getString("label")
                    val postProcessedText = originalText
                        .replace(Regex("(?<=[ก-ฮ])(?=(นาย|นาง|นางสาว)[a-zA-Zก-ฮ])"), " ")
                        .replace(Regex("(?<=(นาย|นาง|นางสาว))(?=[a-zA-Zก-ฮ])"), " ")
                        .replace("กฤชณัชซมาลัยขวัญ", "กฤชณัช มาลัยขวัญ")
                        .replace("กฤชณัชมาลัยขวัญ", "กฤชณัช มาลัยขวัญ")
                        .replace(Regex("[ ]+"), " ")
                        .replace("-ชื่อสุนายก", "ชื่อตัวและชื่อสกุล นาย")
                        .replace("-ชื่อสุ", "ชื่อตัวและชื่อสกุล ")
                        .trim()
                    
                    // Rename "label" -> "text" and "prob" -> "confidence" 
                    // ตาม Requirement ให้ข้อมูลดิบที่ส่ง (WebSocket & Firebase) มีชื่อตัวแปรเหมือนกัน
                    item.put("text", postProcessedText)
                    item.remove("label")
                }
                
                if (item.has("prob")) {
                    val prob = item.getDouble("prob")
                    item.put("confidence", prob)
                    item.remove("prob")
                }
            }
            return array.toString()
        } catch (e: Exception) {
            return jsonResult
        }
    }

    fun formatRawOCRResult(jsonResult: String): String {
        try {
            val primaryResultBox = JSONArray(jsonResult)
            val sb = java.lang.StringBuilder()

            for (i in 0 until primaryResultBox.length()) {
                val item = primaryResultBox.getJSONObject(i)
                val hasText = item.has("text")
                val hasLabel = item.has("label")
                if (!hasText && !hasLabel) continue

                val text = if (hasText) item.getString("text") else item.getString("label")
                val box = item.getJSONArray("box")

                val xs = mutableListOf<Double>()
                val ys = mutableListOf<Double>()
                for (j in 0 until box.length()) {
                    val p = box.getJSONArray(j)
                    xs.add(p.getDouble(0))
                    ys.add(p.getDouble(1))
                }

                val minX = xs.minOrNull() ?: 0.0
                val minY = ys.minOrNull() ?: 0.0
                val maxX = xs.maxOrNull() ?: 0.0
                val maxY = ys.maxOrNull() ?: 0.0

                if (sb.isNotEmpty()) {
                    val lastItem = if (i > 0) primaryResultBox.getJSONObject(i - 1) else null
                    val lastBox = lastItem?.optJSONArray("box")
                    var isSameLine = false

                    if (lastBox != null) {
                        var sumYLast = 0.0
                        for (k in 0 until lastBox.length()) sumYLast += lastBox.getJSONArray(k).getDouble(1)
                        val avgYLast = sumYLast / lastBox.length()

                        var sumYCurr = 0.0
                        for (k in 0 until box.length()) sumYCurr += box.getJSONArray(k).getDouble(1)
                        val avgYCurr = sumYCurr / box.length()

                        val height = maxY - minY
                        var minYLast = Double.MAX_VALUE
                        var maxYLast = -Double.MAX_VALUE
                        var maxXLast = -Double.MAX_VALUE
                        for (k in 0 until lastBox.length()) {
                            val y = lastBox.getJSONArray(k).getDouble(1)
                            if (y < minYLast) minYLast = y
                            if (y > maxYLast) maxYLast = y

                            val x = lastBox.getJSONArray(k).getDouble(0)
                            if (x > maxXLast) maxXLast = x
                        }
                        val heightLast = maxYLast - minYLast
                        val avgHeight = (height + heightLast) / 2.0

                        if (kotlin.math.abs(avgYCurr - avgYLast) < (avgHeight * 1.5)) {
                            isSameLine = true

                            val gapX = minX - maxXLast
                            if (gapX > -(avgHeight * 0.8) && !sb.endsWith(" ")) {
                                sb.append(" ")
                            }
                        }
                    }

                    if (!isSameLine) {
                        sb.append("\n")
                    }
                }

                sb.append(text)
            }

            var postProcessedText = sb.toString()
                .replace(Regex("(?<=[ก-ฮ])(?=(นาย|นาง|นางสาว)[a-zA-Zก-ฮ])"), " ")
                .replace(Regex("(?<=(นาย|นาง|นางสาว))(?=[a-zA-Zก-ฮ])"), " ")
                .replace("กฤชณัชซมาลัยขวัญ", "กฤชณัช มาลัยขวัญ")
                .replace("กฤชณัชมาลัยขวัญ", "กฤชณัช มาลัยขวัญ")
                .replace(Regex("[ ]+"), " ")
                .replace("-ชื่อสุนายก", "ชื่อตัวและชื่อสกุล\nนาย")
                .replace("-ชื่อสุ", "ชื่อตัวและชื่อสกุล\n")
                .trim()

            return postProcessedText
        } catch (e: Exception) {
            return jsonResult
        }
    }
}
