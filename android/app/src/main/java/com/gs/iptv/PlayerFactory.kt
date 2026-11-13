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
        val b = Buffer.current(ctx)
        val load = DefaultLoadControl.Builder()
            // reserva mínima / máxima; quanto precisa para começar; quanto espera depois de uma travada
            .setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.afterStallMs)
            .setTargetBufferBytes(b.maxBytes)          // limite de memória da reserva
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(b.backMs, true)
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
        val b = Buffer.current(ctx)
        val media = MediaItem.Builder()
            .setUri(routes[index])
            // Ao vivo: assiste um pouco atrás do "ao vivo" para ter folga e não travar
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(b.liveOffsetMs).build())
            .build()
        player.setMediaItem(media)
        player.prepare()
        player.playWhenReady = true
    }
}

/** Níveis de buffer (reserva de vídeo) para segurar travadas do servidor ou da internet. */
enum class Buffer(
    val label: String, val desc: String,
    val minMs: Int, val maxMs: Int, val startMs: Int, val afterStallMs: Int,
    val maxBytes: Int, val backMs: Int, val liveOffsetMs: Long,
) {
    NORMAL("Normal", "Abre mais rápido, menos reserva", 15_000, 50_000, 1_000, 2_500, 32 * 1024 * 1024, 0, 8_000),
    LARGE("Grande (recomendado)", "Equilíbrio entre rapidez e estabilidade", 30_000, 120_000, 1_500, 5_000, 64 * 1024 * 1024, 10_000, 15_000),
    MAX("Máximo", "Mais estável, leva uns segundos a mais para abrir", 60_000, 240_000, 2_500, 8_000, 110 * 1024 * 1024, 15_000, 30_000);

    companion object {
        fun current(ctx: Context): Buffer {
            val name = ctx.getSharedPreferences("gs", Context.MODE_PRIVATE).getString("buffer", LARGE.name)
            return values().firstOrNull { it.name == name } ?: LARGE
        }
        fun set(ctx: Context, b: Buffer) =
            ctx.getSharedPreferences("gs", Context.MODE_PRIVATE).edit().putString("buffer", b.name).apply()
    }
}
