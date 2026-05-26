package com.example.data.repository

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadedSong
import com.example.data.model.Song
import com.example.data.network.CobaltService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class SongRepository(private val context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val songDao = database.songDao()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Expose all downloaded songs as a flow
    val downloadedSongs: Flow<List<DownloadedSong>> = songDao.getAllDownloadedSongs()

    // DownloadManager instance
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    // Map to keep track of active download IDs and their corresponding Song information
    private val activeDownloads = mutableMapOf<Long, Song>()

    // Exposed Map of active videoId -> download progress (from 0 to 100) or downloading state
    private val _downloadProgressMap = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgressMap: StateFlow<Map<String, Int>> = _downloadProgressMap

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ctx = context ?: return
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id == -1L) return

                val song = synchronized(activeDownloads) { activeDownloads.remove(id) } ?: return
                
                scope.launch {
                    val query = DownloadManager.Query().setFilterById(id)
                    val cursor: Cursor = downloadManager.query(query)
                    if (cursor.moveToFirst()) {
                        val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = if (statusIdx != -1) cursor.getInt(statusIdx) else DownloadManager.STATUS_FAILED
                        
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            val sizeIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            
                            val localUriStr = if (localUriIdx != -1) cursor.getString(localUriIdx) else null
                            val fileSize = if (sizeIdx != -1) cursor.getLong(sizeIdx) else 0L

                            if (localUriStr != null) {
                                val localUri = Uri.parse(localUriStr)
                                val filePath = localUri.path ?: ""
                                
                                // Clean up the file path naming (Internal standard)
                                val cleanPath = if (filePath.startsWith("/external_files/")) {
                                    val extDir = ctx.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile?.parentFile
                                    File(extDir, filePath.substring("/external_files/".length - 1)).absolutePath
                                } else {
                                    filePath
                                }

                                val finalFile = File(filePath)
                                val actualPath = if (finalFile.exists()) filePath else {
                                    // Fallback to internal media directory matching if path resolution shifted
                                    val fallbackFile = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "YT_${song.videoId}.mp3")
                                    fallbackFile.absolutePath
                                }

                                val downloadedEntity = DownloadedSong(
                                    videoId = song.videoId,
                                    title = song.title,
                                    artist = song.artist,
                                    durationText = song.durationText,
                                    thumbnailUrl = song.thumbnailUrl,
                                    localFilePath = actualPath,
                                    fileSize = fileSize
                                )

                                // Insert into database
                                songDao.insertSong(downloadedEntity)
                                removeProgress(song.videoId)

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(ctx, "${song.title} Download Completed!", Toast.LENGTH_SHORT).show()
                                }
                                Log.d("SongRepository", "Saved downloaded track ${song.title} in DB at $actualPath")
                            }
                        } else {
                            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIdx != -1) cursor.getInt(reasonIdx) else -1
                            removeProgress(song.videoId)
                            Log.e("SongRepository", "DownloadManager failed: ID $id, reason $reason")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(ctx, "Download failed: System Code $reason", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    cursor.close()
                }
            }
        }
    }

    init {
        // Registers broadcast receiver to listen for system download completions
        val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        context.registerReceiver(downloadCompleteReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        
        // Periodic tracker to update downloading progress in UI
        startProgressTracker()
    }

    suspend fun getDownloadedSong(videoId: String): DownloadedSong? {
        return songDao.getSongByVideoId(videoId)
    }

    suspend fun deleteSong(song: Song) = withContext(Dispatchers.IO) {
        // Delete local file
        song.localFilePath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
        // Delete database record
        songDao.deleteSong(song.videoId)
    }

    fun startDownload(song: Song) {
        scope.launch {
            // Check if already in progress or already downloaded
            if (_downloadProgressMap.value.containsKey(song.videoId)) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download already in progress", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val existing = getDownloadedSong(song.videoId)
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Song is already downloaded", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            // Put into progress indicator map with 0% (Initializing/Connecting)
            updateProgress(song.videoId, 0)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Fetching download links for ${song.title}...", Toast.LENGTH_SHORT).show()
            }

            // Call Cobalt API to extract standard stream/mp3 link
            val streamUrl = CobaltService.getStreamUrl(song.videoId)
            if (streamUrl == null) {
                removeProgress(song.videoId)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download failed: Cobalt API extract error", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            // Initialize OS Download Request via native DownloadManager
            try {
                val cleanTitle = song.title.replace("[^a-zA-Z0-9\\s-_]".toRegex(), "")
                val cleanArtist = song.artist.replace("[^a-zA-Z0-9\\s-_]".toRegex(), "")
                val fileName = "YT_${song.videoId}.mp3"

                val request = DownloadManager.Request(Uri.parse(streamUrl))
                    .setTitle("$cleanTitle")
                    .setDescription("Downloading audio from BeatStream")
                    .setMimeType("audio/mpeg")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, fileName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)

                val downloadId = downloadManager.enqueue(request)
                
                synchronized(activeDownloads) {
                    activeDownloads[downloadId] = song
                }

                // Update Progress to 5% indicating active queue
                updateProgress(song.videoId, 5)
                Log.d("SongRepository", "Successfully enqueued download ID $downloadId for video ${song.videoId}")
            } catch (e: Exception) {
                removeProgress(song.videoId)
                Log.e("SongRepository", "Failed to enqueue DownloadManager: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Download Queue failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startProgressTracker() {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val currentActive = synchronized(activeDownloads) { activeDownloads.toMap() }
                if (currentActive.isNotEmpty()) {
                    val updatedProgress = _downloadProgressMap.value.toMutableMap()
                    for ((downloadId, song) in currentActive) {
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        if (cursor.moveToFirst()) {
                            val bytesLoadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val bytesTotalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            
                            val bytesLoaded = if (bytesLoadedIdx != -1) cursor.getLong(bytesLoadedIdx) else 0L
                            val bytesTotal = if (bytesTotalIdx != -1) cursor.getLong(bytesTotalIdx) else 0L

                            if (bytesTotal > 0) {
                                val progress = ((bytesLoaded * 100) / bytesTotal).toInt()
                                // Cap between 5% and 99% for visual fluidity prior to receiver complete
                                updatedProgress[song.videoId] = progress.coerceIn(5, 99)
                            }
                        }
                        cursor.close()
                    }
                    _downloadProgressMap.value = updatedProgress
                }
                delay(1200) // update UI progress bar every 1.2s
            }
        }
    }

    private fun updateProgress(videoId: String, progress: Int) {
        val map = _downloadProgressMap.value.toMutableMap()
        map[videoId] = progress
        _downloadProgressMap.value = map
    }

    private fun removeProgress(videoId: String) {
        val map = _downloadProgressMap.value.toMutableMap()
        map.remove(videoId)
        _downloadProgressMap.value = map
    }

    fun onDestroy() {
        try {
            context.unregisterReceiver(downloadCompleteReceiver)
        } catch (e: Exception) {
            // ignores
        }
        scope.cancel()
    }
}
