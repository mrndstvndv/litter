package com.litter.android.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveWorkTest {

    @Test
    fun `first emission reports pending approvals and inputs only`() {
        val thread = thread(active = true)
        val approval = approval("approval-1")
        val input = input("input-1")
        val next = work(
            threads = mapOf("server/thread" to thread),
            approvals = mapOf(approval.id to approval),
            inputs = mapOf(input.id to input),
        )

        assertEquals(
            listOf(
                WorkEvent.ApprovalRequested(approval, thread.title),
                WorkEvent.InputRequested(input, thread.title),
            ),
            diffWork(null, next),
        )
    }

    @Test
    fun `active to idle reports turn finished`() {
        val previous = work(threads = mapOf("server/thread" to thread(active = true)))
        val currentThread = thread(active = false)

        assertEquals(
            listOf(WorkEvent.TurnFinished(currentThread)),
            diffWork(previous, work(threads = mapOf("server/thread" to currentThread))),
        )
    }

    @Test
    fun `active to system error reports turn failed`() {
        val previous = work(threads = mapOf("server/thread" to thread(active = true)))
        val failedThread = thread(active = false, failed = true)

        assertEquals(
            listOf(WorkEvent.TurnFailed(failedThread)),
            diffWork(previous, work(threads = mapOf("server/thread" to failedThread))),
        )
    }

    @Test
    fun `disappearing thread is not treated as finished`() {
        val previous = work(threads = mapOf("server/thread" to thread(active = true)))

        assertEquals(emptyList<WorkEvent>(), diffWork(previous, work()))
    }

    @Test
    fun `new approval is requested and removed approval is resolved`() {
        val existing = approval("approval-1")
        val added = approval("approval-2")
        val previous = work(approvals = mapOf(existing.id to existing))
        val current = work(
            threads = mapOf("server/thread" to thread(active = false)),
            approvals = mapOf(existing.id to existing, added.id to added),
        )

        assertEquals(
            listOf(WorkEvent.ApprovalRequested(added, "Thread")),
            diffWork(previous, current),
        )
        assertEquals(
            listOf(WorkEvent.ApprovalResolved(existing.id), WorkEvent.ApprovalResolved(added.id)),
            diffWork(current, work()),
        )
    }

    @Test
    fun `new input is requested and removed input is resolved`() {
        val pending = input("input-1")
        val previous = work()
        val current = work(
            threads = mapOf("server/thread" to thread(active = false)),
            inputs = mapOf(pending.id to pending),
        )

        assertEquals(
            listOf(WorkEvent.InputRequested(pending, "Thread")),
            diffWork(previous, current),
        )
        assertEquals(
            listOf(WorkEvent.InputResolved(pending.id)),
            diffWork(current, work()),
        )
    }

    @Test
    fun `unchanged snapshot has no events`() {
        val current = work(
            threads = mapOf("server/thread" to thread(active = true)),
            approvals = mapOf("approval-1" to approval("approval-1")),
            inputs = mapOf("input-1" to input("input-1")),
        )

        assertEquals(emptyList<WorkEvent>(), diffWork(current, current))
    }

    @Test
    fun `shouldRun matches policy for every combination`() {
        var combinationsTested = 0

        Mode.entries.forEach { mode ->
            listOf(false, true).forEach { userStopped ->
                listOf(false, true).forEach { hasWork ->
                    listOf(false, true).forEach { hasSavedServers ->
                        val expected = !userStopped && when (mode) {
                            Mode.OFF -> false
                            Mode.ALWAYS -> hasSavedServers
                            Mode.WHILE_WORKING -> hasWork
                        }
                        assertEquals(
                            "mode=$mode, userStopped=$userStopped, hasWork=$hasWork, hasSavedServers=$hasSavedServers",
                            expected,
                            shouldRun(mode, userStopped, hasWork, hasSavedServers),
                        )
                        combinationsTested++
                    }
                }
            }
        }

        assertEquals(24, combinationsTested)
    }

    private fun work(
        threads: Map<String, ThreadView> = emptyMap(),
        approvals: Map<String, ApprovalView> = emptyMap(),
        inputs: Map<String, InputView> = emptyMap(),
    ) = WorkView(threads, approvals, inputs, activeThreadKey = null)

    private fun thread(active: Boolean, failed: Boolean = false) = ThreadView(
        serverId = "server",
        threadId = "thread",
        title = "Thread",
        active = active,
        failed = failed,
        snippet = null,
        agentLabel = "Claude",
    )

    private fun approval(id: String) = ApprovalView(
        id = id,
        serverId = "server",
        threadId = "thread",
        kind = "COMMAND",
        summary = "Run: command",
        detail = null,
    )

    private fun input(id: String) = InputView(
        id = id,
        serverId = "server",
        threadId = "thread",
        summary = "Question",
    )
}
