package io.nekohasekai.sfa.myvpn.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun ApplyFooter(enabled:Boolean,saving:Boolean,onSave:()->Unit) {
    Button(shape=MaterialTheme.shapes.medium,onClick=onSave,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
        Text(if(saving) "Сохранение…" else "Сохранить и применить")
    }
}
