package io.nekohasekai.sfa.myvpn.update

import io.nekohasekai.sfa.BuildConfig

/** Public builds do not use an operational update channel. */
object UpdateTrust {
    const val ENABLED = false
    const val ENDPOINT = "https://updates.example.invalid"
    const val PUBLIC_KEY = ""
    const val APK_SIGNER = ""
    val ABI = BuildConfig.MYVPN_ABI
}
