"""Pinned client patch generator; see README for scratch replay and public safety overlay."""
from pathlib import Path
import subprocess
import argparse
import json

parser=argparse.ArgumentParser()
parser.add_argument('--abi',choices=['arm64-v8a','x86_64'],default='arm64-v8a')
parser.add_argument('--version-code',type=int,default=2026101213)
parser.add_argument('--version-name',default='1.14.3-myvpn13')
args=parser.parse_args()
assert args.version_code >= 2026101202
assert __import__('re').fullmatch(r'[A-Za-z0-9.+_-]{1,80}',args.version_name)

root = Path(__file__).parent / 'client-source'
expected = 'ae93152f21457723af77d144f28c1f7be7362aec'
assert subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD']).decode().strip() == expected

def original(name):
    return subprocess.check_output(['git', '-C', str(root), 'show', 'HEAD:' + name]).decode('utf-8')

def write(name, content):
    (root / name).write_text(content, encoding='utf-8', newline='\n')

name = 'app/build.gradle.kts'
text = original(name).replace('applicationId = "io.nekohasekai.sfa"', 'applicationId = "com.myvpn.mobile"')
text = text.replace('base.archivesName.set("SFA-${versionName}")', 'base.archivesName.set("myVPN-Mobile-${versionName}")')
text = text.replace('storeFile = file("release.keystore")', 'storeFile = file(getProps("KEYSTORE_FILE"))')
text = text.replace('include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")', 'include("'+args.abi+'")')
write(name, text+'\ndependencies { testImplementation("junit:junit:4.13.2"); implementation("org.yaml:snakeyaml:2.5") }\n')
write('version.properties', f'VERSION_CODE={args.version_code}\nVERSION_NAME={args.version_name}\nGO_VERSION=go1.26.8\n')
name = 'app/src/main/res/values/strings.xml'
write(name, original(name).replace('translatable="false">sing-box</string>', 'translatable="false">myVPN Mobile</string>'))
for strings in (root/'app/src/main/res').glob('values-*/strings.xml'):
    relative=str(strings.relative_to(root)).replace('\\','/')
    text=original(relative)
    if '<string name="app_name"' in text:
        write(relative,__import__('re').sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)',r'\1myVPN Mobile\2',text))
name = 'app/src/main/AndroidManifest.xml'
manifest=original(name).replace('@mipmap/ic_launcher', '@drawable/ic_myvpn').replace('android:allowBackup="true"','android:allowBackup="false"')
manifest=manifest.replace('<application','<application android:usesCleartextTraffic="true"',1)
manifest=manifest.replace('</application>', '''<provider android:name=".myvpn.update.UpdateFileProvider" android:authorities="${applicationId}.updates" android:exported="false" android:grantUriPermissions="true">
            <meta-data android:name="android.support.FILE_PROVIDER_PATHS" android:resource="@xml/myvpn_update_paths" />
        </provider>
    </application>''')
main_start=manifest.index('android:name=".compose.MainActivity"')
main_end=manifest.index('</activity>',main_start)
schemes=['happ','vless','vmess','trojan','ss','ssconf','socks','socks5','socks4','tuic','hysteria','hysteria2','hy2','anytls','naive+https','wireguard','wg','vpn']
filters='''<intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
'''+''.join(f'                <data android:scheme="{scheme}" />\n' for scheme in schemes)+'''            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="text/plain" />
            </intent-filter>
            '''
manifest=manifest[:main_end]+filters+manifest[main_end:]
write(name,manifest)
write('app/src/main/res/xml/myvpn_update_paths.xml','<paths><cache-path name="updates" path="myvpn-updates/" /></paths>\n')
exclusions='\n'.join(f'    <exclude domain="{domain}" path="." />' for domain in ['root','file','database','sharedpref','external'])
write('app/src/main/res/xml/backup_rules.xml', '<full-backup-content>\n'+exclusions+'\n</full-backup-content>\n')
write('app/src/main/res/xml/data_extraction_rules.xml', '<data-extraction-rules>\n  <cloud-backup>\n'+exclusions+'\n  </cloud-backup>\n  <device-transfer>\n'+exclusions+'\n  </device-transfer>\n</data-extraction-rules>\n')
write('app/src/main/res/drawable/ic_myvpn.xml', '''<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">
<path android:fillColor="#19364A" android:pathData="M0,0h48v48h-48z"/>
<path android:fillColor="@android:color/transparent" android:strokeColor="#FFFFFF" android:strokeWidth="5" android:strokeLineCap="round" android:strokeLineJoin="round" android:pathData="M12,13L24,35L36,13"/>
</vector>\n''')
name = 'app/src/other/java/io/nekohasekai/sfa/vendor/Vendor.kt'
text = original(name)
start=text.index('    override fun checkUpdate(activity:')
end=text.index('    private fun showUpdateDialog',start)
text=text[:start]+'''    override fun checkUpdate(activity: Activity, byUser: Boolean) {
        if (byUser) activity.runOnUiThread {
            activity.startActivity(Intent(activity, io.nekohasekai.sfa.compose.MainActivity::class.java)
                .putExtra("myvpn_route", "settings/myvpn-update")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

'''+text[end:]
start = text.index('    override fun checkUpdateAsync():')
end = text.index('\n    }', start) + len('\n    }')
text = text[:start] + '    override fun checkUpdateAsync(): UpdateInfo? = null' + text[end:]
text = text.replace('override val hasCustomUpdate: Boolean get() = true', 'override val hasCustomUpdate: Boolean get() = false')
text = text.replace('override val hasCustomUpdate = true', 'override val hasCustomUpdate = false')
text = text.replace('UpdateWorker.schedule(', '/* myVPN does not schedule upstream binary updates */\n        // UpdateWorker.schedule(')
write(name, text)
assert 'override val hasCustomUpdate = false' in text
assert 'GitHubUpdateChecker().use' not in text
name='app/src/github/java/io/nekohasekai/sfa/vendor/UpdateWorker.kt'
text=original(name)
start=text.index('    override suspend fun doWork(): Result {')
text=text[:start]+'    override suspend fun doWork(): Result = Result.success()\n}\n'
write(name,text)

# Profile deletion also removes our private overlays and imported config/metadata.
# A ticket survives failure after the DB commit; list() retries only absent IDs.
name='app/src/main/java/io/nekohasekai/sfa/database/ProfileManager.kt'
text=original(name)
for parameter,targets in [('profile','listOfNotNull(instance.profileDao().get(profile.id))'),('profiles','existing.filter { it.id in profiles.map { p -> p.id }.toSet() }')]:
    anchor=f'            return instance.profileDao().delete({parameter})'
    assert anchor in text
    text=text.replace(anchor,f'''            val existing=instance.profileDao().list()
            val targets={targets}
            if(targets.isEmpty()) return 0
            if(targets.any {{ io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.matchesActiveProfile(it.id) }})
                error("Сначала остановите VPN перед удалением работающего профиля.")
            val context=Application.application
            val cleanup=io.nekohasekai.sfa.myvpn.importer.ProfileDeletion
            var committed=false
            try {{
                cleanup.prepare(context,targets,existing)
                val count=instance.profileDao().delete({parameter})
                committed=count>0
                if(committed) android.util.Log.i("myVPNProfiles","Deleted profile rows; ids="+targets.map {{ it.id }})
                if(committed) cleanup.retry(context,instance.profileDao().list())
                return count
            }} finally {{ if(!committed) cleanup.cancel(context,targets.map {{ it.id }}) }}''')
text=text.replace('    suspend fun list(): List<Profile> = instance.profileDao().list()', '''    suspend fun list(): List<Profile> {
        val profiles=instance.profileDao().list()
        io.nekohasekai.sfa.myvpn.importer.ProfileDeletion.retry(Application.application,profiles)
        return profiles
    }''')
# Import rollback already owns this gate; all explicit UI deletes acquire it.
text=text.replace('    suspend fun delete(profile: Profile): Int {', '''    suspend fun delete(profile: Profile): Int = io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial { deleteUnlocked(profile) }
    internal suspend fun rollbackImportedProfile(profile: Profile): Int = deleteUnlocked(profile)
    private suspend fun deleteUnlocked(profile: Profile): Int {''')
text=text.replace('    suspend fun delete(profiles: List<Profile>): Int {', '''    suspend fun delete(profiles: List<Profile>): Int = io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial { deleteUnlocked(profiles) }
    private suspend fun deleteUnlocked(profiles: List<Profile>): Int {''')
write(name,text)
print('Pinned client patched: own package/name/icon; upstream binary updates disabled.')

trust=json.loads((Path(__file__).parent/'updates/trust.json').read_text())
target='app/src/main/java/io/nekohasekai/sfa/myvpn/update/'
(root/target).mkdir(parents=True,exist_ok=True)
for file in (Path(__file__).parent/'updates/client').glob('*.kt'):
    write(target+file.name,file.read_text(encoding='utf-8'))
(root/target/'UpdateActivity.kt').unlink(missing_ok=True)
policytarget='app/src/main/java/io/nekohasekai/sfa/myvpn/policy/'
(root/policytarget).mkdir(parents=True,exist_ok=True)
for file in (Path(__file__).parent/'updates/policy').glob('*.kt'):
    write(policytarget+file.name,file.read_text(encoding='utf-8'))

autotarget='app/src/main/java/io/nekohasekai/sfa/myvpn/autoselect/'
(root/autotarget).mkdir(parents=True,exist_ok=True)
for file in (Path(__file__).parent/'updates/autoselect').glob('*.kt'):
    write(autotarget+file.name,file.read_text(encoding='utf-8'))

importtarget='app/src/main/java/io/nekohasekai/sfa/myvpn/importer/'
(root/importtarget).mkdir(parents=True,exist_ok=True)
for file in (Path(__file__).parent/'updates/import').glob('*.kt'):
    write(importtarget+file.name,file.read_text(encoding='utf-8'))
uitarget='app/src/main/java/io/nekohasekai/sfa/myvpn/ui/'
(root/uitarget).mkdir(parents=True,exist_ok=True)
for file in (Path(__file__).parent/'updates/ui').glob('*.kt'):
    write(uitarget+file.name,file.read_text(encoding='utf-8'))
(root/policytarget/'PolicyActivity.kt').unlink(missing_ok=True)
write(target+'UpdateTrust.kt',f'''package io.nekohasekai.sfa.myvpn.update
object UpdateTrust {
    const val ENABLED = false
    const val ENABLED = false{
    const val ENDPOINT = "{trust['endpoint']}"
    const val PUBLIC_KEY = "{trust['manifestPublicKey']}"
    const val APK_SIGNER = "{trust['apkSigner']}"
    const val ABI = "{args.abi}"
}}
''')
testtarget='app/src/test/java/io/nekohasekai/sfa/myvpn/update/'
(root/testtarget).mkdir(parents=True,exist_ok=True)
for file in (Path(__file__).parent/'updates/tests').glob('*.kt'):
    write(testtarget+file.name,file.read_text(encoding='utf-8'))
name='app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/SettingsScreen.kt'
text=original(name).replace('bottom = scaffoldPadding.calculateBottomPadding() + 8.dp','bottom = scaffoldPadding.calculateBottomPadding() + io.nekohasekai.sfa.myvpn.policy.LocalBottomOverlay.current + 8.dp')
anchor='''                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.core),'''
assert anchor in text
text=text.replace(anchor,'''                ListItem(
                    headlineContent = { Text("Маршрутизация", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text("Домены и приложения", style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Icon(Icons.Outlined.FilterAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { navController.navigate("settings/myvpn-policy") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )

'''+anchor)
write(name,text)
name='app/src/main/java/io/nekohasekai/sfa/compose/screen/settings/AppSettingsScreen.kt'
text=original(name)
start=text.index('        Card(',text.index('text = stringResource(R.string.update_settings)'))
end=text.index('\n@Composable\nprivate fun UpdateSourceDialog',start)
assert text[start:end].endswith('    }\n}\n')
text=text[:start]+'''        Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            ListItem(headlineContent = { Text("Обновления myVPN", style = MaterialTheme.typography.bodyLarge) },
                supportingContent = { Text("Проверка версии и установка", style = MaterialTheme.typography.bodyMedium) },
                leadingContent = { Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { navController.navigate("settings/myvpn-update") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
        }
    }
}
'''+text[end:]
write(name,text)
name='app/src/main/java/io/nekohasekai/sfa/compose/navigation/Navigation.kt'
text=original(name)
anchor='        composable(Screen.Settings.route) {'
assert anchor in text
screens=''
for route,function in [('myvpn-update','update.UpdateScreen'),('myvpn-policy','policy.PolicyScreen'),('myvpn-stable','policy.StableExitScreen'),('myvpn-import','importer.ImportScreen')]:
    screens+='''        composable(route = "settings/'''+route+'''", enterTransition = slideInFromRight,
            exitTransition = slideOutToLeft, popEnterTransition = slideInFromLeft, popExitTransition = slideOutToRight) {
            io.nekohasekai.sfa.myvpn.'''+function+'''(navController)
        }

'''
write(name,text.replace(anchor,screens+anchor))

group_path='app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/GroupsCard.kt'
group_text=original(group_path)
assert 'text = item.tag,' in group_text
write(group_path,group_text.replace('text = item.tag,','text = io.nekohasekai.sfa.myvpn.importer.NodeNames.name(item.tag),'))
__import__('runpy').run_path(str(Path(__file__).parent/'updates/autoselect/patch.py'))['patch'](original,write)

for update_path in ['app/src/main/java/io/nekohasekai/sfa/bg/UpdateProfileWork.kt',
                    'app/src/main/java/io/nekohasekai/sfa/compose/screen/profile/EditProfileViewModel.kt',
                    'app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/DashboardViewModel.kt']:
    update_text=original(update_path)
    fetch='HTTPClient().use { it.getString(profile.typed.remoteURL) }'
    assert fetch in update_text
    # All remote profiles commit via the same runtime/deletion gate. In particular,
    # never let upstream writeText/reconnect run after a managed refresh committed.
    start=update_text.index('val content = '+fetch)
    finish=update_text.index('ProfileManager.update(profile)',start)+len('ProfileManager.update(profile)')
    update_text=update_text[:start]+'io.nekohasekai.sfa.myvpn.importer.ImportSource.refresh(profile)'+update_text[finish:]
    if update_path.endswith('UpdateProfileWork.kt'):
        start=update_text.index('            if (selectedProfileUpdated) {')
        finish=update_text.index('            return if (success)',start)
    elif update_path.endswith('EditProfileViewModel.kt'):
        start=update_text.index('                // Reload service if needed')
        finish=update_text.index('            } catch (e: Exception)',start)
        # The inner reload catch belongs to the block being removed.
        finish=update_text.index('            } catch (e: Exception)',finish+1)
    else:
        start=update_text.index('                // Restart service if this is the selected profile and content changed')
        finish=update_text.index('            } catch (e: Exception)',start)
    update_text=update_text[:start]+update_text[finish:]
    update_text=update_text.replace('            var selectedProfileUpdated = false\n','').replace('                var selectedProfileUpdated = false\n','')
    update_text=update_text.replace('            val selectedProfile = Settings.selectedProfile\n','')
    assert 'writeText(content)' not in update_text
    write(update_path,update_text)
__import__('runpy').run_path(str(Path(__file__).parent/'updates/import/patch-transfer.py'))['patch'](root,write)
name='app/src/main/java/io/nekohasekai/sfa/bg/BoxService.kt'
text=original(name)
old='val content = File(profile.typed.path).readText()'
assert text.count(old)==2
text=text.replace(old,'io.nekohasekai.sfa.myvpn.importer.NodeNames.load(profile.typed.path)\n            val content = io.nekohasekai.sfa.myvpn.policy.PolicyStore(Application.application).apply(profile.id, File(profile.typed.path).readText())')
runtime='io.nekohasekai.sfa.myvpn.policy.PolicyRuntime'
text=text.replace('    private var lastProfileName = ""','    @Volatile private var lastPolicyConfiguration: String? = null\n    @Volatile private var lastPolicyProfileId: Long? = null\n    private var lastProfileName = ""')
text=text.replace('            status.postValue(Status.Started)','            lastPolicyConfiguration = content\n            lastPolicyProfileId = profile.id\n            status.postValue(Status.Started)')
start=text.index('    suspend fun serviceReload0() {')
end=text.index('    override fun getSystemProxyStatus()',start)
reload=text[start:end]
reload=reload.replace('suspend fun serviceReload0() {', 'suspend fun serviceReload0() = '+runtime+'.serial { serviceReloadInternal() }\n\n    private suspend fun serviceReloadInternal(candidate: String? = null) {')
reload=reload.replace('val content = io.nekohasekai.sfa.myvpn.policy.PolicyStore', 'val content = candidate ?: io.nekohasekai.sfa.myvpn.policy.PolicyStore')
for alert in ['EmptyConfiguration','CreateService','RequestLocationPermission']:
    anchor='            stopAndAlert(Alert.'+alert
    reload=reload.replace(anchor,'            if (candidate != null) error("Policy reload failed: '+alert+'")\n'+anchor)
assert reload.endswith('    }\n\n')
reload=reload[:-7]+'        lastPolicyConfiguration = content\n        lastPolicyProfileId = profile.id\n    }\n\n'
text=text[:start]+reload+text[end:]
text=text.replace('status.value = Status.Starting\n\n        if (!receiverRegistered)', '''status.value = Status.Starting
        '''+runtime+'''.attach(this,
            apply = { id, content ->
                check(Settings.selectedProfile == id)
                check(withContext(Dispatchers.Main) { status.value == Status.Started })
                serviceReloadInternal(content)
            }, stop = { stopAndAlert(Alert.CreateService, "Could not restore previous routing policy") }, current = { lastPolicyConfiguration }, profile = { lastPolicyProfileId })

        if (!receiverRegistered)''')
text=text.replace('            startService()\n', '            '+runtime+'.serial { startService() }\n')
# Serialize asynchronous ordinary stop against policy apply and all reload calls.
stop_start=text.index('    private fun stopService() {')
stop_end=text.index('    private fun closeService()',stop_start)
stop=text[stop_start:stop_end]
stop=stop.replace('GlobalScope.launch(Dispatchers.IO) {','GlobalScope.launch(Dispatchers.IO) {\n            '+runtime+'.serial {')
stop=stop.replace('        }\n    }\n', '            }\n        }\n    }\n')
text=text[:stop_start]+stop+text[stop_end:]
text=text.replace('    internal fun onDestroy() {','    internal fun onDestroy() {\n        '+runtime+'.detach(this)')
write(name,text)
name='app/src/main/java/io/nekohasekai/sfa/compose/MainActivity.kt'
text=original(name).replace('mutableStateOf(!Settings.updateCheckPrompted)', 'mutableStateOf(false)')
text=text.replace('    override fun onCreate(savedInstanceState: Bundle?) {','    private var myvpnStartupReady by mutableStateOf(false)\n\n    override fun onCreate(savedInstanceState: Bundle?) {')
text=text.replace('        enableEdgeToEdge()\n\n        lifecycleScope.launch {', '''        enableEdgeToEdge()
        Log.i("myVPNLifecycle", "Main create; restored=" + (savedInstanceState != null))
        setContent {
            Theme {
                if (myvpnStartupReady) App() else androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }
            }
        }

        lifecycleScope.launch {''')
text=text.replace('UpdateState.loadFromCache()', 'UpdateState.setUpdate(null)')
text=text.replace('''            handleIntent(intent)

            setContent {
                Theme {
                    App()
                }
            }''', '''            handleIntent(intent)
            // A consumed import must not be replayed after rotation/package replacement.
            setIntent(Intent(this@MainActivity, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            myvpnStartupReady = true
            Log.i("myVPNLifecycle", "Main ready")''')
text=text.replace('''        super.onNewIntent(intent)
        handleIntent(intent)''','''        super.onNewIntent(intent)
        handleIntent(intent)
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
        Log.i("myVPNLifecycle", "Main new intent consumed")''')
anchor='''        val uri = intent.data ?: return'''
assert anchor in text
text=text.replace(anchor,'''        intent.getStringExtra("myvpn_route")?.takeIf {
            it == "settings/myvpn-update" || it == "settings/myvpn-policy" || it == "settings/myvpn-stable" || it == "settings/myvpn-import"
        }?.let { pendingNavigationRoute.value = it }
        val importText = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT) else intent.dataString
        val importScheme = intent.data?.scheme?.lowercase()
        if (intent.action == Intent.ACTION_SEND || importScheme in setOf("happ","vless","vmess","trojan","ss","ssconf","socks","socks5","socks4","tuic","hysteria","hysteria2","hy2","anytls","naive+https","wireguard","wg","vpn")) {
            importText?.let { io.nekohasekai.sfa.myvpn.importer.ImportInbox.offer(it) }
            pendingNavigationRoute.value = "settings/myvpn-import"
            return
        }
'''+anchor)
text=text.replace('navController.navigate(ProfileRoutes.NewProfile) {','navController.navigate(if (args.importUrl == null && args.qrsData == null) "settings/myvpn-import" else ProfileRoutes.NewProfile) {',1)
text=text.replace('import android.net.VpnService', 'import androidx.compose.ui.layout.onSizeChanged\nimport android.net.VpnService')
anchor='        val scaffoldContent: @Composable (PaddingValues) -> Unit ='
assert anchor in text
text=text.replace(anchor, '''        var policyOverlayHeight by remember { mutableStateOf(0.dp) }
        val policyDensity = androidx.compose.ui.platform.LocalDensity.current
'''+anchor)
text=text.replace('CompositionLocalProvider(LocalScaffoldPadding provides paddingValues)', '''CompositionLocalProvider(LocalScaffoldPadding provides paddingValues,
                io.nekohasekai.sfa.myvpn.policy.LocalBottomOverlay provides
                    if (!useNavigationRail && !isSubScreen && (isRemote || currentServiceStatus != Status.Stopped)) policyOverlayHeight else 0.dp)''')
overlay='Modifier.align(Alignment.BottomCenter).padding(bottom = bottomOverlayPadding)'
assert text.count(overlay)==2
text=text.replace(overlay, '''Modifier.align(Alignment.BottomCenter).onSizeChanged { size ->
                                    policyOverlayHeight = with(policyDensity) { (size.height.toDp() - bottomOverlayPadding).coerceAtLeast(0.dp) }
                                }.padding(bottom = bottomOverlayPadding)''')
write(name,text)

# Use the same preview for conventional QR links; preserve native multipart QRS.
name='app/src/main/java/io/nekohasekai/sfa/compose/screen/qrscan/QRScanViewModel.kt'
text=original(name)
anchor='    data class RemoteProfile(val uri: Uri) : QRScanResult()'
assert anchor in text
text=text.replace(anchor,anchor+'\n    class ProviderLink(val text: String) : QRScanResult() { override fun toString() = "ProviderLink(input omitted)" }')
anchor='        Log.d(TAG, "Scanned: ${rawValue.take(100)}...")'
assert anchor in text
text=text.replace(anchor,'        Log.d(TAG, "QR received; content omitted")')
anchor='''            val uri = Uri.parse(value)
            if (uri.scheme != "sing-box"'''
assert anchor in text
text=text.replace(anchor,'''            val uri = Uri.parse(value)
            if (value.toByteArray(Charsets.UTF_8).size <= io.nekohasekai.sfa.myvpn.importer.SubscriptionDecoder.MAX_BYTES &&
                uri.scheme?.lowercase() in setOf("happ","http","https","vless","vmess","trojan","ss","ssconf","socks","socks5","socks4","tuic","hysteria","hysteria2","hy2","anytls","naive+https","wireguard","wg","vpn")) {
                _uiState.update { it.copy(result = QRScanResult.ProviderLink(value)) }
                return true
            }
            if (uri.scheme != "sing-box"''')
write(name,text)
name='app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/ProfilesCard.kt'
text=original(name)
anchor='''                when (result) {
                    is QRScanResult.QRSData ->'''
assert anchor in text
text=text.replace(anchor,'''                when (result) {
                    is QRScanResult.ProviderLink -> {
                        io.nekohasekai.sfa.myvpn.importer.ImportInbox.offer(result.text)
                        onOpenNewProfile(NewProfileArgs())
                    }
                    is QRScanResult.QRSData ->''')
write(name,text)

ui_namespace={}
name='app/src/main/java/io/nekohasekai/sfa/compose/screen/dashboard/ProfilesCard.kt'
text=(root/name).read_text(encoding='utf-8')
text=text.replace('Text(stringResource(R.string.profile_add_create_manually))','Text("Ссылка или подписка")',1)
text=text.replace('Text(stringResource(R.string.create_new_profile_description))','Text("Добавить сервер, подписку или готовый профиль")',1)
write(name,text)
exec((Path(__file__).parent/'updates/ui/patch.py').read_text(encoding='utf-8'),ui_namespace)
ui_namespace['patch'](root,original,write)
(root/'app/src/main/java/io/nekohasekai/sfa/myvpn/diagnostics').mkdir(parents=True,exist_ok=True)
for reset in ['app/src/main/java/io/nekohasekai/sfa/compose/screen/log/LogScreen.kt','app/src/main/java/io/nekohasekai/sfa/bg/DefaultNetworkListener.kt']:
    write(reset,original(reset))
for file in (Path(__file__).parent/'updates/diagnostics').glob('*.kt'):
    write('app/src/main/java/io/nekohasekai/sfa/myvpn/diagnostics/'+file.name,file.read_text(encoding='utf-8'))
__import__('runpy').run_path(str(Path(__file__).parent/'updates/diagnostics/patch.py'))['patch'](root,write)
