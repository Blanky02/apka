package dev.blanky.vinyl.player

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.data.settings.VinylSettings
import dev.blanky.vinyl.data.source.ApiLog
import dev.blanky.vinyl.data.source.SourceManager
import dev.blanky.vinyl.data.source.StreamResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Właściciel stanu odtwarzania: kolejka, aktualny utwór, pozycja,
 * tryby powtarzania/losowania. Mówi do ExoPlayera przez MediaController.
 *
 * Adresy strumieni są wyłaniane (resolve) przed wgraniem kolejki do
 * odtwarzacza. Przy błędzie odtwarzania (np. wygasły URL CDN) pobieramy
 * świeży adres i gramy ponownie (max 2 ponowienia na utwór).
 */
class PlayerRepository(
    private val app: Application,
    private val sources: SourceManager,
    private val settings: VinylSettings,
) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val controller: MediaController by lazy {
        MediaController.Builder(
            app,
            ComponentName(app, PlayerService::class.java),
        ).buildAsync()
    }

    // ---- stan udostępniany UI ----

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleMode = MutableStateFlow(Player.SHUFFLE_MODE_OFF)
    val shuffleMode: StateFlow<Int> = _shuffleMode.asStateFlow()

    /** "Przygotowuję kolejkę x/y" — null, gdy nic się nie dzieje. */
    private val _preparing = MutableStateFlow<PreparingInfo?>(null)
    val preparing: StateFlow<PreparingInfo?> = _preparing.asStateFlow()

    /** Komunikat o utworach niedostępnych w wybranej jakości (nie blokuje grania). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    data class PreparingInfo(val done: Int, val total: Int)

    // ---- wewnętrzne dane odtwarzania ----

    /** MediaItems w 1:1 z [_queue] (w tym samym porządku). */
    @Volatile
    private var mediaItems: List<MediaItem> = emptyList()

    /** Ochrona przed nakładającymi się playQueue(). */
    @Volatile
    private var generation: Int = 0

    private var ticker: AtomicReference<Job> = AtomicReference(null)
    private val retryAttempts = ConcurrentHashMap<String, Int>()

    init {
        mainScope.launch {
            controller.addListener(object : MediaController.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _isPlaying.value = isPlaying
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    _currentIndex.value = controller.currentMediaItemIndex.coerceAtLeast(0)
                    _positionMs.value = 0L
                    retryAttempts.clear()
                    startPositionTicker()
                }

                override fun onIsTimelineChanged() {
                    val d = controller.duration
                    _durationMs.value = if (d > 0 && d != C.TIME_UNSET) d else 0L
                }

                override fun onSessionReady() {
                    _repeatMode.value = controller.repeatMode
                    _shuffleMode.value = controller.shuffleMode
                    // Serwis mógł zostać zresetowany (kill procesu) — przywracamy kolejkę.
                    if (controller.mediaItemCount == 0 && mediaItems.isNotEmpty()) {
                        val idx = if (_currentIndex.value >= 0 && _currentIndex.value < mediaItems.size) {
                            _currentIndex.value
                        } else {
                            0
                        }
                        mainScope.launch {
                            controller.setMediaItems(mediaItems, idx, 0)
                            controller.prepare()
                            controller.play()
                        }
                    }
                }

                override fun onRepeatModeChanged(mode: Int) {
                    _repeatMode.value = mode
                }

                override fun onShuffleModeChanged(mode: Int) {
                    _shuffleMode.value = mode
                }

                override fun onPlayerError(error: PlaybackException) {
                    handlePlaybackError()
                }
            })
        }
        mainScope.launch {
            combine(_queue, _currentIndex) { tracks, index -> tracks.getOrNull(index) }
                .collect { _currentTrack.value = it }
        }
    }

    // ---- operacje publiczne ----

    /** Odtwórz listę od podanego indeksu (nowa kolejka). */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        generation++
        val gen = generation
        _queue.value = tracks
        _notice.value = null
        _preparing.value = PreparingInfo(0, tracks.size)
        ioScope.launch {
            try {
                val urls = resolveAll(tracks, gen)
                if (gen != generation) return@launch // nowsza kolejka już idzie
                val playableTracks = tracks.filter { urls.containsKey(it.id) }
                if (playableTracks.isEmpty()) {
                    _notice.value = "Żaden utwór nie jest dostępny w wybranej jakości. Zmień jakość w Ustawieniach."
                    return@launch
                }
                val missing = tracks.size - playableTracks.size
                if (missing > 0) {
                    _notice.value = "$missing z ${tracks.size} utworów niedostępnych w tej jakości — pomięte."
                }
                val targetId = tracks.getOrNull(startIndex)?.id ?: playableTracks.first().id
                val idx = playableTracks.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
                val items = playableTracks.map { buildMediaItem(it, urls[it.id]!!) }
                mainScope.launch {
                    ensureServiceStarted()
                    mediaItems = items
                    controller.setMediaItems(items, idx, 0)
                    _currentIndex.value = idx
                    _durationMs.value = playableTracks[idx].durationMs ?: 0L
                    controller.prepare()
                    controller.play()
                }
            } catch (e: Exception) {
                if (gen == generation) {
                    _notice.value = "Nie udało się przygotować kolejki: ${e.message ?: e::class.simpleName}"
                }
            } finally {
                if (gen == generation) _preparing.value = null
            }
        }
    }

    /** Dodaj utwór na koniec kolejki. */
    fun addToQueue(track: Track) {
        val q = _queue.value
        if (q.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }
        val index = q.size
        _queue.value = q + track
        _preparing.value = PreparingInfo(0, 1)
        ioScope.launch {
            val ok = tryEnqueue(track, index)
            if (!ok) {
                // wyciągamy utwór, którego nie udało się pobrać
                _queue.value = _queue.value.filterNot { it.id == track.id }
                _notice.value = "Nie udało się pobrać strumienia: ${track.title}"
            }
            _preparing.value = null
        }
    }

    /** „Zagraj następny” — tuż po bieżącym utworze. */
    fun playNext(track: Track) {
        val q = _queue.value
        if (q.isEmpty()) {
            playQueue(listOf(track), 0)
            return
        }
        val index = QueueOps.insertIndexForPlayNext(_currentIndex.value, q.size)
        val newQ = q.toMutableList().also { it.add(index, track) }
        _queue.value = newQ
        _preparing.value = PreparingInfo(0, 1)
        ioScope.launch {
            val ok = tryEnqueue(track, index)
            if (!ok) {
                _queue.value = _queue.value.filterNot { it.id == track.id }
                _notice.value = "Nie udało się pobrać strumienia: ${track.title}"
            }
            _preparing.value = null
        }
    }

    fun removeAt(index: Int) {
        val q = _queue.value
        if (index !in q.indices || index !in mediaItems.indices) return
        val current = _currentIndex.value
        val removingCurrent = index == current
        val newQ = q.toMutableList().also { it.removeAt(index) }
        val newItems = mediaItems.toMutableList().also { it.removeAt(index) }
        _queue.value = newQ
        mediaItems = newItems
        if (newQ.isEmpty()) {
            _currentIndex.value = -1
            stopEverything()
            return
        }
        if (removingCurrent) {
            val nextIdx = if (index < newQ.size) index else newQ.size - 1
            _currentIndex.value = nextIdx
            mainScope.launch {
                controller.stop()
                controller.setMediaItems(newItems, nextIdx, 0)
                controller.prepare()
                controller.play()
            }
        } else if (index < current) {
            _currentIndex.value = current - 1
            mainScope.launch { controller.removeMediaItem(index) }
        } else {
            mainScope.launch { controller.removeMediaItem(index) }
        }
    }

    fun clearQueue() {
        _queue.value = emptyList()
        mediaItems = emptyList()
        _currentIndex.value = -1
        _notice.value = null
        _preparing.value = null
        stopEverything()
    }

    fun skipToNext() = mainScope.launch { controller.seekToNext() }

    fun skipToPrevious() = mainScope.launch {
        if (controller.currentPosition > 3000) controller.seekTo(0)
        else controller.seekToPrevious()
    }

    fun togglePlayPause() {
        mainScope.launch {
            if (controller.mediaItemCount == 0) return@launch
            ensureServiceStarted()
            if (_isPlaying.value) controller.pause() else controller.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        mainScope.launch { controller.seekTo(pos) }
        _positionMs.value = pos
    }

    fun toggleShuffle() {
        mainScope.launch {
            controller.setShuffleMode(
                if (_shuffleMode.value == Player.SHUFFLE_MODE_OFF) Player.SHUFFLE_MODE_OTHER
                else Player.SHUFFLE_MODE_OFF
            )
        }
    }

    fun cycleRepeat() {
        mainScope.launch {
            val next = when (_repeatMode.value) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            controller.setRepeatMode(next)
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    // ---- wewnętrzne ----

    /** Wyłania strumień i dokłada item do kontrolera. true = sukces. */
    private suspend fun tryEnqueue(track: Track, index: Int): Boolean {
        val quality = settings.preferredQuality.first()
        val result = sources.resolveStream(track, quality)
        if (result !is StreamResult.Success) {
            ApiLog.record(sources.sourceFor(track).displayName, "resolve", track.id, -1, (result as StreamResult.Error).message, ok = false)
            return false
        }
        val item = buildMediaItem(track, result.url)
        withContext(Dispatchers.Main) {
            mediaItems = mediaItems.toMutableList().also {
                if (index > it.size) it.add(item) else it.add(index.coerceIn(0, it.size), item)
            }
            if (controller.isInitialized) {
                controller.addMediaItem(item, index.coerceIn(0, controller.mediaItemCount))
            }
        }
        return true
    }

    private suspend fun resolveAll(tracks: List<Track>, gen: Int): Map<String, String> {
        val quality = settings.preferredQuality.first()
        val out = ConcurrentHashMap<String, String>()
        var done = 0
        coroutineScope {
            val semaphore = Semaphore(4)
            tracks.map { track ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val result = sources.resolveStream(track, quality)
                            if (result is StreamResult.Success) out[track.id] = result.url
                        } catch (e: Exception) {
                            ApiLog.record(sources.sourceFor(track).displayName, "resolve", track.id, -1, e.message ?: "błąd", ok = false)
                        }
                        done++
                        if (gen == generation) _preparing.value = PreparingInfo(done, tracks.size)
                    }
                }
            }.awaitAll()
        }
        return out
    }

    private fun buildMediaItem(track: Track, url: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistText)
            .setAlbumTitle(track.album)
            .apply {
                track.coverUrl?.let { setArtworkUri(Uri.parse(it)) }
            }
            .build()
        val extras = Bundle().apply {
            putString("trackId", track.id)
            putString("sourceId", track.sourceId)
        }
        return MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .setExtras(extras)
            .build()
    }

    /** Wygasły/expiry URL CDN: pobierz świeży adres i zagraj ten sam utwór. */
    private fun handlePlaybackError() {
        val track = _queue.value.getOrNull(_currentIndex.value) ?: return
        val attempts = retryAttempts[track.id] ?: 0
        if (attempts >= 2) {
            _notice.value = "Błąd odtwarzania: ${track.title} (może brak w tej jakości — zmień w Ustawieniach)"
            return
        }
        retryAttempts[track.id] = attempts + 1
        ioScope.launch {
            try {
                val quality = settings.preferredQuality.first()
                val result = sources.resolveStream(track, quality)
                if (result is StreamResult.Success) {
                    val newItem = buildMediaItem(track, result.url)
                    mainScope.launch {
                        val index = controller.currentMediaItemIndex.coerceAtLeast(0)
                        if (index < mediaItems.size) {
                            mediaItems = mediaItems.toMutableList().also { it[index] = newItem }
                        }
                        controller.replaceMediaItem(index, newItem)
                        controller.seekTo(index, 0)
                        controller.play()
                    }
                }
            } catch (_: Exception) {
                // następny błąd wywoła kolejny retry (max 2)
            }
        }
    }

    private fun stopEverything() {
        mainScope.launch {
            controller.stop()
            controller.clearMediaItems()
        }
        app.stopService(Intent(app, PlayerService::class.java))
    }

    private fun ensureServiceStarted() {
        val intent = Intent(app, PlayerService::class.java).setAction(PlayerService.ACTION_START)
        ContextCompat.startForegroundService(app, intent)
    }

    private fun startPositionTicker() {
        ticker.getAndSet(null)?.cancel()
        ticker.set(
            mainScope.launch {
                while (true) {
                    if (controller.isReady && controller.currentMediaItemIndex >= 0) {
                        _positionMs.value = controller.currentPosition
                        val d = controller.duration
                        _durationMs.value = if (d > 0 && d != C.TIME_UNSET) d else 0L
                    }
                    delay(500)
                }
            },
        )
    }

    fun shutdown() {
        ticker.getAndSet(null)?.cancel()
        mainScope.cancel()
        ioScope.cancel()
    }
}
