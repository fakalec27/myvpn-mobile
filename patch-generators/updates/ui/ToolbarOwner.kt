package io.nekohasekai.sfa.myvpn.ui
fun <T> toolbarFor(entries:List<T>,active:String?,owner:(T)->String?):T? =
    entries.lastOrNull { owner(it)==null || owner(it)==active } ?: entries.lastOrNull()
