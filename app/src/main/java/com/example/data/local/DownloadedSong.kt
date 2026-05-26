package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloaded_songs")
data class DownloadedSong(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val durationText: String,
    val thumbnailUrl: String,
    val localFilePath: String,
    val fileSize: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)
