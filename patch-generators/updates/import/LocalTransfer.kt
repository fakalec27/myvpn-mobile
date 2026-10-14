package io.nekohasekai.sfa.myvpn.importer

import java.net.URI

/** Our one-shot USB installer endpoint, not arbitrary localhost subscriptions. */
object LocalTransfer {
    fun isInstallerSource(value: String): Boolean = runCatching {
        val uri=URI(value)
        uri.scheme=="http" && uri.host=="127.0.0.1" && uri.port==18997 && uri.rawQuery==null && uri.rawFragment==null &&
            uri.path.matches(Regex("/import/[A-Za-z0-9_-]{16,128}"))
    }.getOrDefault(false)
}
