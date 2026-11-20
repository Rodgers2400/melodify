package com.melodify.melodify.data.model

import android.net.Uri

/**
 * A locally scanned audio file. [id] is the primary key in the database.
 * [albumArtUri] points to embedded album art when present.
 */
data class AudioTrack(
    val id: Long = 0L,
    val title: String,
    val artist: String,
    val album: String,
    val duration: Long = 0L,        // ms
    val path: String,
    val uri: Uri,
    val mimeType: String = "",
    val size: Long = 0L,
    val dateAdded: Long = 0L,
    val trackNumber: Int = 0,
    val albumArtUri: Uri? = null,
    val lyricsSource: LyricsSource = LyricsSource.NONE,
    val lyricsText: String? = null
)

enum class LyricsSource { NONE, EMBEDDED, ONLINE, LOCAL_LRC }

/**
 * Stored lyrics for a track. [lines] is a parsed LRC-style list ordered by time.
 */
data class Lyrics(
    val trackId: Long,
    val source: LyricsSource,
    val lines: List<LyricLine> = emptyList(),
    val plainText: String? = null
)

data class LyricLine(
    val timeMs: Long,
    val text: String
)

/**
 * User-created playlist. The special [id] = -1L is reserved for the "All songs"
 * virtual list and is never persisted.
 */
data class Playlist(
    val id: Long = 0L,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val coverUri: String? = null,
    val songCount: Int = 0
)
