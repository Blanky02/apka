package dev.blanky.vinyl.player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.blanky.vinyl.data.model.Track
import dev.blanky.vinyl.data.settings.VinylSettings
import dev.blanky.vinyl.data.source.ApiLog
import dev.blanky.vinyl.data.source.SourceManager
import dev.blanky.vinyl.data.source.StreamResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
 *
 * Połączenie z serwisem jest nawiązywane leniwie (MediaController.Builder
 * zwraca ListenableFuture) i odnawiane, gdy system zabije PlayerService.
 */
class PlayerRepository(
    private val app: Application,
    private val sources: SourceManager,
    private val settings: VinylSettings,
) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(app) }

    private val sessionToken = SessionToken(app, ComponentName(app, PlayerService::class.java))

    /** Podłączony kontroler; null = brak połączenia (jeszcze nie lub już nie). */
    @Volatile
    private var controller: MediaController? = null

    /** Trwające łączenie — żeby równoległe wywołania nie tworzyły kilku kontrolerów. */
    private var connecting: CompletableDeferred<MediaController>? = null

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

    /** Media3 1.5+ zna shuffle jako boolean (konstanty `SHUFFLE_MODE_*` zniknęły z Playera). */
    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode: StateFlow<Boolean> = _shuffleMode.asStateFlow()

    /** "Przygotowuję kolejkę x/y" — null, gdy nic się nie dzieje. */
    private val _preparing = MutableStateFlow<PreparingInfo?>(null)
    val preparing: StateFlow<PreparingInfo?> = _preparing.asStateFlow()

    /** Komunikat o utworach niedostępnych w wybranej jakości (nie blokuje grania). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Ostatni błąd wyłaniania strumienia — pokazujemy go w komunikacie zamiast zgadywać. */
    private val lastResolveError = AtomicReference<String?>(null)

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

    // ---- nasłuchiwanie odtwarzacza i sesji ----

    /** Zdarzenia odtwarzacza: kolejka, pozycja, tryby, błędy. */
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val c = controller ?: return
            _currentIndex.value = c.currentMediaItemIndex.coerceAtLeast(0)
            _positionMs.value = 0L
            retryAttempts.clear()
            startPositionTicker()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            publishDuration()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffleMode.value = shuffleModeEnabled
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError()
        }
    }

    /** Zdarzenia sesji: rozłączenie (serwis zabity przez system) czyści uchwyt. */
    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(disconnected: MediaController) {
            if (controller === disconnected) controller = null
            connecting = null
            _isPlaying.value = false
        }
    }

    init {
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
        lastResolveError.set(null)
        _preparing.value = PreparingInfo(0, tracks.size)
        ioScope.launch {
            try {
                val urls = resolveAll(tracks, gen)
                if (gen != generation) return@launch // nowsza kolejka już idzie
                val playableTracks = tracks.filter { urls.containsKey(it.id) }
                if (playableTracks.isEmpty()) {
                    val detail = lastResolveError.getAndSet(null)
                    _notice.value = if (detail.isNullOrBlank()) {
                        "Nie udało się pobrać strumienia żadnego utworu. Zmień jakość w Ustawieniach i zajrzyj do Diagnostyki API."
                    } else {
                        "Nie udało się pobrać strumienia: $detail"
                    }
                    return@launch
                }
                val missing = tracks.size - playableTracks.size
                if (missing > 0) {
                    _notice.value = "$missing z ${tracks.size} utworów niedostępnych w tej jakości — pominięte."
                }
                val targetId = tracks.getOrNull(startIndex)?.id ?: playableTracks.first().id
                val idx = playableTracks.indexOfFirst { it.id == targetId }.coerceAtLeast(0)
                val items = playableTracks.map { buildMediaItem(it, urls.getValue(it.id)) }
                withContext(Dispatchers.Main) {
                    mediaItems = items
                    val c = awaitController()
                    c.setMediaItems(items, idx, 0)
                    _currentIndex.value = idx
                    _durationMs.value = playableTracks[idx].durationMs ?: 0L
                    c.prepare()
                    c.play()
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
            if (!enqueue(track, index)) {
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
            if (!enqueue(track, index)) {
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
            val nextIdx = QueueOps.resumeIndex(index, newQ.size)
            _currentIndex.value = nextIdx
            withController { c ->
                c.stop()
                c.setMediaItems(newItems, nextIdx, 0)
                c.prepare()
                c.play()
            }
        } else {
            if (index < current) _currentIndex.value = current - 1
            withController { c -> c.removeMediaItem(index) }
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

    fun skipToNext() = withController { c -> c.seekToNext() }

    fun skipToPrevious() = withController { c ->
        if (c.currentPosition > 3000) c.seekTo(0) else c.seekToPrevious()
    }

    fun togglePlayPause() {
        withController { c ->
            if (c.mediaItemCount == 0) return@withController
            if (_isPlaying.value) c.pause() else c.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val pos = positionMs.coerceAtLeast(0)
        _positionMs.value = pos
        withController { c -> c.seekTo(pos) }
    }

    fun toggleShuffle() {
        withController { c -> c.setShuffleModeEnabled(!_shuffleMode.value) }
    }

    fun cycleRepeat() {
        withController { c ->
            val next = when (_repeatMode.value) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            c.setRepeatMode(next)
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    // ---- połączenie z PlayerService ----

    /**
     * Zwraca podłączony [MediaController], łącząc się z serwisem w razie potrzeby.
     * Media3 wymaga jednego wątku aplikacji, więc wszystko dzieje się na Main.
     */
    private suspend fun awaitController(): MediaController = withContext(Dispatchers.Main) {
        controller?.takeIf { it.isConnected }?.let { return@withContext it }
        (connecting ?: startConnecting().also { connecting = it }).await()
    }

    private fun startConnecting(): CompletableDeferred<MediaController> {
        val deferred = CompletableDeferred<MediaController>()
        val future = MediaController.Builder(app, sessionToken)
            .setListener(sessionListener)
            .buildAsync()
        future.addListener({
            try {
                val connected = checkNotNull(future.get()) { "Brak MediaControllera" }
                controller = connected
                connected.addListener(playerListener)
                connecting = null
                onControllerConnected(connected)
                deferred.complete(connected)
            } catch (e: Exception) {
                connecting = null
                deferred.completeExceptionally(e)
            }
        }, mainExecutor)
        return deferred
    }

    /** Synchronizuje stan repo z kontrolerem tuż po połączeniu (np. po restarcie serwisu). */
    private fun onControllerConnected(c: MediaController) {
        _repeatMode.value = c.repeatMode
        _shuffleMode.value = c.shuffleModeEnabled
        _isPlaying.value = c.isPlaying
        if (c.mediaItemCount > 0) _currentIndex.value = c.currentMediaItemIndex
        startPositionTicker()
        publishDuration()

        // Serwis mógł zostać zresetowany (kill procesu) — przywracamy kolejkę.
        if (c.mediaItemCount == 0 && mediaItems.isNotEmpty()) {
            val idx = if (_currentIndex.value in mediaItems.indices) _currentIndex.value else 0
            c.setMediaItems(mediaItems, idx, 0)
            c.prepare()
            c.play()
        }
    }

    /** Odpala operację na kontrolerze; błąd połączenia ląduje w [notice]. */
    private fun withController(block: suspend (MediaController) -> Unit): Unit {
        mainScope.launch {
            try {
                block(awaitController())
            } catch (e: Exception) {
                _notice.value = "Odtwarzacz niedostępny: ${e.message ?: e::class.simpleName}"
            }
        }
    }

    // ---- wewnętrzne ----

    /** Wyłania strumień i dokłada item do kontrolera; błąd sieci nie zabija apki. */
    private suspend fun enqueue(track: Track, index: Int): Boolean =
        try {
            tryEnqueue(track, index)
        } catch (e: Exception) {
            ApiLog.record(
                sources.sourceFor(track).displayName,
                "enqueue",
                track.id,
                -1,
                e.message ?: "błąd",
                ok = false,
            )
            false
        }

    /** Wyłania strumień i dokłada item do kontrolera. true = sukces. */
    private suspend fun tryEnqueue(track: Track, index: Int): Boolean {
        val quality = settings.preferredQuality.first()
        val result = sources.resolveStream(track, quality)
        if (result !is StreamResult.Success) {
            ApiLog.record(
                sources.sourceFor(track).displayName,
                "resolve",
                track.id,
                -1,
                (result as StreamResult.Error).message,
                ok = false,
            )
            return false
        }
        val item = buildMediaItem(track, result.url)
        withContext(Dispatchers.Main) {
            mediaItems = mediaItems.toMutableList().also {
                it.add(index.coerceIn(0, it.size), item)
            }
            val c = awaitController()
            c.addMediaItem(index.coerceIn(0, c.mediaItemCount), item)
        }
        return true
    }

    private suspend fun resolveAll(tracks: List<Track>, gen: Int): Map<String, String> {
        val quality = settings.preferredQuality.first()
        val out = ConcurrentHashMap<String, String>()
        val done = AtomicInteger(0)
        coroutineScope {
            val semaphore = Semaphore(4)
            tracks.map { track ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val result = sources.resolveStream(track, quality)
                            if (result is StreamResult.Success) {
                                out[track.id] = result.url
                            } else {
                                val message = (result as StreamResult.Error).message
                                lastResolveError.compareAndSet(null, message)
                                ApiLog.record(
                                    sources.sourceFor(track).displayName,
                                    "resolve",
                                    track.id,
                                    -1,
                                    message,
                                    ok = false,
                                )
                            }
                        } catch (e: Exception) {
                            ApiLog.record(
                                sources.sourceFor(track).displayName,
                                "resolve",
                                track.id,
                                -1,
                                e.message ?: "błąd",
                                ok = false,
                            )
                        }
                        val finished = done.incrementAndGet()
                        if (gen == generation) _preparing.value = PreparingInfo(finished, tracks.size)
                    }
                }
            }.awaitAll()
        }
        return out
    }

    private fun buildMediaItem(track: Track, url: String): MediaItem {
        val extras = Bundle().apply {
            putString("trackId", track.id)
            putString("sourceId", track.sourceId)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistText)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
            // MediaItem.Builder nie ma setExtras — metadane są właściwym miejscem.
            .setExtras(extras)
            .build()
        return MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
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
                    withContext(Dispatchers.Main) {
                        val c = awaitController()
                        val index = c.currentMediaItemIndex
                        if (index !in 0 until c.mediaItemCount) return@withContext
                        if (index in mediaItems.indices) {
                            mediaItems = mediaItems.toMutableList().also { it[index] = newItem }
                        }
                        c.replaceMediaItem(index, newItem)
                        c.seekTo(index, 0)
                        c.play()
                    }
                }
            } catch (_: Exception) {
                // następny błąd wywoła kolejny retry (max 2)
            }
        }
    }

    private fun publishDuration() {
        val c = controller ?: return
        val d = c.duration
        _durationMs.value = if (d > 0 && d != C.TIME_UNSET) d else 0L
    }

    private fun stopEverything() {
        withController { c ->
            c.stop()
            c.clearMediaItems()
        }
    }

    private fun startPositionTicker() {
        ticker.getAndSet(null)?.cancel()
        ticker.set(
            mainScope.launch {
                while (true) {
                    val c = controller
                    if (c != null && c.isConnected && c.playbackState == Player.STATE_READY) {
                        _positionMs.value = c.currentPosition
                        publishDuration()
                    }
                    delay(500)
                }
            },
        )
    }

    fun shutdown() {
        ticker.getAndSet(null)?.cancel()
        val c = controller
        controller = null
        connecting?.cancel()
        connecting = null
        // MediaController.release() wolno wołać tylko z wątku aplikacji.
        if (c != null) mainExecutor.execute { c.release() }
        mainScope.cancel()
        ioScope.cancel()
    }
}
