package com.litter.android.live

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.litter.android.state.AppModel
import com.litter.android.util.LLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import uniffi.codex_mobile_client.ApprovalDecisionValue

class LiveNotificationActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_APPROVE = "com.litter.android.live.APPROVE"
        const val ACTION_APPROVE_SESSION = "com.litter.android.live.APPROVE_SESSION"
        const val ACTION_DENY = "com.litter.android.live.DENY"
        const val ACTION_STOP_AGENTS = "com.litter.android.live.STOP_AGENTS"
        const val ACTION_DISCONNECT = "com.litter.android.live.DISCONNECT"

        const val EXTRA_APPROVAL_ID = "litter.live.approvalId"
        const val EXTRA_SERVER_ID = "litter.live.serverId"
        const val EXTRA_THREAD_ID = "litter.live.threadId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(10_000) {
                    when (intent.action) {
                        ACTION_APPROVE -> respond(context, intent, ApprovalDecisionValue.ACCEPT)
                        ACTION_APPROVE_SESSION -> respond(context, intent, ApprovalDecisionValue.ACCEPT_FOR_SESSION)
                        ACTION_DENY -> respond(context, intent, ApprovalDecisionValue.DECLINE)
                        ACTION_STOP_AGENTS -> LiveConnectionController.stopAgents(context)
                        ACTION_DISCONNECT -> LiveConnectionController.disconnectFromUser(context)
                        else -> Unit
                    }
                }
            } catch (error: Exception) {
                LLog.e("LiveActions", "action ${intent.action} failed", error)
                LiveNotifications.postActionFailed(context, intent)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun respond(context: Context, intent: Intent, decision: ApprovalDecisionValue) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID)
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Approval action is missing its id")
        AppModel.init(context.applicationContext).store.respondToApproval(approvalId, decision)
        LiveNotifications.cancelApproval(context, approvalId)
    }
}
