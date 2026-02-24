package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext

/**
 * 模型数据目录管理 — 对齐原项目 model path 处理
 *
 * 移动端将自带的 Live2D 模型和语音模型从 assets/resources
 * 复制到应用自有的数据目录中，以便读取和更新。
 */
expect class ModelDataManager(context: PlatformContext) {
    /**
     * 获取模型数据根目录的绝对路径。
     * Android: {externalFilesDir}/models/
     * iOS: {Documents}/models/
     */
    fun getModelsDir(): String

    /**
     * 获取应用数据根目录的绝对路径。
     * Android: {externalFilesDir}/
     * iOS: {Documents}/
     */
    fun getAppDataDir(): String

    /**
     * 确保内置模型已复制到数据目录。
     * 首次启动或版本升级时调用。
     * @return 是否执行了复制操作
     */
    fun ensureBuiltinModels(): Boolean

    /**
     * 将 assets 中的相对路径解析为数据目录的绝对路径。
     * 例: "models/live2d/mao_pro_zh/runtime/mao_pro.model3.json" → "/data/.../models/live2d/mao_pro_zh/runtime/mao_pro.model3.json"
     */
    fun resolveModelPath(relativePath: String): String

    /**
     * 清除模型缓存 — 删除 .initialized 标记文件。
     * 下次启动时会重新从 assets 复制内置模型。
     * @return 是否成功清除
     */
    fun clearModelCache(): Boolean

    /**
     * 获取数据目录的总大小（字节）
     */
    fun getDataDirSize(): Long

    /**
     * 使用系统文件管理器打开数据目录。
     * Android: 通过 Intent 打开文件管理器
     * iOS: 提示用户通过"文件"App 访问
     * @return 是否成功打开
     */
    fun openDataDirectory(): Boolean
}
