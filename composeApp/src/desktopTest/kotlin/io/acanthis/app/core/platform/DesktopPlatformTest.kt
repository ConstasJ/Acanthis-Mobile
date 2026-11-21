package io.acanthis.app.core.platform

import io.acanthis.app.core.database.buildAppDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopPlatformTest {
    @Test
    fun desktopPlatformDirectoryAndDatabaseBuilderWork() {
        val dataDir = desktopDataDirectory()
        assertTrue(dataDir.exists() && dataDir.isDirectory)

        val cacheDir = File(platformCacheDirectory())
        assertTrue(cacheDir.exists() && cacheDir.isDirectory)
        assertTrue(platformName().startsWith("Desktop"))
        assertTrue(defaultDeviceLabel().isNotBlank())

        val db = buildAppDatabase(createAppDatabaseBuilder())
        db.close()
    }
}
