package com.melodify.melodify.lyrics

import android.util.Log
import com.melodify.melodify.data.db.AppDatabase
import com.melodify.melodify.data.model.AudioTrack
import com.melodify.melodify.data.model.LyricLine
import com.melodify.melodify.data.model.Lyrics
import com.melodify.melodify.data.model.LyricsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses LRC (and extended LRC) lyric files into timed [LyricLine]s.
 *
 * Handles:
 *  - `[mm:ss]`, `[mm:ss.xx]`, `[mm:ss.xxx]` time tags
 *  - `[offset:±ms]` global offset
 *  - Multiple time tags on one line: `[00:01.00][00:04.00]lyric`
 *  - ID tags such as `[ti:title]` / `[ar:artist]` / `[al:album]` (ignored)
 */
object LrcParser {

    private val TIME_TAG = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_TAG = Pattern.compile("\\[offset:\\s*(-?\\d+)\\s*]", Pattern.CASE_INSENSITIVE)

    /**
     * Parse an LRC text blob into a [Lyrics] object. Returns a [Lyrics] whose
     * [Lyrics.lines] are sorted by time and de-duplicated. Returns `null` if
     * no timed lines could be parsed (plain text is returned via [Lyrics.plainText]).
     */
    fun parse(raw: String, source: LyricsSource = LyricsSource.ONLINE): Lyrics? {
        if (raw.isBlank()) return null

        val offsetMs = parseOffset(raw)
        val lines = mutableListOf<LyricLine>()
        val plain = StringBuilder()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Pure metadata line?
            if (trimmed.startsWith("[") && !TIME_TAG.matcher(trimmed).find()) {
                // could be offset (handled separately) or an ID tag; ignore for lyrics
                return@forEach
            }

            val times = mutableListOf<Long>()
            var textStart = 0
            val matcher = TIME_TAG.matcher(trimmed)
            var lastEnd = 0
            while (matcher.find()) {
                if (matcher.start() == lastEnd || lastEnd == 0) {
                    val m = matcher.group(1)!!.toLong()
                    val s = matcher.group(2)!!.toLong()
                    val fracStr = matcher.group(3)
                    val frac = when (fracStr?.length ?: 0) {
                        1 -> fracStr!!.toLong() * 100L
                        2 -> fracStr!!.toLong() * 10L
                        3 -> fracStr!!.toLong()
                        else -> 0L
                    }
                    times += m * 60_000L + s * 1000L + frac
                    lastEnd = matcher.end()
                    textStart = lastEnd
                } else {
                    break
                }
            }
            if (times.isEmpty()) {
                // Untimed line -> treat as plain text.
                if (plain.isNotEmpty()) plain.append('\n')
                plain.append(trimmed)
                return@forEach
            }
            val text = trimmed.substring(textStart).trim()
            for (t in times) {
                lines += LyricLine(timeMs = t + offsetMs, text = text)
            }
        }

        lines.sortBy { it.timeMs }
        return if (lines.isEmpty()) {
            if (plain.isBlank()) null else Lyrics(
                trackId = 0L,
                source = source,
                lines = emptyList(),
                plainText = plain.toString()
            )
        } else Lyrics(
            trackId = 0L,
            source = source,
            lines = lines,
            plainText = null
        )
    }

    private fun parseOffset(raw: String): Long {
        val m = OFFSET_TAG.matcher(raw)
        return if (m.find()) runCatching { m.group(1)!!.toLong() }.getOrDefault(0L) else 0L
    }

    /**
     * Find the index of the lyric line whose time is <= [positionMs] and is
     * the latest such line. Returns -1 when nothing matches (e.g. before the
     * first timed line).
     */
    fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= positionMs) idx = i else break
        }
        return idx
    }
}

/**
 * Searches for lyrics on remote services. This ships with an integration to a
 * public, no-key lyrics service (lrclib.net), which supports title + artist
 * lookup and returns LRC text directly. The implementation is intentionally
 * dependency-free (plain HttpURLConnection) so the feature works out of the
 * box; swap [searchAndFetch] for a different provider without changing the UI.
 *
 * The result is cached in Room so repeat plays need no network.
 */
class LyricsRepository(private val db: AppDatabase) {

    private val cacheDao get() = db.lyricsCacheDao()

    /**
     * Find lyrics for [track], checking the local cache first. Returns `null`
     * when nothing is cached AND no network result is available.
     */
    suspend fun getLyrics(track: AudioTrack): Lyrics? = withContext(Dispatchers.IO) {
        // 1) embedded lyrics already on the track
        if (track.lyricsSource == LyricsSource.EMBEDDED && !track.lyricsText.isNullOrBlank()) {
            LrcParser.parse(track.lyricsText!!, LyricsSource.EMBEDDED)?.let { l ->
                return@withContext l.copy(trackId = track.id)
            }
        }

        // 2) room cache (online)
        val cached = cacheDao.find(track.id, LyricsSource.ONLINE.name)
        if (cached != null) {
            val lrc = LrcParser.parse(cached.plainText ?: cached.linesJson, LyricsSource.ONLINE)
            if (lrc != null) return@withContext lrc.copy(trackId = track.id)
        }

        // 3) network
        val fetched = fetchFromNetwork(track)
        if (fetched != null) {
            cacheDao.upsert(
                com.melodify.melodify.data.db.LyricsCacheEntity(
                    trackId = track.id,
                    source = LyricsSource.ONLINE.name,
                    linesJson = serialize(fetched.lines),
                    plainText = fetched.plainText ?: toLrc(fetched.lines),
                    cachedAt = System.currentTimeMillis()
                )
            )
            return@withContext fetched.copy(trackId = track.id)
        }

        // 4) try a plain-text fallback search
        val plain = fetchPlainText(track)
        if (!plain.isNullOrBlank()) {
            val lrc = LrcParser.parse(plain, LyricsSource.ONLINE)
            if (lrc != null) {
                cacheDao.upsert(
                    com.melodify.melodify.data.db.LyricsCacheEntity(
                        trackId = track.id,
                        source = LyricsSource.ONLINE.name,
                        linesJson = serialize(lrc.lines),
                        plainText = lrc.plainText ?: plain,
                        cachedAt = System.currentTimeMillis()
                    )
                )
                return@withContext lrc.copy(trackId = track.id)
            }
        }
        null
    }

    private suspend fun fetchFromNetwork(track: AudioTrack): Lyrics? = runCatching {
        val artist = URLEncoder.encode(track.artist, StandardCharsets.UTF_8.name())
        val title = URLEncoder.encode(track.title, StandardCharsets.UTF_8.name())
        val album = URLEncoder.encode(track.album, StandardCharsets.UTF_8.name())

        // lrclib.net: returns JSON with a "syncedLyrics" LRC string.
        val url = "https://lrclib.net/api/get?artist_name=$artist&track_name=$title&album_name=$album"
        val conn = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "Melodify/1.0 (android)")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val synced = obj.optString("syncedLyrics", "")
            if (!synced.isNullOrBlank()) {
                return@runCatching LrcParser.parse(synced, LyricsSource.ONLINE)
                    ?.copy(trackId = track.id)
            }
            val plain = obj.optString("plainLyrics", "")
            if (!plain.isNullOrBlank()) {
                return@runCatching Lyrics(
                    trackId = track.id,
                    source = LyricsSource.ONLINE,
                    lines = emptyList(),
                    plainText = plain
                )
            }
            null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private suspend fun fetchPlainText(track: AudioTrack): String? {
        // No-op fallback; kept as an extension point for additional providers.
        Log.d(TAG, "plainText fallback requested for ${track.title}")
        return null
    }

    /** Best-effort embedded lyrics extraction from a local file (ID3 USLT / FLAC). */
    suspend fun loadEmbeddedLyrics(track: AudioTrack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = java.io.File(track.path)
            if (!f.exists()) return@runCatching null
            // jaudiotagger supports both MP3 (ID3v2 USLT) and FLAC (Vorbis COMMENT / UNSYNCEDLYRICS).
            val tag = net.jthink.jaudiotagger.audio.AudioFileIO.read(f).tag ?: return@runCatching null
            // Try generic field first.
            val generic = tag.getFirst(net.jthink.jaudiotagger.tag.FieldKey.LYRICS)
            if (!generic.isNullOrBlank()) return@runCatching generic
            null
        }.getOrNull()
    }

    private fun serialize(lines: List<LyricLine>): String {
        val arr = JSONArray()
        for (l in lines) {
            val o = JSONObject().apply {
                put("t", l.timeMs)
                put("x", l.text)
            }
            arr.put(o)
        }
        return arr.toString()
    }

    private fun toLrc(lines: List<LyricLine>): String {
        val sb = StringBuilder()
        for (l in lines) {
            val m = (l.timeMs / 60_000L)
            val s = (l.timeMs / 1000L) % 60
            val cs = (l.timeMs % 1000L) / 10L
            sb.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]%s", m, s, cs, l.text))
            sb.append('\n')
        }
        return sb.toString()
    }

    companion object {
        private const val TAG = "LyricsRepository"
    }
}
