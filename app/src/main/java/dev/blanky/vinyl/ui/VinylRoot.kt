package dev.blanky.vinyl.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blanky.vinyl.VinylApplication
import dev.blanky.vinyl.ui.player.MiniPlayer
import dev.blanky.vinyl.ui.player.NowPlayingScreen
import dev.blanky.vinyl.ui.queue.QueueScreen
import dev.blanky.vinyl.ui.search.SearchScreen
import dev.blanky.vinyl.ui.settings.SettingsScreen

@Composable
fun VinylRoot(app: VinylApplication) {
    val player = app.player
    val current by player.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by player.isPlaying.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showNowPlaying by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column {
                if (current != null) {
                    MiniPlayer(
                        track = current!!,
                        isPlaying = isPlaying,
                        player = player,
                        onOpen = { showNowPlaying = true },
                    )
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Search, contentDescription = "Wyszukiwarka") },
                        label = { Text("Szukaj") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.QueueMusic, contentDescription = "Kolejka") },
                        label = { Text("Kolejka") },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = "Ustawienia") },
                        label = { Text("Ustawienia") },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            0 -> SearchScreen(app = app, modifier = Modifier.fillMaxSize().then(Modifier.padding(padding)))
            1 -> QueueScreen(player = player, modifier = Modifier.fillMaxSize().then(Modifier.padding(padding)))
            2 -> SettingsScreen(app = app, modifier = Modifier.fillMaxSize().then(Modifier.padding(padding)))
        }
    }

    AnimatedVisibility(
        visible = showNowPlaying && current != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 12 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 12 }),
    ) {
        current?.let { track ->
            NowPlayingScreen(
                player = player,
                track = track,
                onMinimize = { showNowPlaying = false },
            )
        }
    }
}
