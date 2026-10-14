package io.nekohasekai.sfa.myvpn.ui
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.myvpn.policy.LocalBottomOverlay

/** One owner of content/IME/overlay insets beneath the native application's toolbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MyVpnScreen(title:String,onBack:()->Unit,actions:@Composable RowScope.()->Unit={},
    footer:(@Composable ColumnScope.()->Unit)?=null,message:String?=null,content:@Composable ColumnScope.()->Unit) {
    val snackbar=remember {SnackbarHostState()}
    LaunchedEffect(message) {message?.let {snackbar.showSnackbar(it)}}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    OverrideTopBar { TopAppBar(title={Text(title,maxLines=1,overflow=TextOverflow.Ellipsis)},navigationIcon={IconButton(onClick={focus.clearFocus();keyboard?.hide();onBack()}) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")
    }},actions=actions) }
    val padding=LocalScaffoldPadding.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).consumeWindowInsets(padding)
        .padding(bottom=LocalBottomOverlay.current).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize(),content=content)
            SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).padding(16.dp))
        }
        if(footer!=null) Surface(color=MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=footer)
        }
    }
}
@Composable fun FormSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
        }
    }
}
