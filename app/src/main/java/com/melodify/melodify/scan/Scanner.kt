package com.melodify.melodify.scan

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.melodify.melodify.data.db.AlbumArtUriBuilder
import com.melodify.melodify.data.db.TrackEntity
import com.melodify.melodify.data.model.AudioTrack
import com.melodify.melodify.data.model.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device for audio files using MediaStore.
 *
 * Only MP3 and FLAC (and their container MIME types) are accepted; this matches
 * requirement (1) of the spec.
 */
object AudioScanner {

    private const val TAG = "AudioScanner"

    /** Audio MIME types we consider playable in this app. */
    private val SUPPORTED_MIME = setOf(
        // Raw audio MIMEs
        "audio/mpeg",
        "audio/mp3",
        "audio/x-mpeg",
        "audio/x-mp3",
        "audio/flac",
        "audio/x-flac",
        // OGG/OPUS containers are NOT included — only MP3 and FLAC per spec.
    )

    /** File extensions we accept (case-insensitive). */
    private val SUPPORTED_EXT = setOf("mp3", "flac")

    /**
     * Returns the audio collection URI appropriate for the running API level.
     */
    private fun collectionUri(): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    private val projection: Array<String> = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.IS_MUSIC
    )

    /**
     * Scan all supported audio on the device, returning a list of [TrackEntity]
     * ready for insertion into the database. Runs on [Dispatchers.IO].
     */
    suspend fun scan(context: Context): List<TrackEntity> = withContext(Dispatchers.IO) {
        val result = mutableListOf<TrackEntity>()
        val uri = collectionUri()
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val isMusicCol = cursor.getColumnIndex(MediaStore.Audio.Media.IS_MUSIC)

                while (cursor.moveToNext()) {
                    // Skip non-music audio (ring tones, podcasts etc.) when the column is available.
                    if (isMusicCol >= 0 && cursor.getInt(isMusicCol) == 0) continue

                    val id = cursor.getLong(idCol)
                    val data = cursor.getString(dataCol) ?: continue
                    val ext = data.substringAfterLast('.', "").lowercase()
                    val mime = cursor.getString(mimeCol).orEmpty().lowercase()

                    // Require MP3 or FLAC by extension AND/OR MIME.
                    val byExt = ext in SUPPORTED_EXT
                    val byMime = SUPPORTED_MIME.any { mime.startsWith(it) }
                    if (!byExt && !byMime) continue

                    val albumId = cursor.getLong(albumIdCol)
                    val albumArtUri = AlbumArtUriBuilder.albumArtUri(albumId)

                    result += TrackEntity(
                        id = id,
                        title = cursor.getString(titleCol).orEmpty().ifBlank { data.substringAfterLast('/') },
                        artist = cursor.getString(artistCol).orEmpty().ifBlank { "<unknown>" },
                        album = cursor.getString(albumCol).orEmpty(),
                        duration = cursor.getLong(durationCol),
                        path = data,
                        uri = ContentUris.withAppendedId(uri, id).toString(),
                        mimeType = cursor.getString(mimeCol).orEmpty(),
                        size = cursor.getLong(sizeCol),
                        dateAdded = cursor.getLong(dateCol),
                        trackNumber = if (cursor.isNull(trackCol)) 0 else cursor.getInt(trackCol) % 1000,
                        albumArtUri = albumArtUri,
                        lyricsSource = LyricsSource.NONE.name,
                        lyricsText = null
                    )
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scan failed", t)
        }
        Log.i(TAG, "scan complete: ${result.size} tracks")
        result
    }

    fun TrackEntity.toAudioTrack(): AudioTrack = AudioTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        duration = duration,
        path = path,
        uri = Uri.parse(uri),
        mimeType = mimeType,
        size = size,
        dateAdded = dateAdded,
        trackNumber = trackNumber,
        albumArtUri = albumArtUri?.let(Uri::parse),
        lyricsSource = runCatching { com.melodify.melodify.data.model.LyricsSource.valueOf(lyricsSource) }
            .getOrDefault(com.melodify.melodify.data.model.LyricsSource.NONE),
        lyricsText = lyricsText
    )

    /** Scan a single file path and return a [TrackEntity], or null if unsupported. */
    fun fromPath(path: String, id: Long = path.hashCode().toLong()): TrackEntity? {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in SUPPORTED_EXT) return null
        val f = java.io.File(path)
        return TrackEntity(
            id = id,
            title = path.substringAfterLast('/'),
            artist = "<unknown>",
            album = "",
            duration = 0L,
            path = path,
            uri = Uri.fromFile(f).toString(),
            mimeType = if (ext == "flac") "audio/flac" else "audio/mpeg",
            size = f.length(),
            dateAdded = System.currentTimeMillis() / 1000,
            trackNumber = 0,
            albumArtUri = null,
            lyricsSource = LyricsSource.NONE.name,
            lyricsText = null
        )
    }
}
