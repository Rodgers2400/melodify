package com.melodify.melodify.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * A vertically oriented [Slider]. Internally it is a horizontal slider whose
 * layout is rotated 90° counter-clockwise; the touch hit-testing still works
 * because Compose applies the graphicsLayer rotation to input as well.
 */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    thumbColor: androidx.compose.ui.graphics.Color =
        androidx.compose.material3.MaterialTheme.colorScheme.primary,
    activeTrackColor: androidx.compose.ui.graphics.Color =
        androidx.compose.material3.MaterialTheme.colorScheme.primary,
    inactiveTrackColor: androidx.compose.ui.graphics.Color =
        androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
) {
    Box(
        modifier = modifier
            .graphicsLayer {
                rotationZ = -90f
            }
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = thumbColor,
                activeTrackColor = activeTrackColor,
                inactiveTrackColor = inactiveTrackColor
            )
        )
    }
}
