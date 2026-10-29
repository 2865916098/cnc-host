package com.cnc.host.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.cnc.host.logging.LogRecorder
import com.cnc.host.setup.SetupPrefs
import com.cnc.host.sync.AppVersionDto
import com.cnc.host.sync.ServerApi
import com.cnc.host.sync.ServerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 客户端自动更新。
 *
 * 流程：心跳顺带调 [check] 拉取服务端发布的版本 → versionCode 大于本机版本时
 * 通过 [prompt] 通知 UI 弹出「发现新版本，是否更新」；用户确认后 [download]，
 * 完成后校验 SHA-256 并调起系统安装器。
 *
 * 安装期间 [installing] = true，KioskService 看门狗与 Home 键拉回逻辑会暂停，
 * 避免把安装界面顶掉。
 */
object AppUpdate {

    /** 待确认的新版本（null = 无更新或已确认/暂不）。 */
    private val _prompt = MutableStateFlow<AppVersionDto?>(null)
    val prompt: StateFlow<AppVersionDto?> = _prompt.asStateFlow()

    /** 下载进度 0..100；-1 表示未在下载。 */
    private val _progress = MutableStateFlow(-1)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    /** 正在调起系统安装器。 */
    @Volatile
    var installing: Boolean = false

    /** 本次运行内用户点过「暂不」的 versionCode，不再重复弹窗。 */
    @Volatile
    private var dismissedCode: Int = 0

    /**
     * 发布签名证书 SHA-256 指纹白名单（ADR-0005）。
     * 超级管理员分发正式签名密钥后，将证书指纹填入此列表并重新构建客户端；
     * 为空时回退校验「与当前已安装应用同签名」。
     */
    internal var allowedReleaseCertSha256: Set<String> = emptySet()

    /** 签名读取器工厂（单测可注入假实现）。 */
    internal var signatureReaderFactory: (Context) -> ApkSignatureReader = { AndroidApkSignatureReader(it) }

    private var lastCheckAt: Long = 0L
    private const val CHECK_INTERVAL_MS = 60_000L

    /** 本机版本（versionCode, versionName）。 */
    fun localVersion(context: Context): Pair<Int, String> = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
        code to (info.versionName ?: "")
    }.getOrDefault(0 to "")

    /**
     * 检查服务端是否有新版本（最多每 60 秒一次，心跳调用）。
     * 发现新版本且用户未点「暂不」时更新 [prompt]。
     */
    suspend fun check(context: Context, force: Boolean = false) {
        if (installing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        val settings = ServerPrefs.load(context)
        if (settings.baseUrl.isBlank()) return
        val remote = ServerApi(settings).appVersion().getOrNull() ?: return
        if (remote.versionCode <= 0) return
        val (localCode, _) = localVersion(context)
        if (remote.versionCode > localCode && remote.versionCode != dismissedCode) {
            _prompt.value = remote
        }
    }

    /** 用户点「暂不」。 */
    fun dismiss() {
        dismissedCode = _prompt.value?.versionCode ?: 0
        _prompt.value = null
    }

    /**
     * 下载并安装新版本。失败时恢复提示框并返回错误信息。
     */
    suspend fun downloadAndInstall(context: Context, version: AppVersionDto): String? {
        val settings = ServerPrefs.load(context)
        val target = File(context.cacheDir, "apk/cnc-host-update.apk")
        _prompt.value = null
        _progress.value = 0
        LogRecorder.info(context, "SYSTEM", "开始下载更新 ${version.versionName}(${version.versionCode})")
        val result = ServerApi(settings).downloadApk(target) { downloaded, total ->
            _progress.value = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        }
        if (result.isFailure) {
            _progress.value = -1
            _prompt.value = version
            val msg = "下载失败：${result.exceptionOrNull()?.message ?: "网络错误"}"
            LogRecorder.error(context, "SYSTEM", msg, details = result.exceptionOrNull()?.stackTraceToString())
            return msg
        }
        // 校验完整性
        if (version.sha256.isNotBlank()) {
            val actual = sha256(target)
            if (!actual.equals(version.sha256, ignoreCase = true)) {
                target.delete()
                _progress.value = -1
                _prompt.value = version
                LogRecorder.error(context, "SYSTEM", "安装包 SHA-256 校验失败：期望 ${version.sha256.take(16)}...，实际 ${actual.take(16)}...")
                return "安装包校验失败，请重试"
            }
        }
        // 签名校验（ADR-0005）：SHA-256 只防传输损坏，签名校验防恶意替换；失败删包拒绝安装
        val sigReader = signatureReaderFactory(context)
        val sigError = ApkSignaturePolicy.verify(
            apkCerts = sigReader.certSha256Of(target),
            allowedReleaseCerts = allowedReleaseCertSha256,
            currentAppCert = sigReader.currentAppCertSha256()
        )
        if (sigError != null) {
            target.delete()
            _progress.value = -1
            _prompt.value = version
            LogRecorder.error(context, "SYSTEM", "安装包签名校验失败：$sigError")
            return sigError
        }
        LogRecorder.info(context, "SYSTEM", "更新下载完成，开始安装 ${version.versionName}(${version.versionCode})")
        _progress.value = -1
        install(context, target)
        return null
    }

    private fun install(context: Context, apk: File) {
        installing = true
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /** 从安装器返回（用户取消或安装失败回到 App）时恢复看门狗。 */
    fun onInstallerReturned() {
        installing = false
    }

    private suspend fun sha256(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}
