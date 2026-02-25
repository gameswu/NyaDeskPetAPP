package com.gameswu.nyadeskpet.data

/**
 * 跨平台 Zip 工具 — 从 zip 字节中提取指定条目。
 *
 * expect/actual：
 * - Android: java.util.zip.ZipInputStream
 * - iOS: Foundation + compression APIs
 */

/**
 * 从 zip 字节数据中提取所有条目。
 * @return Map<文件名, 文件内容字节>
 */
expect fun extractZipEntries(zipBytes: ByteArray): Map<String, ByteArray>
