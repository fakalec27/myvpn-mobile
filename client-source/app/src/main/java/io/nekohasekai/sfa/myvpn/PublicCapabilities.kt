package io.nekohasekai.sfa.myvpn

/** Ordinary Android builds do not expose the optional privileged hook integration. */
object PublicCapabilities {
    val privilegedIntegration: Boolean get() = false
}
