package com.example.android_screen_relay.ocr

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class PaddleOCR {
    companion object {
        init {
            System.loadLibrary("paddleocrncnn")
        }
    }

    inner class Obj {
        var x0: Float = 0f
        var y0: Float = 0f
        var x1: Float = 0f
        var y1: Float = 0f
        var x2: Float = 0f
        var y2: Float = 0f
        var x3: Float = 0f
        var y3: Float = 0f
        var label: String? = null
        var prob: Float = 0f
    }

    external fun init(assetManager: AssetManager): Boolean
    private external fun detectNative(bitmap: Bitmap, use_gpu: Boolean): Array<Obj>

    fun detect(bitmap: Bitmap): String {
        val result = detectNative(bitmap, false)
        val jsonArray = JSONArray()

        result.forEach { obj ->
            val jsonObject = JSONObject()
            jsonObject.put("x0", obj.x0)
            jsonObject.put("y0", obj.y0)
            jsonObject.put("x1", obj.x1)
            jsonObject.put("y1", obj.y1)
            jsonObject.put("x2", obj.x2)
            jsonObject.put("y2", obj.y2)
            jsonObject.put("x3", obj.x3)
            jsonObject.put("y3", obj.y3)
            jsonObject.put("label", obj.label)
            
            // Fix for org.json.JSONException: Forbidden numeric value: NaN
            val safeProb = if (obj.prob.isNaN() || obj.prob.isInfinite()) 0f else obj.prob
            jsonObject.put("prob", safeProb)

            // OCRScreen compatibility: box [[x,y],...]
            val box = JSONArray()
            box.put(JSONArray().put(obj.x0).put(obj.y0))
            box.put(JSONArray().put(obj.x1).put(obj.y1))
            box.put(JSONArray().put(obj.x2).put(obj.y2))
            box.put(JSONArray().put(obj.x3).put(obj.y3))
            jsonObject.put("box", box)

            jsonArray.put(jsonObject)
        }
        return jsonArray.toString()
    }


    fun initModel(context: Context): Boolean {
        return try {
            init(context.assets)
        } catch (e: Exception) {
            Log.e("PaddleOCR", "Error initializing model", e)
            false
        }
    }
}
