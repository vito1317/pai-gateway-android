package com.vito.gateway

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 輕量 Markdown → AnnotatedString（給語音字幕用）。
 * 支援：標題、**粗體**、*斜體*、`code`、清單(-/*/數字→•)、表格符號清掉、LaTeX→純文字。
 * 不依賴外部庫，避免編譯風險。
 */
fun renderMarkdown(src: String): AnnotatedString = buildAnnotatedString {
    val tableSep = Regex("^\\s*\\|?[\\s:|\\-]+\\|?\\s*$")
    val heading = Regex("^\\s*#{1,6}\\s*")
    val lines = src.split("\n")
    lines.forEachIndexed { idx, raw0 ->
        var line = raw0
        if (line.contains("|") && tableSep.matches(line)) return@forEachIndexed // 表格分隔行丟掉
        // LaTeX → 純文字
        line = line.replace(Regex("\\\\(?:long)?(?:right)?arrow|\\\\to\\b"), "→")
            .replace(Regex("\\\\times"), "×").replace(Regex("\\\\[a-zA-Z]+"), "")
            .replace(Regex("\\$(?!\\s*\\d)"), "")
        val isHeading = heading.containsMatchIn(line)
        if (isHeading) line = line.replace(heading, "")
        line = line.replace(Regex("^\\s*[-*+]\\s+"), "• ")
            .replace(Regex("^\\s*\\d+[.)]\\s+"), "• ")
            .replace(Regex("\\s*\\|\\s*"), "  ")
        appendInline(this, line, isHeading)
        if (idx < lines.size - 1) append("\n")
    }
}

private fun appendInline(b: androidx.compose.ui.text.AnnotatedString.Builder, line: String, bold: Boolean) {
    // 逐段處理 **粗體** / *斜體* / `code`
    val token = Regex("\\*\\*(.+?)\\*\\*|\\*(.+?)\\*|`(.+?)`")
    var i = 0
    val base: (String) -> Unit = { s ->
        if (bold) b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { b.append(s) } else b.append(s)
    }
    for (m in token.findAll(line)) {
        if (m.range.first > i) base(line.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> b.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { b.append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> b.withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { b.append(m.groupValues[2]) }
            else -> b.append(m.groupValues[3])
        }
        i = m.range.last + 1
    }
    if (i < line.length) base(line.substring(i))
}
