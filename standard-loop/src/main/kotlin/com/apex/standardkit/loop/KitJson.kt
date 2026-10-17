package com.apex.standardkit.loop

/**
 * # Kit Json — 库自含迷你 JSON 值模型与编解码器
 *
 * 标准思考逻辑线的 skills 远程索引解析、MCP 工具入参/schema 处理与
 * 输出归一化都需要结构化 JSON 访问。本库坚持**零第三方运行时依赖**
 * （协程之外不引任何坐标），因此这里提供一套自包含的最小 JSON 子集：
 *
 * - 解析：递归下降、容错（尾随空白/换行允许；非法文档返回 null 而非抛异常）；
 * - 值模型：[KitJsonValue] 六型（null / bool / number / string / array / object）；
 * - 访问器：类型安全的按路径取值（`obj["skills"][0]["name"].text` 风格）；
 * - 序列化：[KitJson.write]（紧凑形态；字符串转义含 `\uXXXX`）。
 *
 * ## 设计边界
 *
 * 这不是通用 JSON 库：不支持大数精度语义（全部走 [Double]）、不支持
 * 重复键保留（后者胜出）、注释/尾随逗号一律拒绝。它只服务于库内三个
 * 消费方，语义收敛、行为可预测——上游宿主若有完整 JSON 栈，应在其
 * 适配层做富解析，库内此实现只作为兜底坐标。
 */
object KitJson {

    // ═══════════════════════ 值模型 ═══════════════════════

    /** JSON 六型值（sealed —— 消费侧 when 穷尽）。 */
    sealed interface KitJsonValue {
        /** 字符串字面量。 */
        data class Str(val value: String) : KitJsonValue

        /** 数字（整数与小数统一走双精度；库内无大数需求）。 */
        data class Num(val value: Double) : KitJsonValue {
            /** 整数语义视图（截断）。 */
            val asInt: Int get() = value.toInt()
        }

        /** 布尔。 */
        data class Bool(val value: Boolean) : KitJsonValue

        /** null 字面量。 */
        data object Null : KitJsonValue

        /** 数组。 */
        data class Arr(val items: List<KitJsonValue>) : KitJsonValue

        /** 对象（保持文档顺序；重复键后者覆盖前者）。 */
        data class Obj(val fields: LinkedHashMap<String, KitJsonValue>) : KitJsonValue
    }

    // ═══════════════════════ 便捷构造 ═══════════════════════

    /** 构造对象（pair 列表按序入表）。 */
    fun obj(vararg pairs: Pair<String, KitJsonValue>): KitJsonValue.Obj =
        KitJsonValue.Obj(LinkedHashMap<String, KitJsonValue>().apply {
            pairs.forEach { (k, v) -> put(k, v) }
        })

    /** 构造数组。 */
    fun arr(items: List<KitJsonValue>): KitJsonValue.Arr = KitJsonValue.Arr(items)

    /** 字符串值。 */
    fun str(value: String): KitJsonValue.Str = KitJsonValue.Str(value)

    /** 数字值。 */
    fun num(value: Double): KitJsonValue.Num = KitJsonValue.Num(value)

    /** 布尔值。 */
    fun bool(value: Boolean): KitJsonValue.Bool = KitJsonValue.Bool(value)

    // ═══════════════════════ 解析 ═══════════════════════

    /**
     * 解析 JSON 文档（容错：任何非法输入返回 null，不抛异常——调用方
     * 用 null 兜底「不是 JSON」分支，例如 MCP 文本输出的 JSON 探测）。
     */
    fun parse(source: String): KitJsonValue? {
        val text = source.trim()
        if (text.isEmpty()) return null
        val cursor = Cursor(text)
        val value = parseValue(cursor) ?: return null
        cursor.skipWhitespace()
        return if (cursor.isAtEnd) value else null
    }

    /** 解析为对象（顶层非对象 → null）。 */
    fun parseObject(source: String): KitJsonValue.Obj? = parse(source) as? KitJsonValue.Obj

    /** 解析为数组（顶层非数组 → null）。 */
    fun parseArray(source: String): KitJsonValue.Arr? = parse(source) as? KitJsonValue.Arr

    private class Cursor(val text: String) {
        var pos: Int = 0

        val isAtEnd: Boolean get() = pos >= text.length

        fun peek(): Char? = if (isAtEnd) null else text[pos]

        fun take(): Char? {
            if (isAtEnd) return null
            val c = text[pos]
            pos += 1
            return c
        }

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos += 1
        }

        fun expect(c: Char): Boolean {
            skipWhitespace()
            val actual = peek() ?: return false
            if (actual != c) return false
            pos += 1
            return true
        }

        fun keyword(word: String): Boolean {
            if (pos + word.length > text.length) return false
            if (text.regionMatches(pos, word, 0, word.length)) {
                pos += word.length
                return true
            }
            return false
        }
    }

    private fun parseValue(cursor: Cursor): KitJsonValue? {
        cursor.skipWhitespace()
        return when (cursor.peek()) {
            null -> null
            '{' -> parseObject(cursor)
            '[' -> parseArray(cursor)
            '"' -> parseString(cursor)?.let { KitJsonValue.Str(it) }
            't' -> if (cursor.keyword("true")) KitJsonValue.Bool(true) else null
            'f' -> if (cursor.keyword("false")) KitJsonValue.Bool(false) else null
            'n' -> if (cursor.keyword("null")) KitJsonValue.Null else null
            '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber(cursor)
            else -> null
        }
    }

    private fun parseObject(cursor: Cursor): KitJsonValue.Obj? {
        if (cursor.take() != '{') return null
        val fields = LinkedHashMap<String, KitJsonValue>()
        cursor.skipWhitespace()
        if (cursor.peek() == '}') {
            cursor.take()
            return KitJsonValue.Obj(fields)
        }
        while (true) {
            cursor.skipWhitespace()
            val key = parseString(cursor) ?: return null
            if (!cursor.expect(':')) return null
            val value = parseValue(cursor) ?: return null
            fields[key] = value
            cursor.skipWhitespace()
            when (cursor.peek()) {
                ',' -> {
                    cursor.take()
                    continue
                }
                '}' -> {
                    cursor.take()
                    return KitJsonValue.Obj(fields)
                }
                else -> return null
            }
        }
    }

    private fun parseArray(cursor: Cursor): KitJsonValue.Arr? {
        if (cursor.take() != '[') return null
        val items = mutableListOf<KitJsonValue>()
        cursor.skipWhitespace()
        if (cursor.peek() == ']') {
            cursor.take()
            return KitJsonValue.Arr(items)
        }
        while (true) {
            val value = parseValue(cursor) ?: return null
            items.add(value)
            cursor.skipWhitespace()
            when (cursor.peek()) {
                ',' -> {
                    cursor.take()
                    continue
                }
                ']' -> {
                    cursor.take()
                    return KitJsonValue.Arr(items)
                }
                else -> return null
            }
        }
    }

    private fun parseString(cursor: Cursor): String? {
        if (cursor.take() != '"') return null
        val out = StringBuilder()
        while (true) {
            val c = cursor.take() ?: return null
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    val esc = cursor.take() ?: return null
                    when (esc) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = StringBuilder()
                            repeat(4) {
                                val h = cursor.take() ?: return null
                                hex.append(h)
                            }
                            val code = hex.toString().toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                        }
                        else -> return null
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun parseNumber(cursor: Cursor): KitJsonValue.Num? {
        val start = cursor.pos
        if (cursor.peek() == '-') cursor.take()
        // 整数部分
        if (cursor.peek() == '0') {
            cursor.take()
        } else {
            var digits = 0
            while (cursor.peek()?.isDigit() == true) {
                cursor.take()
                digits += 1
            }
            if (digits == 0) return null
        }
        // 小数部分
        if (cursor.peek() == '.') {
            cursor.take()
            var fracDigits = 0
            while (cursor.peek()?.isDigit() == true) {
                cursor.take()
                fracDigits += 1
            }
            if (fracDigits == 0) return null
        }
        // 指数部分
        if (cursor.peek() == 'e' || cursor.peek() == 'E') {
            cursor.take()
            if (cursor.peek() == '+' || cursor.peek() == '-') cursor.take()
            var expDigits = 0
            while (cursor.peek()?.isDigit() == true) {
                cursor.take()
                expDigits += 1
            }
            if (expDigits == 0) return null
        }
        val raw = cursor.text.substring(start, cursor.pos)
        val value = raw.toDoubleOrNull() ?: return null
        return KitJsonValue.Num(value)
    }

    // ═══════════════════════ 序列化 ═══════════════════════

    /** 紧凑序列化（无缩进；键按入表顺序）。 */
    fun write(value: KitJsonValue): String {
        val sb = StringBuilder()
        writeInto(value, sb)
        return sb.toString()
    }

    private fun writeInto(value: KitJsonValue, sb: StringBuilder) {
        when (value) {
            is KitJsonValue.Null -> sb.append("null")
            is KitJsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is KitJsonValue.Num -> {
                val asLong = value.value.toLong()
                if (value.value == asLong.toDouble()) {
                    sb.append(asLong.toString())
                } else {
                    sb.append(value.value.toString())
                }
            }
            is KitJsonValue.Str -> writeString(value.value, sb)
            is KitJsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeInto(item, sb)
                }
                sb.append(']')
            }
            is KitJsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((key, item) in value.fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(key, sb)
                    sb.append(':')
                    writeInto(item, sb)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(value: String, sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        sb.append('"')
    }

    // ═══════════════════════ 合规扫描辅助 ═══════════════════════

    /**
     * 文本「疑似 JSON」探测：MCP 输出归一化用它判定「服务器未声明
     * outputSchema 时，文本形态的 JSON 是否应解析为对象回传」——
     * 业界 agent 约定：以 `{` 或 `[` 起头的文本先尝试结构化。
     */
    fun looksStructured(text: String): Boolean {
        val trimmed = text.trimStart()
        return trimmed.startsWith("{") || trimmed.startsWith("[")
    }
}

// ═══════════════════════ 顶层访问器扩展（同包免 import，宿主按名导入） ═══════════════════════


/** 按键取字段（非对象 / 键缺失 → null）。 */
operator fun KitJson.KitJsonValue?.get(key: String): KitJson.KitJsonValue? =
    (this as? KitJson.KitJsonValue.Obj)?.fields?.get(key)

/** 按下标取元素（非数组 / 越界 → null）。 */
operator fun KitJson.KitJsonValue?.get(index: Int): KitJson.KitJsonValue? =
    (this as? KitJson.KitJsonValue.Arr)?.items?.getOrNull(index)

/** 字符串视图（非字符串 → null）。 */
val KitJson.KitJsonValue?.text: String?
    get() = (this as? KitJson.KitJsonValue.Str)?.value

/** 数字视图（非数字 → null）。 */
val KitJson.KitJsonValue?.number: Double?
    get() = (this as? KitJson.KitJsonValue.Num)?.value

/** 布尔视图（非布尔 → null）。 */
val KitJson.KitJsonValue?.boolean: Boolean?
    get() = (this as? KitJson.KitJsonValue.Bool)?.value

/** 对象视图。 */
val KitJson.KitJsonValue?.obj: KitJson.KitJsonValue.Obj?
    get() = this as? KitJson.KitJsonValue.Obj

/** 数组视图。 */
val KitJson.KitJsonValue?.array: KitJson.KitJsonValue.Arr?
    get() = this as? KitJson.KitJsonValue.Arr

/** 字符串数组视图（元素逐个取 text，非字符串元素跳过）。 */
val KitJson.KitJsonValue?.textList: List<String>
    get() = (this as? KitJson.KitJsonValue.Arr)?.items?.mapNotNull { (it as? KitJson.KitJsonValue.Str)?.value }
        ?: emptyList()

