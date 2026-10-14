package io.nekohasekai.sfa.myvpn.policy
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment

@Composable fun RoutePicker(item: PolicyAssignment, nodes: List<String>, stableAvailable: Boolean, enabled: Boolean,bypassExit:BypassExit=BypassExit.VPN,
                           choose: (RouteDestination, String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focus=LocalFocusManager.current
    Box(Modifier.width(128.dp)) {
        Surface(onClick = { focus.clearFocus(); expanded = true }, enabled = enabled,shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) { Text(when (item.destination) {
            RouteDestination.DIRECT -> "Напрямую"
            RouteDestination.BYPASS -> "Обход"
            RouteDestination.VPN -> "Обход"
            RouteDestination.PROVIDER -> "Подписка"
            RouteDestination.STABLE -> "Свой VPS"
            RouteDestination.NODE -> item.nodeTag?.let { io.nekohasekai.sfa.myvpn.importer.NodeNames.name(it) } ?: "Узел"
        },modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis); Icon(Icons.Default.ArrowDropDown,null,Modifier.size(24.dp)) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },modifier=Modifier.heightIn(max=320.dp)) {
            fun pick(destination: RouteDestination, node: String? = null) { expanded = false; choose(destination, node) }
            Text(item.value,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            HorizontalDivider()
            DropdownMenuItem(text={Text("Выход обхода · ${if(bypassExit==BypassExit.STABLE) "свой VPS" else "подписка"}")},leadingIcon={RadioButton(item.destination in setOf(RouteDestination.BYPASS,RouteDestination.VPN),null)},onClick={pick(RouteDestination.BYPASS)})
            DropdownMenuItem(text = { Text("Напрямую") },leadingIcon={RadioButton(item.destination==RouteDestination.DIRECT,null)}, onClick = { pick(RouteDestination.DIRECT) })
            DropdownMenuItem(text = { Text("Всегда через подписку") },leadingIcon={RadioButton(item.destination==RouteDestination.PROVIDER,null)}, onClick = { pick(RouteDestination.PROVIDER) })
            DropdownMenuItem(text = { Text(if (stableAvailable) "Свой VPS" else "Свой VPS · сначала настройте") },
                leadingIcon={RadioButton(item.destination==RouteDestination.STABLE,null)},onClick = { pick(RouteDestination.STABLE) }, enabled = stableAvailable)
            if (nodes.isNotEmpty()) HorizontalDivider()
            nodes.forEach { node -> DropdownMenuItem(text = { Text(io.nekohasekai.sfa.myvpn.importer.NodeNames.name(node)) },leadingIcon={RadioButton(item.destination==RouteDestination.NODE && item.nodeTag==node,null)}, onClick = { pick(RouteDestination.NODE, node) }) }
        }
    }
}
