package com.gameswu.nyadeskpet.data

/**
 * iOS 实现 — 手动解析 PKZip 本地文件头。
 *
 * 支持 stored（无压缩）模式，适用于小文件如 skill.json。
 * 若需要完整 deflate 支持，可后续引入 SSZipArchive via CocoaPods。
 */
actual fun extractZipEntries(zipBytes: ByteArray): Map<String, ByteArray> {
    val result = mutableMapOf<String, ByteArray>()

    var offset = 0
    while (offset + 30 <= zipBytes.size) {
        // PK local file header signature = 0x04034b50
        val sig = readInt32LE(zipBytes, offset)
        if (sig != 0x04034b50) break

        val compressionMethod = readInt16LE(zipBytes, offset + 8)
        val compressedSize = readInt32LE(zipBytes, offset + 18)
        val uncompressedSize = readInt32LE(zipBytes, offset + 22)
        val fileNameLen = readInt16LE(zipBytes, offset + 26)
        val extraFieldLen = readInt16LE(zipBytes, offset + 28)

        val fileNameStart = offset + 30
        val fileName = zipBytes.decodeToString(fileNameStart, fileNameStart + fileNameLen)

        val dataStart = fileNameStart + fileNameLen + extraFieldLen

        if (compressionMethod == 0 && !fileName.endsWith("/")) {
            // Stored — 无压缩
            val end = minOf(dataStart + uncompressedSize, zipBytes.size)
            val data = zipBytes.copyOfRange(dataStart, end)
            result[fileName] = data
        } else if (!fileName.endsWith("/")) {
            // Deflate 或其他压缩方式 — 跳过（技能 zip 建议使用无压缩模式）
            println("[ZipUtils] 跳过压缩条目: $fileName (method=$compressionMethod)")
        }

        offset = dataStart + compressedSize
    }

    return result
}

private fun readInt16LE(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8)
}

private fun readInt32LE(data: ByteArray, offset: Int): Int {
    return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}

