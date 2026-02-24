package com.gameswu.nyadeskpet.data

import android.content.Intent
import android.net.Uri
import com.gameswu.nyadeskpet.PlatformContext
import java.io.File

actual class ModelDataManager actual constructor(private val context: PlatformContext) {

    private val appDataDir: File by lazy {
        (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
    }

    private val modelsDir: File by lazy {
        File(appDataDir, "models").also { it.mkdirs() }
    }

    actual fun getModelsDir(): String = modelsDir.absolutePath

    actual fun getAppDataDir(): String = appDataDir.absolutePath

    actual fun ensureBuiltinModels(): Boolean {
        val marker = File(modelsDir, ".initialized")
        if (marker.exists()) return false

        // 从 assets/models/ 递归复制到数据目录
        copyAssetsDir("models", modelsDir)

        marker.writeText("1")
        return true
    }

    actual fun resolveModelPath(relativePath: String): String {
        // 如果已经是绝对路径，直接返回
        if (relativePath.startsWith("/")) return relativePath
        // 去掉可能的 "models/" 前缀避免重复
        val clean = relativePath.removePrefix("models/")
        return File(modelsDir, clean).absolutePath
    }

    actual fun clearModelCache(): Boolean {
        return try {
            val marker = File(modelsDir, ".initialized")
            if (marker.exists()) {
                marker.delete()
            }
            // 删除模型目录下所有文件
            modelsDir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    actual fun getDataDirSize(): Long {
        return calculateDirSize(appDataDir)
    }

    actual fun openDataDirectory(): Boolean {
        return try {
            val uri = Uri.parse("content://${context.packageName}.fileprovider${appDataDir.absolutePath}")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "resource/folder")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 日如果没有文件管理器可以处理 resource/folder，则退回到通用方式
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                // 尝试用 DocumentsUI 打开
                val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("content://com.android.externalstorage.documents/document/primary:Android/data/${context.packageName}/files")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(fallbackIntent)
                } catch (_: Exception) {
                    // 最终回退：打开系统文件管理器
                    val storageIntent = Intent("android.provider.action.BROWSE").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(storageIntent)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun calculateDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { calculateDirSize(it) } ?: 0
    }

    private fun copyAssetsDir(assetPath: String, targetDir: File) {
        val assets = context.assets
        val children = try {
            assets.list(assetPath) ?: return
        } catch (_: Exception) {
            return
        }

        if (children.isEmpty()) {
            // 是文件，直接复制
            val targetFile = targetDir
            targetFile.parentFile?.mkdirs()
            try {
                assets.open(assetPath).use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                // 如果 assetPath 其实是目录（空目录），忽略错误
            }
        } else {
            // 是目录，递归
            targetDir.mkdirs()
            for (child in children) {
                copyAssetsDir("$assetPath/$child", File(targetDir, child))
            }
        }
    }
}
