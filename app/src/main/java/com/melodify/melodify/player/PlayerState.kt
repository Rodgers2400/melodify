package com.melodify.melodify.player

/**
 * Playback repeat / queue mode. Stored as an Int ordinal in DataStore.
 */
enum class RepeatMode {
    /** Stop after the last track (no auto-advance beyond end). */
    OFF,

    /** Repeat the current track forever. */
    ONE,

    /** Repeat the entire queue when it ends. */
    ALL;

    fun next(): RepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/** Shuffle state for the playback queue. */
enum class ShuffleMode {
    OFF,
    ON;

    fun next(): ShuffleMode = if (this == OFF) ON else OFF
}

/**
 * UI-facing snapshot of the player. [positionMs] is updated by the controller
 * at ~4 Hz; [durationMs] comes from the media metadata.
 */
data class PlayerState(
    val currentTrack: com.melodify.melodify.data.model.AudioTrack? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val queue: List<com.melodify.melodify.data.model.AudioTrack> = emptyList(),
    val queueIndex: Int = -1,
    val history: List<Long> = emptyList()
) {
    /** 0f..1f */
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    val hasNext: Boolean get() = queue.isNotEmpty() && queueIndex < queue.lastIndex
    val hasPrevious: Boolean get() = queue.isNotEmpty() && queueIndex > 0
}

/**
 * Ordered playback queue with history. Supports appending, removing by index,
 * clearing, and generating a shuffled ordering of the remaining tracks.
 */
class PlaybackQueue(initial: List<com.melodify.melodify.data.model.AudioTrack> = emptyList()) {

    private val _items = initial.toMutableList()
    private val _history = ArrayDeque<Long>()

    /** Tracks as they were added (used to restore order after un-shuffling). */
    private var baseOrder: List<com.melodify.melodify.data.model.AudioTrack> = initial.toList()

    val items: List<com.melodify.melodify.data.model.AudioTrack> get() = _items
    val history: List<Long> get() = _history.toList()
    var index: Int = if (initial.isEmpty()) -1 else 0
        private set

    val size: Int get() = _items.size
    val isEmpty: Boolean get() = _items.isEmpty()

    fun current(): com.melodify.melodify.data.model.AudioTrack? =
        _items.getOrNull(index)

    fun setList(list: List<com.melodify.melodify.data.model.AudioTrack>, startIndex: Int = 0) {
        _items.clear()
        _items += list
        baseOrder = list.toList()
        _history.clear()
        index = if (list.isEmpty()) -1 else startIndex.coerceIn(0, list.lastIndex)
    }

    fun addAll(tracks: List<com.melodify.melodify.data.model.AudioTrack>) {
        _items += tracks
        if (index < 0 && _items.isNotEmpty()) index = 0
    }

    fun addNext(track: com.melodify.melodify.data.model.AudioTrack) {
        val at = (index + 1).coerceAtMost(_items.size)
        _items.add(at, track)
    }

    fun addNext(tracks: List<com.melodify.melodify.data.model.AudioTrack>) {
        val at = (index + 1).coerceAtMost(_items.size)
        _items.addAll(at, tracks)
    }

    fun removeAt(i: Int): Boolean {
        if (i !in _items.indices) return false
        _items.removeAt(i)
        when {
            _items.isEmpty() -> index = -1
            i < index -> index--
            i == index -> if (index >= _items.size) index = _items.lastIndex
        }
        return true
    }

    fun clear() {
        _items.clear()
        baseOrder = emptyList()
        _history.clear()
        index = -1
    }

    fun previous(): com.melodify.melodify.data.model.AudioTrack? {
        if (_history.isEmpty()) {
            if (index > 0) index--
            return current()
        }
        val prevId = _history.removeLast()
        val i = _items.indexOfFirst { it.id == prevId }
        index = if (i < 0) 0 else i
        return current()
    }

    fun next(shuffle: Boolean, repeatOne: Boolean): com.melodify.melodify.data.model.AudioTrack? {
        val cur = current()
        if (cur != null) _history.addLast(cur.id)

        return when {
            repeatOne -> {
                // Caller will restart the same item.
                _history.removeLastOrNull()
                cur
            }
            shuffle -> {
                if (_items.size <= 1) {
                    index = 0.coerceAtMost((_items.size - 1).coerceAtLeast(0))
                    _items.getOrNull(index)
                } else {
                    var ni = index
                    while (ni == index) ni = (0 until _items.size).random()
                    index = ni
                    _items[index]
                }
            }
            index < _items.lastIndex -> {
                index++
                _items[index]
            }
            else -> {
                // End of queue; caller decides based on repeat mode.
                null
            }
        }
    }

    fun setShuffle(on: Boolean) {
        val cur = current()
        if (on) {
            val remaining = _items.filterIndexed { i, _ -> i != index }
            _items.clear()
            if (cur != null) _items += cur
            _items += remaining.shuffled()
        } else {
            // Restore original order; current track stays first.
            _items.clear()
            _items += baseOrder
            if (cur != null) {
                _items.removeAll { it.id == cur.id }
                _items.add(0, cur)
            }
        }
        index = if (_items.isEmpty()) -1 else 0
    }

    fun move(from: Int, to: Int) {
        if (from !in _items.indices || to !in _items.indices || from == to) return
        val t = _items.removeAt(from)
        _items.add(to, t)
        // Keep index pointing at the same logical track.
        if (from == index) index = to
        else if (from < index && to >= index) index--
        else if (from > index && to <= index) index++
    }
}
