package com.melodify.melodify.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.melodify.melodify.data.model.AudioTrack
import com.melodify.melodify.equalizer.EqualizerEngine
import com.melodify.melodify.lyrics.Lyrics
import com.melodify.melodify.lyrics.LyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the [ExoPlayer] instance and is the single source of truth for playback
 * state. UI components collect [state] and call the imperative methods below.
 *
 * The controller:
 *  - Maintains an in-memory [PlaybackQueue] (requirement 5).
 *  - Wires the platform [Equalizer] to the player's audio session.
 *  - Polls position at ~4 Hz for the seek bar and lyric-following logic.
 */
class PlayerController(
    private val context: Context,
    private val lyricsRepository: LyricsRepository
) {
    private val exo: ExoPlayer
    private var eq: EqualizerEngine? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val tickIntervalMs = 250L

    private val queue = PlaybackQueue()
    private var positionPollJob: Job? = null

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow<Lyrics?>(null)
    val lyrics: StateFlow<Lyrics?> = _lyrics.asStateFlow()

    private val _lyricIndex = MutableStateFlow(-1)
    val lyricIndex: StateFlow<Int> = _lyricIndex.asStateFlow()

    private val tickRunnable = object : Runnable {
        override fun run() {
            publishPosition()
            handler.postDelayed(this, tickIntervalMs)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying, isBuffering = !isPlaying && exo.playbackState == Player.STATE_BUFFERING) }
            if (isPlaying) startTicking() else stopTicking()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) handleTrackEnded()
            _state.update { it.copy(isBuffering = playbackState == Player.STATE_BUFFERING) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // We manage our own queue, so rely on play() to update state.
        }
    }

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        exo = ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(attrs, true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            addListener(playerListener)
        }
    }

    /* ---------------- public API ---------------- */

    /** Replace the queue and start playback at [startIndex]. */
    fun playQueue(list: List<AudioTrack>, startIndex: Int = 0) {
        if (list.isEmpty()) return
        queue.setList(list, startIndex)
        exo.stop()
        exo.clearMediaItems()
        list.forEach { exo.addMediaItem(mediaItemFor(it)) }
        exo.prepare()
        exo.seekTo(startIndex.coerceIn(0, list.lastIndex), 0L)
        attachEqualizer()
        exo.playWhenReady = true
        updateStateForCurrent()
        scope.launch { loadLyricsForCurrent() }
    }

    /** Append tracks after the current one ("play next"). */
    fun playNext(track: AudioTrack) {
        queue.addNext(track)
        exo.addMediaItem(queue.index + 1, mediaItemFor(track))
        updateStateForCurrent()
    }

    fun playNext(tracks: List<AudioTrack>) {
        queue.addNext(tracks)
        var pos = queue.index + 1
        for (t in tracks) {
            exo.addMediaItem(pos++, mediaItemFor(t))
        }
        updateStateForCurrent()
    }

    /** Append tracks at the end of the queue. */
    fun enqueue(tracks: List<AudioTrack>) {
        if (queue.isEmpty) {
            playQueue(tracks, 0)
            return
        }
        queue.addAll(tracks)
        for (t in tracks) exo.addMediaItem(mediaItemFor(t))
        updateStateForCurrent()
    }

    /** Move a queue entry without changing playback. */
    fun moveQueueItem(from: Int, to: Int) {
        queue.move(from, to)
        exo.moveMediaItem(from, to)
        updateStateForCurrent()
    }

    fun removeQueueItem(index: Int) {
        if (!queue.removeAt(index)) return
        exo.removeMediaItem(index)
        if (queue.isEmpty) {
            exo.stop()
            _state.update { PlayerState() }
            _lyrics.value = null
            return
        }
        updateStateForCurrent()
    }

    fun clearQueue() {
        queue.clear()
        exo.stop()
        exo.clearMediaItems()
        _state.update { PlayerState() }
        _lyrics.value = null
        stopTicking()
    }

    fun togglePlayPause() {
        if (queue.isEmpty) return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    fun play() {
        if (queue.isEmpty) return
        exo.play()
    }

    fun pause() {
        exo.pause()
    }

    fun seekTo(positionMs: Long) {
        exo.seekTo(positionMs.coerceIn(0L, exo.duration.coerceAtLeast(0L)))
        publishPosition()
    }

    fun seekBy(deltaMs: Long) = seekTo(state.value.positionMs + deltaMs)

    fun next() {
        val s = _state.value
        if (queue.isEmpty) return
        val repeatOne = s.repeatMode == RepeatMode.ONE
        val shuffle = s.shuffleMode == ShuffleMode.ON
        val t = queue.next(shuffle, repeatOne) ?: run {
            // end of queue
            when (s.repeatMode) {
                RepeatMode.ALL -> {
                    queue.index = 0
                    exo.seekTo(0, 0L)
                    exo.play()
                    updateStateForCurrent()
                }
                RepeatMode.ONE -> {
                    exo.seekTo(0L)
                    exo.play()
                }
                RepeatMode.OFF -> {
                    // leave at last item, paused
                }
            }
            return
        }
        val newIndex = queue.index
        exo.seekTo(newIndex, 0L)
        exo.play()
        updateStateForCurrent()
        scope.launch { loadLyricsForCurrent() }
    }

    fun previous() {
        if (queue.isEmpty) return
        // If we're more than 3 seconds in, restart the current track.
        if (exo.currentPosition > 3000L) {
            exo.seekTo(0L)
            publishPosition()
            return
        }
        queue.previous()
        val newIndex = queue.index.coerceAtLeast(0)
        exo.seekTo(newIndex, 0L)
        exo.play()
        updateStateForCurrent()
        scope.launch { loadLyricsForCurrent() }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _state.update { it.copy(repeatMode = mode) }
        when (mode) {
            RepeatMode.ONE -> exo.repeatMode = Player.REPEAT_MODE_ONE
            else -> exo.repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    fun cycleRepeatMode() = setRepeatMode(state.value.repeatMode.next())

    fun setShuffle(on: Boolean) {
        queue.setShuffle(on)
        // rebuild exo playlist order to match the (now-shuffled) queue.
        rebuildPlaylistFromQueue()
        _state.update { it.copy(shuffleMode = if (on) ShuffleMode.ON else ShuffleMode.OFF) }
    }

    fun cycleShuffle() = setShuffle(state.value.shuffleMode == ShuffleMode.OFF)

    /** Skip to a specific position in the queue (used by the queue sheet). */
    fun jumpToQueueIndex(index: Int) {
        if (index !in queue.items.indices) return
        queue.index = index
        exo.seekTo(index, 0L)
        exo.play()
        updateStateForCurrent()
        scope.launch { loadLyricsForCurrent() }
    }

    fun equalizer(): EqualizerEngine? = eq

    /** Internal: expose the ExoPlayer for media-session integration. */
    internal fun exo(): ExoPlayer = exo

    /** Force a reload of lyrics for the current track (e.g. "sync again"). */
    fun refreshLyrics() {
        scope.launch { loadLyricsForCurrent() }
    }

    fun release() {
        stopTicking()
        handler.removeCallbacks(tickRunnable)
        runCatching { exo.release() }
        eq?.release()
        eq = null
        scope.cancel()
    }

    /* ---------------- internals ---------------- */

    private fun mediaItemFor(track: AudioTrack): MediaItem =
        MediaItem.Builder().setUri(track.uri).setMediaId(track.id.toString()).build()

    private fun attachEqualizer() {
        try {
            val session = exo.audioSessionId
            if (session == C.AUDIO_SESSION_ID_UNSET) return
            eq?.release()
            eq = EqualizerEngine(session)
        } catch (t: Throwable) {
            // Some devices forbid audio effects; continue without EQ.
        }
    }

    private fun handleTrackEnded() {
        val s = _state.value
        if (s.repeatMode == RepeatMode.ONE) {
            exo.seekTo(0L)
            exo.play()
            return
        }
        // Advance using our queue rules.
        next()
    }

    private fun updateStateForCurrent() {
        val cur = queue.current()
        val s = _state.value
        _state.update {
            it.copy(
                currentTrack = cur,
                queue = queue.items,
                queueIndex = queue.index,
                history = queue.history,
                durationMs = exo.duration.coerceAtLeast(0L),
                repeatMode = s.repeatMode,
                shuffleMode = s.shuffleMode
            )
        }
    }

    private fun publishPosition() {
        _state.update { st ->
            st.copy(
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                durationMs = exo.duration.coerceAtLeast(0L)
            )
        }
        updateLyricIndex()
    }

    private fun updateLyricIndex() {
        val lines = _lyrics.value?.lines ?: return
        _lyricIndex.value = com.melodify.melodify.lyrics.LrcParser.currentLineIndex(
            lines, _state.value.positionMs
        )
    }

    private fun startTicking() {
        stopTicking()
        positionPollJob = scope.launch {
            tickRunnable.run()
        }
    }

    private fun stopTicking() {
        positionPollJob?.cancel()
        positionPollJob = null
        handler.removeCallbacks(tickRunnable)
    }

    private suspend fun loadLyricsForCurrent() = withContext(Dispatchers.Main) {
        val track = queue.current() ?: return@withContext
        _lyrics.value = null
        _lyricIndex.value = -1
        // Small debounce so rapid skips don't spam the network.
        delay(150)
        val result = withContext(Dispatchers.IO) { lyricsRepository.getLyrics(track) }
        if (queue.current()?.id == track.id) {
            _lyrics.value = result
            updateLyricIndex()
        }
    }

    private fun rebuildPlaylistFromQueue() {
        val cur = queue.current()
        exo.stop()
        exo.clearMediaItems()
        for (t in queue.items) exo.addMediaItem(mediaItemFor(t))
        exo.prepare()
        val i = queue.index.coerceAtLeast(0)
        exo.seekTo(i, 0L)
        if (cur != null) exo.play()
    }

    @Suppress("unused")
    private fun Uri?.orEmpty(): Uri = this ?: Uri.EMPTY

    @Suppress("unused")
    private fun String?.toUriSafe(): Uri = this?.toUri() ?: Uri.EMPTY
}
