package io.nekohasekai.sfa.myvpn.importer

import java.util.Base64

/** Bounded share-link validation; the native engine validates padding and handshake semantics. */
object VlessEncryption {
    fun checked(input: String?): String? {
        if(input==null || input.isEmpty() || input=="none") return null
        fun invalid(): Nothing = throw ImportProblem("unsupported-vless-encryption")
        if(input.length>32768 || input.any { it.code !in 33..126 }) invalid()
        val parts=input.split('.')
        if(parts.size !in 4..19 || parts[0]!="mlkem768x25519plus" || parts[1] !in setOf("native","xorpub","random") || parts[2] !in setOf("0rtt","1rtt")) invalid()
        var keys=0
        for(part in parts.drop(3)) {
            if(keys==0 && part.length<20) {
                if(!part.matches(Regex("[0-9]+-[0-9]+-[0-9]+"))) invalid()
            } else {
                if(!part.matches(Regex("[A-Za-z0-9_-]+"))) invalid()
                val decoded=try { Base64.getUrlDecoder().decode(part) } catch(_:IllegalArgumentException) { invalid() }
                if(decoded.size !in setOf(32,1184) || ++keys>8) invalid()
            }
        }
        if(keys==0) invalid()
        return input
    }
}
