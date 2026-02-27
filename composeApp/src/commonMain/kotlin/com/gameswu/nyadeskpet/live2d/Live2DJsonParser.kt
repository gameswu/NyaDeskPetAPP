package com.gameswu.nyadeskpet.live2d

import com.gameswu.nyadeskpet.agent.*

/**
 * 跨平台 Live2D model3.json / param-map.json 解析工具。
 *
 * 纯 Kotlin 字符串操作，不依赖平台 API，可在 iOS 和 Android 共用。
 */
object Live2DJsonParser {

    val DEFAULT_LIP_SYNC_PARAMS = listOf("ParamMouthOpenY")
    const val PARAM_MAP_FILENAME = "param-map.json"

    // ===================== model3.json 解析 =====================

    /**
     * 解析 Groups 中 Name="LipSync" 的 Ids 列表。
     */
    fun parseLipSyncIds(json: String): List<String> {
        val result = mutableListOf<String>()
        val groupsKey = "\"Groups\""
        val groupsStart = json.indexOf(groupsKey)
        if (groupsStart == -1) return result

        val arrayStart = json.indexOf('[', groupsStart + groupsKey.length)
        if (arrayStart == -1) return result

        val arrayEnd = findMatchingBracket(json, arrayStart, '[', ']')
        if (arrayEnd == -1) return result

        val arrayContent = json.substring(arrayStart, arrayEnd + 1)
        var objStart = arrayContent.indexOf('{')
        while (objStart != -1) {
            var objDepth = 0; var objEnd = objStart
            while (objEnd < arrayContent.length) {
                when (arrayContent[objEnd]) { '{' -> objDepth++; '}' -> { objDepth--; if (objDepth == 0) break } }
                objEnd++
            }
            if (objEnd >= arrayContent.length) break

            val obj = arrayContent.substring(objStart, objEnd + 1)
            val name = extractJsonStringValue(obj, "Name")
            if (name == "LipSync") {
                val idsKey = "\"Ids\""
                val idsKeyPos = obj.indexOf(idsKey)
                if (idsKeyPos != -1) {
                    val idsArrayStart = obj.indexOf('[', idsKeyPos + idsKey.length)
                    val idsArrayEnd = obj.indexOf(']', idsArrayStart)
                    if (idsArrayStart != -1 && idsArrayEnd != -1) {
                        val idsStr = obj.substring(idsArrayStart + 1, idsArrayEnd)
                        val regex = Regex("\"([^\"]+)\"")
                        for (match in regex.findAll(idsStr)) {
                            result.add(match.groupValues[1])
                        }
                    }
                }
                break
            }
            objStart = arrayContent.indexOf('{', objEnd + 1)
        }
        return result
    }

    /**
     * 解析 HitAreas 数组（Name 为空时 fallback 到 Id）。
     */
    fun parseHitAreas(json: String): List<String> {
        val result = mutableListOf<String>()
        val hitAreasKey = "\"HitAreas\""
        val start = json.indexOf(hitAreasKey)
        if (start == -1) return result

        val arrayStart = json.indexOf('[', start + hitAreasKey.length)
        if (arrayStart == -1) return result

        val arrayEnd = findMatchingBracket(json, arrayStart, '[', ']')
        if (arrayEnd == -1) return result

        val arrayContent = json.substring(arrayStart, arrayEnd + 1)
        var objStart = arrayContent.indexOf('{')
        while (objStart != -1) {
            val objEnd = arrayContent.indexOf('}', objStart)
            if (objEnd == -1) break
            val obj = arrayContent.substring(objStart, objEnd + 1)
            val id = extractJsonStringValue(obj, "Id")
            val name = extractJsonStringValue(obj, "Name")
            val effectiveName = name.ifBlank { id }
            if (effectiveName.isNotBlank()) result.add(effectiveName)
            objStart = arrayContent.indexOf('{', objEnd + 1)
        }
        return result
    }

    /**
     * 解析 FileReferences.Expressions 数组中的 Name 字段。
     */
    fun parseExpressions(json: String): List<String> {
        val result = mutableListOf<String>()
        val expKey = "\"Expressions\""
        val start = json.indexOf(expKey)
        if (start == -1) return result

        val arrayStart = json.indexOf('[', start + expKey.length)
        if (arrayStart == -1) return result

        val arrayEnd = findMatchingBracket(json, arrayStart, '[', ']')
        if (arrayEnd == -1) return result

        val arrayContent = json.substring(arrayStart, arrayEnd + 1)
        var objStart = arrayContent.indexOf('{')
        while (objStart != -1) {
            val objEnd = arrayContent.indexOf('}', objStart)
            if (objEnd == -1) break
            val obj = arrayContent.substring(objStart, objEnd + 1)
            val name = extractJsonStringValue(obj, "Name")
            if (name.isNotBlank()) result.add(name)
            objStart = arrayContent.indexOf('{', objEnd + 1)
        }
        return result
    }

    /**
     * 解析 FileReferences.Motions 对象。
     */
    fun parseMotions(json: String): Map<String, MotionGroup> {
        val result = mutableMapOf<String, MotionGroup>()
        val motionsKey = "\"Motions\""
        val start = json.indexOf(motionsKey)
        if (start == -1) return result

        val objStart = json.indexOf('{', start + motionsKey.length)
        if (objStart == -1) return result

        val objEnd = findMatchingBracket(json, objStart, '{', '}')
        if (objEnd == -1) return result

        val motionsContent = json.substring(objStart + 1, objEnd)

        var pos = 0
        while (pos < motionsContent.length) {
            val keyStart = motionsContent.indexOf('"', pos)
            if (keyStart == -1) break
            val keyEnd = motionsContent.indexOf('"', keyStart + 1)
            if (keyEnd == -1) break
            val groupName = motionsContent.substring(keyStart + 1, keyEnd)
            val displayName = groupName.ifBlank { "Default" }

            val arrStart = motionsContent.indexOf('[', keyEnd)
            if (arrStart == -1) break
            val arrEnd = findMatchingBracket(motionsContent, arrStart, '[', ']')
            if (arrEnd == -1) break

            val arrContent = motionsContent.substring(arrStart, arrEnd + 1)
            val files = mutableListOf<String>()
            var fPos = 0
            while (true) {
                val fileKeyPos = arrContent.indexOf("\"File\"", fPos)
                if (fileKeyPos == -1) break
                val file = extractJsonStringValue(arrContent.substring(fileKeyPos), "File")
                if (file.isNotBlank()) files.add(file)
                fPos = fileKeyPos + 6
            }

            result[displayName] = MotionGroup(count = files.size.coerceAtLeast(1), files = files)
            pos = arrEnd + 1
        }
        return result
    }

    // ===================== param-map.json 解析 =====================

    fun parseParamMap(json: String): ParamMapData? {
        val versionKey = "\"version\""
        val versionPos = json.indexOf(versionKey)
        if (versionPos == -1) return null
        var vp = versionPos + versionKey.length
        while (vp < json.length && json[vp] in " \t\n\r:") vp++
        val vStart = vp
        while (vp < json.length && json[vp].isDigit()) vp++
        val version = json.substring(vStart, vp).toIntOrNull() ?: return null
        if (version != 1) return null

        val parameters = parseParamMapSection(json, "\"parameters\"") { obj ->
            val id = extractJsonStringValue(obj, "id")
            val alias = extractJsonStringValue(obj, "alias")
            val description = extractJsonStringValue(obj, "description")
            if (id.isNotBlank() && alias.isNotBlank()) ParamMapEntry(id, alias, description) else null
        }
        val expressions = parseParamMapSection(json, "\"expressions\"") { obj ->
            val id = extractJsonStringValue(obj, "id")
            val alias = extractJsonStringValue(obj, "alias")
            val description = extractJsonStringValue(obj, "description")
            if (id.isNotBlank() && alias.isNotBlank()) ParamMapEntry(id, alias, description) else null
        }
        val motions = parseParamMapSection(json, "\"motions\"") { obj ->
            val group = extractJsonStringValue(obj, "group")
            val alias = extractJsonStringValue(obj, "alias")
            val description = extractJsonStringValue(obj, "description")
            val index = extractJsonNumberValue(obj, "index").toIntOrNull() ?: 0
            if (group.isNotBlank() && alias.isNotBlank()) ParamMapMotionEntry(group, index, alias, description) else null
        }

        return ParamMapData(parameters, expressions, motions)
    }

    /**
     * 将 param-map.json 的映射附加到 ModelInfo。
     */
    fun enrichModelInfoWithParamMap(
        base: ModelInfo,
        expressions: List<String>,
        motions: Map<String, MotionGroup>,
        paramMap: ParamMapData,
    ): ModelInfo {
        val mappedParams = if (paramMap.parameters.isNotEmpty()) {
            paramMap.parameters.map { entry ->
                MappedParameter(
                    id = entry.id, alias = entry.alias, description = entry.description,
                    min = 0f, max = 1f, default = 0f,
                )
            }
        } else null

        val validExps = expressions.toSet()
        val mappedExps = if (paramMap.expressions.isNotEmpty()) {
            paramMap.expressions.filter { it.id in validExps }.map { entry ->
                MappedExpression(id = entry.id, alias = entry.alias, description = entry.description)
            }.takeIf { it.isNotEmpty() }
        } else null

        val mappedMots = if (paramMap.motions.isNotEmpty()) {
            paramMap.motions.filter { entry ->
                val group = motions[entry.group]
                group != null && entry.index >= 0 && entry.index < group.count
            }.map { entry ->
                MappedMotion(
                    group = entry.group, index = entry.index,
                    alias = entry.alias, description = entry.description,
                )
            }.takeIf { it.isNotEmpty() }
        } else null

        return base.copy(
            mappedParameters = mappedParams,
            mappedExpressions = mappedExps,
            mappedMotions = mappedMots,
        )
    }

    // ===================== 内部工具方法 =====================

    private fun <T : Any> parseParamMapSection(json: String, key: String, mapper: (String) -> T?): List<T> {
        val result = mutableListOf<T>()
        val start = json.indexOf(key)
        if (start == -1) return result
        val arrStart = json.indexOf('[', start + key.length)
        if (arrStart == -1) return result
        val arrEnd = findMatchingBracket(json, arrStart, '[', ']')
        if (arrEnd == -1) return result
        val content = json.substring(arrStart, arrEnd + 1)

        var objStart = content.indexOf('{')
        while (objStart != -1) {
            val objEnd = content.indexOf('}', objStart)
            if (objEnd == -1) break
            val obj = content.substring(objStart, objEnd + 1)
            mapper(obj)?.let { result.add(it) }
            objStart = content.indexOf('{', objEnd + 1)
        }
        return result
    }

    internal fun extractJsonStringValue(json: String, key: String): String {
        val keyStr = "\"$key\""
        val keyPos = json.indexOf(keyStr)
        if (keyPos == -1) return ""
        var pos = keyPos + keyStr.length
        while (pos < json.length && json[pos] in " \t\n\r:") pos++
        if (pos >= json.length || json[pos] != '"') return ""
        val valueStart = pos + 1
        val valueEnd = json.indexOf('"', valueStart)
        return if (valueEnd == -1) "" else json.substring(valueStart, valueEnd)
    }

    internal fun extractJsonNumberValue(json: String, key: String): String {
        val keyStr = "\"$key\""
        val keyPos = json.indexOf(keyStr)
        if (keyPos == -1) return ""
        var pos = keyPos + keyStr.length
        while (pos < json.length && json[pos] in " \t\n\r:") pos++
        val vStart = pos
        while (pos < json.length && (json[pos].isDigit() || json[pos] == '-' || json[pos] == '.')) pos++
        return json.substring(vStart, pos)
    }

    internal fun findMatchingBracket(json: String, start: Int, open: Char, close: Char): Int {
        var depth = 0; var pos = start
        while (pos < json.length) {
            when (json[pos]) { open -> depth++; close -> { depth--; if (depth == 0) return pos } }
            pos++
        }
        return -1
    }
}

/** param-map.json 解析结果 */
data class ParamMapData(
    val parameters: List<ParamMapEntry>,
    val expressions: List<ParamMapEntry>,
    val motions: List<ParamMapMotionEntry>,
)

data class ParamMapEntry(val id: String, val alias: String, val description: String)
data class ParamMapMotionEntry(val group: String, val index: Int, val alias: String, val description: String)
