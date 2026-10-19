package com.litter.android.live

data class ThreadView(
    val serverId: String,
    val threadId: String,
    val title: String,
    val active: Boolean,
    val failed: Boolean,
    val snippet: String?,
    val agentLabel: String,
)

data class ApprovalView(
    val id: String,
    val serverId: String,
    val threadId: String?,
    val kind: String,
    val summary: String,
    val detail: String?,
)

data class InputView(
    val id: String,
    val serverId: String,
    val threadId: String,
    val summary: String,
)

data class WorkView(
    val threads: Map<String, ThreadView>,
    val approvals: Map<String, ApprovalView>,
    val inputs: Map<String, InputView>,
    val activeThreadKey: String?,
)

sealed interface WorkEvent {
    data class TurnFinished(val thread: ThreadView) : WorkEvent
    data class TurnFailed(val thread: ThreadView) : WorkEvent
    data class ApprovalRequested(val approval: ApprovalView, val threadTitle: String?) : WorkEvent
    data class ApprovalResolved(val id: String) : WorkEvent
    data class InputRequested(val input: InputView, val threadTitle: String?) : WorkEvent
    data class InputResolved(val id: String) : WorkEvent
}

fun diffWork(prev: WorkView?, next: WorkView): List<WorkEvent> {
    val events = mutableListOf<WorkEvent>()

    if (prev == null) {
        next.approvals.forEach { (_, approval) ->
            events += WorkEvent.ApprovalRequested(approval, next.threadTitle(approval.serverId, approval.threadId))
        }
        next.inputs.forEach { (_, input) ->
            events += WorkEvent.InputRequested(input, next.threadTitle(input.serverId, input.threadId))
        }
        return events
    }

    prev.threads.forEach { (key, previousThread) ->
        val currentThread = next.threads[key] ?: return@forEach
        if (!previousThread.active || currentThread.active) return@forEach

        events += if (currentThread.failed) {
            WorkEvent.TurnFailed(currentThread)
        } else {
            WorkEvent.TurnFinished(currentThread)
        }
    }

    next.approvals.forEach { (id, approval) ->
        if (id !in prev.approvals) {
            events += WorkEvent.ApprovalRequested(approval, next.threadTitle(approval.serverId, approval.threadId))
        }
    }
    prev.approvals.keys.forEach { id ->
        if (id !in next.approvals) events += WorkEvent.ApprovalResolved(id)
    }

    next.inputs.forEach { (id, input) ->
        if (id !in prev.inputs) {
            events += WorkEvent.InputRequested(input, next.threadTitle(input.serverId, input.threadId))
        }
    }
    prev.inputs.keys.forEach { id ->
        if (id !in next.inputs) events += WorkEvent.InputResolved(id)
    }

    return events
}

private fun WorkView.threadTitle(serverId: String, threadId: String?): String? =
    threadId?.let { threads["$serverId/$it"]?.title }

enum class Mode { OFF, WHILE_WORKING, ALWAYS }

fun shouldRun(mode: Mode, userStopped: Boolean, hasWork: Boolean, hasSavedServers: Boolean): Boolean =
    !userStopped && when (mode) {
        Mode.OFF -> false
        Mode.ALWAYS -> hasSavedServers
        Mode.WHILE_WORKING -> hasWork
    }
