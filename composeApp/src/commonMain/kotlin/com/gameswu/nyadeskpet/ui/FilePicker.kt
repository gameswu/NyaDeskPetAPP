package com.gameswu.nyadeskpet.ui

import androidx.compose.runtime.Composable

/**
 * 文件选择结果。
 */
data class FilePickerResult(
    val uri: String,       // 平台 URI（Android content://, iOS file://）
    val name: String,      // 文件名
    val mimeType: String?, // MIME 类型
    val bytes: ByteArray?, // 文件内容（小文件可用，大文件为 null）
)

/**
 * 创建平台原生文件选择器的 launcher。
 *
 * @param mimeTypes 允许的 MIME 类型列表，默认全部
 * @param onResult  选择文件后的回调，取消时传 null
 * @return 调用即可打开文件选择器的 lambda
 */
@Composable
expect fun rememberFilePickerLauncher(
    mimeTypes: List<String> = listOf("*/*"),
    onResult: (FilePickerResult?) -> Unit,
): () -> Unit
