package net.khoango.koder.tools

import java.nio.file.Path

class FileTracker {

    private val knownFiles = mutableSetOf<Path>()

    fun markKnown(path: Path) {
        knownFiles.add(path)
    }

    fun isKnown(path: Path) = path in knownFiles

    fun clearKnown() = knownFiles.clear()
}