package io.nekohasekai.sfa.myvpn.importer

/** Country is provider naming metadata, never IP geolocation or TLS/SNI inference. */
object NodeCatalog {
    data class Country(val code: String, val name: String)
    private data class Entry(val country: Country, val aliases: List<String>)
    private val countries=listOf(
        Entry(Country("RU","Россия"),listOf("Россия","Russia","Russian Federation")),
        Entry(Country("DE","Германия"),listOf("Германия","Germany","Deutschland")),
        Entry(Country("PL","Польша"),listOf("Польша","Poland")),
        Entry(Country("FI","Финляндия"),listOf("Финляндия","Finland")),
        Entry(Country("MD","Молдова"),listOf("Молдова","Молдавия","Moldova")),
        Entry(Country("RO","Румыния"),listOf("Румыния","Romania")),
        Entry(Country("JP","Япония"),listOf("Япония","Japan")),
        Entry(Country("US","США"),listOf("США","USA","United States","United States of America")),
        Entry(Country("AE","ОАЭ"),listOf("ОАЭ","UAE","United Arab Emirates")),
        Entry(Country("KZ","Казахстан"),listOf("Казахстан","Kazakhstan")),
        Entry(Country("TR","Турция"),listOf("Турция","Turkey","Türkiye")),
        Entry(Country("AR","Аргентина"),listOf("Аргентина","Argentina")),
        Entry(Country("CH","Швейцария"),listOf("Швейцария","Switzerland")),
        Entry(Country("NL","Нидерланды"),listOf("Нидерланды","Netherlands","Голландия")),
        Entry(Country("GB","Великобритания"),listOf("Великобритания","UK","United Kingdom")),
        Entry(Country("FR","Франция"),listOf("Франция","France")),
        Entry(Country("SE","Швеция"),listOf("Швеция","Sweden")),
        Entry(Country("NO","Норвегия"),listOf("Норвегия","Norway")),
        Entry(Country("ES","Испания"),listOf("Испания","Spain")),
        Entry(Country("IT","Италия"),listOf("Италия","Italy")),
        Entry(Country("CZ","Чехия"),listOf("Чехия","Czechia","Czech Republic")),
        Entry(Country("AT","Австрия"),listOf("Австрия","Austria")),
        Entry(Country("CA","Канада"),listOf("Канада","Canada")),
        Entry(Country("SG","Сингапур"),listOf("Сингапур","Singapore")),
        Entry(Country("HK","Гонконг"),listOf("Гонконг","Hong Kong")),
        Entry(Country("SO","Сомали"),listOf("Сомали","Somalia"))
    )
    private fun flag(code: String)=code.map { String(Character.toChars(0x1F1E6+(it-'A'))) }.joinToString("")
    private fun hasWord(name: String, word: String): Boolean =
        name.contains(word,true) && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])",RegexOption.IGNORE_CASE).containsMatchIn(name)
    private val iso=java.util.Locale.getISOCountries().toSet()
    private val localized=iso.map {code ->
        val locale=java.util.Locale("",code)
        Triple(code,locale.getDisplayCountry(java.util.Locale("ru")),locale.getDisplayCountry(java.util.Locale.ENGLISH))
    }
    private data class Lookup(val country:Country?)
    private val cache=object:LinkedHashMap<String,Lookup>(64,0.75f,true) {override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Lookup>?)=size>512}

    @Synchronized fun country(name:String):Country? {
        cache[name]?.let {return it.country}
        return classify(name).also {cache[name]=Lookup(it)}
    }
    private fun classify(name: String): Country? {
        val prefix=name.substringBefore('·')
        val normalized=prefix.lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
        // Auto's decorative flag describes a bundle. A numbered leaf's explicit flag is real metadata.
        if(generic(prefix) && !normalized.matches(Regex("(?:proxy|node|узел)(?: [0-9]+)?"))) return null
        if('.' in name && name.matches(Regex("[A-Za-z0-9.:-]+"))) return null // A host fallback is not provider country metadata.
        val flags=mutableListOf<Country>()
        val points=name.codePoints().toArray()
        for(i in 0 until points.size-1) if(points[i] in 0x1F1E6..0x1F1FF && points[i+1] in 0x1F1E6..0x1F1FF) {
            val code=""+('A'.code+points[i]-0x1F1E6).toChar()+('A'.code+points[i+1]-0x1F1E6).toChar()
            if(code in iso) flags+=countries.firstOrNull {it.country.code==code}?.country ?: Country(code,java.util.Locale("",code).getDisplayCountry(java.util.Locale("ru")))
        }
        val localized=localized.mapNotNull { (code,ru,en) ->
            if(hasWord(name,ru) || hasWord(name,en)) countries.firstOrNull {it.country.code==code}?.country ?: Country(code,ru) else null
        }
        val found=(flags+localized+countries.filter { entry -> entry.aliases.any { hasWord(name,it) } }.map {it.country}).distinctBy {it.code}
        // Contradictory provider labels remain unknown; do not silently choose a country.
        return found.singleOrNull()
    }
    fun isPro(name: String)=hasWord(name,"PRO")
    fun displayName(name:String):String {
        if(country(name)!=null || !generic(name.substringBefore('·'))) return name
        val suffix=if('·' in name) name.substringAfter('·').trim()
            else name.takeIf {it.matches(Regex("(?:proxy|node|узел)[ -][0-9]+",RegexOption.IGNORE_CASE))}.orEmpty()
        return "Узел"+if(suffix.isNotBlank()) " · $suffix" else ""
    }
    private fun generic(name: String):Boolean {
        val clean=name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
        return clean in setOf("auto","авто","автовыбор","автоматически","automatic","default","proxy","узел","vpn","auto select","auto selection","автовыбор провайдера") || clean.matches(Regex("(?:proxy|node|узел) [0-9]+"))
    }

    /** Equivalent connection graphs can appear first in Auto and later in named regions. */
    fun preferredName(labels:List<String>,identity:String):String {
        require(labels.isNotEmpty())
        val countries=labels.mapNotNull {country(it)?.code}.distinct()
        if(countries.size>1) return "Узел с разными странами · ${identity.takeLast(8)}"
        fun quality(label:String)=when {
            country(label)!=null -> 2
            generic(label.substringBefore('·')) -> 0
            else -> 1
        }
        return labels.sortedWith(compareByDescending<String> {quality(it)}
            .thenByDescending {isPro(it)}.thenBy {it.length}.thenBy {it}).first()
    }

    /** Wrapper labels like Auto must not erase an explicit country-bearing inner tag. */
    fun profileName(wrapper: String?, inner: String, single: Boolean): String {
        if(wrapper.isNullOrBlank()) return inner
        if(generic(wrapper)) return inner
        if(country(wrapper)==null && country(inner)!=null) {
            return if(isPro(wrapper) && !isPro(inner)) "$inner · PRO" else inner
        }
        return if(single) wrapper else "$wrapper · $inner"
    }
}
