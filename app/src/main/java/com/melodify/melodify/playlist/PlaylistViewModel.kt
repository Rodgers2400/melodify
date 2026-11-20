package com.melodify.melodify.playlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.melodify.melodify.MelodifyApp
import com.melodify.melodify.data.model.Playlist
import com.melodify.melodify.data.repository.MusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistViewModel(
    application: Application,
    private val repo: MusicRepository
) : AndroidViewModel(application) {

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _isLoading.value = true
        viewModelScope.launch {
            val entities = repo.allPlaylistsOnce()
            _playlists.value = entities
            _isLoading.value = false
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            val id = repo.createPlaylist(name)
            if (id > 0) refresh()
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repo.renamePlaylist(id, name)
            refresh()
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repo.deletePlaylist(id)
            refresh()
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val app = application as MelodifyApp
                    return PlaylistViewModel(application, app.musicRepository) as T
                }
            }
    }
}
