package io.nekohasekai.sfa.myvpn.diagnostics

import java.io.File

/** Bound on disk: at most six 128 KiB segments and no events older than three days. */
class DiagnosticFiles(private val directory: File, private val maxBytes: Long = 128 * 1024, private val maxFiles: Int = 6, private val retentionMs: Long = 3L * 24 * 60 * 60 * 1000) {
    private var lastPrune = -1L
    private fun files() = directory.listFiles()?.filter { it.name.matches(Regex("events-[0-9]+\\.tsv")) }?.sortedBy { it.name.removePrefix("events-").removeSuffix(".tsv").toLongOrNull() ?: 0L }.orEmpty()
    @Synchronized fun append(wallMs: Long, elapsedMs: Long, event: DiagnosticCodec.Event) {
        directory.mkdirs()
        if (lastPrune < 0 || wallMs - lastPrune >= 60_000 || wallMs < lastPrune) { prune(wallMs); lastPrune = wallMs }
        val line = DiagnosticCodec.encode(wallMs, elapsedMs, event) + "\n"
        if (!DiagnosticCodec.safeLine(line.trimEnd()) || line.toByteArray(Charsets.UTF_8).size > maxBytes) return
        var file = files().lastOrNull()
        if (file == null || file.length() + line.toByteArray(Charsets.UTF_8).size > maxBytes) {
            val serial = maxOf(wallMs, (file?.name?.removePrefix("events-")?.removeSuffix(".tsv")?.toLongOrNull() ?: 0L) + 1)
            file = File(directory, "events-$serial.tsv")
            file.createNewFile()
            file.setReadable(false, false); file.setWritable(false, false)
            file.setReadable(true, true); file.setWritable(true, true)
        }
        file.appendText(line, Charsets.UTF_8)
        files().dropLast(maxFiles).forEach { it.delete() }
    }
    @Synchronized fun read(nowMs: Long): List<String> {
        prune(nowMs)
        return files().flatMap { it.readLines(Charsets.UTF_8) }.filter { DiagnosticCodec.safeLine(it) }
    }
    private fun prune(nowMs: Long) {
        for (file in files()) {
            // Ignore malformed lines, including externally modified files, also on export.
            if (file.length() > maxBytes) { file.delete(); continue }
            val original = file.readLines(Charsets.UTF_8)
            val kept = original.filter { DiagnosticCodec.safeLine(it) && (it.substringBefore('\t').toLongOrNull() ?: 0) >= nowMs - retentionMs }
            if (kept.isEmpty()) file.delete() else if (kept.size != original.size) file.writeText(kept.joinToString("\n", postfix="\n"), Charsets.UTF_8)
        }
        files().dropLast(maxFiles).forEach { it.delete() }
    }
}
