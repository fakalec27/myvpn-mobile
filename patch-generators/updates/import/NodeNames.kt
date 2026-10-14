package io.nekohasekai.sfa.myvpn.importer
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.json.*
import android.util.AtomicFile
import java.io.File

/** Display names are separate from stable routing identities and never contain credentials. */
object NodeNames {
    private val names=mutableStateOf<Map<String,String>>(emptyMap())
    fun name(tag: String) = NodeCatalog.displayName(names.value[tag] ?: tag)
    fun snapshot():Map<String,String> = names.value
    fun load(path: String) {
        names.value=try {
            val file=AtomicFile(File(path+".import.json"))
            if(!file.baseFile.exists() && !File(path+".import.json.bak").exists()) emptyMap()
            else Json.parseToJsonElement(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject["names"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        } catch(_:Exception) { emptyMap() }
    }
}
