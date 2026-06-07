package com.vito.gateway

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit

/**
 * 輕量 Markdown → AnnotatedString（給語音字幕 / 文件彈窗 / 迷你卡用）。
 * 支援：# 標題（依層級放大字級）、**粗體**、*斜體*、~~刪除線~~、`行內 code`、```程式碼區塊```、
 *       清單（- / 數字 轉圓點）、[文字](網址) 連結只留文字、表格分隔行清掉、LaTeX 轉純文字。
 * 不依賴外部庫，避免編譯風險。
 */
fun renderMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    val tableSep = Regex("^\\s*\\|?[\\s:|\\-]+\\|?\\s*$")
    val heading = Regex("^\\s*(#{1,6})\\s*")
    val lines = src.split("\n")
    var inCode = false
    lines.forEachIndexed { idx, raw0 ->
        var line = raw0
        // 程式碼區塊圍籬 ``` → 切換等寬模式、本行不顯示圍籬
        if (line.trimStart().startsWith("```")) {
            inCode = !inCode
            if (idx < lines.size - 1) append("\n")
            return@forEachIndexed
        }
        if (inCode) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(line) }
            if (idx < lines.size - 1) append("\n")
            return@forEachIndexed
        }
        if (line.contains("|") && tableSep.matches(line)) return@forEachIndexed // 表格分隔行丟掉
        // LaTeX → 純文字
        line = line.replace(Regex("\\\\(?:long)?(?:right)?arrow|\\\\to\\b"), "→")
            .replace(Regex("\\\\times"), "×").replace(Regex("\\\\[a-zA-Z]+"), "")
            .replace(Regex("\\$(?!\\s*\\d)"), "")
        // 連結 [文字](網址) → 只留文字
        line = line.replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        // 標題層級 → 字級
        var headSize: TextUnit = TextUnit.Unspecified
        val hm = heading.find(line)
        if (hm != null) {
            val level = hm.groupValues[1].length
            headSize = when (level) { 1 -> 20.sp; 2 -> 18.sp; 3 -> 16.sp; else -> 15.sp }
            line = line.replace(heading, "")
        }
        line = line.replace(Regex("^\\s*[-*+]\\s+"), "• ")
            .replace(Regex("^\\s*\\d+[.)]\\s+"), "• ")
            .replace(Regex(">\\s?"), "")
            .replace(Regex("\\s*\\|\\s*"), "  ")
        appendInline(this, line, headSize)
        if (idx < lines.size - 1) append("\n")
    }
}

private fun appendInline(
    b: androidx.compose.ui.text.AnnotatedString.Builder,
    line: String,
    headSize: TextUnit,
) {
    val isHeading = headSize != TextUnit.Unspecified
    // **粗體** / *斜體* / ~~刪除線~~ / `code`
    val token = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|~~(.+?)~~|`(.+?)`")
    var i = 0
    val baseStyle = if (isHeading) SpanStyle(fontWeight = FontWeight.Bold, fontSize = headSize) else SpanStyle()
    val base: (String) -> Unit = { s -> b.withStyle(baseStyle) { b.append(s) } }
    for (m in token.findAll(line)) {
        if (m.range.first > i) base(line.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> b.withStyle(baseStyle.copy(fontWeight = FontWeight.Bold)) { b.append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> b.withStyle(baseStyle.copy(fontWeight = FontWeight.Medium)) { b.append(m.groupValues[2]) }
            m.groupValues[3].isNotEmpty() -> b.withStyle(baseStyle.copy(textDecoration = TextDecoration.LineThrough)) { b.append(m.groupValues[3]) }
            else -> b.withStyle(baseStyle.copy(fontFamily = FontFamily.Monospace)) { b.append(m.groupValues[4]) }
        }
        i = m.range.last + 1
    }
    if (i < line.length) base(line.substring(i))
}
