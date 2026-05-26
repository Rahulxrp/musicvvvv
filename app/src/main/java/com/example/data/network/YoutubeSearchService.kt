package com.example.data.network

import android.util.Log
import com.example.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.regex.Pattern

object YoutubeSearchService {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        val songList = mutableListOf<Song>()
        try {
            val encodedQuery = URLEncoder.encode("$query", "UTF-8")
            val url = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAQ%253D%253D" // filter ONLY videos
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val html = response.body?.string() ?: return@withContext emptyList()
                
                // Partition html by videoRenderer blocks
                val blocks = html.split("\"videoRenderer\":")
                for (i in 1 until blocks.size) { // Skip the first block as it's the head HTML
                    val block = blocks[i]
                    
                    // Extract videoId: "videoId":"..."
                    val videoId = extractValue(block, "\"videoId\":\"", "\"") ?: continue
                    if (videoId.length != 11) continue
                    
                    // Extract title: "title":{"runs":[{"text":"..."}]
                    var title = extractValue(block, "\"title\":{\"runs\":[{\"text\":\"", "\"}")
                    if (title == null) {
                        title = extractValue(block, "\"title\":{\"simpleText\":\"", "\"")
                    }
                    if (title == null) {
                        title = "Unknown Track"
                    } else {
                        title = unescapeUnicode(title)
                    }
                    
                    // Extract artist: "ownerText":{"runs":[{"text":"..."}] or "longBylineText":{"runs":[{"text":"..."}]
                    var artist = extractValue(block, "\"longBylineText\":{\"runs\":[{\"text\":\"", "\"}")
                    if (artist == null) {
                        artist = extractValue(block, "\"ownerText\":{\"runs\":[{\"text\":\"", "\"}")
                    }
                    if (artist == null) {
                        artist = "Unknown Artist"
                    } else {
                        artist = unescapeUnicode(artist)
                    }
                    
                    // Extract duration text
                    val lengthSection = if (block.contains("\"lengthText\":")) block.substringAfter("\"lengthText\":") else ""
                    val durationText = extractValue(lengthSection, "\"simpleText\":\"", "\"") ?: "--:--"
                    
                    // Explicitly skip unwanted channels or lists
                    if (block.contains("\"playlistId\":")) continue
                    
                    val thumbnailUrl = "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
                    
                    songList.add(
                        Song(
                            videoId = videoId,
                            title = title,
                            artist = artist,
                            durationText = durationText,
                            thumbnailUrl = thumbnailUrl
                        )
                    )
                    
                    if (songList.size >= 15) break
                }
            }
        } catch (e: Exception) {
            Log.e("YoutubeSearchService", "Failed to search songs on YouTube: ${e.message}", e)
        }
        return@withContext songList
    }

    private fun extractValue(text: String, prefix: String, suffix: String): String? {
        val start = text.indexOf(prefix)
        if (start == -1) return null
        val remain = text.substring(start + prefix.length)
        val end = remain.indexOf(suffix)
        if (end == -1) return null
        return remain.substring(0, end)
    }

    private fun unescapeUnicode(text: String): String {
        var str = text
        try {
            str = str.replace("\\u0026", "&")
            str = str.replace("\\u0027", "'")
            str = str.replace("\\\"", "\"")
            str = str.replace("&amp;", "&")
            str = str.replace("&quot;", "\"")
            str = str.replace("&#39;", "'")
            
            val matcher = Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(str)
            val sb = StringBuffer()
            while (matcher.find()) {
                val hex = matcher.group(1) ?: continue
                val charCode = hex.toInt(16)
                matcher.appendReplacement(sb, charCode.toChar().toString())
            }
            matcher.appendTail(sb)
            str = sb.toString()
        } catch (e: Exception) {
            Log.e("YoutubeSearchService", "Error unescaping string: ${e.message}")
        }
        return str
    }
}
