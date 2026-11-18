package com.jarves.mh.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String,
)

class AppUpdater(
    private val context: Context,
    private val manifestUrlOverride: String = "",
) {
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        AppStrings.get(context, id, *args)

    /** Invalid or incomplete manifests fail closed; unsigned/unpinned APKs are never offered. */
    fun check(): AppUpdateInfo? {
        val manifestUrl = manifestUrlOverride.ifBlank { BuildConfig.APP_UPDATE_MANIFEST_URL }
        if (!manifestUrl.startsWith("https://", ignoreCase = true)) {
            throw IOException("The update manifest must use HTTPS.")
        }
        val connection = URL(manifestUrl).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 12_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "MobileHarness/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("Update manifest request failed (HTTP $code).")
            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("The update manifest redirected to a non-HTTPS URL.")
            }
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val manifestPackage = root.optString("packageName")
            if (manifestPackage.isNotBlank() && manifestPackage != context.packageName) return null
            val versionCode = root.optLong("versionCode", -1L)
            if (versionCode <= BuildConfig.VERSION_CODE) return null

            val artifact = root.optJSONObject("artifacts")?.optJSONObject(BuildConfig.APP_VARIANT)
                ?: root.optJSONObject(BuildConfig.APP_VARIANT)
                ?: root
            val apkUrl = artifact.optString("url").ifBlank { artifact.optString("apkUrl") }
            if (!apkUrl.startsWith("https://", ignoreCase = true)) {
                throw IOException("The release manifest contains an invalid APK URL.")
            }
            val checksum = artifact.optString("sha256").lowercase()
            if (!SHA256_PATTERN.matches(checksum)) {
                throw IOException("The release manifest contains an invalid SHA-256 checksum.")
            }
            val sizeBytes = artifact.optLong("sizeBytes", -1L)
            if (sizeBytes !in 1..MAX_APK_BYTES) {
                throw IOException("The release manifest contains an invalid APK size.")
            }

            AppUpdateInfo(
                versionCode = versionCode,
                versionName = root.optString("versionName").ifBlank { versionCode.toString() },
                apkUrl = apkUrl,
                sha256 = checksum,
                sizeBytes = sizeBytes,
                notes = root.optString("notes"),
            )
        } finally {
            connection.disconnect()
        }
    }

    /** Download, enforce exact size and digest, verify package/version/signer, then atomically stage. */
    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
        require(info.sha256.matches(SHA256_PATTERN)) { "The update checksum is missing or invalid." }
        require(info.sizeBytes in 1..MAX_APK_BYTES) { "The update size is invalid." }
        require(info.apkUrl.startsWith("https://", ignoreCase = true)) { "The APK URL must use HTTPS." }

        val directory = File(context.filesDir, "updates")
        check(directory.exists() || directory.mkdirs()) { str(R.string.rt_update_prepare_failed) }
        val partial = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk.part")
        val target = File(directory, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")
        val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
        var completed = false
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MobileHarness/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            check(code in 200..299) { str(R.string.rt_update_download_failed, code) }
            check(connection.url.protocol.equals("https", ignoreCase = true)) {
                "The APK download redirected to a non-HTTPS URL."
            }
            val responseLength = connection.contentLengthLong
            if (responseLength > 0L) {
                check(responseLength == info.sizeBytes) { "Downloaded APK size does not match the release manifest." }
            }

            partial.delete()
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= info.sizeBytes) { "Downloaded APK is larger than the release manifest." }
                        output.write(buffer, 0, count)
                        progress(downloaded, info.sizeBytes)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            check(partial.length() == info.sizeBytes) { "Downloaded APK is incomplete or has an unexpected size." }
            check(sha256(partial).equals(info.sha256, ignoreCase = true)) {
                str(R.string.rt_update_checksum_failed)
            }
            verifyApk(partial, info.versionCode)

            if (target.exists() && !target.delete()) error(str(R.string.rt_update_prepare_failed))
            check(partial.renameTo(target)) { str(R.string.rt_update_prepare_failed) }
            completed = true
            return target
        } finally {
            connection.disconnect()
            if (!completed && partial.exists()) partial.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(apk: File, expectedVersionCode: Long) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: error(str(R.string.rt_update_invalid_apk))
        check(archive.packageName == context.packageName) { str(R.string.rt_update_package_mismatch) }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        check(archiveVersion == expectedVersionCode && archiveVersion > BuildConfig.VERSION_CODE) {
            str(R.string.rt_update_version_mismatch)
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(signerDigests(archive).let { it.isNotEmpty() && it == signerDigests(installed) }) {
            str(R.string.rt_update_signature_mismatch)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
        } else {
            info.signatures
        }
        return signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
        const val MAX_APK_BYTES = 512L * 1024L * 1024L
    }
}
