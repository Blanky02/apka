package dev.blanky.vinyl.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.SkipPrevious
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.player.PlayerRepository
import dev.blanky.vinyl.ui.components.AlbumArt
import dev.blanky.vinyl.ui.components.QualityBadge
import dev.blanky.vinyl.ui.components.SourceBadge
import dev.blanky.vinyl.ui.components.TrackRow
import androidx.media3.common.Player as Media3Player

/** Pełnoekranowy odtwarzacz "vinyl-style". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    player: PlayerRepository,
    track: Track,
    onMinimize: () -> Unit,
) {
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()
    val position by player.positionMs.collectAsStateWithLifecycle()
    val duration by player.durationMs.collectAsStateWithLifecycle()
    val repeatMode by player.repeatMode.collectAsStateWithLifecycle(Media3Player.REPEAT_MODE_OFF)
    val shuffleMode by player.shuffleMode.collectAsStateWithLifecycle(Media3Player.SHUFFLE_MODE_OFF)
    val queue by player.queue.collectAsStateWithLifecycle()
    val currentIndex by player.currentIndex.collectAsStateWithLifecycle()

    var draggingPos by remember { mutableStateOf<Long?>(null) }
    var showQueue by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onMinimize) {
                    Icon(
                        imageVector = Icons.Filled.ExpandLess,
                        contentDescription = "Zwiń",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showQueue = true }) {
                    Icon(
                        imageVector = Icons.Filled.QueueMusic,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text("Kolejka (${queue.size})")
                }
            }

            Spacer(Modifier.weight(1f))

            // Płytka
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AlbumArt(
                    url = track.coverUrl,
                    modifier = Modifier
                        .fillMaxWidth(1f)
                        .aspectRatio(1f),
                    cornerRadiusDp = 20,
                )
            }

            Spacer(Modifier.size(28.dp))

            Text(
                text = track.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = track.artistText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            track.album?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.size(24.dp))

            val shownPos = draggingPos ?: position
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Track.formatDuration(shownPos),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    modifier = Modifier.weight(1f),
                    value = if (duration > 0) shownPos.toFloat() else 0f,
                    onValueChange = { draggingPos = it.toLong() },
                    onValueChangeFinished = {
                        draggingPos?.let { player.seekTo(it) }
                        draggingPos = null
                    },
                    enabled = duration > 0,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                Text(
                    text = Track.formatDuration(
                        if (duration > 0) duration else track.durationMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.size(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.toggleShuffle() }) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Losowo",
                        tint = if (shuffleMode != Media3Player.SHUFFLE_MODE_OFF) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { player.skipToPrevious() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.SkipPrevious,
                        contentDescription = "Poprzedni",
                        modifier = Modifier.size(34.dp),
                    )
                }
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = { player.togglePlayPause() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pauza" else "Graj",
                        modifier = Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                IconButton(onClick = { player.skipToNext() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.SkipNext,
                        contentDescription = "Następny",
                        modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { player.cycleRepeat() }) {
                    Box {
                        Icon(
                            imageVector = if (repeatMode == Media3Player.REPEAT_MODE_ONE) {
                                Icons.Filled.RepeatOne
                            } else {
                                Icons.Filled.Repeat
                            },
                            contentDescription = "Powtarzaj",
                            tint = if (repeatMode != Media3Player.REPEAT_MODE_OFF) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceBadge(sourceId = track.sourceId)
                Spacer(Modifier.size(8.dp))
                QualityBadge(quality = track.maxQuality)
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(
            onDismissRequest = { showQueue = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 48.dp),
            ) {
                item {
                    Text(
                        text = "Kolejka",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
                itemsIndexed(queue, key = { _, t -> t.id }) { index, t ->
                    TrackRow(
                        track = t,
                        isCurrent = index == currentIndex,
                        showIndex = index + 1,
                        onClick = {
                            showQueue = false
                            player.playQueue(queue, index)
                        },
                        onPlayNext = { },
                        onAddToQueue = { },
                        onRemove = { player.removeAt(index) },
                    )
                }
            }
        }
    }
}
