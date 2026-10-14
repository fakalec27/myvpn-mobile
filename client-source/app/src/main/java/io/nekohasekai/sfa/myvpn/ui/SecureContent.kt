package io.nekohasekai.sfa.myvpn.ui
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

private class Protection(var owners:Int,val inherited:Boolean)
private val protections=WeakHashMap<Window,Protection>()
private tailrec fun Context.findActivity():Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
/** Overlapping navigation destinations share protection until the final owner disappears. */
@Composable fun SecureContent(enabled:Boolean=true) {
    val window=LocalContext.current.findActivity()?.window
    DisposableEffect(window,enabled) {
        if(window==null || !enabled) onDispose { }
        else {
            val protection=protections.getOrPut(window) { Protection(0,window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE!=0) }
            protection.owners++
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                protection.owners--
                if(protection.owners==0) {
                    protections.remove(window)
                    if(!protection.inherited) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}
