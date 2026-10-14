package io.nekohasekai.sfa.utils

import android.content.Context
import android.os.IBinder
import android.os.Parcel

object ConnectivityBinderUtils {
    // No supported Android API exposes this private raw binder to an ordinary app.
    // Callers already model null as unavailable and do not transact in that state.
    fun getBinder(context: Context): IBinder? = null

    inline fun <T> withParcel(block: (data: Parcel, reply: Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            block(data, reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
