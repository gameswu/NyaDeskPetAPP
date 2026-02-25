package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.currentTimeMillis
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

actual class LogManager actual constructor(
    private val context: PlatformContext,
    private val settingsRepository: SettingsRepository,
) {
    private val sessionTimestamp = currentTimeMillis()

    actual val currentSessionFileName: String = "app-$sessionTimestamp.log"

    actual val logDir: String by lazy {
        val dir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "logs"
        )
        dir.mkdirs()
        dir.absolutePath
    }

    private val logFile: File by lazy { File(logDir, currentSessionFileName) }

    private var writer: OutputStreamWriter? = null

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    // ==================== 公共 API ====================

    actual fun initialize() {
        val settings = settingsRepository.current
        if (!settings.logEnabled) return

        try {
            File(logDir).mkdirs()
            writer = OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8)

            // 写入会话头
            val header = buildString {
                appendLine("================================================================================")
                appendLine("Session started at: ${isoFormat.format(Date())}")
                appendLine("Platform: Android")
                appendLine("App version: ${com.gameswu.nyadeskpet.getAppVersion()}")
                appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("================================================================================")
            }
            writer?.write(header)
            writer?.flush()

            // 清理过期日志
            cleanupOldLogs(settings.logRetentionDays)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual fun log(level: LogLevel, message: String, source: String, data: String?) {
        val settings = settingsRepository.current
        if (!settings.logEnabled) return
        if (!settings.logLevels.contains(level.key)) return

        val timestamp = isoFormat.format(Date())
        val levelStr = level.label.padEnd(8)
        val formatted = buildString {
            append("[$timestamp] [$levelStr] [$source] $message")
            if (data != null) {
                appendLine()
                data.lines().forEach { line ->
                    appendLine("  $line")
                }
            }
            appendLine()
        }

        try {
            synchronized(this) {
                if (writer == null && settings.logEnabled) {
                    // 延迟初始化：如果 initialize() 未被调用但日志已启用
                    File(logDir).mkdirs()
                    writer = OutputStreamWriter(FileOutputStream(logFile, true), Charsets.UTF_8)
                }
                writer?.write(formatted)
                writer?.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual fun getLogFiles(): List<LogFileInfo> {
        val dir = File(logDir)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                LogFileInfo(
                    name = file.name,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    lastModifiedMs = file.lastModified(),
                    isCurrentSession = file.name == currentSessionFileName,
                )
            } ?: emptyList()
    }

    actual fun deleteLogFile(fileName: String): Boolean {
        if (fileName == currentSessionFileName) return false
        return try {
            File(logDir, fileName).delete()
        } catch (_: Exception) {
            false
        }
    }

    actual fun deleteAllLogFiles(): Int {
        val dir = File(logDir)
        if (!dir.exists()) return 0
        var count = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".log") && file.name != currentSessionFileName) {
                if (file.delete()) count++
            }
        }
        return count
    }

    actual fun getLogDirSize(): Long {
        val dir = File(logDir)
        if (!dir.exists()) return 0
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    actual fun openLogDirectory(): Boolean {
        return try {
            val logDirFile = File(logDir)
            logDirFile.mkdirs()
            val uri = android.net.Uri.parse("content://${context.packageName}.fileprovider${logDirFile.absolutePath}")
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // 回退：打开系统文件管理器
                val fallbackIntent = android.content.Intent("android.provider.action.BROWSE").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {
                    // 最终回退：用 ACTION_GET_CONTENT
                    val pickIntent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(pickIntent)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    actual fun shutdown() {
        try {
            synchronized(this) {
                val footer = buildString {
                    appendLine("================================================================================")
                    appendLine("Session ended at: ${isoFormat.format(Date())}")
                    appendLine("================================================================================")
                }
                writer?.write(footer)
                writer?.flush()
                writer?.close()
                writer = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 内部方法 ====================

    private fun cleanupOldLogs(retentionDays: Int) {
        try {
            val dir = File(logDir)
            if (!dir.exists()) return
            val cutoffMs = currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000
            dir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".log")
                    && file.name != currentSessionFileName
                    && file.lastModified() < cutoffMs
                ) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
