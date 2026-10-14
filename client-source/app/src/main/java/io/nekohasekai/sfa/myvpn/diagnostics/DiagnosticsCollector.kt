package io.nekohasekai.sfa.myvpn.diagnostics

import android.content.Context
import android.net.NetworkCapabilities
import android.os.SystemClock
import io.nekohasekai.libbox.LogEntry
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.*

/** Service-owned local command connection: stays active when every Compose screen is closed. */
class DiagnosticsCollector private constructor(context: Context, scope: CoroutineScope) : CommandClient.Handler {
    private val context = context.applicationContext
    private val client = CommandClient(scope, CommandClient.ConnectionType.Log, this, localOnly = true)
    private val lock = Any()
    private val counts = mutableMapOf<String, Int>()
    private val replayFingerprints = linkedSetOf<String>()
    private var initialBatch = true
    @Volatile private var connected = false
    @Volatile private var stopped = false
    private var lastConnectEvent = 0L
    private val worker = scope.launch(Dispatchers.IO) {
        while (isActive && !stopped) {
            if (!connected) client.connect()
            delay(15_000)
            flushErrors()
        }
    }
    override fun onConnected() {
        connected = true
        synchronized(lock) { initialBatch = true }
        val now = SystemClock.elapsedRealtime()
        if (now - lastConnectEvent > 30_000 || lastConnectEvent == 0L) {
            lastConnectEvent = now
            DiagnosticsJournal.event(context, "log_connected")
        }
    }
    override fun onDisconnected() {
        if (connected && !stopped) DiagnosticsJournal.event(context, "log_disconnected")
        connected = false
    }
    override fun onConnectionError(message: String) {
        connected = false
        synchronized(lock) { counts["connect"] = (counts["connect"] ?: 0) + 1 }
        // Message discarded; it may contain a socket path or user-controlled profile text.
    }
    override fun appendLogs(message: List<LogEntry>) {
        synchronized(lock) {
            for (entry in message) {
                val raw = entry.message ?: continue
                val fingerprint = DiagnosticsJournal.hash(raw)
                if (initialBatch && fingerprint in replayFingerprints) continue
                replayFingerprints.add(fingerprint)
                while (replayFingerprints.size > 4096) replayFingerprints.remove(replayFingerprints.first())
                DiagnosticCodec.native(raw)?.let { DiagnosticsJournal.event(context, it.name, it.fields) }
                if (entry.level <= 2) {
                    val stage = DiagnosticCodec.errorStage(raw)
                    counts[stage] = ((counts[stage] ?: 0) + 1).coerceAtMost(999_999)
                }
            }
            initialBatch = false
        }
    }
    private fun flushErrors() {
        val copy = synchronized(lock) { counts.toMap().also { counts.clear() } }
        for ((stage,count) in copy) DiagnosticsJournal.event(context, "error_summary", mapOf("stage" to stage, "count" to count.toString()))
    }
    fun stop() {
        stopped = true
        worker.cancel()
        client.disconnect()
        flushErrors()
    }
    companion object {
        private var lastNetworkSummary: Pair<String,Boolean>? = null
        fun start(context: Context, scope: CoroutineScope) = DiagnosticsCollector(context, scope)
        @Synchronized fun network(context: Context, capabilities: NetworkCapabilities?,force:Boolean=false) {
            val transport = when {
                capabilities == null -> "none"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            val summary = transport to validated
            if (summary == lastNetworkSummary && !force) return
            lastNetworkSummary = summary
            DiagnosticsJournal.event(context, "network_change", mapOf("transport" to transport, "validated" to validated.toString()))
        }
    }
}
