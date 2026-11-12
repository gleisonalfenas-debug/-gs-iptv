package com.gs.iptv

import android.app.Activity
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Tela da TV com o QR Code para enviar a lista pelo celular. */
class SendListActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_send_list)
        ListServer.start(this)

        if (Store.hasFixedServer) {
            findViewById<TextView>(R.id.title).text = "Entre com seu usuário e senha"
            findViewById<TextView>(R.id.step3).text = "3   Digite usuário e senha no celular e toque em Entrar"
        }
        val addr = ListServer.address()
        val qr = findViewById<ImageView>(R.id.qr)
        val address = findViewById<TextView>(R.id.address)
        if (addr != null) {
            qr.setImageBitmap(Net.qrCode(addr, 520))
            address.text = addr
        } else {
            address.text = "Conecte a TV ao Wi-Fi"
        }
    }

    override fun onResume() {
        super.onResume()
        ListServer.listener = {
            findViewById<TextView>(R.id.status).text = "Login confirmado! Carregando canais…"
            setResult(Activity.RESULT_OK)
            window.decorView.postDelayed({ finish() }, 1200)
        }
    }

    override fun onPause() {
        super.onPause()
        ListServer.listener = null
    }
}
