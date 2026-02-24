package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable
import kotlinx.cinterop.*
import platform.Foundation.*
import platform.UIKit.*
import platform.posix.memcpy

/**
 * iOS 拍照实现。
 * 使用 UIImagePickerController 打开系统相机。
 * 需要在 Info.plist 中添加: NSCameraUsageDescription
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberCameraCaptureLauncher(
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit {
    return {
        if (!UIImagePickerController.isSourceTypeAvailable(
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
            )
        ) {
            onResult(null)
        } else {
            val picker = UIImagePickerController()
            picker.sourceType =
                UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera

            val delegate = object : NSObject(),
                UIImagePickerControllerDelegateProtocol,
                UINavigationControllerDelegateProtocol {

                override fun imagePickerController(
                    picker: UIImagePickerController,
                    didFinishPickingMediaWithInfo: Map<Any?, *>
                ) {
                    picker.dismissViewControllerAnimated(true, null)
                    val image =
                        didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage
                    if (image != null) {
                        val jpegData = UIImageJPEGRepresentation(image, 0.85)
                        if (jpegData != null) {
                            val bytes = jpegData.toByteArray()
                            onResult(
                                FilePickerResult(
                                    uri = "camera://capture",
                                    name = "photo_${NSDate().timeIntervalSince1970.toLong()}.jpg",
                                    mimeType = "image/jpeg",
                                    bytes = bytes,
                                )
                            )
                        } else {
                            onResult(null)
                        }
                    } else {
                        onResult(null)
                    }
                }

                override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                    picker.dismissViewControllerAnimated(true, null)
                    onResult(null)
                }
            }

            picker.delegate = delegate

            UIApplication.sharedApplication.keyWindow?.rootViewController
                ?.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/**
 * 将 NSData 转换为 ByteArray。
 */
@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this.bytes, this.length)
    }
    return bytes
}
