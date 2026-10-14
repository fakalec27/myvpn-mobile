package io.nekohasekai.sfa.myvpn.importer
import android.content.Context
import android.provider.Settings
import java.util.UUID

object DeviceIdentity {
    fun choose(systemId:String?,fallback:()->String):String =
        systemId?.takeIf { it.matches(Regex("[0-9a-fA-F]{16}")) && it!="0000000000000000" } ?: fallback()
    fun read(context:Context):String=choose(Settings.Secure.getString(context.contentResolver,Settings.Secure.ANDROID_ID)) {
        val prefs=context.getSharedPreferences("myvpn_device_identity",Context.MODE_PRIVATE)
        prefs.getString("fallback",null) ?: UUID.randomUUID().toString().also { check(prefs.edit().putString("fallback",it).commit()) }
    }
}
