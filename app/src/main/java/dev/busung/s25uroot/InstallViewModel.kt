package dev.busung.s25uroot

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class InstallPhase {
    Checking,
    Ready,
    Downloading,
    Exploiting,
    LoadingKernelSu,
    Installed,
    Failed,
}

data class InstallUiState(
    val phase: InstallPhase = InstallPhase.Checking,
    val message: String = "",
    val probeOutput: String = "",
    val log: String = "",
) {
    val busy: Boolean
        get() = phase in setOf(
            InstallPhase.Checking,
            InstallPhase.Downloading,
            InstallPhase.Exploiting,
            InstallPhase.LoadingKernelSu,
        )

}

data class TargetCatalogUiState(
    val loading: Boolean = false,
    val profiles: List<TargetProfile> = emptyList(),
    val error: String? = null,
)

private data class CommandResult(val code: Int, val output: String)

/**
 * Payloads are truncated to a fixed release size, so a rebuild of a target --
 * or a different target padded to the same size -- has exactly the length of
 * whatever is already staged, and would keep running in its place.
 */
internal fun stagedFileIsCurrent(staged: File, source: File): Boolean {
    if (!staged.exists()) return false
    val stagedDigest = sha256OrNull(staged) ?: return false
    return stagedDigest == sha256OrNull(source)
}

private fun sha256OrNull(file: File): String? = runCatching {
    file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}.getOrNull()

class InstallViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = PayloadRepository(application)
    private val historyStore = InstallHistoryStore(application)
    private val mutableState = MutableStateFlow(InstallUiState())
    private val mutableHistory = MutableStateFlow(historyStore.closeInterruptedRuns())
    private val mutableTargetCatalog = MutableStateFlow(TargetCatalogUiState())
    private var discoveryJob: Job? = null
    private var installJob: Job? = null
    private var activeHistoryEntry: InstallHistoryEntry? = null

    @Volatile
    private var activeRunShizuku: Boolean? = null
    val state: StateFlow<InstallUiState> = mutableState.asStateFlow()
    val history: StateFlow<List<InstallHistoryEntry>> = mutableHistory.asStateFlow()
    val targetCatalog: StateFlow<TargetCatalogUiState> = mutableTargetCatalog.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (installJob?.isActive == true) return
        mutableHistory.value = historyStore.load()
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = NativeProbe.run()
            if (detectInstalled()) {
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Installed,
                    message = app.getString(R.string.status_ksu_active),
                    probeOutput = probe,
                    log = probe,
                )
                return@launch
            }
            try {
                val profile = repository.resolveTarget(DeviceSnapshot.current())
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Ready,
                    message = app.getString(R.string.status_not_installed),
                    probeOutput = probe,
                    log = "$probe\n${app.getString(R.string.log_profile, profile.profileId)}",
                )
            } catch (error: Throwable) {
                mutableState.value = InstallUiState(
                    phase = InstallPhase.Failed,
                    message = app.getString(R.string.status_support_failed),
                    probeOutput = probe,
                    log = "$probe\n[-] ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun deleteHistoryEntries(ids: Collection<String>) {
        val runningId = activeHistoryEntry?.id
        val toDelete = ids.filterNot { it == runningId }
        if (toDelete.isEmpty()) return
        toDelete.forEach(historyStore::delete)
        mutableHistory.value = mutableHistory.value.filterNot { it.id in toDelete }
    }

    fun loadTargetCatalog() {
        if (mutableTargetCatalog.value.loading) return
        viewModelScope.launch(Dispatchers.IO) {
            mutableTargetCatalog.value = TargetCatalogUiState(loading = true)
            mutableTargetCatalog.value = try {
                TargetCatalogUiState(
                    profiles = repository.loadTargets().sortedWith(
                        compareBy(
                            TargetProfile::displayName,
                            TargetProfile::profileId,
                        ),
                    ),
                )
            } catch (error: Throwable) {
                TargetCatalogUiState(error = error.message ?: error.javaClass.simpleName)
            }
        }
    }

    fun install(profileId: String? = null) {
        if (installJob?.isActive == true || mutableState.value.phase == InstallPhase.Installed) return
        discoveryJob?.cancel()
        installJob = viewModelScope.launch(Dispatchers.IO) {
            mutableState.value = InstallUiState(
                phase = InstallPhase.Checking,
                probeOutput = mutableState.value.probeOutput,
            )
            startHistory()
            // Freeze the transport for the whole run so a mid-run preference
            // change cannot mix Shizuku and standalone execution between the
            // exploit and the KernelSU staging steps.
            activeRunShizuku = AppPreferences.shizukuMode(app)
            try {
                if (shizukuEnabled()) {
                    appendLog(app.getString(R.string.log_shizuku_prepare))
                    if (!ShizukuController.isRunning() && !ShizukuController.pingUntilRunning()) {
                        error(app.getString(R.string.error_shizuku_unavailable))
                    }
                    if (!ShizukuController.isGranted() && !ShizukuController.requestPermission()) {
                        error(app.getString(R.string.error_shizuku_permission))
                    }
                    appendLog(app.getString(R.string.log_shizuku_permission))
                }
                setPhase(InstallPhase.Checking, app.getString(R.string.status_checking_github))
                val profile = if (profileId == null) {
                    repository.resolveTarget(DeviceSnapshot.current())
                } else {
                    repository.resolveTarget(profileId)
                }
                appendLog(app.getString(R.string.log_profile, profile.profileId))
                updateHistoryProfile(profile.profileId)

                setPhase(InstallPhase.Downloading, app.getString(R.string.status_downloading_payload))
                val payloads = repository.download(profile) { appendLog("[*] $it") }
                appendLog(app.getString(R.string.log_download_verified))

                setPhase(InstallPhase.Exploiting, app.getString(R.string.status_exploit_running))
                executeExploit(payloads.exploit, payloads.profile.exploitAttempts.toString(),payloads.profile.pselectDelayUsec.toString())

                setPhase(InstallPhase.LoadingKernelSu, app.getString(R.string.status_ksu_loading))
                installKernelSu(payloads)
installKsuManagerIfNeeded()
                setPhase(InstallPhase.Installed, app.getString(R.string.status_ksu_active))
                appendLog(app.getString(R.string.log_install_complete))
                finishHistory(InstallRunResult.Succeeded)
            } catch (error: Throwable) {
                appendLog("[-] ${error.message ?: error.javaClass.simpleName}")
                setPhase(InstallPhase.Failed, app.getString(R.string.status_install_failed))
                finishHistory(InstallRunResult.Failed)
            } finally {
                activeRunShizuku = null
            }
        }
    }

    private suspend fun executeExploit(payload: File, attempts: String = EXPLOIT_ATTEMPTS, pselectDelay: String = "20000") {
        val shizuku = shizukuEnabled()
        val logFile = if (shizuku) File(SHIZUKU_LOG_PATH) else File(app.filesDir, "exploit.log")
        if (shizuku) {
            ShizukuController.exec(arrayOf("rm", "-f", SHIZUKU_LOG_PATH)).waitFor()
    // 清除上次 panic/重启留下的零字节尸体，防 bad ELF magic
    ShizukuController.exec(arrayOf("/system/bin/sh", "-c",
        "rm -f $SHIZUKU_PAYLOAD_PATH $SHIZUKU_HELPER_PATH $SHIZUKU_KSUD_PATH $GRKU_KSUD_PATH $SHIZUKU_KSUD_STAGE_PATH"
    )).waitFor()
        } else {
            logFile.delete()
        }
        val helper = helperFile()
        if (!shizuku) {
            require(helper.canExecute()) { app.getString(R.string.error_helper_unavailable) }
        }
        val logPrefix = mutableState.value.log
        val bootToken = currentBootToken()
        // ★ 新增:独立预热步骤,注意【没有】第二个 env 参数 → 干净环境,只热缓存不触发 payload
        if (shizuku) {
            appendLog("[*] warmup 400x /system/bin/true")
            ShizukuController.exec(
                arrayOf("/system/bin/sh", "-c",
                    "i=0; while [ \$i -lt 400 ]; do /system/bin/true; i=\$((\$i+1)); done")
            ).waitFor()
        }
        val process = if (shizuku) {
            val stagedPayload = shizukuStage(payload, SHIZUKU_PAYLOAD_PATH, "755")
            val stagedHelper = shizukuStage(helper, SHIZUKU_HELPER_PATH, "755")
            ShizukuController.exec(
                arrayOf("/system/bin/true"),
                shizukuEnvironment(bootToken, stagedPayload.absolutePath, stagedHelper.absolutePath, attempts, pselectDelay),
            )
        } else {
            val processBuilder = ProcessBuilder(
                helper.absolutePath,
                "--run-payload",
                payload.absolutePath,
                helper.absolutePath,
                logFile.absolutePath,
            ).redirectErrorStream(true)
            processBuilder.environment().apply {
                // 传递 attempts 和 pselectDelay 
                put("EXPLOIT_ATTEMPTS", attempts)
                put("PSELECT_DELAY_USEC", pselectDelay)
                put("P0_ATTEMPT_TIMEOUT_SEC", P0_ATTEMPT_TIMEOUT_SEC)
                put("EXPLOIT_ATTEMPT_TIMEOUT_SEC", EXPLOIT_ATTEMPT_TIMEOUT_SEC)
                cachedP0Offset(bootToken)?.let { put(P0_OFFSET_ENV, it) }
            }
            processBuilder.start()
        }
        val captured = StringBuilder()
        val readLog: () -> String = if (shizuku) {
            { drainProcessOutput(process, captured) }
        } else {
            // Keep draining stdout while polling: if the helper fills the OS
            // pipe buffer it blocks on write and stops making log progress,
            // which would trip the stall detector spuriously.
            { drainProcessOutput(process, captured); logFile.readTextIfPresent() }
        }

        try {
            val startedAt = SystemClock.elapsedRealtime()
            var lastProgressAt = startedAt
            var lastRawLog = ""
            while (process.isAlive) {
                val rawLog = readLog()
                if (rawLog != lastRawLog) {
                    cacheP0Offset(bootToken, rawLog)
                    publishExploitLog(logPrefix, rawLog)
                    lastRawLog = rawLog
                    lastProgressAt = SystemClock.elapsedRealtime()
                }
                val now = SystemClock.elapsedRealtime()
                require(now - lastProgressAt < EXPLOIT_STALL_MILLIS) {
                    app.getString(R.string.error_exploit_stalled)
                }
                require(now - startedAt < EXPLOIT_TOTAL_MILLIS) {
                    app.getString(R.string.error_exploit_timeout)
                }
                delay(if (shizuku) SHIZUKU_LOG_POLL_INTERVAL else LOG_POLL_INTERVAL)
            }

            val exitCode = process.waitFor()
            val rawLog = readLog()
            cacheP0Offset(bootToken, rawLog)
            publishExploitLog(logPrefix, rawLog)
            // Both transports drain into `captured` during the poll loop, so
            // this never blocks on a child still holding the pipe open.
            val earlyOutput = captured.toString().trim()
            require(exitCode == 0) {
                app.getString(
                    R.string.error_payload_exit,
                    exitCode,
                    earlyOutput.takeIf(String::isNotBlank)?.let { " ($it)" } ?: "",
                )
            }
            //require(rawLog.contains("exploit completed") && rawLog.contains("done=1 root=1")) {
            require(rawLog.contains("exploit completed") || rawLog.contains("temporary-root-ready")) {
                app.getString(R.string.error_success_marker)
            }
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
        appendLog(app.getString(R.string.log_bootstrap_root))
    }

    private fun drainProcessOutput(process: Process, buffer: StringBuilder): String {
        return try {
            drainStream(process.inputStream, buffer)
            drainStream(process.errorStream, buffer)
            buffer.toString()
        } catch (_: Throwable) {
            buffer.toString()
        }
    }

    private fun drainStream(stream: InputStream, buffer: StringBuilder) {
        val data = ByteArray(4096)
        while (stream.available() > 0) {
            val count = stream.read(data)
            if (count <= 0) break
            buffer.append(String(data, 0, count, Charsets.UTF_8))
        }
    }

    private fun publishExploitLog(prefix: String, rawLog: String) {
        mutableState.value = mutableState.value.copy(
            log = listOf(prefix, stripAnsi(rawLog))
                .filter(String::isNotBlank)
                .joinToString("\n"),
        )
        updateHistoryLog()
    }

    private suspend fun installKernelSu(payloads: VerifiedPayloads) {
        if (shizukuEnabled()) {
            shizukuStage(payloads.kernelSu, SHIZUKU_KSUD_PATH, "755")
            shizukuStage(payloads.kernelSu, SHIZUKU_KSUD_STAGE_PATH, "755")
            shizukuStage(payloads.kernelSu, GRKU_KSUD_PATH, "755")      // ★ 新增
            appendLog(app.getString(R.string.log_ksu_staged))
        } else {
            val source = shellQuote(payloads.kernelSu.absolutePath)
            val stageCommand =
                "/system/bin/cp $source $SHIZUKU_KSUD_PATH && " +
                    "/system/bin/cp $source $SHIZUKU_KSUD_STAGE_PATH && " +
                    "/system/bin/cp $source $GRKU_KSUD_PATH && " +
                    "/system/bin/chmod 755 $SHIZUKU_KSUD_PATH $SHIZUKU_KSUD_STAGE_PATH $GRKU_KSUD_PATH"
            val stage = runHelper("-c", stageCommand)
            require(stage.code == 0) { app.getString(R.string.error_ksu_stage, stage.output) }
            appendLog(app.getString(R.string.log_ksu_staged))
        }

        val lateLoad = runHelper("--late-load")
        require(lateLoad.code == 0) {
            app.getString(R.string.error_ksu_verify, lateLoad.code, lateLoad.output)
        }
        if (lateLoad.output.isNotBlank()) appendLog(lateLoad.output)
        storeInstallReceipt()
        appendLog(app.getString(R.string.log_ksu_control_verified))
    }

    private fun detectInstalled(): Boolean {
        if (NativeProbe.isKernelSuActive()) return true
        val bootToken = currentBootToken() ?: return false
        val receipt = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
        return receipt.getString(RECEIPT_BOOT_TOKEN, null) == bootToken &&
            receipt.getBoolean(RECEIPT_VERIFIED, false)
    }

    private fun storeInstallReceipt() {
        val bootToken = currentBootToken() ?: error(app.getString(R.string.error_boot_id))
        val stored = app.getSharedPreferences(INSTALL_RECEIPT, Application.MODE_PRIVATE)
            .edit()
            .putString(RECEIPT_BOOT_TOKEN, bootToken)
            .putBoolean(RECEIPT_VERIFIED, true)
            .commit()
        require(stored) { app.getString(R.string.error_receipt) }
    }

    private fun currentBootToken(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id")
            .readText(Charsets.US_ASCII)
            .trim()
            .takeIf(String::isNotBlank)
    }.getOrNull()

    private fun cachedP0Offset(bootToken: String?): String? {
        if (bootToken == null) return null
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) != bootToken) return null
        return stored.getString(P0_CACHE_OFFSET, null)
    }

    private fun cacheP0Offset(bootToken: String?, log: String) {
        if (bootToken == null) return
        val match = P0_OFFSET_PATTERN.findAll(log).lastOrNull() ?: return
        val offset = match.groupValues[1].toLongOrNull(16) ?: return
        if (offset !in 0..P0_OFFSET_MAX || offset and P0_OFFSET_MASK != 0L) return
        val value = "0x${offset.toString(16)}"
        val stored = app.getSharedPreferences(P0_CACHE, Application.MODE_PRIVATE)
        if (stored.getString(P0_CACHE_BOOT_TOKEN, null) == bootToken &&
            stored.getString(P0_CACHE_OFFSET, null) == value
        ) return
        stored.edit()
            .putString(P0_CACHE_BOOT_TOKEN, bootToken)
            .putString(P0_CACHE_OFFSET, value)
            .apply()
    }

    private fun helperFile(): File =
        if (shizukuEnabled()) {
            shizukuStage(nativeHelperFile(), SHIZUKU_HELPER_PATH, "755")
        } else {
            nativeHelperFile()
        }

    private fun nativeHelperFile() = File(app.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private fun shizukuEnabled(): Boolean = activeRunShizuku ?: AppPreferences.shizukuMode(app)

    private fun shizukuStage(source: File, target: String, mode: String): File {
        val staged = File(target)
        if (stagedFileIsCurrent(staged, source)) return staged
        try {
            ShizukuController.writeFile(target, mode, source.inputStream())
        } catch (error: Throwable) {
            throw IllegalStateException(
                app.getString(R.string.error_shizuku_stage, target, error.message.orEmpty()),
                error,
            )
        }
        return staged
    }

    private fun shizukuEnvironment(
        bootToken: String?,
        payloadPath: String,
        helperPath: String,
		attempts: String = EXPLOIT_ATTEMPTS,
		pselectDelay: String = "20000"
    ): Array<String> = buildList {
        add("EXPLOIT_ATTEMPTS=$attempts")
		add("PSELECT_DELAY_USEC=$pselectDelay") // 新增
        add("P0_ATTEMPT_TIMEOUT_SEC=$P0_ATTEMPT_TIMEOUT_SEC")
        add("EXPLOIT_ATTEMPT_TIMEOUT_SEC=$EXPLOIT_ATTEMPT_TIMEOUT_SEC")
        add("CVE43499_ROOT_HELPER=$helperPath")
        add("LD_PRELOAD=$payloadPath")
        cachedP0Offset(bootToken)?.let { add("$P0_OFFSET_ENV=$it") }
    }.toTypedArray()

    /**
     * Runs the bootstrap helper for a short management command. Unlike the
     * exploit run there is no log file to poll, so output is drained inline
     * and a hard deadline guards against a helper that never exits — without
     * this, a hung `--late-load` leaves the install stuck in LoadingKernelSu
     * indefinitely.
     */
    private suspend fun runHelper(vararg arguments: String): CommandResult {
        val helper = helperFile()
        val process = if (shizukuEnabled()) {
            ShizukuController.exec(arrayOf(helper.absolutePath) + arguments)
        } else {
            ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        }
        val captured = StringBuilder()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (process.isAlive) {
                drainProcessOutput(process, captured)
                require(SystemClock.elapsedRealtime() - startedAt < HELPER_TIMEOUT_MILLIS) {
                    app.getString(
                        R.string.error_helper_timeout,
                        captured.toString().trim().takeIf(String::isNotBlank)
                            ?.let { ": $it" } ?: "",
                    )
                }
                delay(HELPER_POLL_INTERVAL)
            }
            drainProcessOutput(process, captured)
            val exitCode = process.waitFor()
            return CommandResult(exitCode, stripAnsi(captured.toString().trim()))
        } finally {
            if (process.isAlive) {
                process.destroy()
                delay(500.milliseconds)
                if (process.isAlive) process.destroyForcibly()
            }
        }
    }

    private fun shellQuote(value: String) = "'${value.replace("'", "'\\''")}'"

    private fun setPhase(phase: InstallPhase, message: String) {
        mutableState.value = mutableState.value.copy(phase = phase, message = message)
        appendLog("[*] $message")
    }

    private fun appendLog(line: String) {
        val cleanLine = stripAnsi(line).trim()
        if (cleanLine.isBlank()) return
        mutableState.value = mutableState.value.copy(
            log = (mutableState.value.log + "\n" + cleanLine).trim(),
        )
        updateHistoryLog()
    }

    private fun startHistory() {
        val entry = historyStore.create()
        activeHistoryEntry = entry
        publishHistory(entry)
    }

    private fun updateHistory(transform: (InstallHistoryEntry) -> InstallHistoryEntry) {
        val entry = activeHistoryEntry ?: return
        val updated = transform(entry)
        activeHistoryEntry = updated
        historyStore.save(updated)
        publishHistory(updated)
    }

    private fun updateHistoryLog() =
        updateHistory { it.copy(log = mutableState.value.log) }

    private fun updateHistoryProfile(profileId: String) =
        updateHistory { it.copy(profileId = profileId) }

    private fun finishHistory(result: InstallRunResult) {
        updateHistory { entry ->
            entry.copy(
                completedAtMillis = System.currentTimeMillis(),
                result = result,
                log = mutableState.value.log,
            )
        }
        activeHistoryEntry = null
    }

    private fun publishHistory(entry: InstallHistoryEntry) {
        mutableHistory.value = (mutableHistory.value.filterNot { it.id == entry.id } + entry)
            .sortedByDescending(InstallHistoryEntry::startedAtMillis)
    }

    private fun File.readTextIfPresent(): String = if (exists()) readText() else ""

	// 1. 检测是否已安装 KSU Manager
private fun isKsuManagerInstalled(): Boolean {
    return try {
        // 注意：如果老白改了包名，把这里换成他的包名
        app.packageManager.getPackageInfo("me.weishu.kernelsu", 0)
        true
    } catch (e: Exception) {
        false
    }
}

// 2. 通过 Shizuku 静默安装 APK
private suspend fun installKsuManagerIfNeeded() {
    if (isKsuManagerInstalled()) {
        appendLog("[*] KernelSU Manager already installed, skipping.")
        return
    }

    appendLog("[*] Installing KernelSU Manager 3.2.5 via Shizuku...")
    try {
        val tmpApkPath = "/data/local/tmp/ksu-manager.apk"
        
        // Step A: 用 Shizuku 起一个 shell，通过 stdin 把 assets 里的 apk 写到 /data/local/tmp
        // (因为 shell 用户读不到 App 的沙箱目录，必须用管道写)
        val writeCmd = arrayOf("/system/bin/sh", "-c", "cat > $tmpApkPath")
        val writeProcess = ShizukuController.exec(writeCmd)
        
        app.assets.open("payload/KernelSU_Manager_v3.2.5_32525.apk").use { input ->
            writeProcess.outputStream.use { output ->
                input.copyTo(output)
            }
        }
        val writeExit = writeProcess.waitFor()
        if (writeExit != 0) throw RuntimeException("Failed to write APK to tmp")

        // Step B: 执行 pm install 静默安装
        val installCmd = arrayOf("/system/bin/sh", "-c", "pm install -r $tmpApkPath")
        val installProcess = ShizukuController.exec(installCmd)
        val installOutput = installProcess.inputStream.bufferedReader().readText()
        val installExit = installProcess.waitFor()
        
        // Step C: 清理临时文件
        ShizukuController.exec(arrayOf("/system/bin/sh", "-c", "rm -f $tmpApkPath")).waitFor()

        if (installExit == 0 || installOutput.contains("Success")) {
            appendLog("[+] KernelSU Manager installed successfully!")
        } else {
            appendLog("[-] pm install failed: $installOutput")
        }
    } catch (e: Exception) {
        appendLog("[-] Failed to install KernelSU Manager: ${e.message}")
    }
}
	
    companion object {
        private const val EXPLOIT_ATTEMPTS = "6"
        private const val P0_ATTEMPT_TIMEOUT_SEC = "45"
        private const val EXPLOIT_ATTEMPT_TIMEOUT_SEC = "120"
        private const val EXPLOIT_STALL_MILLIS = 210_000L
        private const val EXPLOIT_TOTAL_MILLIS = 900_000L
        private const val HELPER_TIMEOUT_MILLIS = 120_000L
        private const val INSTALL_RECEIPT = "install_receipt"
        private const val RECEIPT_BOOT_TOKEN = "kernel_boot_id"
        private const val RECEIPT_VERIFIED = "verified"
        private const val P0_CACHE = "p0_cache"
        private const val P0_CACHE_BOOT_TOKEN = "kernel_boot_id"
        private const val P0_CACHE_OFFSET = "offset"
        private const val P0_OFFSET_ENV = "SLIDE_P0_OFFSET"
        private const val P0_OFFSET_MAX = 0x1f0000L
        private const val P0_OFFSET_MASK = 0xffffL
        private const val SHIZUKU_LOG_PATH = "/data/local/tmp/ksu-exploit.log"
        private const val SHIZUKU_HELPER_PATH = "/data/local/tmp/ksu-helper"
        private const val SHIZUKU_PAYLOAD_PATH = "/data/local/tmp/ksu-payload"
        private const val SHIZUKU_KSUD_PATH = "/data/local/tmp/ksud-s25u-kdp"
        private const val GRKU_KSUD_PATH = "/data/local/tmp/ksud-selected"   // ★ grku helper 硬编码找这个
        private const val SHIZUKU_KSUD_STAGE_PATH = "/data/local/tmp/.ksud-stage"
        private val LOG_POLL_INTERVAL = 250.milliseconds
        private val HELPER_POLL_INTERVAL = 250.milliseconds
        private val SHIZUKU_LOG_POLL_INTERVAL = 1.seconds
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
        private val P0_OFFSET_PATTERN = Regex(
            "slide-kaslr-ok[^\\n]*slide=([0-9a-fA-F]{16})",
        )

        private fun stripAnsi(value: String): String = ANSI_ESCAPE.replace(value, "").replace("\r", "")
    }
}
