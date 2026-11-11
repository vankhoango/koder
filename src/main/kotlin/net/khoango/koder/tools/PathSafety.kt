package net.khoango.koder.tools

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Resolves [relativePath] against this project root.
 * Returns null if the result would be outside the root, or if the path is invalid.
 */
internal fun Path.resolveInside(relativePath: String): Path? {
    val root = toAbsolutePath().normalize()
    val target = try {
        root.resolve(relativePath).normalize()
    } catch (e: InvalidPathException) {
        return null
    }
    return if (target.startsWith(root)) target else null
}

internal fun writeAtomically(file: Path, content: String) {
    val directory = requireNotNull(file.parent) { "Cannot write to a root directory: $file" }
    val temp = createTempFile(directory, ".${file.name}.", ".tmp")
    try {
        temp.writeText(content)
        try {
            temp.moveTo(file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            temp.moveTo(file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temp.deleteIfExists()
    }
}