package com.example.android_screen_relay

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

object GoogleSheetsLogger {
    // Replace with your Google Apps Script Web App URL
    const val SCRIPT_URL = "https://script.google.com/macros/s/AKfycbxCwzLppgTDjSRRGA_cXbSpnKxp1pPD0YvdxREqmjd8RlP3qLAl8TH0c7GnfAhkadCW/exec"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun log(jsonPayload: String) {
        val body = jsonPayload.toRequestBody("text/plain;charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(SCRIPT_URL)
            .post(body)
            .build()
            
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Silently fail if no network
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }
}
