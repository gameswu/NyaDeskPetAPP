package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 日志等级 — 对齐原项目，多选模式（levels 数组）而非最小等级过滤
 */
enum class LogLevel(val label: String, val priority: Int) {
    DEBUG("DEBUG", 0),
    INFO("INFO", 1),
    WARN("WARN", 2),
    ERROR("ERROR", 3),
    CRITICAL("CRITICAL", 4);

    /** 设置中使用的 key（小写） */
    val key: String get() = name.lowercase()
}

/**
 * 日志文件信息
 */
data class LogFileInfo(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val isCurrentSession: Boolean,
)

/**
 * 日志管理器 — expect/actual 模式
 *
 * 对齐原项目设计:
 * - 每个会话一个日志文件: app-{timestamp}.log
 * - 纯文本格式: [timestamp] [LEVEL   ] message
 * - 支持日志等级多选过滤
 * - 支持按保留天数自动清理
 * - 文件存储在 {appDataDir}/logs/ 目录下
 */
expect class LogManager(context: PlatformContext, settingsRepository: SettingsRepository) {

    /** 日志目录路径 */
    val logDir: String

    /** 当前会话日志文件名 */
    val currentSessionFileName: String

    /**
     * 初始化日志系统 — 创建日志目录、写入会话头、清理过期日志
     */
    fun initialize()

    /**
     * 写入日志条目
     * @param level 日志等级
     * @param message 日志消息
     * @param source 日志来源（如 "Plugin:expression", "AgentService", "System"）
     * @param data 附加数据（可选）
     */
    fun log(level: LogLevel, message: String, source: String = "System", data: String? = null)

    /**
     * 获取当前日志文件列表
     */
    fun getLogFiles(): List<LogFileInfo>

    /**
     * 删除指定日志文件（不可删除当前会话文件）
     * @return 是否成功
     */
    fun deleteLogFile(fileName: String): Boolean

    /**
     * 删除所有日志文件（当前会话除外）
     * @return 删除的文件数
     */
    fun deleteAllLogFiles(): Int

    /**
     * 获取日志目录总大小（字节）
     */
    fun getLogDirSize(): Long

    /**
     * 打开日志文件夹（调用系统文件管理器）
     * @return 是否成功
     */
    fun openLogDirectory(): Boolean

    /**
     * 关闭日志系统，写入会话结束标记
     */
    fun shutdown()
}

// ==================== 便捷扩展 ====================

fun LogManager.info(message: String, source: String = "System") =
    log(LogLevel.INFO, message, source)

fun LogManager.warn(message: String, source: String = "System") =
    log(LogLevel.WARN, message, source)

fun LogManager.error(message: String, source: String = "System", error: Throwable? = null) =
    log(LogLevel.ERROR, message, source, error?.let { "${it.message}\n${it.stackTraceToString()}" })

fun LogManager.debug(message: String, source: String = "System") =
    log(LogLevel.DEBUG, message, source)

fun LogManager.critical(message: String, source: String = "System") =
    log(LogLevel.CRITICAL, message, source)
