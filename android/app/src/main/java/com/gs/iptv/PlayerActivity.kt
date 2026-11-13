package com.gs.iptv

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/** Player em tela cheia. Nos canais ao vivo, cima/baixo troca de canal. */
@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var view: PlayerView
    private lateinit var osd: TextView
    private var player: ExoPlayer? = null
    private var index = 0
    private val queue get() = Store.playQueue
    private val hideOsd = Runnable { osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        view = findViewById(R.id.player)
        osd = findViewById(R.id.osd)
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        if (queue.isEmpty()) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStart() {
        super.onStart()
        val p = PlayerFactory.create(this)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showOsd("Não foi possível reproduzir este conteúdo")
            }
        })
        view.player = p
        player = p
        play(index)
    }

    override fun onStop() {
        super.onStop()
        view.player = null
        player?.release()
        player = null
    }

    private fun play(i: Int) {
        if (queue.isEmpty()) return
        index = (i + queue.size) % queue.size
        val item = queue[index]
        val live = item.kind == Kind.LIVE
        view.useController = !live
        player?.apply {
            setMediaItem(MediaItem.fromUri(Store.playUrl(this@PlayerActivity, item)))
            prepare()
            playWhenReady = true
        }
        if (live) Store.setLastUrl(this, item.url)
        showOsd(if (item.number > 0) "${item.number}  ${item.name}" else item.name)
    }

    private fun showOsd(text: String) {
        osd.text = text
        osd.visibility = View.VISIBLE
        osd.removeCallbacks(hideOsd)
        osd.postDelayed(hideOsd, 4000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val live = queue.getOrNull(index)?.kind == Kind.LIVE
        if (live) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { play(index - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { play(index + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val it = queue[index]; showOsd(if (it.number > 0) "${it.number}  ${it.name}" else it.name); return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
