package io.nekohasekai.sfa.myvpn.importer

/** Auto has a verified non-Russian country. Unknown/provider-wide Auto remain available manually. */
object AutoNodePolicy {
    fun eligible(tags: List<String>, names: Map<String,String>): List<String> = tags.filter {
        NodeCatalog.country(names[it] ?: it)?.code?.let {code -> code!="RU"}==true
    }
}
