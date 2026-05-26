package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.DownloadedSong
import com.example.data.model.Song
import com.example.data.network.YoutubeSearchService
import com.example.data.repository.SongRepository
import com.example.ui.player.PlayerManager
import com.example.ui.player.PlayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SearchState {
    IDLE, LOADING, SUCCESS, ERROR
}

class MusicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SongRepository(application.applicationContext)
    val playerManager = PlayerManager(application.applicationContext)

    // Search flows
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchState = MutableStateFlow(SearchState.IDLE)
    val searchState: StateFlow<SearchState> = _searchState

    private val _searchResults = MutableStateFlow<List<Song>>(emptyList())
    val searchResults: StateFlow<List<Song>> = _searchResults

    // Progress map of ongoing downloads (videoId -> progress %)
    val downloadProgressMap: StateFlow<Map<String, Int>> = repository.downloadProgressMap

    // Playback flows mirroring playerManager state
    val currentSong: StateFlow<Song?> = playerManager.currentSong
    val isPlaying: StateFlow<Boolean> = playerManager.isPlaying
    val currentPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val durationMs: StateFlow<Long> = playerManager.durationMs
    val activePlayerType: StateFlow<PlayerType> = playerManager.activePlayerType

    // Track downloaded songs list converted to unified model Songs
    val downloadedSongs: StateFlow<List<Song>> = repository.downloadedSongs
        .map { list -> list.map { Song.fromDownloadedSong(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Polish static seed of great tracks (real YouTube video IDs)
    private val _curatedTracks = MutableStateFlow<List<Song>>(emptyList())
    val curatedTracks: StateFlow<List<Song>> = _curatedTracks

    init {
        seedCuratedTracks()
    }

    private fun seedCuratedTracks() {
        _curatedTracks.value = listOf(
            Song("jfKfPfyJRdk", "lofi hip hop radio 📚 beats to relax/study to", "Lofi Girl", "24/7", "https://img.youtube.com/vi/jfKfPfyJRdk/hqdefault.jpg"),
            Song("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd", "3:22", "https://img.youtube.com/vi/4NRXx6U8ABQ/hqdefault.jpg"),
            Song("dvgZkm1xWPE", "Viva La Vida", "Coldplay", "4:02", "https://img.youtube.com/vi/dvgZkm1xWPE/hqdefault.jpg"),
            Song("JGwWNGJdvx8", "Shape of You", "Ed Sheeran", "4:24", "https://img.youtube.com/vi/JGwWNGJdvx8/hqdefault.jpg"),
            Song("U3ASj1Lg_sY", "Easy On Me", "Adele", "3:44", "https://img.youtube.com/vi/U3ASj1Lg_sY/hqdefault.jpg"),
            Song("viimfQi_pUw", "Ocean Eyes", "Billie Eilish", "3:20", "https://img.youtube.com/vi/viimfQi_pUw/hqdefault.jpg"),
            Song("hT_nvWreIhg", "Counting Stars", "OneRepublic", "4:17", "https://img.youtube.com/vi/hT_nvWreIhg/hqdefault.jpg")
        )
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchState.value = SearchState.IDLE
        }
    }

    fun executeSearch() {
        val query = _searchQuery.value
        if (query.isBlank()) return

        viewModelScope.launch {
            _searchState.value = SearchState.LOADING
            try {
                val results = YoutubeSearchService.searchSongs(query)
                if (results.isEmpty()) {
                    _searchResults.value = emptyList()
                    _searchState.value = SearchState.ERROR
                } else {
                    _searchResults.value = results
                    _searchState.value = SearchState.SUCCESS
                }
            } catch (e: Exception) {
                Log.e("MusicViewModel", "Search error: ${e.message}")
                _searchState.value = SearchState.ERROR
            }
        }
    }

    fun playSongDirect(song: Song) {
        // Find if this song is already downloaded locally, use the local track for offline performance!
        viewModelScope.launch {
            val localEntity = repository.getDownloadedSong(song.videoId)
            val playableSong = if (localEntity != null) {
                Song.fromDownloadedSong(localEntity)
            } else {
                song
            }
            playerManager.playSong(playableSong)
        }
    }

    fun playSongWithQueue(activeSong: Song, currentList: List<Song>) {
        viewModelScope.launch {
            // Map list to use offline paths if they exist
            val resolvedList = currentList.map { song ->
                val localEntity = repository.getDownloadedSong(song.videoId)
                if (localEntity != null) Song.fromDownloadedSong(localEntity) else song
            }
            val activeIndex = resolvedList.indexOfFirst { it.videoId == activeSong.videoId }
            playerManager.setQueue(resolvedList, if (activeIndex != -1) activeIndex else 0)
        }
    }

    fun startSongDownload(song: Song) {
        repository.startDownload(song)
    }

    fun deleteDownloadedSong(song: Song) {
        viewModelScope.launch {
            repository.deleteSong(song)
            // If the deleted song is currently loaded in player, update state or pause
            val active = playerManager.currentSong.value
            if (active?.videoId == song.videoId) {
                // If it is playing offline, we pause/reload it as an online stream dynamically
                if (playerManager.activePlayerType.value == PlayerType.OFFLINE) {
                    playerManager.pause()
                    val onlineSong = song.copy(isLocal = false, localFilePath = null)
                    playerManager.playSong(onlineSong)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
        repository.onDestroy()
    }
}
