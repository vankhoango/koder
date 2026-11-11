package net.khoango.koder.tools

internal enum class LineEnding {
    LF, CRLF;

    /** Converts text with "\n" line breaks to this line-ending style. */
    fun applyTo(normalized: String): String =
        if (this == CRLF) normalized.replace("\n", "\r\n") else normalized

    companion object {
        fun detect(text: String): LineEnding = if ("\r\n" in text) CRLF else LF
    }
}

/** Converts all line breaks to "\n". */
internal fun String.normalizeLineEndings(): String = replace("\r\n", "\n")