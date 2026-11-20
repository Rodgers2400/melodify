package com.melodify.melodify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.unit.dp
import com.melodify.melodify.data.model.AudioTrack
import com.melodify.melodify.data.repository.MusicRepository
import com.melodify.melodify.ui.components.CoverImage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    repository: MusicRepository,
    onPlayTrack: (AudioTrack, List<AudioTrack>) -> Unit,
    onPlayNext: (AudioTrack) -> Unit,
    onEnqueue: (AudioTrack) -> Unit,
    onAddToPlaylist: (AudioTrack) -> Unit,
    onSyncLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var hasScanned by remember { mutableStateOf(false) }

    // Initial load + auto-scan once so the user sees something immediately.
    LaunchedEffect(Unit) {
        repository.allTracks().collect { list ->
            tracks = list
            if (list.isEmpty() && !hasScanned) {
                hasScanned = true
                isScanning = true
                val count = repository.scanAndPersist()
                tracks = repository.allTracksOnce()
                isScanning = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${tracks.size} tracks · MP3 / FLAC",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                isScanning = true
                                repository.scanAndPersist()
                                tracks = repository.allTracksOnce()
                                isScanning = false
                            }
                        },
                        enabled = !isScanning
                    ) {
                        if (isScanning) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Refresh, contentDescription = "Scan device")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { padding ->
        if (tracks.isEmpty() && !isScanning) {
            EmptyLibrary(onScan = {
                scope.launch {
                    isScanning = true
                    repository.scanAndPersist()
                    tracks = repository.allTracksOnce()
                    isScanning = false
                }
            })
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(tracks, key = { it.id }) { track ->
                    TrackRow(
                        track = track,
                        onClick = { onPlayTrack(track, tracks) },
                        onPlayNext = { onPlayNext(track) },
                        onEnqueue = { onEnqueue(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) }
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: AudioTrack,
    onClick: () -> Unit,
    onPlayNext: () -> Unit,
    onEnqueue: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            uri = track.albumArtUri,
            contentDescription = track.album.ifBlank { "cover" },
            modifier = Modifier
                .size(48.dp)
                .let { m -> m }
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.artist}${if (track.album.isNotBlank()) " · ${track.album}" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add to playlist",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            androidx.compose.material3.DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Play next") },
                    onClick = { menuOpen = false; onPlayNext() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Add to queue") },
                    onClick = { menuOpen = false; onEnqueue() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    onClick = { menuOpen = false; onAddToPlaylist() }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Sync lyrics") },
                    onClick = { menuOpen = false; /* caller handles */ }
                )
            }
        }
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyLibrary(onScan: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            Text("No music found yet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Scan your device for MP3 and FLAC files",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onScan, shape = RoundedCornerShape(24.dp)) {
                Icon(Icons.Filled.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan device")
            }
        }
    }
}
