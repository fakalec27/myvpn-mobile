package io.nekohasekai.sfa.myvpn.diagnostics

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** A single app-process writer; I/O never runs on service/network/UI callback threads. */
object DiagnosticsJournal {
    private data class Pending(val wall: Long = 0, val elapsed: Long = 0, val event: DiagnosticCodec.Event? = null, val barrier: CompletableDeferred<Unit>? = null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<Pending>(256)
    private val dropped = AtomicInteger()
    @Volatile private var store: DiagnosticFiles? = null
    @Volatile private var writeFailed = false
    @Synchronized private fun initialize(context: Context): DiagnosticFiles {
        store?.let { return it }
        val base = context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir
        return DiagnosticFiles(File(base, "diagnostics")).also { files ->
            store = files
            scope.launch {
                for (item in pending) {
                    if (item.barrier != null) { item.barrier.complete(Unit); continue }
                    try {
                        val lost = dropped.getAndSet(0)
                        if (lost > 0) files.append(item.wall, item.elapsed, DiagnosticCodec.validate("journal_dropped", mapOf("count" to lost.toString()))!!)
                        files.append(item.wall, item.elapsed, requireNotNull(item.event))
                        writeFailed = false
                    } catch (_: Exception) { writeFailed = true }
                }
            }
        }
    }
    fun event(context: Context, name: String, fields: Map<String, String> = emptyMap()) {
        val safe = DiagnosticCodec.validate(name, fields) ?: return
        initialize(context)
        if (pending.trySend(Pending(System.currentTimeMillis(), SystemClock.elapsedRealtime(), safe)).isFailure) dropped.incrementAndGet()
    }
    fun native(context: Context, message: String) {
        DiagnosticCodec.native(message)?.let { event(context, it.name, it.fields) }
    }
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    suspend fun lines(context: Context): List<String> = withContext(Dispatchers.IO) {
        val files = initialize(context)
        withTimeout(5_000) {
            val barrier = CompletableDeferred<Unit>()
            pending.send(Pending(barrier = barrier))
            barrier.await()
        }
        files.read(System.currentTimeMillis())
    }
    fun hasWriteFailure() = writeFailed
    suspend fun exportSafeText(context: Context): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "myVPN diagnostics v1; UTC; elapsed_ms; event; safe fields\n" + lines(context).joinToString("\n") { line ->
            val first = line.substringBefore('\t').toLong()
            format.format(Date(first)) + "\t" + line.substringAfter('\t')
        } + "\n"
    }
}
