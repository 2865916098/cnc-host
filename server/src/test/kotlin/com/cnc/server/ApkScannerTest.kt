package com.cnc.server

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ApkScanner 选择逻辑测试：用临时目录 + 假 APK 文件 + 注入的版本解析器，
 * 不依赖真实 APK 构建产物。
 */
class ApkScannerTest {

    private fun fakeApk(dir: File, name: String, mtime: Long): File {
        val f = File(dir, name)
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) // 只是占位字节
        f.setLastModified(mtime)
        return f
    }

    private fun scannerOf(
        dir: File,
        versions: Map<String, AxmlVersionReader.VersionInfo?>
    ): ApkScanner = ApkScanner(
        versionReader = { f -> versions[f.name] },
        dirs = listOf(dir)
    )

    @Test
    fun `picks highest versionCode`() {
        val dir = createTempDir()
        fakeApk(dir, "app-v2.apk", mtime = 1000)
        fakeApk(dir, "app-v5.apk", mtime = 500) // 更旧但版本更高
        fakeApk(dir, "app-v3.apk", mtime = 2000)
        val s = scannerOf(
            dir,
            mapOf(
                "app-v2.apk" to AxmlVersionReader.VersionInfo(2, "1.1.0"),
                "app-v5.apk" to AxmlVersionReader.VersionInfo(5, "1.4.0"),
                "app-v3.apk" to AxmlVersionReader.VersionInfo(3, "1.2.0")
            )
        )
        val latest = s.latest()
        assertNotNull(latest)
        assertEquals(5, latest.versionCode)
        assertEquals("app-v5.apk", latest.fileName)
    }

    @Test
    fun `same versionCode picks newest mtime`() {
        val dir = createTempDir()
        fakeApk(dir, "old.apk", mtime = 1000)
        fakeApk(dir, "new.apk", mtime = 3000)
        val s = scannerOf(
            dir,
            mapOf(
                "old.apk" to AxmlVersionReader.VersionInfo(7, "2.0.0"),
                "new.apk" to AxmlVersionReader.VersionInfo(7, "2.0.0")
            )
        )
        assertEquals("new.apk", s.latest()?.fileName)
    }

    @Test
    fun `unparseable apks are skipped`() {
        val dir = createTempDir()
        fakeApk(dir, "broken.apk", mtime = 9000)
        fakeApk(dir, "good.apk", mtime = 1000)
        val s = scannerOf(
            dir,
            mapOf(
                "broken.apk" to null,
                "good.apk" to AxmlVersionReader.VersionInfo(1, "1.0.0")
            )
        )
        val latest = s.latest()
        assertNotNull(latest)
        assertEquals("good.apk", latest.fileName)
    }

    @Test
    fun `no apk returns null`() {
        val dir = createTempDir()
        val s = scannerOf(dir, emptyMap())
        assertNull(s.latest())
        assertTrue(s.scan().isEmpty())
    }

    @Test
    fun `non apk files ignored`() {
        val dir = createTempDir()
        File(dir, "notes.txt").writeText("not an apk")
        fakeApk(dir, "app.apk", mtime = 100)
        val s = scannerOf(dir, mapOf("app.apk" to AxmlVersionReader.VersionInfo(9, "3.0.0")))
        assertEquals(1, s.scan().size)
        assertEquals(9, s.latest()?.versionCode)
    }

    @Test
    fun `missing directory tolerated`() {
        val s = ApkScanner(
            versionReader = { AxmlVersionReader.VersionInfo(1, "1.0") },
            dirs = listOf(File("definitely-not-exists-${System.nanoTime()}"))
        )
        assertNull(s.latest())
    }
}
