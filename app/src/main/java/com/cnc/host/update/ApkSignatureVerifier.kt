package com.cnc.host.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * APK 更新签名校验（ADR-0005，规范 §11.2 强制）。
 *
 * SHA-256 只防传输损坏；签名校验防「服务端/传输被攻破后恶意 APK 直达平板」。
 * 密钥模型：发布私钥由超级管理员离线保管，公钥证书指纹白名单内置客户端。
 * 白名单未配置时回退校验「与当前已安装应用同签名」（Android 系统同样强制同签名升级，
 * 此处提前拦截并给出明确错误，避免恶意包进入系统安装器后才失败）。
 */

/** APK 签名证书指纹读取器（Android 依赖抽成接口以便单测）。 */
interface ApkSignatureReader {
    /** 读取 APK 文件全部签名证书的 SHA-256 指纹（小写 hex）。无有效签名返回空集。 */
    fun certSha256Of(apkFile: File): Set<String>

    /** 当前已安装应用的签名证书 SHA-256 指纹；读取失败返回 null。 */
    fun currentAppCertSha256(): String?
}

/** 签名策略决策（纯 JVM，可单测）。返回 null = 允许安装；否则为拒绝原因。 */
object ApkSignaturePolicy {

    fun verify(
        apkCerts: Set<String>,
        allowedReleaseCerts: Set<String>,
        currentAppCert: String?
    ): String? {
        if (apkCerts.isEmpty()) return "安装包无有效签名，已拒绝安装"
        if (allowedReleaseCerts.isNotEmpty()) {
            return if (apkCerts.any { it in allowedReleaseCerts }) null
            else "安装包签名与发布证书不匹配，已拒绝安装"
        }
        // 白名单未配置（正式密钥尚未分发）：回退为与当前应用同签名
        if (currentAppCert != null && apkCerts.contains(currentAppCert)) return null
        return "安装包签名与当前应用不一致，已拒绝安装"
    }
}

/** 基于 PackageManager 的 Android 实现。 */
class AndroidApkSignatureReader(private val context: Context) : ApkSignatureReader {

    override fun certSha256Of(apkFile: File): Set<String> {
        val info = packageArchiveInfo(apkFile.absolutePath) ?: return emptySet()
        return currentSigners(info).mapTo(LinkedHashSet()) { sha256Hex(it.toByteArray()) }
    }

    override fun currentAppCertSha256(): String? = runCatching {
        val info = packageInfo(context.packageName) ?: return null
        currentSigners(info).firstOrNull()?.let { sha256Hex(it.toByteArray()) }
    }.getOrNull()

    private fun packageArchiveInfo(path: String): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    }

    private fun packageInfo(packageName: String): PackageInfo? {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
    }

    /** 当前生效的签名者集合（不含签名轮换历史中的旧签名）。 */
    private fun currentSigners(info: PackageInfo): List<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList() ?: emptyList()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
