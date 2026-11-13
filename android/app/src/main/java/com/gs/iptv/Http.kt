package com.gs.iptv

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Opções de DNS: o da operadora (sistema), Google ou Cloudflare. */
enum class DnsMode(val label: String) {
    AUTO("Automático (operadora)"), GOOGLE("Google (8.8.8.8)"), CLOUDFLARE("Cloudflare (1.1.1.1)");

    companion object {
        fun current(ctx: Context): DnsMode {
            val n = ctx.getSharedPreferences("gs", Context.MODE_PRIVATE).getString("dns", AUTO.name)
            return values().firstOrNull { it.name == n } ?: AUTO
        }
        fun set(ctx: Context, m: DnsMode) {
            ctx.getSharedPreferences("gs", Context.MODE_PRIVATE).edit().putString("dns", m.name).apply()
            Http.reset()
        }
    }
}

/**
 * Conexão de rede compartilhada pelo player:
 * - reaproveita conexões abertas (troca de canal sem "aperto de mão" novo)
 * - guarda o DNS em memória por 10 minutos
 * - prefere IPv4 (IPv6 quebrado em muitas operadoras deixa a abertura lenta)
 */
object Http {
    private lateinit var app: Context
    @Volatile private var client: OkHttpClient? = null
    private val pool = ConnectionPool(10, 5, TimeUnit.MINUTES)

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun reset() { client = null; FastDns.clear() }

    fun client(): OkHttpClient = client ?: synchronized(this) {
        client ?: build().also { client = it }
    }

    private fun build(): OkHttpClient {
        val base = OkHttpClient.Builder()
            .connectionPool(pool)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        val backend: Dns = when (DnsMode.current(app)) {
            DnsMode.AUTO -> Dns.SYSTEM
            DnsMode.GOOGLE -> doh(base, "https://dns.google/dns-query", "8.8.8.8", "8.8.4.4")
            DnsMode.CLOUDFLARE -> doh(base, "https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1")
        }
        FastDns.backend = backend
        return base.newBuilder().dns(FastDns).build()
    }

    private fun doh(base: OkHttpClient, url: String, vararg ips: String): Dns =
        DnsOverHttps.Builder().client(base).url(url.toHttpUrl())
            .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
            .includeIPv6(false)
            .build()

    /** Resolve o servidor antes de precisar (abre o primeiro canal mais rápido). */
    fun warm(url: String?) {
        val host = try { url?.toHttpUrl()?.host } catch (_: Exception) { null } ?: return
        thread(isDaemon = true) {
            val t = System.currentTimeMillis()
            try {
                client() // cria o cliente e o DNS escolhido
                val ips = FastDns.lookup(host)
                Diag.log("DNS ${DnsMode.current(app).label}: $host -> ${ips.firstOrNull()?.hostAddress} em ${System.currentTimeMillis() - t}ms")
            } catch (e: Exception) {
                Diag.log("DNS falhou para $host: ${e.message}")
            }
        }
    }
}

/** DNS com memória e IPv4 primeiro; se o DNS escolhido falhar, usa o do sistema. */
object FastDns : Dns {
    @Volatile var backend: Dns = Dns.SYSTEM
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val TTL = 10 * 60 * 1000L

    fun clear() = cache.clear()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.let { (t, ips) -> if (now - t < TTL) return ips }
        val ips = try { backend.lookup(hostname) } catch (e: Exception) { Dns.SYSTEM.lookup(hostname) }
        val sorted = ips.sortedBy { if (it is Inet4Address) 0 else 1 }
        if (sorted.isNotEmpty()) cache[hostname] = now to sorted
        return sorted
    }
}
