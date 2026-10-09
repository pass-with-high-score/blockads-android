package app.pwhs.blockads.ui.config.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class ConfigSyntaxVisualTransformation(
    private val sectionColors: Map<String, Color>,
    private val defaultSectionColor: Color,
    private val commentColor: Color,
    private val rejectColor: Color = Color(0xFFEF4444),
    private val directColor: Color = Color(0xFF10B981),
    private val proxyColor: Color = Color(0xFF3B82F6),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val builder = AnnotatedString.Builder(raw)
        var start = 0
        val len = raw.length

        while (start < len) {
            var end = raw.indexOf('\n', start)
            if (end == -1) end = len

            // Find first non-whitespace character in line
            var firstNonWs = start
            while (firstNonWs < end && raw[firstNonWs].isWhitespace()) {
                firstNonWs++
            }

            if (firstNonWs < end) {
                val ch = raw[firstNonWs]
                if (ch == '#' || ch == ';' || (ch == '/' && firstNonWs + 1 < end && raw[firstNonWs + 1] == '/')) {
                    // Comment line
                    builder.addStyle(
                        SpanStyle(
                            color = commentColor,
                            fontStyle = FontStyle.Italic
                        ),
                        firstNonWs,
                        end
                    )
                } else if (ch == '[') {
                    val closeBracket = raw.indexOf(']', firstNonWs)
                    if (closeBracket in (firstNonWs + 1)..<end) {
                        val sectionName = raw.substring(firstNonWs + 1, closeBracket).trim().lowercase()
                        val color = sectionColors[sectionName] ?: defaultSectionColor
                        builder.addStyle(
                            SpanStyle(
                                color = color,
                                fontWeight = FontWeight.Bold,
                                background = color.copy(alpha = 0.16f)
                            ),
                            firstNonWs,
                            closeBracket + 1
                        )
                    }
                } else {
                    // Highlight action keywords in rule line
                    val lineSub = raw.substring(firstNonWs, end)
                    highlightWord(builder, lineSub, firstNonWs, "reject", rejectColor)
                    highlightWord(builder, lineSub, firstNonWs, "direct", directColor)
                    highlightWord(builder, lineSub, firstNonWs, "proxy", proxyColor)
                }
            }

            start = end + 1
        }

        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    private fun highlightWord(
        builder: AnnotatedString.Builder,
        line: String,
        lineOffset: Int,
        word: String,
        color: Color
    ) {
        var idx = 0
        while (idx < line.length) {
            val found = line.indexOf(word, idx, ignoreCase = true)
            if (found == -1) break
            val beforeOk = found == 0 || !line[found - 1].isLetterOrDigit()
            val afterIndex = found + word.length
            val afterOk = afterIndex >= line.length || !line[afterIndex].isLetterOrDigit()
            if (beforeOk && afterOk) {
                builder.addStyle(
                    SpanStyle(color = color, fontWeight = FontWeight.SemiBold),
                    lineOffset + found,
                    lineOffset + afterIndex
                )
            }
            idx = afterIndex
        }
    }
}
