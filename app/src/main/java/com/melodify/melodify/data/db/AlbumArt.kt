package com.melodify.melodify.data.db

import android.net.Uri
import android.os.Build

/**
 * Builds a content:// URI pointing at album artwork for a given MediaStore
 * album id, when available.
 */
internal object AlbumArtUriBuilder {

    private const val ALBUM_ART_URI = "content://media/external/audio/albumart"

    fun albumArtUri(albumId: Long): String? =
        if (albumId <= 0L) null else "$ALBUM_ART_URI/$albumId"

    /** Best-effort artwork URI for a track; falls back to embedded picture bytes when known. */
    fun artworkFor(albumId: Long, embeddedPictureUri: Uri?): Uri? {
        if (embeddedPictureUri != null) return embeddedPictureUri
        val s = albumArtUri(albumId) ?: return null
        return Uri.parse(s)
    }

    // Suppress API level lint for the albumart authority (it is undocumented but stable).
    @Suppress("unused")
    private val SDK_INT = Build.VERSION.SDK_INT
}
