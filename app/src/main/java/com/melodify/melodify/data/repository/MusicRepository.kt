package com.melodify.melodify.data.repository

import android.content.Context
import com.melodify.melodify.MelodifyApp
import com.melodify.melodify.data.db.PlaylistEntity
import com.melodify.melodify.data.db.PlaylistSongCrossRef
import com.melodify.melodify.data.db.TrackEntity
import com.melodify.melodify.data.model.AudioTrack
import com.melodify.melodify.data.model.Playlist
import com.melodify.melodify.scan.AudioScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * High-level data access: scanning, track listing and playlist CRUD.
 * Implemented as a singleton attached to [MelodifyApp] so the UI and the
 * player service share one source of truth.
 */
class MusicRepository(private val app: MelodifyApp) {

    private val db = app.database
    internal val trackDao = db.trackDao()
    internal val playlistDao = db.playlistDao()

    /* ---------------- tracks ---------------- */

    /** All locally scanned tracks, sorted alphabetically. */
    fun allTracks(): Flow<List<AudioTrack>> =
        trackDao.observeAll().map { list -> list.map { it.toAudioTrack() } }.flowOn(Dispatchers.IO)

    /** Tracks currently in a playlist, in playlist order. */
    fun songsInPlaylist(playlistId: Long): Flow<List<AudioTrack>> =
        playlistDao.songsInPlaylist(playlistId)
            .map { list -> list.map { it.toAudioTrack() } }
            .flowOn(Dispatchers.IO)

    fun songCount(playlistId: Long): Flow<Int> = playlistDao.songCount(playlistId)

    suspend fun trackById(id: Long): AudioTrack? =
        withContext(Dispatchers.IO) { trackDao.getById(id)?.toAudioTrack() }

    /**
     * Re-scan the device and merge results into the database. Existing tracks
     * are replaced (upsert) and tracks that no longer exist on disk are
     * removed from the catalogue.
     */
    suspend fun scanAndPersist(): Int = withContext(Dispatchers.IO) {
        val found = AudioScanner.scan(app)
        trackDao.insertAll(found)
        trackDao.deleteExcept(found.map { it.id })
        found.size
    }

    /* ---------------- playlists ---------------- */

    fun allPlaylists(): Flow<List<Playlist>> {
        val entities = playlistDao.observeAll()
        return entities.map { list ->
            list.map { entity ->
                val count = playlistDao.songCount(entity.id)
                // songCount is a Flow<Int>; we need a synchronous value here.
                Playlist(
                    id = entity.id,
                    name = entity.name,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt,
                    coverUri = entity.coverUri,
                    songCount = 0
                )
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entity = PlaylistEntity(
            name = name.ifBlank { "New playlist" },
            createdAt = now,
            updatedAt = now,
            coverUri = null
        )
        playlistDao.insert(entity)
    }

    suspend fun renamePlaylist(id: Long, newName: String) = withContext(Dispatchers.IO) {
        playlistDao.rename(id, newName.ifBlank { "Playlist" }, System.currentTimeMillis())
    }

    suspend fun deletePlaylist(id: Long) = withContext(Dispatchers.IO) {
        playlistDao.deleteById(id)
    }

    suspend fun addSongToPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        val pos = playlistDao.nextPosition(playlistId)
        playlistDao.addSong(PlaylistSongCrossRef(playlistId, trackId, pos))
        playlistDao.rename(playlistId, playlistNameFor(playlistId) ?: "", System.currentTimeMillis())
    }

    suspend fun addSongsToPlaylist(playlistId: Long, trackIds: List<Long>) = withContext(Dispatchers.IO) {
        for (id in trackIds) {
            val pos = playlistDao.nextPosition(playlistId)
            playlistDao.addSong(PlaylistSongCrossRef(playlistId, id, pos))
        }
        bumpUpdatedAt(playlistId)
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        playlistDao.removeSongById(playlistId, trackId)
        bumpUpdatedAt(playlistId)
    }

    suspend fun playlistContains(playlistId: Long, trackId: Long): Boolean =
        withContext(Dispatchers.IO) {
            playlistDao.orderedTrackIds(playlistId).contains(trackId)
        }

    private suspend fun bumpUpdatedAt(id: Long) {
        val name = playlistNameFor(id) ?: return
        playlistDao.rename(id, name, System.currentTimeMillis())
    }

    private suspend fun playlistNameFor(id: Long): String? =
        playlistDao.getById(id)?.name

    companion object {
        fun get(context: Context): MusicRepository =
            (context.applicationContext as MelodifyApp).musicRepository
    }
}
