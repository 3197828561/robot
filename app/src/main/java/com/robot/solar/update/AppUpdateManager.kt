package com.robot.solar.update

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.robot.solar.BuildConfig
import com.robot.solar.data.session.SessionManager
import com.robot.solar.network.http.ApiClient
import com.robot.solar.network.http.dto.AppReleaseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AppUpdateManager(private val activity: AppCompatActivity) {

    private val session by lazy { SessionManager.getInstance(activity) }
    private var checking = false
    private var pendingApk: File? = null

    fun check() {
        if (checking || session.accessToken.isNullOrBlank()) return
        checking = true
        activity.lifecycleScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    ApiClient.getService(session).getLatestAppRelease(BuildConfig.APP_UPDATE_CHANNEL)
                }
                val decision = AppUpdatePolicy.decide(BuildConfig.VERSION_CODE.toLong(), release)
                if (decision.available && shouldPrompt(release, decision.mandatory)) {
                    showPrompt(release, decision.mandatory)
                }
            } catch (_: Exception) {
                // Update checks must not block normal device operation when the service is unavailable.
            } finally {
                checking = false
            }
        }
    }

    fun resumePendingInstall() {
        val apk = pendingApk ?: return
        if (activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = null
            launchInstaller(apk)
        }
    }

    private fun shouldPrompt(release: AppReleaseDto, mandatory: Boolean): Boolean {
        if (mandatory) return true
        val prefs = activity.getSharedPreferences("app_update", AppCompatActivity.MODE_PRIVATE)
        return prefs.getLong("skipped_${release.channel}", -1L) != release.versionCode
    }

    private fun showPrompt(release: AppReleaseDto, mandatory: Boolean) {
        val notes = release.releaseNotes?.takeIf { it.isNotBlank() } ?: "包含新的功能与修复。"
        AlertDialog.Builder(activity)
            .setTitle("发现新版本 ${release.versionName}")
            .setMessage(notes)
            .setCancelable(!mandatory)
            .setPositiveButton("下载并安装") { _, _ -> download(release) }
            .apply {
                if (!mandatory) {
                    setNegativeButton("稍后") { _, _ ->
                        activity.getSharedPreferences("app_update", AppCompatActivity.MODE_PRIVATE)
                            .edit().putLong("skipped_${release.channel}", release.versionCode).apply()
                    }
                }
            }
            .show()
    }

    private fun download(release: AppReleaseDto) {
        Toast.makeText(activity, "正在下载更新…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) { downloadAndVerify(release) }
                requestInstall(apk)
            } catch (error: Exception) {
                Toast.makeText(activity, error.message ?: "更新下载失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun downloadAndVerify(release: AppReleaseDto): File {
        require(release.fileSizeBytes in 1..MAX_APK_BYTES) { "更新包大小异常" }
        val response = ApiClient.getService(session)
            .downloadAppRelease(release.channel, release.versionCode)
        val directory = File(activity.cacheDir, "app-updates").apply { mkdirs() }
        val temporary = File(directory, "${release.versionCode}.apk.part")
        val target = File(directory, "${release.versionCode}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        try {
            response.byteStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        require(bytes <= MAX_APK_BYTES) { "更新包超过大小限制" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(bytes == release.fileSizeBytes) { "更新包大小校验失败" }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(sha256.equals(release.sha256, ignoreCase = true)) { "更新包完整性校验失败" }
            verifyPackage(temporary, release)
            if (target.exists()) target.delete()
            require(temporary.renameTo(target)) { "无法保存更新包" }
            return target
        } finally {
            response.close()
            if (temporary.exists()) temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, release: AppReleaseDto) {
        val manager = activity.packageManager
        val archiveFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = manager.getPackageArchiveInfo(apk.absolutePath, archiveFlags)
            ?: error("更新包无法解析")
        require(archive.packageName == BuildConfig.APPLICATION_ID) { "更新包应用标识不匹配" }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        require(archiveVersionCode == release.versionCode) { "更新包版本号不匹配" }
        val installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, archiveFlags)
        require(signingDigests(archive) == signingDigests(installed)) { "更新包签名证书不匹配" }
    }

    @Suppress("DEPRECATION")
    private fun signingDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun requestInstall(apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            pendingApk = apk
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    companion object {
        private const val MAX_APK_BYTES = 256L * 1024L * 1024L
    }
}
