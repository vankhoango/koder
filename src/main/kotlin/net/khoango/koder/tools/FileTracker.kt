package net.khoango.koder.tools

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

enum class FileStatus {
    NOT_READ,
    CHANGED,
    UP_TO_DATE,
}

class FileTracker {

    private val fingerprints = mutableMapOf<Path, String>()

    fun markKnown(path: Path, content: ByteArray) {
        fingerprints[path] = fingerprint(content)
    }

    fun statusOf(path: Path, content: ByteArray): FileStatus {
        val known = fingerprints[path] ?: return FileStatus.NOT_READ
        return if (known == fingerprint(content)) FileStatus.UP_TO_DATE else FileStatus.CHANGED
    }

    fun isKnown(path: Path) = path in fingerprints

    fun clearKnown() = fingerprints.clear()

    private fun fingerprint(content: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
}

/**
 * Returns an error if the model may not modify [file] based on what it has seen,
 * or null if the modification is allowed.
 */
internal fun FileTracker.refusalFor(path: String, file: Path, currentContent: ByteArray): ToolOutput? =
    when (statusOf(file, currentContent)) {
        FileStatus.NOT_READ -> failure(
            "'$path' already exists, and you haven't read it completely in this conversation. " +
                    "Read it with read_file first."
        )
        FileStatus.CHANGED -> failure(
            "'$path' has changed since you last read it, probably edited by the user or another program. " +
                    "Read it again with read_file, and keep those changes."
        )
        FileStatus.UP_TO_DATE -> null
    }