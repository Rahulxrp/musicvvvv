package com.example.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object CobaltService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mediaTypeJson = "application/json; charset=utf-8".toMediaType()

    // We define a list of working public Cobalt backend API endpoints for seamless failover!
    private val cobaltEndpoints = listOf(
        "https://api.cobalt.tools/api/json",
        "https://co.wuk.sh/api/json"
    )

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val ytUrl = "https://www.youtube.com/watch?v=$videoId"
        
        // Construct the payload supporting various Cobalt version specifications (v7 & v10)
        val jsonPayload = """
            {
              "url": "$ytUrl",
              "downloadMode": "audio",
              "audioFormat": "mp3",
              "isAudioOnly": true,
              "aFormat": "mp3",
              "vCodec": "h264",
              "audioBitrate": "128"
            }
        """.trimIndent()

        val body = jsonPayload.toRequestBody(mediaTypeJson)

        // Try each endpoint sequentially until one succeeds. Robust, ad-free, full fallback!
        for (apiEndpoint in cobaltEndpoints) {
            try {
                val request = Request.Builder()
                    .url(apiEndpoint)
                    .post(body)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseStr = response.body?.string() ?: ""
                    Log.d("CobaltService", "Response from $apiEndpoint: $responseStr")
                    if (response.isSuccessful && responseStr.isNotEmpty()) {
                        // Extract status: "status":"..."
                        val status = extractValue(responseStr, "\"status\":\"", "\"")
                        if (status != "error") {
                            val downloadUrl = extractValue(responseStr, "\"url\":\"", "\"")
                            if (downloadUrl != null && downloadUrl.isNotEmpty()) {
                                // Return parsed URL while removing backslash escapes
                                return@withContext downloadUrl.replace("\\/", "/")
                            }
                        } else {
                            val errorMsg = extractValue(responseStr, "\"text\":\"", "\"")
                            Log.e("CobaltService", "Cobalt backend error: $errorMsg")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CobaltService", "Error contacting Cobalt endpoint $apiEndpoint: ${e.message}")
            }
        }
        return@withContext null
    }

    private fun extractValue(text: String, prefix: String, suffix: String): String? {
        val start = text.indexOf(prefix)
        if (start == -1) return null
        val remain = text.substring(start + prefix.length)
        val end = remain.indexOf(suffix)
        if (end == -1) return null
        return remain.substring(0, end)
    }
}
