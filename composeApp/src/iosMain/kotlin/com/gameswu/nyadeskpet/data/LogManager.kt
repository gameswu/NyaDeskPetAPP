package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import com.gameswu.nyadeskpet.currentTimeMillis
import com.gameswu.nyadeskpet.getAppVersion
import platform.Foundation.*

actual class LogManager actual constructor(
    private val context: PlatformContext,
    private val settingsRepository: SettingsRepository,
) {
    private val sessionTimestamp = currentTimeMillis()

    actual val currentSessionFileName: String = "app-$sessionTimestamp.log"

    actual val logDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        val docDir = (paths.firstOrNull() as? String) ?: NSHomeDirectory()
        val dir = "$docDir/logs"
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(dir)) {
            fm.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        dir
    }

    private val logFilePath: String by lazy { "$logDir/$currentSessionFileName" }

    private val isoFormatter: NSDateFormatter by lazy {
        NSDateFormatter().apply {
            dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            timeZone = NSTimeZone.timeZoneWithName("UTC")
            locale = NSLocale(localeIdentifier = "en_US_POSIX")
        }
    }

    // ==================== 公共 API ====================

    actual fun initialize() {
        val settings = settingsRepository.current
        if (!settings.logEnabled) return

        try {
            val fm = NSFileManager.defaultManager
            if (!fm.fileExistsAtPath(logDir)) {
                fm.createDirectoryAtPath(logDir, withIntermediateDirectories = true, attributes = null, error = null)
            }

            val header = buildString {
                appendLine("================================================================================")
                appendLine("Session started at: ${isoFormatter.stringFromDate(NSDate())}")
                appendLine("Platform: iOS")
                appendLine("App version: ${getAppVersion()}")
                appendLine("================================================================================")
            }
            appendToFile(header)

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

        val timestamp = isoFormatter.stringFromDate(NSDate())
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
            appendToFile(formatted)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual fun getLogFiles(): List<LogFileInfo> {
        val fm = NSFileManager.defaultManager
        val contents = fm.contentsOfDirectoryAtPath(logDir, error = null) ?: return emptyList()

        return (0 until contents.count.toInt()).mapNotNull { i ->
            val name = contents.objectAtIndex(i.toULong()) as? String ?: return@mapNotNull null
            if (!name.endsWith(".log")) return@mapNotNull null
            val path = "$logDir/$name"
            val attrs = fm.attributesOfItemAtPath(path, error = null) ?: return@mapNotNull null
            val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
            val modDate = attrs[NSFileModificationDate] as? NSDate
            val modMs = modDate?.let { (it.timeIntervalSince1970 * 1000).toLong() } ?: 0L

            LogFileInfo(
                name = name,
                path = path,
                sizeBytes = size,
                lastModifiedMs = modMs,
                isCurrentSession = name == currentSessionFileName,
            )
        }.sortedByDescending { it.lastModifiedMs }
    }

    actual fun deleteLogFile(fileName: String): Boolean {
        if (fileName == currentSessionFileName) return false
        return try {
            NSFileManager.defaultManager.removeItemAtPath("$logDir/$fileName", error = null)
        } catch (_: Exception) {
            false
        }
    }

    actual fun deleteAllLogFiles(): Int {
        val fm = NSFileManager.defaultManager
        val contents = fm.contentsOfDirectoryAtPath(logDir, error = null) ?: return 0
        var count = 0
        for (i in 0 until contents.count.toInt()) {
            val name = contents.objectAtIndex(i.toULong()) as? String ?: continue
            if (name.endsWith(".log") && name != currentSessionFileName) {
                if (fm.removeItemAtPath("$logDir/$name", error = null)) count++
            }
        }
        return count
    }

    actual fun getLogDirSize(): Long {
        val fm = NSFileManager.defaultManager
        val contents = fm.contentsOfDirectoryAtPath(logDir, error = null) ?: return 0
        var total = 0L
        for (i in 0 until contents.count.toInt()) {
            val name = contents.objectAtIndex(i.toULong()) as? String ?: continue
            val path = "$logDir/$name"
            val attrs = fm.attributesOfItemAtPath(path, error = null) ?: continue
            val size = (attrs[NSFileSize] as? NSNumber)?.longValue ?: 0L
            total += size
        }
        return total
    }

    actual fun openLogDirectory(): Boolean {
        // iOS 无法直接打开文件夹，返回 false
        return false
    }

    actual fun shutdown() {
        try {
            val footer = buildString {
                appendLine("================================================================================")
                appendLine("Session ended at: ${isoFormatter.stringFromDate(NSDate())}")
                appendLine("================================================================================")
            }
            appendToFile(footer)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== 内部方法 ====================

    private fun appendToFile(text: String) {
        val data = (text as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(logFilePath)) {
            fm.createFileAtPath(logFilePath, contents = data, attributes = null)
        } else {
            val handle = NSFileHandle.fileHandleForWritingAtPath(logFilePath) ?: return
            handle.seekToEndOfFile()
            handle.writeData(data)
            handle.closeFile()
        }
    }

    private fun cleanupOldLogs(retentionDays: Int) {
        try {
            val fm = NSFileManager.defaultManager
            val contents = fm.contentsOfDirectoryAtPath(logDir, error = null) ?: return
            val cutoffMs = currentTimeMillis() - retentionDays * 24L * 60 * 60 * 1000

            for (i in 0 until contents.count.toInt()) {
                val name = contents.objectAtIndex(i.toULong()) as? String ?: continue
                if (!name.endsWith(".log") || name == currentSessionFileName) continue
                val path = "$logDir/$name"
                val attrs = fm.attributesOfItemAtPath(path, error = null) ?: continue
                val modDate = attrs[NSFileModificationDate] as? NSDate ?: continue
                val modMs = (modDate.timeIntervalSince1970 * 1000).toLong()
                if (modMs < cutoffMs) {
                    fm.removeItemAtPath(path, error = null)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
