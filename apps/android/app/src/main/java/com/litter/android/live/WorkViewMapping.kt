package com.litter.android.live

import com.litter.android.state.displayTitle
import com.litter.android.state.hasActiveTurn
import com.litter.android.state.latestAssistantSnippet
import com.litter.android.state.resolvedPreview
import com.litter.android.ui.common.runtimeLabel
import uniffi.codex_mobile_client.AppSnapshotRecord
import uniffi.codex_mobile_client.ApprovalKind
import uniffi.codex_mobile_client.ThreadSummaryStatus

fun AppSnapshotRecord.toWorkView(): WorkView {
    val threadViews = threads.associate { thread ->
        val key = "${thread.key.serverId}/${thread.key.threadId}"
        key to ThreadView(
            serverId = thread.key.serverId,
            threadId = thread.key.threadId,
            title = thread.displayTitle,
            active = thread.hasActiveTurn,
            failed = thread.info.status == ThreadSummaryStatus.SYSTEM_ERROR,
            snippet = thread.latestAssistantSnippet ?: thread.resolvedPreview,
            agentLabel = thread.agentRuntimeKind.runtimeLabel,
        )
    }
    val approvalViews = pendingApprovals.associate { approval ->
        val kind = when (approval.kind) {
            ApprovalKind.COMMAND -> "COMMAND"
            ApprovalKind.FILE_CHANGE -> "FILE_CHANGE"
            ApprovalKind.PERMISSIONS -> "PERMISSIONS"
            ApprovalKind.MCP_ELICITATION -> "MCP_ELICITATION"
        }
        val summary = when (approval.kind) {
            ApprovalKind.COMMAND -> "Run: ${approval.command ?: "command"}"
            ApprovalKind.FILE_CHANGE -> "Edit: ${approval.path ?: "files"}"
            ApprovalKind.PERMISSIONS -> "Permission request"
            ApprovalKind.MCP_ELICITATION -> "Tool needs input"
        }
        approval.id to ApprovalView(
            id = approval.id,
            serverId = approval.serverId,
            threadId = approval.threadId,
            kind = kind,
            summary = summary,
            detail = approval.reason ?: approval.cwd,
        )
    }
    val inputViews = pendingUserInputs.associate { input ->
        input.id to InputView(
            id = input.id,
            serverId = input.serverId,
            threadId = input.threadId,
            summary = input.questions.firstOrNull()?.question
                ?.takeIf(String::isNotBlank)
                ?: "Agent needs your input",
        )
    }

    return WorkView(
        threads = threadViews,
        approvals = approvalViews,
        inputs = inputViews,
        activeThreadKey = activeThread?.let { "${it.serverId}/${it.threadId}" },
    )
}
