package dev.busung.s25uroot

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class VerifiedPayloads(
    val profile: TargetProfile,
    val exploit: File,
    val kernelSu: File,
)

class PayloadRepository(private val context: Context) {
    
    fun loadTargets(): List<TargetProfile> {
        // 【魔改 1】：彻底移除 resolveMainCommit()，不再请求 GitHub API
        // 直接从本地 assets 读取 JSON 列表
        val manifestBytes = context.assets.open("targets-v3.json").readBytes()
        
        // 【魔改 2】：不再调用 pinArtifactUrl，直接使用 JSON 中配置的本地路径
        return SupportManifest.parse(manifestBytes).targets
    }

    fun resolveTarget(snapshot: DeviceSnapshot): TargetProfile = loadTargets()
        .firstOrNull { it.matches(snapshot) }
        ?: error(context.getString(R.string.repo_no_profile))

    fun resolveTarget(profileId: String): TargetProfile = loadTargets()
        .firstOrNull { it.profileId == profileId }
        ?: error(context.getString(R.string.repo_profile_missing, profileId))

    fun download(profile: TargetProfile, onProgress: (String) -> Unit): VerifiedPayloads {
        val directory = File(context.filesDir, "payloads/${profile.profileId}").apply { mkdirs() }
        val exploit = downloadArtifact(
            profile.exploit,
            //File(directory, "cve-2026-43499-app.so"),
            File(directory, profile.exploit.url.substringAfterLast('/')),   // payload/cve-2026-43499 → cve-2026-43499
            context.getString(R.string.artifact_exploit),
            onProgress,
        )
        val kernelSu = downloadArtifact(
            profile.kernelSu,
            //File(directory, "ksud-s25u-kdp"),
            File(directory, profile.kernelSu.url.substringAfterLast('/')),  // → ksud-dm3q-S9
            context.getString(R.string.artifact_kernelsu),
            onProgress,
        )
        // 赋予 0755 权限
        Os.chmod(exploit.absolutePath, 0b111101101) // 八进制 0755 的十进制表示是 493，这里用二进制 0b111101101 或 493
        Os.chmod(kernelSu.absolutePath, 0b111101101) 
        return VerifiedPayloads(profile, exploit, kernelSu)
    }

    private fun downloadArtifact(
        artifact: RemoteArtifact,
        destination: File,
        label: String,
        onProgress: (String) -> Unit,
    ): File {
        onProgress(context.getString(R.string.repo_downloading, label))
        val temporary = File(destination.parentFile, "${destination.name}.part")
        
        // 【核心魔改 3】：本地 Assets 拦截器
        // 如果 JSON 里的 url 不是以 http 开头，说明是我们放在 assets 里的本地文件
        if (!artifact.url.startsWith("http")) {
            context.assets.open(artifact.url).use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (destination.exists()) destination.delete()
            require(temporary.renameTo(destination)) {
                context.getString(R.string.repo_finalize_failed, label)
            }
            onProgress(context.getString(R.string.repo_verified, label))
            return destination
        }

        // 原有的网络下载逻辑（保留作为备用，以防 JSON 里写了 http 链接）
        val connection = open(artifact.url)
        require(connection.contentLengthLong == -1L || connection.contentLengthLong == artifact.size) {
            context.getString(R.string.repo_size_mismatch, label)
        }
        var total = 0L
        connection.inputStream.use { input ->
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= artifact.size) {
                        context.getString(R.string.repo_size_exceeded, label)
                    }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
        }
        connection.disconnect()
        require(total == artifact.size) { context.getString(R.string.repo_incomplete, label) }
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) {
            context.getString(R.string.repo_finalize_failed, label)
        }
        onProgress(context.getString(R.string.repo_verified, label))
        return destination
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "S25URoot/${BuildConfig.VERSION_NAME}")
            connect()
            require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode" }
        }

    companion object {
        private const val MAX_MANIFEST_BYTES = 256 * 1024
    }
}