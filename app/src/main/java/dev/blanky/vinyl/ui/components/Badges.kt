package dev.blanky.vinyl.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.blanky.vinyl.data.model.Track

private val MonoBadgeColor = Color(0xFF7FB4FF)
private val OctaveBadgeColor = Color(0xFFE8B04B)
private val UnknownBadgeColor = Color(0xFF9E9E9E)

/** Mały kółko-badżet źródła: M = Monochrome, O = Octave. */
@Composable
fun SourceBadge(sourceId: String, modifier: Modifier = Modifier) {
    val (label, color) = when (sourceId) {
        "monochrome" -> "M" to MonoBadgeColor
        "octave" -> "O" to OctaveBadgeColor
        else -> "?" to UnknownBadgeColor
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.18f),
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = color,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
        }
    }
}

/** Etykieta maksymalnej jakości utworu, np. "24/192". */
@Composable
fun QualityBadge(quality: String?, modifier: Modifier = Modifier) {
    val text = Track.qualityBadge(quality) ?: return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = androidx.compose.foundation.layout.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
