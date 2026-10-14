package io.nekohasekai.sfa.myvpn.autoselect
import io.nekohasekai.sfa.compose.model.GroupItem
import io.nekohasekai.sfa.myvpn.importer.NodeCatalog

enum class ServerSort { SPEED, COUNTRY, NAME }
enum class ServerTier { ALL, PRO, STANDARD }
object ServerPresentation {
    fun visible(items:List<GroupItem>,names:Map<String,String>,measurements:List<GroupItem>,sort:ServerSort,tier:ServerTier,country:String?,query:String,now:Long):List<GroupItem> {
        val measured=measurements.associateBy {it.tag}
        fun name(item:GroupItem)=names[item.tag] ?: item.tag
        fun delay(item:GroupItem):Int {val m=measured[item.tag] ?: item;return if(AutoTopology.fresh(m.urlTestTime,m.urlTestDelay,now)) m.urlTestDelay else Int.MAX_VALUE}
        val filtered=items.filter {
            val label=name(it);val c=NodeCatalog.country(label)?.code
            (tier==ServerTier.ALL || NodeCatalog.isPro(label)==(tier==ServerTier.PRO)) &&
                (country==null || c==country || (country=="unknown" && c==null)) && label.contains(query,true)
        }
        val compare=when(sort) {
            ServerSort.SPEED -> compareBy<GroupItem> {delay(it)}.thenBy {name(it).lowercase()}
            ServerSort.COUNTRY -> compareBy<GroupItem> {NodeCatalog.country(name(it))?.name ?: "я"}.thenBy {name(it).lowercase()}
            ServerSort.NAME -> compareBy<GroupItem> {name(it).lowercase()}
        }
        return filtered.sortedWith(compare)
    }
    fun visibleGroupTags(groups:List<io.nekohasekai.sfa.compose.model.Group>,independent:Set<String>):Set<String> {
        val hidden=AutoTopology.pairs(groups,independent).values.toSet()
        return groups.filter {it.tag !in hidden}.map {it.tag}.toSet()
    }
}
