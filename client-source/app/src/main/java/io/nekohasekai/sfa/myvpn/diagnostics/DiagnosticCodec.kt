package io.nekohasekai.sfa.myvpn.diagnostics

/** Deliberately no generic message field: untrusted native text cannot enter the journal. */
object DiagnosticCodec {
    private val names = setOf("service_start", "service_ready", "service_stop", "service_destroy", "service_reload", "service_failure", "network_change", "log_connected", "log_disconnected", "log_failure", "error_summary", "auto_check", "auto_check_done", "auto_selection", "auto_failure", "ssh_alive", "ssh_failure", "selection_request", "selection_confirmed", "selection_failure", "check_request", "journal_dropped", "auto-selection", "auto-node-close", "ssh-reset", "ssh-channel-timeout", "auto-dial-failure", "profile-refresh")
    private val enums = mapOf(
        "reason" to setOf("scheduled", "forced", "network", "dial", "timeout", "cancelled", "unavailable", "all_down", "recovered", "user", "unknown", "cooldown", "closed", "no_route", "tls", "dns", "ssh", "other", "performance_or_failure", "qualification", "eof", "interface_change", "heartbeat_timeout", "heartbeat_error", "context_budget", "repeated"),
        "mode" to setOf("auto", "manual"),
        "transport" to setOf("wifi", "cellular", "ethernet", "vpn", "other", "none"),
        "validated" to setOf("true", "false"),
        "stage" to setOf("start", "reload", "stop", "connect", "read", "write", "dns", "tls", "ssh", "other"),
        "status" to setOf("started", "finished", "success", "failed", "empty", "pending", "retained", "changed"),
    )
    private val numeric = setOf("duration_ms", "failures", "count", "nodes", "healthy", "delay_ms", "interval_ms", "known_countries", "unknown_countries")
    private val hashed = setOf("node", "previous", "group")
    data class Event(val name: String, val fields: Map<String, String>)
    fun validate(name: String, fields: Map<String, String>): Event? {
        if (name !in names || fields.size > 12) return null
        for ((key, value) in fields) {
            val valid = when {
                key in enums -> value in enums.getValue(key)
                key in numeric -> value.matches(Regex("[0-9]{1,10}"))
                key in hashed -> value.matches(Regex("[a-f0-9]{8,64}"))
                else -> false
            }
            if (!valid) return null
        }
        return Event(name, fields.toSortedMap())
    }
    fun native(message: String): Event? {
        val clean=message.replace(Regex("\u001B\\[[0-9;]*m"),"")
        val marker = "myvpn_event "
        val offset = clean.indexOf(marker)
        if (offset < 0) return null
        val tokens = clean.substring(offset + marker.length).trim().split(' ')
        if (tokens.size !in 1..13 || tokens.any { it.count { c -> c == '=' } != 1 }) return null
        val fields = linkedMapOf<String, String>()
        for (token in tokens) {
            val pair = token.split('=', limit = 2)
            if (fields.put(pair[0], pair[1]) != null) return null
        }
        val name = fields.remove("name") ?: return null
        return validate(name, fields)
    }
    fun encode(wallMs: Long, elapsedMs: Long, event: Event): String =
        "$wallMs\t$elapsedMs\t${event.name}" + event.fields.entries.joinToString("") { "\t${it.key}=${it.value}" }
    fun safeLine(line: String): Boolean {
        val parts = line.split('\t')
        if (parts.size !in 3..15 || parts[0].toLongOrNull()?.let { it >= 0 } != true || parts[1].toLongOrNull()?.let { it >= 0 } != true) return false
        val fields = linkedMapOf<String,String>()
        for (part in parts.drop(3)) {
            val pair = part.split('=', limit = 2)
            if (pair.size != 2 || fields.put(pair[0], pair[1]) != null) return false
        }
        return validate(parts[2], fields) != null
    }
    fun errorStage(message: String): String = when {
        message.contains("ssh", true) -> "ssh"
        message.contains("tls", true) || message.contains("handshake", true) -> "tls"
        message.contains("dns", true) || message.contains("lookup", true) -> "dns"
        message.contains("read", true) -> "read"
        message.contains("write", true) -> "write"
        message.contains("connect", true) || message.contains("dial", true) -> "connect"
        else -> "other"
    }
}
