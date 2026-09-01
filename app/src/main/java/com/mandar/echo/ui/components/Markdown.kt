package com.mandar.echo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mandar.echo.ui.theme.EchoTheme

/**
 * Minimal renderer for the subset of Markdown [com.mandar.echo.summary.SummaryEngine]
 * emits: `##` headings, `-` bullets, `**bold**`, `_italic_`. Pulling in a full
 * Markdown library for four constructs would be a poor trade.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val colors = EchoTheme.colors

    Column(modifier) {
        markdown.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(10.dp))

                line.startsWith("## ") -> {
                    Spacer(Modifier.height(14.dp))
                    SectionLabel(line.removePrefix("## "))
                    Spacer(Modifier.height(10.dp))
                }

                line.startsWith("# ") -> {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        line.removePrefix("# "),
                        style = MaterialTheme.typography.headlineMedium,
                        color = colors.foreground,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                line.startsWith("### ") -> {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        line.removePrefix("### "),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.foreground,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                line.startsWith("- ") -> Row(
                    Modifier
                        .padding(bottom = 7.dp)
                        .semantics(mergeDescendants = true) { }
                ) {
                    // A drawn dot rather than a typed glyph. It was an em dash,
                    // which is decoration doing a bullet's job, and it is also
                    // the one character a screen reader reads aloud as "dash"
                    // before the sentence that actually matters.
                    Box(
                        Modifier
                            .padding(top = 9.dp)
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(colors.faint)
                            .clearAndSetSemantics { },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        inline(line.removePrefix("- "), colors.foreground),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.foreground,
                    )
                }

                // Exactly two underscores, so this is a whole-line aside and not a
                // line that merely happens to begin and end with emphasis. The old
                // test matched "_a_ and _b_" and then `trim('_')` stripped both
                // ends while leaving the pair in the middle.
                line.startsWith("_") && line.endsWith("_") && line.length > 2 &&
                    line.count { it == '_' } == 2 -> Text(
                    line.removeSurrounding("_"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.faint,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                else -> Text(
                    inline(line, colors.foreground),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.foreground,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
    }
}

private fun inline(text: String, fg: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val bold = text.indexOf("**", i)
            val italic = text.indexOf("_", i)

            val next = listOf(bold, italic).filter { it >= 0 }.minOrNull()
            if (next == null) {
                append(text.substring(i))
                break
            }
            append(text.substring(i, next))

            if (next == bold) {
                val end = text.indexOf("**", next + 2)
                if (end < 0) { append(text.substring(next)); break }
                withStyleCompat(SpanStyle(fontWeight = FontWeight.SemiBold, color = fg)) {
                    append(text.substring(next + 2, end))
                }
                i = end + 2
            } else {
                val end = text.indexOf("_", next + 1)
                if (end < 0) { append(text.substring(next)); break }
                withStyleCompat(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(next + 1, end))
                }
                i = end + 1
            }
        }
    }

private inline fun androidx.compose.ui.text.AnnotatedString.Builder.withStyleCompat(
    style: SpanStyle,
    block: androidx.compose.ui.text.AnnotatedString.Builder.() -> Unit,
) {
    val index = pushStyle(style)
    try { block() } finally { pop(index) }
}
