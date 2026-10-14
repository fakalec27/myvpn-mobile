"""Native editor recovery for old one-shot USB transfer profiles; metadata only."""
def patch(root,write):
    name='app/src/main/java/io/nekohasekai/sfa/compose/screen/profile/EditProfileViewModel.kt'
    text=(root/name).read_text(encoding='utf-8')
    anchor='    fun updateRemoteProfile() {'
    assert anchor in text
    text=text.replace(anchor,'''    fun keepLocalTransfer() {
        val state = _uiState.value
        val original = state.profile ?: return
        if (state.isSaving || state.isUpdating || state.hasChanges || !io.nekohasekai.sfa.myvpn.importer.LocalTransfer.isInstallerSource(state.remoteUrl)) return
        _uiState.update { it.copy(isSaving=true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                io.nekohasekai.sfa.myvpn.policy.PolicyRuntime.serial {
                    val current = ProfileManager.get(original.id) ?: error("Profile unavailable")
                    check(current.typed.path==original.typed.path && current.typed.remoteURL==state.originalRemoteUrl)
                    check(io.nekohasekai.sfa.myvpn.importer.LocalTransfer.isInstallerSource(current.typed.remoteURL))
                    current.typed.type=TypedProfile.Type.Local
                    current.typed.remoteURL=""
                    current.typed.autoUpdate=false
                    ProfileManager.update(current)
                }
                runCatching { UpdateProfileWork.reconfigureUpdater() }
                loadProfile(original.id)
            } catch (failure:kotlinx.coroutines.CancellationException) { throw failure }
            catch (_:Exception) { _uiState.update { it.copy(errorMessage="Не удалось изменить источник. Текущая конфигурация сохранена.") } }
            finally { _uiState.update { it.copy(isSaving=false) } }
        }
    }

'''+anchor,1)
    write(name,text)
    name='app/src/main/java/io/nekohasekai/sfa/compose/screen/profile/EditProfileScreen.kt'
    text=(root/name).read_text(encoding='utf-8')
    anchor='                            // Auto Update Toggle'
    assert anchor in text
    text=text.replace(anchor,'''                            if (io.nekohasekai.sfa.myvpn.importer.LocalTransfer.isInstallerSource(uiState.originalRemoteUrl)) {
                                Text("Этот профиль передан с ПК как локальный снимок. Временная ссылка не является подпиской; конфигурация, правила и ключи уже сохранены на телефоне.", style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick=viewModel::keepLocalTransfer,enabled=!uiState.hasChanges && !uiState.isUpdating && !uiState.isSaving) {
                                    Text("Сохранить как локальный")
                                }
                            }

'''+anchor,1)
    if 'import androidx.compose.material3.TextButton' not in text:
        text=text.replace('import androidx.compose.material3.Text','import androidx.compose.material3.TextButton\nimport androidx.compose.material3.Text',1)
    write(name,text)
