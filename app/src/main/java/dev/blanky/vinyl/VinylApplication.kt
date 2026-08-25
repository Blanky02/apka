package dev.blanky.vinyl

import android.app.Application
import dev.blanky.vinyl.data.settings.VinylSettings
import dev.blanky.vinyl.data.source.SourceManager
import dev.blanky.vinyl.player.PlayerRepository

/**
 * Vinyl — osobisty klient streamingowy (Monochrome API + Octave).
 * Aplikacja-scoped obiekty: settings, źródła, odtwarzacz.
 */
class VinylApplication : Application() {

    lateinit var settings: VinylSettings
        private set

    lateinit var sources: SourceManager
        private set

    lateinit var player: PlayerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = VinylSettings(this)
        sources = SourceManager(this, settings)
        player = PlayerRepository(this, sources, settings)
    }
}
