package com.gs.iptv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

object PlayerFactory {
    /** Player ajustado para começar rápido e não engasgar. */
    @OptIn(UnstableApi::class)
    fun create(ctx: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
        val load = DefaultLoadControl.Builder()
            // mín. 15s / máx. 50s de reserva; começa a tocar com 1s; volta após travada com 2s
            .setBufferDurationsMs(15_000, 50_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true) // se o decodificador da TV falhar, tenta outro
        return ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
    }
}

/**
 * Toca um item escolhendo a melhor rota: para canais ao vivo tenta o formato que
 * funcionou da última vez (TS ou HLS); se falhar, troca sozinho para o outro.
 */
class SmartPlay(
    private val ctx: Context,
    val player: ExoPlayer,
    private val onFail: () -> Unit,
) {
    private var routes: List<String> = emptyList()
    private var index = 0
    var current: Item? = null
        private set
    val currentRoute: String? get() = routes.getOrNull(index)

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) routes.getOrNull(index)?.let { Store.rememberRoute(ctx, it) }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                if (index + 1 < routes.size) { index++; Diag.log("Trocando rota de \"${current?.name}\""); start() }
                else { current?.let { Diag.failed(it.name) }; onFail() }
            }
        })
    }

    fun play(item: Item) {
        current = item
        routes = Store.playRoutes(ctx, item)
        index = 0
        start()
    }

    private fun start() {
        player.setMediaItem(MediaItem.fromUri(routes[index]))
        player.prepare()
        player.playWhenReady = true
    }
}
