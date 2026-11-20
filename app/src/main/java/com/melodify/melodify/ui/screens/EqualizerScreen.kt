package com.melodify.melodify.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melodify.melodify.equalizer.EqualizerEngine
import com.melodify.melodify.player.PlayerController
import kotlin.math.roundToInt

/**
 * 10-band equalizer screen.
 *
 * Bands (Hz): 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000
 *
 * Each slider is vertically oriented and snaps to 0.5 dB. A band is disabled
 * when the device's audio effect chain does not expose a matching centre
 * frequency.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerScreen(
    playerController: PlayerController,
    embedded: Boolean = false,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val engine = remember { playerController.equalizer() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Equalizer", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (engine != null) "10-band graphic EQ" else "Audio effects unavailable on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (embedded && onClose != null) {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Close")
                        }
                    }
                },
                actions = {
                    if (engine != null) {
                        IconButton(onClick = { engine.reset() }) {
                            Icon(Icons.Filled.RestartAlt, contentDescription = "Reset to flat")
                        }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            if (engine == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    Text(
                        "The system reports no equalizer effect for the active audio session. " +
                            "This usually happens on emulators or devices that route audio through " +
                            "a proprietary DSP. Try on a physical device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                return@Scaffold
            }

            PresetRow(engine = engine)

            Spacer(Modifier.height(16.dp))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                items(engine.logicalBands.toList()) { hz ->
                    BandColumn(engine = engine, hz = hz)
                }
            }
        }
    }
}

@Composable
private fun PresetRow(engine: EqualizerEngine) {
    val presets = remember { listOf("Flat", "Rock", "Pop", "Jazz", "Classical", "Bass") }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(presets) { name ->
            androidx.compose.material3.AssistChip(
                onClick = {
                    if (name == "Flat") engine.reset()
                    else if (name == "Bass") {
                        engine.reset()
                        engine.setGain(31, 6f)
                        engine.setGain(62, 5f)
                        engine.setGain(125, 3f)
                    } else engine.applyPreset(name)
                },
                label = { Text(name) }
            )
        }
    }
}

@Composable
private fun BandColumn(engine: EqualizerEngine, hz: Int) {
    val gains by engine.gainState.collectAsState()
    val available = engine.isAvailable(hz)
    val db = (gains[hz] ?: 0f).coerceIn(-15f, 15f)
    val minDb = engine.minGainDb.coerceAtLeast(-15f)
    val maxDb = engine.maxGainDb.coerceAtMost(15f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (available) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
            )
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        Text(
            text = "%.1f".format(db),
            style = MaterialTheme.typography.labelSmall,
            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(6.dp))

        // Vertical fader: a horizontal Slider rotated 90°. Layout dimensions are
        // swapped (height becomes width post-rotation).
        com.melodify.melodify.ui.components.VerticalSlider(
            value = db,
            onValueChange = { engine.setGain(hz, it) },
            valueRange = minDb..maxDb,
            enabled = available,
            modifier = Modifier
                .width(220.dp)
                .height(36.dp)
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = formatBand(hz),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

private fun formatBand(hz: Int): String = when {
    hz >= 1000 -> "${hz / 1000}k"
    else -> "$hz"
}
