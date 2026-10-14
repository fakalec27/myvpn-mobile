package io.nekohasekai.sfa.myvpn.autoselect
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.saveable.rememberSaveable
import io.nekohasekai.sfa.myvpn.importer.NodeCatalog
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.myvpn.importer.NodeNames
import kotlinx.coroutines.delay
import io.nekohasekai.sfa.myvpn.ui.SettingChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerCard(selector:Group,auto:Group,pending:Boolean,select:(String,String)->Unit,test:(String)->Unit,label:String?=null,
    diagnostics:Boolean=false,toggleDiagnostics:()->Unit={},testing:Boolean=false) {
    var sort by rememberSaveable {mutableStateOf(ServerSort.SPEED)}
    var tier by rememberSaveable {mutableStateOf(ServerTier.ALL)}
    var country by rememberSaveable {mutableStateOf<String?>(null)}
    var sortMenu by remember {mutableStateOf(false)}
    var filterMenu by remember {mutableStateOf(false)}
    val names=NodeNames.snapshot()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()/1000) }
    LaunchedEffect(Unit) {while(true) {delay(1000);now=System.currentTimeMillis()/1000}}
    val automatic=selector.selected==auto.tag
    val effective=if(automatic) auto.selected else selector.selected
    val measured=if(automatic) auto.items.firstOrNull {it.tag==effective} else selector.items.firstOrNull {it.tag==effective}
    val fresh=measured?.let {AutoTopology.fresh(it.urlTestTime,it.urlTestDelay,now)}==true
    val pin=AutoTopology.manualTarget(selector,auto,now)
    Card(Modifier.fillMaxWidth().padding(bottom=16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            SettingChoice(label ?: "Сервер VPN",if(automatic) "Авто" else "Вручную",enabled=!pending && !testing,
                modifier=Modifier.fillMaxWidth(),horizontalPadding=0.dp,containerColor=androidx.compose.ui.graphics.Color.Transparent) {dismiss ->
                DropdownMenuItem(text={Text("Авто")},leadingIcon={RadioButton(automatic,null)},enabled=auto.items.isNotEmpty(),onClick={dismiss();if(!automatic) select(selector.tag,auto.tag)})
                DropdownMenuItem(text={Text("Вручную")},leadingIcon={RadioButton(!automatic,null)},onClick={dismiss();if(!diagnostics) toggleDiagnostics();if(automatic) pin?.let {select(selector.tag,it)}})
            }
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(effective.isBlank()) "Узел не выбран" else NodeNames.name(effective),style=MaterialTheme.typography.titleMedium)
                Text(when {
                    pending -> "Переключаем соединение…"
                    testing -> "Проверяем узлы…"
                    automatic && auto.items.isEmpty() -> "Нет доступных узлов вне России"
                    measured==null || measured.urlTestDelay<=0 -> "Соединение не подтверждено"
                    !fresh -> "Нужна повторная проверка"
                    else -> "${measured.urlTestDelay} мс · проверено ${now-measured.urlTestTime} с назад"
                },style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(pending || testing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick={test(if(automatic) auto.tag else selector.tag)},enabled=!pending && !testing && (!automatic || auto.items.isNotEmpty()),modifier=Modifier.weight(1f).heightIn(min=48.dp)) {
                    if(testing) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Icon(Icons.Default.Refresh,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(testing) "Проверяем…" else "Проверить")
                }
                Box {
                    IconButton(onClick={sortMenu=true},modifier=Modifier.size(48.dp)) {Icon(Icons.Default.Sort,"Сортировка серверов")}
                    DropdownMenu(sortMenu,{sortMenu=false}) {ServerSort.entries.forEach {value -> DropdownMenuItem(text={Text(when(value) {ServerSort.SPEED->"По задержке";ServerSort.COUNTRY->"По стране";ServerSort.NAME->"По имени"})},leadingIcon={RadioButton(sort==value,null)},onClick={sort=value;sortMenu=false})}}
                }
                Box {
                    IconButton(onClick={filterMenu=true},modifier=Modifier.size(48.dp)) {Icon(Icons.Default.FilterList,"Фильтры серверов",tint=if(tier!=ServerTier.ALL || country!=null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
                    DropdownMenu(filterMenu,{filterMenu=false}) {
                        ServerTier.entries.forEach {value -> DropdownMenuItem(text={Text(when(value) {ServerTier.ALL->"Все узлы";ServerTier.PRO->"Только PRO";ServerTier.STANDARD->"Без PRO"})},leadingIcon={RadioButton(tier==value,null)},onClick={tier=value})}
                        HorizontalDivider()
                        DropdownMenuItem(text={Text("Все страны")},leadingIcon={RadioButton(country==null,null)},onClick={country=null})
                        selector.items.mapNotNull {NodeCatalog.country(names[it.tag] ?: it.tag)}.distinctBy {it.code}.sortedBy {it.name}.forEach {c -> DropdownMenuItem(text={Text(c.name)},leadingIcon={RadioButton(country==c.code,null)},onClick={country=c.code})}
                        DropdownMenuItem(text={Text("Страна не указана")},leadingIcon={RadioButton(country=="unknown",null)},onClick={country="unknown"})
                    }
                }
            }
            if(diagnostics) {
                if(automatic && pin==null) Text("Нет проверенного узла. Проверьте соединение или выберите сервер ниже.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(automatic) "Выбор узла включит ручной режим" else "Выберите сервер",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Column {
                    val visible=ServerPresentation.visible(selector.items.filter {it.tag!=auto.tag},names,auto.items,sort,tier,country,"",now)
                    if(visible.isEmpty()) Text("Нет узлов с такими фильтрами",Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium)
                    visible.forEach {item ->
                        key(item.tag) {
                        val result=auto.items.firstOrNull {it.tag==item.tag} ?: item
                        val chosen=!automatic && selector.selected==item.tag
                        Surface(onClick={select(selector.tag,item.tag)},enabled=!pending && !testing,shape=RoundedCornerShape(12.dp),
                            color=if(chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                            Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                RadioButton(selected=chosen,onClick=null,enabled=!pending)
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(NodeNames.name(item.tag),style=MaterialTheme.typography.bodyLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                                    val nodeCountry=NodeCatalog.country(NodeNames.name(item.tag))
                                    Text((if(nodeCountry==null) "Страна не указана · " else "")+item.displayType,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    if(item.tag !in auto.items.map {it.tag}.toSet()) Text("Только вручную",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(when {
                                    result==null || result.urlTestDelay<=0 -> "—"
                                    !AutoTopology.fresh(result.urlTestTime,result.urlTestDelay,now) -> "Устарело"
                                    else -> "${result.urlTestDelay} мс"
                                },style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider(Modifier.padding(horizontal=12.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.45f))
                        }
                    }
                }
            }
        }
    }
}
