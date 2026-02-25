package com.gameswu.nyadeskpet.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Android 实现 — 使用 java.util.zip.ZipInputStream 解压。
 */
actual fun extractZipEntries(zipBytes: ByteArray): Map<String, ByteArray> {
    val result = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                result[entry.name] = zis.readBytes()
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    return result
}
