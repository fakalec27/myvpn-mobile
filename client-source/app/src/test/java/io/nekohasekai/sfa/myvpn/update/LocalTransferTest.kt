package io.nekohasekai.sfa.myvpn.importer
import org.junit.Assert.*
import org.junit.Test
class LocalTransferTest {
    @Test fun onlyOurOneShotNativeInstallerIsLocal() {
        assertTrue(LocalTransfer.isInstallerSource("http://127.0.0.1:18997/import/0123456789abcdef0123456789abcdef"))
        for(url in listOf("http://127.0.0.1:18998/subscription/0123456789abcdef","http://127.0.0.1:18997/provider",
            "https://example.org/import/0123456789abcdef","http://127.0.0.1:18997/import/0123456789abcdef?refresh=true","invalid")) assertFalse(LocalTransfer.isInstallerSource(url))
    }
}
