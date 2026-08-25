package dev.blanky.vinyl.ui.queue

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.player.PlayerRepository
import dev.blanky.vinyl.ui.components.TrackRow

@Composable
fun QueueScreen(player: PlayerRepository, modifier: Modifier = Modifier) {
    val queue by player.queue.collectAsStateWithLifecycle()
    val currentIndex by player.currentIndex.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Kolejka",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (queue.isNotEmpty()) {
                TextButton(onClick = { player.clearQueue() }) {
                    Text("Wyczyść")
                }
            }
        }

        if (queue.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.QueueMusic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Kolejka jest pusta",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = "Znajdź coś w zakładce „Szukaj”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(queue, key = { _, t -> t.id }) { index, track ->
                    TrackRow(
                        track = track,
                        isCurrent = index == currentIndex,
                        showIndex = index + 1,
                        onClick = { player.playQueue(queue, index) },
                        onPlayNext = { /* w kolejce operacja bez sensu — klik zapętlony */ },
                        onAddToQueue = { },
                        onRemove = { player.removeAt(index) },
                    )
                }
            }
        }
    }
}
