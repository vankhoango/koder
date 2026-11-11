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