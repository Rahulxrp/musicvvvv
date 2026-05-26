package com.example.ui.player

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import com.example.data.model.Song
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class PlayerType {
    NONE, ONLINE, OFFLINE
}

class PlayerManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null

    // Native MediaPlayer for offline downloads
    private var mediaPlayer: MediaPlayer? = null

    // Reference to the active YouTubePlayer (WebView iframe player)
    private var youtubePlayerRef: YouTubePlayer? = null

    // Unified Audio Flow States
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _activePlayerType = MutableStateFlow(PlayerType.NONE)
    val activePlayerType: StateFlow<PlayerType> = _activePlayerType

    // Track queue for skipping
    private var songQueue = mutableListOf<Song>()
    private var currentQueueIndex = -1

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        songQueue.clear()
        songQueue.addAll(songs)
        currentQueueIndex = if (startIndex in songs.indices) startIndex else 0
        if (songQueue.isNotEmpty()) {
            playSong(songQueue[currentQueueIndex])
        }
    }

    fun playSong(song: Song) {
        // Stop any currently running players
        stopAllPlayers()
        _currentSong.value = song
        _currentPositionMs.value = 0L
        _durationMs.value = 0L

        if (song.isLocal && !song.localFilePath.isNullOrEmpty()) {
            val file = File(song.localFilePath)
            if (file.exists()) {
                playLocalFile(file, song)
            } else {
                Log.e("PlayerManager", "Local file does not exist, falling back to online streaming")
                // Fallback: If local file was deleted but database remains
                val onlineSong = song.copy(isLocal = false, localFilePath = null)
                playSong(onlineSong)
            }
        } else {
            playOnlineVideo(song.videoId, song)
        }
    }

    // Connects modern compose view to the actual player interface
    fun registerYoutubePlayer(ytPlayer: YouTubePlayer) {
        youtubePlayerRef = ytPlayer
        Log.d("PlayerManager", "YouTubePlayer registered with manager")
        // If an online song is loaded and active, play it
        val song = _currentSong.value
        if (_activePlayerType.value == PlayerType.ONLINE && song != null) {
            ytPlayer.loadVideo(song.videoId, (_currentPositionMs.value / 1000f))
            _isPlaying.value = true
        }
    }

    fun unregisterYoutubePlayer() {
        youtubePlayerRef = null
        Log.d("PlayerManager", "YouTubePlayer unregistered")
    }

    private fun playLocalFile(file: File, song: Song) {
        _activePlayerType.value = PlayerType.OFFLINE
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, Uri.fromFile(file))
                prepare()
                setOnPreparedListener { mp ->
                    _durationMs.value = mp.duration.toLong()
                    mp.start()
                    _isPlaying.value = true
                    startLocalProgressTracker()
                }
                setOnCompletionListener {
                    skipNext()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e("PlayerManager", "MediaPlayer error: what=$what, extra=$extra")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("PlayerManager", "Failed to setup local MediaPlayer: ${e.message}", e)
            _isPlaying.value = false
        }
    }

    private fun playOnlineVideo(videoId: String, song: Song) {
        _activePlayerType.value = PlayerType.ONLINE
        _isPlaying.value = true
        youtubePlayerRef?.let { ytPlayer ->
            ytPlayer.loadVideo(videoId, 0f)
            Log.d("PlayerManager", "Sent loadVideo to registered YouTube Player")
        } ?: run {
            Log.d("PlayerManager", "YouTube Player not loaded yet, cached video ID $videoId")
        }
    }

    fun togglePlayPause() {
        val song = _currentSong.value ?: return
        if (_isPlaying.value) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        _isPlaying.value = false
        when (_activePlayerType.value) {
            PlayerType.OFFLINE -> {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        it.pause()
                    }
                }
                stopLocalProgressTracker()
            }
            PlayerType.ONLINE -> {
                youtubePlayerRef?.pause()
            }
            else -> {}
        }
    }

    fun resume() {
        _isPlaying.value = true
        when (_activePlayerType.value) {
            PlayerType.OFFLINE -> {
                mediaPlayer?.let {
                    it.start()
                    startLocalProgressTracker()
                }
            }
            PlayerType.ONLINE -> {
                youtubePlayerRef?.play()
            }
            else -> {}
        }
    }

    fun seekTo(positionMs: Long) {
        _currentPositionMs.value = positionMs
        when (_activePlayerType.value) {
            PlayerType.OFFLINE -> {
                mediaPlayer?.seekTo(positionMs.toInt())
            }
            PlayerType.ONLINE -> {
                youtubePlayerRef?.seekTo(positionMs / 1000f)
            }
            else -> {}
        }
    }

    fun skipNext() {
        if (songQueue.isEmpty()) return
        currentQueueIndex = (currentQueueIndex + 1) % songQueue.size
        playSong(songQueue[currentQueueIndex])
    }

    fun skipPrevious() {
        if (songQueue.isEmpty()) return
        currentQueueIndex = if (currentQueueIndex - 1 < 0) songQueue.size - 1 else currentQueueIndex - 1
        playSong(songQueue[currentQueueIndex])
    }

    // Callbacks from YouTube Player to synchonize Compose visual state
    fun onYoutubeStateChanged(isPlaying: Boolean) {
        if (_activePlayerType.value == PlayerType.ONLINE) {
            _isPlaying.value = isPlaying
        }
    }

    fun onYoutubeProgress(currentSec: Float, durationSec: Float) {
        if (_activePlayerType.value == PlayerType.ONLINE) {
            _currentPositionMs.value = (currentSec * 1000).toLong()
            _durationMs.value = (durationSec * 1000).toLong()
        }
    }

    private fun startLocalProgressTracker() {
        stopLocalProgressTracker()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        _currentPositionMs.value = mp.currentPosition.toLong()
                    }
                }
                delay(400)
            }
        }
    }

    private fun stopLocalProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun stopAllPlayers() {
        stopLocalProgressTracker()
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("PlayerManager", "Error stopping local player: ${e.message}")
        }
        
        try {
            if (_activePlayerType.value == PlayerType.ONLINE) {
                youtubePlayerRef?.pause()
            }
        } catch (e: Exception) {
            Log.e("PlayerManager", "Error pausing youtube player: ${e.message}")
        }

        _activePlayerType.value = PlayerType.NONE
        _isPlaying.value = false
    }

    fun release() {
        stopAllPlayers()
        scope.cancel()
    }
}
