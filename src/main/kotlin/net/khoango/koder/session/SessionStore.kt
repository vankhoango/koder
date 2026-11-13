package net.khoango.koder.session

import kotlinx.serialization.json.Json
import net.khoango.koder.agent.AgentState
import net.khoango.koder.tools.writeAtomically
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class SessionStore private constructor(val file: Path) {

    fun save(state: AgentState) {
        file.parent.createDirectories()
        writeAtomically(file, json.encodeToString(state))
    }

    data class Resumed(val store: SessionStore, val state: AgentState)

    companion object {
        private val FILE_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")
        private val json = Json { prettyPrint = true }

        private fun directory(projectRoot: Path) = projectRoot.resolve(".koder").resolve("sessions")

        /** A new, empty session, named after the current time. */
        fun newSession(projectRoot: Path) =
            SessionStore(directory(projectRoot).resolve(LocalDateTime.now().format(FILE_NAME_FORMAT) + ".json"))

        /**
         * The most recent saved session, or null if there is none.
         * Throws IllegalArgumentException if the file can't be read as a session.
         */
        fun resumeLatest(projectRoot: Path): Resumed? {
            val directory = directory(projectRoot)
            if (!directory.exists()) return null

            val file = directory.listDirectoryEntries("*.json").maxByOrNull { it.name } ?: return null
            val state = json.decodeFromString<AgentState>(file.readText())
            return Resumed(SessionStore(file), state)
        }
    }
}