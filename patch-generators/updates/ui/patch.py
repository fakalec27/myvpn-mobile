def patch(root,original,write):
    name='app/src/main/java/io/nekohasekai/sfa/compose/topbar/TopBarController.kt'
    text=original(name)
    text=text.replace('val content: @Composable () -> Unit)', 'val content: @Composable () -> Unit, val ownerId: String? = null)',1)
    text=text.replace('fun set(key: Any, content: @Composable () -> Unit)', 'fun set(key: Any, content: @Composable () -> Unit, ownerId: String? = null)',1)
    text=text.replace('TopBarEntry(key, content)','TopBarEntry(key, content, ownerId)',1)
    text=text.replace('    val currentContent = rememberUpdatedState(content)', '    val currentContent = rememberUpdatedState(content)\n    val ownerId = (androidx.lifecycle.compose.LocalLifecycleOwner.current as? androidx.navigation.NavBackStackEntry)?.id',1)
    text=text.replace('DisposableEffect(controller, token)', 'DisposableEffect(controller, token, ownerId)',1)
    text=text.replace('controller.set(token) { currentContent.value() }','controller.set(token, content = { currentContent.value() }, ownerId = ownerId)',1)
    assert 'ownerId = ownerId' in text
    write(name,text)
    name='app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt'
    text=(root/name).read_text(encoding='utf-8')
    anchor='val topBarOverride = topBarState.value.lastOrNull()?.content'
    assert anchor in text
    text=text.replace(anchor,'val topBarOverride = io.nekohasekai.sfa.myvpn.ui.toolbarFor(topBarState.value, navBackStackEntry?.id) { it.ownerId }?.content',1)
    write(name,text)
