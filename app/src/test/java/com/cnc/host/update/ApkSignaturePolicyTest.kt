package com.cnc.host.update

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APK 签名策略测试（ADR-0005，R4 修复）。
 *
 * 铁律：SHA-256 只防传输损坏；签名不匹配/无签名的 APK 一律拒绝安装。
 * 白名单未配置（正式密钥未分发）时回退「与当前应用同签名」。
 */
class ApkSignaturePolicyTest {

    private val releaseCert = "aa".repeat(32)
    private val debugCert = "bb".repeat(32)
    private val evilCert = "cc".repeat(32)

    // ---------- 白名单已配置（正式密钥已分发） ----------

    @Test
    fun `白名单内指纹允许安装`() {
        val err = ApkSignaturePolicy.verify(
            apkCerts = setOf(releaseCert),
            allowedReleaseCerts = setOf(releaseCert),
            currentAppCert = debugCert
        )
        assertNull(err)
    }

    @Test
    fun `白名单外指纹拒绝安装`() {
        val err = ApkSignaturePolicy.verify(
            apkCerts = setOf(evilCert),
            allowedReleaseCerts = setOf(releaseCert),
            currentAppCert = debugCert
        )
        assertNotNull(err)
        assertTrue(err!!.contains("发布证书不匹配"))
    }

    @Test
    fun `多签名 APK 任一命中白名单即通过`() {
        // APK 含多个当前签名者时，任一属于发布证书即视为可信
        val err = ApkSignaturePolicy.verify(
            apkCerts = setOf(evilCert, releaseCert),
            allowedReleaseCerts = setOf(releaseCert),
            currentAppCert = null
        )
        assertNull(err)
    }

    // ---------- 无签名 ----------

    @Test
    fun `无签名 APK 一律拒绝`() {
        assertNotNull(ApkSignaturePolicy.verify(emptySet(), setOf(releaseCert), debugCert))
        assertNotNull(ApkSignaturePolicy.verify(emptySet(), emptySet(), debugCert))
        assertNotNull(ApkSignaturePolicy.verify(emptySet(), emptySet(), null))
    }

    // ---------- 白名单未配置：回退同签名校验 ----------

    @Test
    fun `白名单为空时与当前应用同签名允许安装`() {
        val err = ApkSignaturePolicy.verify(
            apkCerts = setOf(debugCert),
            allowedReleaseCerts = emptySet(),
            currentAppCert = debugCert
        )
        assertNull(err)
    }

    @Test
    fun `白名单为空时与当前应用不同签名拒绝安装`() {
        val err = ApkSignaturePolicy.verify(
            apkCerts = setOf(evilCert),
            allowedReleaseCerts = emptySet(),
            currentAppCert = debugCert
        )
        assertNotNull(err)
        assertTrue(err!!.contains("当前应用不一致"))
    }

    @Test
    fun `白名单为空且当前应用指纹读取失败时拒绝安装`() {
        // 读不到本机签名 = 无法建立信任锚，宁可拒绝
        assertNotNull(ApkSignaturePolicy.verify(setOf(debugCert), emptySet(), null))
    }
}
