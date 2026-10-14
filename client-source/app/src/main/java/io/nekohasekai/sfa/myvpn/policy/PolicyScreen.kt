package io.nekohasekai.sfa.myvpn.policy
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material.icons.filled.Add
import io.nekohasekai.sfa.myvpn.ui.MyVpnScreen
import io.nekohasekai.sfa.myvpn.ui.ApplyFooter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import io.nekohasekai.sfa.myvpn.ui.SettingChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyScreen(navController: NavController, model: PolicyViewModel = viewModel()) {
        val state by model.ui.collectAsStateWithLifecycle()
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.refreshExits() }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var input by rememberSaveable { mutableStateOf("") }
        var query by rememberSaveable { mutableStateOf("") }
        var systems by rememberSaveable { mutableStateOf(false) }
        var filters by remember { mutableStateOf(false) }
        var actionsMenu by remember { mutableStateOf(false) }
        var discard by rememberSaveable { mutableStateOf(false) }
        var restore by rememberSaveable { mutableStateOf(false) }
        var adding by rememberSaveable { mutableStateOf(false) }
        var inputError by rememberSaveable { mutableStateOf<String?>(null) }
        val focus = LocalFocusManager.current
        // Build observable lists outside the non-composable LazyListScope DSL.
        val shownDomains = remember(state.domains,state.protectedDomains,state.assignments,query) {
            (state.domains + state.protectedDomains + state.assignments.filter { it.kind==RuleKind.DOMAIN }.map { it.value })
                .distinct().sorted().filter { it.contains(query,true) }
        }
        val shownApps = remember(state.installed,state.selectedApps,state.protectedApps,state.assignments,systems,query) {
            val installed=state.installed.associateBy { it.packageName }
            val missing=(state.selectedApps + state.assignments.filter { it.kind==RuleKind.APP }.map { it.value })
                .filter { it !in installed }.map { InstalledApp("Не установлено",it,false) }
            (state.installed + missing).filter {
                (!it.system || systems || it.packageName in state.selectedApps || it.packageName in state.protectedApps || state.assignments.any { rule -> rule.kind==RuleKind.APP && rule.value==it.packageName }) &&
                    (it.name.contains(query,true) || it.packageName.contains(query,true))
            }
        }
        fun close() { if (state.dirty) discard = true else navController.navigateUp() }
        BackHandler(state.dirty) { close() }
        MyVpnScreen("Маршрутизация",{close()},actions={
            IconButton(onClick={navController.navigate("settings/myvpn-stable")},enabled=!state.busy) { Icon(Icons.Default.Storage,"Настройки VPS") }
            if(state.canRestore) Box {
                IconButton(onClick={actionsMenu=true},enabled=!state.busy) {Icon(Icons.Default.MoreVert,"Действия маршрутизации")}
                DropdownMenu(actionsMenu,{actionsMenu=false}) {DropdownMenuItem(text={Text("Вернуть исходные списки")},onClick={actionsMenu=false;restore=true})}
            }
        },message=state.message.takeIf {state.loaded && !state.busy && !state.sourceChanged},footer={
            ApplyFooter(state.loaded && state.dirty && !state.busy && !state.sourceChanged,state.busy && state.dirty) { focus.clearFocus(); model.save() }
        }) {
            Column(Modifier.fillMaxSize()) {
                if(!state.loaded || state.sourceChanged) state.message?.let { Text(it,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium) }
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(!state.loaded && !state.busy && state.canRestore) TextButton(onClick={restore=true}) { Text("Восстановить исходные списки") }
                if(!state.loaded && !state.busy && io.nekohasekai.sfa.database.Settings.selectedProfile<=0) FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick={navController.navigate("settings/myvpn-import")},modifier=Modifier.fillMaxWidth().padding(16.dp)) {Text("Добавить подключение")}
                if(state.loaded) {
                    Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        SettingChoice("Выход обхода",if(state.bypassExit==BypassExit.STABLE) "Свой VPS" else "Подписка",enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {dismiss ->
                            DropdownMenuItem(text={Text("Подписка")},leadingIcon={RadioButton(state.bypassExit==BypassExit.VPN,null)},onClick={dismiss();model.bypass(BypassExit.VPN)})
                            DropdownMenuItem(text={Text(if(state.stableAvailable) "Свой VPS" else "Свой VPS · сначала настройте")},leadingIcon={RadioButton(state.bypassExit==BypassExit.STABLE,null)},enabled=state.stableAvailable,onClick={dismiss();model.bypass(BypassExit.STABLE)})
                        }
                        Text("Для текущего профиля. Прямые и закреплённые выходы сохраняются.",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(!state.stableAvailable) FilledTonalButton(shape=MaterialTheme.shapes.medium,onClick={navController.navigate("settings/myvpn-stable")},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Storage,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Настроить свой VPS")
                        }
                    }
                    PrimaryTabRow(selectedTabIndex=tab) {
                        Tab(selected=tab==0,onClick={tab=0;query=""},text={Text("Домены")})
                        Tab(selected=tab==1,onClick={tab=1;query=""},text={Text("Приложения")})
                    }
                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(query,{query=it},placeholder={Text("Поиск")},singleLine=true,modifier=Modifier.weight(1f).heightIn(min=56.dp))
                        if(tab==0) IconButton(onClick={adding=true;inputError=null},enabled=!state.busy,modifier=Modifier.size(48.dp)) { Icon(Icons.Default.Add,"Добавить домен") }
                        else Box {
                            IconButton(onClick={filters=true},modifier=Modifier.size(48.dp)) {Icon(Icons.Default.FilterList,"Фильтры приложений",tint=if(systems) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}
                            DropdownMenu(filters,{filters=false}) {DropdownMenuItem(text={Text("Системные приложения")},leadingIcon={Checkbox(systems,null)},onClick={systems=!systems})}
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(bottom=16.dp)) {
                        if(tab==0) items(shownDomains,key={it}) { domain ->
                            RuleRow(domain,null,{
                                RoutePicker(model.target(RuleKind.DOMAIN,domain),state.nodes,state.stableAvailable,!state.busy,state.bypassExit) { target,node -> model.assign(RuleKind.DOMAIN,domain,target,node) }
                            },!state.busy,if(domain !in state.protectedDomains) ({model.removeDomain(domain)}) else null,
                                if(state.assignments.any { it.kind==RuleKind.DOMAIN && it.value==domain }) ({model.reset(RuleKind.DOMAIN,domain)}) else null)
                        } else items(shownApps,key={it.packageName}) { app ->
                            RuleRow(app.name,app.packageName,{
                                RoutePicker(model.target(RuleKind.APP,app.packageName),state.nodes,state.stableAvailable,!state.busy,state.bypassExit) { target,node -> model.assign(RuleKind.APP,app.packageName,target,node) }
                            },!state.busy,null,if(state.assignments.any { it.kind==RuleKind.APP && it.value==app.packageName }) ({model.reset(RuleKind.APP,app.packageName)}) else null)
                        }
                        if((tab==0 && shownDomains.isEmpty()) || (tab==1 && shownApps.isEmpty())) item { Text(if(tab==1) "Приложений не найдено" else if(query.isBlank()) "Правил пока нет" else "Ничего не найдено",Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if(adding) AlertDialog(onDismissRequest={adding=false},title={Text("Добавить домен")},text={
            OutlinedTextField(input,{input=it;inputError=null},label={Text("Домен или ссылка")},singleLine=true,isError=inputError!=null,
                supportingText={Text(inputError ?: "Правило действует и для поддоменов.")},modifier=Modifier.fillMaxWidth())
        },confirmButton={TextButton(onClick={if(model.addDomain(input)) {input="";adding=false;focus.clearFocus()} else inputError=model.ui.value.message},enabled=input.isNotBlank() && !state.busy) {Text("Добавить")}},dismissButton={TextButton(onClick={adding=false}) {Text("Отмена")}})
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Изменения не сохранены") }, text = { Text("Закрыть экран и оставить прежние списки?") },
            confirmButton = { TextButton(onClick = { navController.navigateUp() }) { Text("Закрыть") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Продолжить") } })
        if (restore) AlertDialog(onDismissRequest = { restore = false }, title = { Text("Исходные списки") }, text = { Text("Заменить изменения исходными списками этого профиля? Они применятся после сохранения.") },
            confirmButton = { TextButton(onClick = { model.restore(); restore = false }) { Text("Восстановить") } }, dismissButton = { TextButton(onClick = { restore = false }) { Text("Отмена") } })
    }

@Composable private fun RuleRow(title:String,subtitle:String?,picker:@Composable ()->Unit,enabled:Boolean,remove:(()->Unit)?,reset:(()->Unit)?) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(start=16.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge,maxLines=1,overflow=TextOverflow.Ellipsis)
            subtitle?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)}
        }
        picker()
        if(remove!=null || reset!=null) Box {
            IconButton(onClick={menu=true},enabled=enabled) {Icon(Icons.Default.MoreVert,"Действия с правилом")}
            DropdownMenu(menu,{menu=false}) {
                reset?.let {DropdownMenuItem(text={Text("По исходному профилю")},onClick={menu=false;it()})}
                remove?.let {DropdownMenuItem(text={Text("Удалить")},onClick={menu=false;it()})}
            }
        } else Spacer(Modifier.width(48.dp))
    }
    HorizontalDivider(Modifier.padding(horizontal=16.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.45f))
}
