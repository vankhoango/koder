package net.khoango.koder.tools

import java.nio.file.InvalidPathException
import java.nio.file.Path

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