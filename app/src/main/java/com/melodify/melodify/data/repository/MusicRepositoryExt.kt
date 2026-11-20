package com.melodify.melodify.data.repository

import com.melodify.melodify.data.model.Playlist
import com.melodify.melodify.data.model.AudioTrack
import kotlinx.coroutines.flow.first

/**
 * One-shot (non-flow) read helpers used by ViewModels. These intentionally do
 * not expose internal Room entities outside this module.
 */
suspend fun MusicRepository.allPlaylistsOnce(): List<Playlist> {
    val entities = playlistDao.observeAll().first()
    return entities.map { e ->
        val count = playlistDao.songCount(e.id).first()
        Playlist(
            id = e.id,
            name = e.name,
            createdAt = e.createdAt,
            updatedAt = e.updatedAt,
            coverUri = e.coverUri,
            songCount = count
        )
    }
}

suspend fun MusicRepository.playlistSongsOnce(playlistId: Long): List<AudioTrack> =
    playlistDao.songsInPlaylist(playlistId).first().map { it.toAudioTrack() }
