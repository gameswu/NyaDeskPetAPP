package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * iOS 原生文件选择器实现。
 * 使用 UIDocumentPickerViewController。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePickerLauncher(
    mimeTypes: List<String>,
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit {
    return {
        val types = mimeTypes.mapNotNull { mime ->
            when {
                mime == "*/*" -> UTType.item
                mime.startsWith("image/") -> UTType.image
                mime.startsWith("video/") -> UTType.movie
                mime.startsWith("audio/") -> UTType.audio
                mime.startsWith("text/") -> UTType.text
                else -> UTType.item
            }
        }.ifEmpty { listOf(UTType.item) }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = types,
            asCopy = true
        )

        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>
            ) {
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
                if (url != null) {
                    val name = url.lastPathComponent ?: "unknown"

                    // 读取文件内容（限制 10MB 防 OOM）
                    val bytes: ByteArray? = try {
                        val data = NSData.dataWithContentsOfURL(url)
                        if (data != null && data.length.toInt() <= 10 * 1024 * 1024) {
                            val size = data.length.toInt()
                            if (size > 0) {
                                ByteArray(size).also { arr ->
                                    arr.usePinned { pinned ->
                                        memcpy(pinned.addressOf(0), data.bytes, data.length)
                                    }
                                }
                            } else null
                        } else null
                    } catch (_: Exception) { null }

                    onResult(FilePickerResult(
                        uri = url.absoluteString ?: "",
                        name = name,
                        mimeType = null,
                        bytes = bytes,
                    ))
                } else {
                    onResult(null)
                }
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                onResult(null)
            }
        }

        picker.delegate = delegate

        UIApplication.sharedApplication.keyWindow?.rootViewController?.presentViewController(
            picker, animated = true, completion = null
        )
    }
}
