package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM downloaded_songs ORDER BY timestamp DESC")
    fun getAllDownloadedSongs(): Flow<List<DownloadedSong>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: DownloadedSong)

    @Query("DELETE FROM downloaded_songs WHERE videoId = :videoId")
    suspend fun deleteSong(videoId: String)

    @Query("SELECT * FROM downloaded_songs WHERE videoId = :videoId LIMIT 1")
    suspend fun getSongByVideoId(videoId: String): DownloadedSong?
}
