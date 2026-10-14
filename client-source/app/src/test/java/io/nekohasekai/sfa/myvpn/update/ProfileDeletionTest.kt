package io.nekohasekai.sfa.myvpn.importer
import java.nio.file.Files
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProfileDeletionTest {
    @Test fun configOwnershipNeverIncludesExternalOrTraversalPaths() {
        val root=Files.createTempDirectory("myvpn-cleanup-plan-").toFile()
        try {
            val configs=File(root,"configs").apply { mkdirs() }
            assertEquals("import-fixture.json",ProfileDeletion.ownedConfigName(root,File(configs,"import-fixture.json").path))
            assertNull(ProfileDeletion.ownedConfigName(root,File(root,"external.json").path))
            assertNull(ProfileDeletion.ownedConfigName(root,File(configs,"../external.json").path))
            assertNull(ProfileDeletion.ownedConfigName(root,File(configs,"owner.p12").path))
        } finally { File(root,"configs").delete();root.delete() }
    }
}
