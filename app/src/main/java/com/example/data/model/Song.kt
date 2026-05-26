package com.example.data.model

import com.example.data.local.DownloadedSong

data class Song(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String,
    val isLocal: Boolean = false,
    val localFilePath: String? = null
) {
    fun toDownloadedSong(): DownloadedSong {
        return DownloadedSong(
            videoId = videoId,
            title = title,
            artist = artist,
            durationText = durationText,
            thumbnailUrl = thumbnailUrl,
            localFilePath = localFilePath ?: ""
        )
    }

    companion object {
        fun fromDownloadedSong(downloaded: DownloadedSong): Song {
            return Song(
                videoId = downloaded.videoId,
                title = downloaded.title,
                artist = downloaded.artist,
                durationText = downloaded.durationText,
                thumbnailUrl = downloaded.thumbnailUrl,
                isLocal = true,
                localFilePath = downloaded.localFilePath
            )
        }
    }
}
