package io.nekohasekai.sfa.xposed.hooks.hidevpn

/** The public client does not install privileged proxy broadcast hooks. */
class HookConnectivityManagerProxyChangeAction(private val helper: ConnectivityServiceHookHelper) {
    fun install() {
        // Privileged integration is disabled in this edition; standard VPN routing is independent.
    }
}
