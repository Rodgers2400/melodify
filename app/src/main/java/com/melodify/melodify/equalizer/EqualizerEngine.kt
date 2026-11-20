package com.melodify.melodify.equalizer

import android.media.audiofx.Equalizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * Wraps the platform [Equalizer] effect and exposes a 10-band EQ with the
 * exact centre frequencies required by the spec:
 *
 * `31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz`
 *
 * The platform [Equalizer] does not guarantee those exact frequencies, so this
 * class maps each of our logical bands to the closest available hardware band.
 * Bands not supported by the device are reported with [isAvailable] = false
 * (the UI renders them disabled).
 *
 * All gains are in **millibels** internally (1 dB = 100 mB) but the public API
 * uses dB values in `[-15f, +15f]` matching the UI slider range.
 */
class EqualizerEngine(audioSessionId: Int) {

    private var eq: Equalizer? = try {
        Equalizer(0, audioSessionId).apply {
            enabled = true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Equalizer unavailable for session $audioSessionId", t)
        null
    }

    private val hardwareBandCount: Short = eq?.numberOfBands ?: 0
    private val hardwareBandFreqs: IntArray = IntArray(hardwareBandCount.toInt()) { i ->
        runCatching { eq?.getCenterFreq(i.toShort())?.toInt() ?: 0 }.getOrDefault(0)
    }

    /** Logical bands in Hz (spec). */
    val logicalBands: IntArray = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

    /** Logical band -> hardware band index, or -1 if unavailable. */
    private val mapping: IntArray = IntArray(logicalBands.size) { li ->
        val target = logicalBands[li]
        var best = -1
        var bestDist = Int.MAX_VALUE
        for (hi in hardwareBandFreqs.indices) {
            val d = kotlin.math.abs(hardwareBandFreqs[hi] - target)
            if (d < bestDist) {
                bestDist = d
                best = hi
            }
        }
        best
    }

    /** Min/max gain the hardware accepts, in dB. */
    val minGainDb: Float
    val maxGainDb: Float

    private val _gainState = MutableStateFlow<Map<Int, Float>>(
        logicalBands.associateWith { 0f }
    )
    val gainState: StateFlow<Map<Int, Float>> = _gainState.asStateFlow()

    init {
        val range = try { eq?.bandLevelRange } catch (_: Throwable) { null }
        val minMb = range?.getOrNull(0) ?: -1500
        val maxMb = range?.getOrNull(1) ?: 1500
        minGainDb = minMb / 100f
        maxGainDb = maxMb / 100f
        // Pull current hardware values into the state.
        val map = mutableMapOf<Int, Float>()
        for (li in logicalBands.indices) {
            val hi = mapping[li]
            map[logicalBands[li]] = if (hi < 0) 0f else readHardware(hi)
        }
        _gainState.value = map
    }

    private fun readHardware(hi: Int): Float {
        val mb = runCatching { eq?.getBandLevel(hi.toShort())?.toInt() ?: 0 }.getOrDefault(0)
        return (mb / 100f)
    }

    /** Whether the logical band at [hz] is backed by a hardware band. */
    fun isAvailable(hz: Int): Boolean {
        val li = logicalBands.indexOf(hz)
        return li >= 0 && mapping[li] >= 0
    }

    /**
     * Set gain for [hz] in dB (clamped to the hardware range). Returns the
     * value actually applied.
     */
    fun setGain(hz: Int, db: Float): Float {
        val li = logicalBands.indexOf(hz)
        if (li < 0) return 0f
        val clamped = db.coerceIn(minGainDb, maxGainDb)
        val hi = mapping[li]
        if (hi >= 0) {
            runCatching {
                eq?.setBandLevel(hi.toShort(), (clamped * 100f).roundToInt().toShort())
            }
        }
        val next = _gainState.value.toMutableMap()
        next[hz] = clamped
        _gainState.value = next
        return clamped
    }

    /** Reset all bands to flat (0 dB). */
    fun reset() {
        val next = _gainState.value.toMutableMap()
        for (li in logicalBands.indices) {
            val hi = mapping[li]
            if (hi >= 0) {
                runCatching { eq?.setBandLevel(hi.toShort(), 0.toShort()) }
            }
            next[logicalBands[li]] = 0f
        }
        _gainState.value = next
    }

    /** Apply a named preset (e.g. "Rock", "Jazz"). Returns true if applied. */
    fun applyPreset(presetName: String): Boolean {
        val eqRef = eq ?: return false
        val count = runCatching { eqRef.numberOfPresets }.getOrDefault(0.toShort())
        for (i in 0 until count.toInt()) {
            val p = runCatching { eqRef.getPresetName(i.toShort()) }.getOrNull()
            if (p != null && p.equals(presetName, ignoreCase = true)) {
                runCatching { eqRef.usePreset(i.toShort()) }
                // Re-read into state.
                val next = _gainState.value.toMutableMap()
                for (li in logicalBands.indices) {
                    val hi = mapping[li]
                    next[logicalBands[li]] = if (hi < 0) 0f else readHardware(hi)
                }
                _gainState.value = next
                return true
            }
        }
        return false
    }

    fun release() {
        runCatching { eq?.release() }
        eq = null
    }

    companion object {
        private const val TAG = "EqualizerEngine"
    }
}
