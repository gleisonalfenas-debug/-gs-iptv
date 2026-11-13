package com.gs.iptv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

object PlayerFactory {
    /** Player ajustado para começar rápido e não engasgar. */
    @OptIn(UnstableApi::class)
    fun create(ctx: Context, preview: Boolean = false): ExoPlayer {
        // Conexão compartilhada: reaproveita conexões e usa o DNS rápido
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Http.client())
            .setUserAgent(Net.USER_AGENT)
        // A prévia da tela inicial usa sempre o modo leve; a tela cheia usa o nível escolhido
        val b = if (preview) Buffer.NORMAL else Buffer.current(ctx)
        val load = DefaultLoadControl.Builder()
            // reserva mínima / máxima; quanto precisa para começar; quanto espera depois de uma travada
            .setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.afterStallMs)
            .setTargetBufferBytes(Buffer.bytesFor(ctx, b))  // limite de memória da reserva
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(b.backMs, true)
            .build()
        val renderers = DefaultRenderersFactory(ctx)
            .setEnableDecoderFallback(true) // se o decodificador da TV falhar, tenta outro
        // Canais em TS: começa a mostrar o vídeo sem esperar o próximo quadro-chave completo
        val extractors = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(
                androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
        return ExoPlayer.Builder(ctx, renderers)
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
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
    private val preview: Boolean = false,
    /** Mensagens para o usuário: "tentando outra rota", motivo do erro etc. */
    private val onStatus: (String) -> Unit = {},
    private val onFail: () -> Unit,
) {
    var lastError: String? = null
        private set

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
                val why = describe(error)
                lastError = why
                Diag.log("Erro em \"${current?.name}\": $why")
                if (index + 1 < routes.size) {
                    index++
                    onStatus("$why · tentando outra rota…")
                    start()
                } else {
                    current?.let { Diag.failed(it.name) }
                    onStatus(why)
                    onFail()
                }
            }
        })
    }

    /** Posição de onde começar (continuar de onde parou); vale também se trocar de rota. */
    private var startAt = 0L
    private var fresh = true

    fun play(item: Item, startPositionMs: Long = 0L) {
        startAt = startPositionMs
        fresh = true
        current = item
        lastError = null
        routes = Store.playRoutes(ctx, item)
        index = 0
        start()
    }

    /** Explica o erro em português simples. */
    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Sem conexão com o servidor"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "O servidor não respondeu a tempo"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            when (code) {
                401, 403 -> "Servidor negou o acesso (login ou limite de telas)"
                404 -> "Canal não encontrado no servidor"
                in 500..599 -> "Servidor com problema (erro $code)"
                else -> "Servidor recusou (erro $code)"
            }
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Canal não encontrado no servidor"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "O servidor mandou um formato inválido"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "A TV não conseguiu decodificar este vídeo"
        else -> "Erro ao abrir (${e.errorCodeName})"
    }

    /** Força uma rota específica (usado nas "outras opções" quando o canal não abre). */
    fun playDirect(item: Item, url: String) {
        fresh = true
        startAt = 0L
        current = item
        lastError = null
        routes = listOf(url)
        index = 0
        start()
    }

    /** Todas as rotas possíveis do item atual. */
    fun allRoutes(): List<String> = current?.let { Store.playRoutes(ctx, it) } ?: emptyList()

    private fun start() {
        val b = if (preview) Buffer.NORMAL else Buffer.current(ctx)
        val media = MediaItem.Builder()
            .setUri(routes[index])
            // Ao vivo: assiste um pouco atrás do "ao vivo" para ter folga e não travar
            .apply {
                if (b.liveOffsetMs > 0) setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(b.liveOffsetMs).build())
            }
            .build()
        // se já tinha avançado, mantém a posição ao trocar de rota
        val pos = if (fresh) startAt else maxOf(player.currentPosition, startAt)
        fresh = false
        if (pos > 0) player.setMediaItem(media, pos) else player.setMediaItem(media)
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
    NORMAL("Leve (recomendado)", "Abre rápido e usa pouca memória", 12_000, 30_000, 1_000, 2_000, 16 * 1024 * 1024, 0, 0),
    LARGE("Grande", "Mais reserva contra travadas", 20_000, 60_000, 1_200, 3_000, 32 * 1024 * 1024, 0, 10_000),
    MAX("Máximo", "Mais estável, leva uns segundos a mais para abrir", 30_000, 120_000, 2_000, 4_000, 48 * 1024 * 1024, 0, 20_000);

    companion object {
        private const val PREF_VER = 2 // muda o padrão para "Leve" uma vez nas TVs já instaladas

        fun current(ctx: Context): Buffer {
            val p = ctx.getSharedPreferences("gs", Context.MODE_PRIVATE)
            if (p.getInt("buffer_ver", 0) < PREF_VER) {
                p.edit().putString("buffer", NORMAL.name).putInt("buffer_ver", PREF_VER).apply()
            }
            return values().firstOrNull { it.name == p.getString("buffer", NORMAL.name) } ?: NORMAL
        }

        fun set(ctx: Context, b: Buffer) =
            ctx.getSharedPreferences("gs", Context.MODE_PRIVATE).edit().putString("buffer", b.name).putInt("buffer_ver", PREF_VER).apply()

        /** Em TVs com pouca memória a reserva é limitada para não deixar o app lento. */
        fun bytesFor(ctx: Context, b: Buffer): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val small = am.isLowRamDevice || am.memoryClass < 192
            return if (small) minOf(b.maxBytes, 12 * 1024 * 1024) else b.maxBytes
        }
    }
}
