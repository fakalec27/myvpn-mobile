package io.nekohasekai.sfa.myvpn.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UpdateUiState(val hasAccess: Boolean = false, val busy: Boolean = false,
    val message: String = "Проверьте, доступна ли новая версия.", val release: Release? = null,
    val progress: Int? = null, val ready: File? = null)

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val access = AccessStore(app)
    private val repository = UpdateRepository(app, access)
    private val state = MutableStateFlow(UpdateUiState(hasAccess = access.read() != null))
    val ui = state.asStateFlow()
    fun saveCode(code: String) {
        if (state.value.busy) return
        try { access.save(code.trim()); state.value = UpdateUiState(hasAccess = true, message = "Доступ сохранён. Можно проверить обновления.") }
        catch (_: Exception) { state.update { it.copy(message = "Не удалось сохранить код. Проверьте код доступа и повторите.") } }
    }
    fun check() {
        if (state.value.busy) return
        state.update { it.copy(busy = true, release = null, ready = null, progress = null, message = "Проверяем обновления…") }
        viewModelScope.launch {
            try {
                val release = repository.check()
                state.update { it.copy(release = release.takeIf { r -> r.versionCode > BuildConfig.VERSION_CODE },
                    message = if (release.versionCode > BuildConfig.VERSION_CODE) "Доступна версия ${release.versionName}" else "Установлена актуальная версия.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun download() {
        val release = state.value.release ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true, progress = 0, ready = null, message = "Скачиваем обновление…") }
        viewModelScope.launch {
            try {
                val file = repository.download(release) { p -> state.update { it.copy(progress = p) } }
                state.update { it.copy(ready = file, message = "Обновление проверено. Подтвердите установку в Android.") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { state.update { it.copy(busy = false, progress = null) } }
        }
    }
    fun prepareInstall(onReady: (File) -> Unit) {
        val file = state.value.ready ?: return; val release = state.value.release ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.verifyApk(file, release) }; repository.authorizeInstall(release) { onReady(file) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed(e) }
            finally { state.update { it.copy(busy = false) } }
        }
    }
    fun installFailed() { state.update { it.copy(message = "Не удалось открыть установщик. Попробуйте ещё раз.") } }
    private fun failed(e: Exception) {
        val message = when ((e as? UpdateFailure)?.reason) {
            "access" -> "Код доступа не принят. Введите действующий код устройства."
            "trust" -> "Проверка подлинности не пройдена. Обновление не будет установлено. Проверьте дату на телефоне или повторите позже."
            "rate" -> "Слишком много запросов. Попробуйте позже."
            "storage" -> "Не удалось сохранить обновление. Проверьте свободное место."
            else -> "Не удалось связаться с сервером. Проверьте интернет и повторите."
        }
        state.update { it.copy(message = message, ready = null) }
    }
}
