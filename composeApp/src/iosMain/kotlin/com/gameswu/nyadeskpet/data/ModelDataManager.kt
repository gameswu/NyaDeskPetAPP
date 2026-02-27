@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.gameswu.nyadeskpet.data

import com.gameswu.nyadeskpet.PlatformContext
import platform.Foundation.*
import platform.UIKit.UIApplication

actual class ModelDataManager actual constructor(private val context: PlatformContext) {

    private val documentsDir: String by lazy {
        val paths = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, true
        )
        (paths.firstOrNull() as? String) ?: NSHomeDirectory()
    }

    private val modelsDir: String by lazy {
        "$documentsDir/models"
    }

    actual fun getModelsDir(): String {
        val fm = NSFileManager.defaultManager
        if (!fm.fileExistsAtPath(modelsDir)) {
            fm.createDirectoryAtPath(modelsDir, withIntermediateDirectories = true, attributes = null, error = null)
        }
        return modelsDir
    }

    actual fun getAppDataDir(): String = documentsDir

    actual fun ensureBuiltinModels(): Boolean {
        val fm = NSFileManager.defaultManager
        val bundlePath = NSBundle.mainBundle.resourcePath ?: run {
            println("[ModelDataManager] resourcePath is null")
            return false
        }
        val sourceDir = "$bundlePath/models"

        println("[ModelDataManager] Bundle resourcePath: $bundlePath")
        println("[ModelDataManager] Looking for models in: $sourceDir")
        println("[ModelDataManager] Source exists: ${fm.fileExistsAtPath(sourceDir)}")

        // Bundle 中没有 models 目录则跳过（避免误设标记）
        if (!fm.fileExistsAtPath(sourceDir)) {
            // 列出 Bundle 根目录内容以供诊断
            val bundleContents = fm.contentsOfDirectoryAtPath(bundlePath, error = null)
            println("[ModelDataManager] Bundle root contents: $bundleContents")
            return false
        }

        // 用 Bundle 版本号作为标记，App 更新后自动重新复制
        val bundleVersion = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "1"
        val marker = "$modelsDir/.initialized_v$bundleVersion"

        if (fm.fileExistsAtPath(marker)) {
            println("[ModelDataManager] Already initialized for version $bundleVersion")
            return false
        }

        // 确保 models 目录存在
        if (!fm.fileExistsAtPath(modelsDir)) {
            fm.createDirectoryAtPath(modelsDir, withIntermediateDirectories = true, attributes = null, error = null)
        }

        // 从 Bundle 递归复制模型到 Documents/models/
        println("[ModelDataManager] Copying models from Bundle to Documents...")
        copyDirRecursive(fm, sourceDir, modelsDir)

        // 验证复制结果
        val targetFile = "$modelsDir/live2d/mao_pro_zh/runtime/mao_pro.model3.json"
        println("[ModelDataManager] Copy done. Target file exists: ${fm.fileExistsAtPath(targetFile)}")

        fm.createFileAtPath(marker, contents = null, attributes = null)
        return true
    }

    actual fun resolveModelPath(relativePath: String): String {
        if (relativePath.startsWith("/")) return relativePath
        val clean = relativePath.removePrefix("models/")
        return "$modelsDir/$clean"
    }

    actual fun clearModelCache(): Boolean {
        val fm = NSFileManager.defaultManager
        return try {
            // 删除所有 .initialized* 标记文件（含版本化标记 .initialized_vX）
            val contents = fm.contentsOfDirectoryAtPath(modelsDir, error = null)
            @Suppress("UNCHECKED_CAST")
            val markers = (contents as? List<String>)?.filter { it.startsWith(".initialized") } ?: emptyList()
            for (marker in markers) {
                fm.removeItemAtPath("$modelsDir/$marker", error = null)
            }
            // 删除 models 目录下所有内容
            val remaining = fm.contentsOfDirectoryAtPath(modelsDir, error = null)
            @Suppress("UNCHECKED_CAST")
            val items = (remaining as? List<String>) ?: emptyList()
            for (item in items) {
                fm.removeItemAtPath("$modelsDir/$item", error = null)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    actual fun getDataDirSize(): Long {
        val fm = NSFileManager.defaultManager
        return calculateDirSize(fm, documentsDir)
    }

    actual fun openDataDirectory(): Boolean {
        // iOS 不支持直接打开文件管理器到指定目录
        // 但可以引导用户通过"文件"App 查看应用数据
        // 如果 Info.plist 中设置了 UIFileSharingEnabled 和 LSSupportsOpeningDocumentsInPlace
        // 用户可以在"文件"App 中看到应用文档
        return try {
            val url = NSURL(string = "shareddocuments://")
            if (UIApplication.sharedApplication.canOpenURL(url)) {
                UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any>(), null)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateDirSize(fm: NSFileManager, path: String): Long {
        if (!fm.fileExistsAtPath(path)) return 0

        val attrs = fm.attributesOfItemAtPath(path, error = null) ?: return 0
        val fileType = attrs[NSFileType] as? String

        return if (fileType == NSFileTypeDirectory) {
            val contents = fm.contentsOfDirectoryAtPath(path, error = null)
            @Suppress("UNCHECKED_CAST")
            val items = (contents as? List<String>) ?: emptyList()
            items.sumOf { calculateDirSize(fm, "$path/$it") }
        } else {
            (attrs[NSFileSize] as? Number)?.toLong() ?: 0L
        }
    }

    private fun copyDirRecursive(fm: NSFileManager, src: String, dst: String) {
        if (!fm.fileExistsAtPath(dst)) {
            fm.createDirectoryAtPath(dst, withIntermediateDirectories = true, attributes = null, error = null)
        }
        val contents = fm.contentsOfDirectoryAtPath(src, error = null) ?: return
        @Suppress("UNCHECKED_CAST")
        val items = contents as List<String>
        for (item in items) {
            val srcPath = "$src/$item"
            val dstPath = "$dst/$item"
            // 用文件属性判断是否为目录
            val attrs = fm.attributesOfItemAtPath(srcPath, error = null)
            val fileType = attrs?.get(NSFileType) as? String
            if (fileType == NSFileTypeDirectory) {
                copyDirRecursive(fm, srcPath, dstPath)
            } else if (!fm.fileExistsAtPath(dstPath)) {
                fm.copyItemAtPath(srcPath, toPath = dstPath, error = null)
            }
        }
    }
}
