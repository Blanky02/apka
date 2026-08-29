package dev.blanky.vinyl.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.player.PlayerRepository
import dev.blanky.vinyl.ui.components.AlbumArt

/** Pasek "właśnie gra" nad dolną nawigacją. */
@Composable
fun MiniPlayer(
    track: Track,
    isPlaying: Boolean,
    player: PlayerRepository,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(url = track.coverUrl, modifier = Modifier.size(44.dp), cornerRadiusDp = 8)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artistText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { player.skipToPrevious() }) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Poprzedni",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { player.togglePlayPause() }) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pauza" else "Graj",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = { player.skipToNext() }) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Następny",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
