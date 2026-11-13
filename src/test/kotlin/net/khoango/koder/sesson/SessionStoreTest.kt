package net.khoango.koder.session

import net.khoango.koder.agent.AgentState
import net.khoango.koder.domain.Message
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionStoreTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `returns null when there is no saved session`() {
        assertNull(SessionStore.resumeLatest(root))
    }

    @Test
    fun `resumes the most recent session`() {
        SessionStore.newSession(root).save(AgentState(listOf(Message.user("old"))))
        Thread.sleep(5)   // make sure the second file name differs
        val newer = SessionStore.newSession(root)
        newer.save(AgentState(listOf(Message.user("new"))))

        val resumed = assertNotNull(SessionStore.resumeLatest(root))

        assertEquals(newer.file, resumed.store.file)
        assertEquals(listOf(Message.user("new")), resumed.state.history)
    }
}