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
        val marker = "$modelsDir/.initialized"
        if (fm.fileExistsAtPath(marker)) return false

        // 从 Bundle 中复制模型到 Documents/models/
        val bundlePath = NSBundle.mainBundle.resourcePath ?: return false
        val sourceDir = "$bundlePath/models"

        if (fm.fileExistsAtPath(sourceDir)) {
            if (!fm.fileExistsAtPath(modelsDir)) {
                fm.copyItemAtPath(sourceDir, toPath = modelsDir, error = null)
            } else {
                // 目录已部分存在，逐项复制
                copyDirRecursive(fm, sourceDir, modelsDir)
            }
        }

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
            // 删除 .initialized 标记
            val marker = "$modelsDir/.initialized"
            if (fm.fileExistsAtPath(marker)) {
                fm.removeItemAtPath(marker, error = null)
            }
            // 删除 models 目录下所有内容
            val contents = fm.contentsOfDirectoryAtPath(modelsDir, error = null)
            @Suppress("UNCHECKED_CAST")
            val items = (contents as? List<String>) ?: emptyList()
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
                UIApplication.sharedApplication.openURL(url)
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
            if (!fm.fileExistsAtPath(dstPath)) {
                fm.copyItemAtPath(srcPath, toPath = dstPath, error = null)
            }
        }
    }
}
