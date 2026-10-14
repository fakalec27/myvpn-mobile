package io.nekohasekai.sfa.myvpn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.alpha

/** Native settings-row geometry, shared by routing and server mode. */
@Composable fun SettingChoice(title:String,value:String,enabled:Boolean=true,modifier:Modifier=Modifier,
    horizontalPadding:Dp=16.dp,containerColor:Color=MaterialTheme.colorScheme.surfaceContainer,
    choices:@Composable ColumnScope.(dismiss:()->Unit)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Box(modifier) {
        Surface(onClick={expanded=true},enabled=enabled,shape=MaterialTheme.shapes.medium,color=containerColor) {
            Row(Modifier.fillMaxWidth().heightIn(min=72.dp).alpha(if(enabled) 1f else 0.38f).padding(horizontal=horizontalPadding,vertical=12.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title,style=MaterialTheme.typography.bodyLarge)
                    Text(value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ExpandMore,null,Modifier.size(24.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded,{expanded=false}) {choices {expanded=false}}
    }
}

@Composable fun SettingSwitch(title:String,value:String,checked:Boolean,enabled:Boolean=true,onCheckedChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=72.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).alpha(if(enabled) 1f else 0.38f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.bodyLarge)
            Text(value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked,onCheckedChange,enabled=enabled)
    }
}
