package net.khoango.koder.tools

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.PathMatcher
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

internal val IGNORED_DIRECTORIES = setOf(".git", ".gradle", ".idea", ".kotlin", "build", "node_modules", ".venv")

internal fun walkProjectFiles(root: Path, onFile: (Path) -> Unit) {
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
            if (dir != root && dir.name in IGNORED_DIRECTORIES) FileVisitResult.SKIP_SUBTREE
            else FileVisitResult.CONTINUE

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            onFile(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
}

internal fun globMatchers(pattern: String): List<PathMatcher> {
    val fileSystem = FileSystems.getDefault()
    return listOfNotNull(
        fileSystem.getPathMatcher("glob:$pattern"),
        if (pattern.startsWith("**/")) fileSystem.getPathMatcher("glob:${pattern.removePrefix("**/")}") else null,
    )
}