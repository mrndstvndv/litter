package com.litter.android.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.litter.android.MainActivity
import com.litter.android.util.LLog

object LiveNotifications {
    const val ONGOING_ID = 7100

    private const val CHANNEL_LIVE_CONNECTION = "live_connection"
    private const val CHANNEL_AGENT_ATTENTION = "agent_attention"
    private const val CHANNEL_AGENT_DONE = "agent_done"

    private const val TAG_APPROVAL = "approval"
    private const val TAG_INPUT = "input"
    private const val TAG_DONE = "done"
    private const val TAG_ACTION_FAILED = "action_failed"

    private const val ACTION_OPEN_APP = "com.litter.android.live.OPEN_APP"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_LIVE_CONNECTION,
                    "Live connection",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows the live connection to your coding-agent host."
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(
                    CHANNEL_AGENT_ATTENTION,
                    "Agent needs you",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Approval requests and questions from your coding agent."
                },
                NotificationChannel(
                    CHANNEL_AGENT_DONE,
                    "Agent finished",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Reports when a coding-agent turn finishes or fails."
                },
            ),
        )
    }

    /**
     * Builds the foreground service's ongoing notification.
     *
     * @param title Notification title, such as "Claude is working" or "Connected".
     * @param text Current progress snippet or status text.
     * @param whenMs Active turn start time in epoch milliseconds; non-null enables the elapsed-time chronometer.
     * @param showStop Whether to show the confirmation-free "Stop agent" action; Disconnect is always shown.
     * @param openIntent PendingIntent that opens the active thread, or null when no thread is available.
     */
    fun buildOngoing(
        context: Context,
        title: String,
        text: String,
        whenMs: Long?,
        showStop: Boolean,
        openIntent: PendingIntent?,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_LIVE_CONNECTION)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setShowWhen(whenMs != null)
            .setUsesChronometer(whenMs != null)

        if (whenMs != null) builder.setWhen(whenMs)
        if (showStop) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Stop agent",
                actionIntent(context, LiveNotificationActionReceiver.ACTION_STOP_AGENTS, "stop-agents"),
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Disconnect",
            actionIntent(context, LiveNotificationActionReceiver.ACTION_DISCONNECT, "disconnect"),
        )
        return builder.build()
    }

    fun postApproval(context: Context, approval: ApprovalView, threadTitle: String?) {
        if (!canPostAlerts(context)) return
        ensureChannels(context)

        val title = "${threadTitle.displayOrAgent()} needs approval"
        val detail = approval.detail?.takeIf { it.isNotBlank() }
        val content = listOfNotNull(approval.summary, detail).joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_AGENT_ATTENTION)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(title)
            .setContentText(approval.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openThreadOrApp(context, approval.serverId, approval.threadId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        builder.addAction(
            0,
            "Approve",
            approvalActionIntent(context, LiveNotificationActionReceiver.ACTION_APPROVE, approval),
        )
        builder.addAction(
            0,
            "Always",
            approvalActionIntent(context, LiveNotificationActionReceiver.ACTION_APPROVE_SESSION, approval),
        )
        builder.addAction(
            0,
            "Deny",
            approvalActionIntent(context, LiveNotificationActionReceiver.ACTION_DENY, approval),
        )

        NotificationManagerCompat.from(context).notify(
            TAG_APPROVAL,
            notificationId(7200, approval.id),
            builder.build(),
        )
    }

    fun postInput(context: Context, input: InputView, threadTitle: String?) {
        if (!canPostAlerts(context)) return
        ensureChannels(context)

        val notification = NotificationCompat.Builder(context, CHANNEL_AGENT_ATTENTION)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("${threadTitle.displayOrAgent()} has a question")
            .setContentText(input.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(input.summary))
            .setContentIntent(openThreadIntent(context, input.serverId, input.threadId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        NotificationManagerCompat.from(context).notify(
            TAG_INPUT,
            notificationId(7400, input.id),
            notification,
        )
    }

    fun postDone(context: Context, thread: ThreadView, failed: Boolean) {
        if (!canPostAlerts(context)) return
        ensureChannels(context)

        val detail = thread.snippet?.takeIf { it.isNotBlank() }
            ?: if (failed) "Turn failed" else "Turn finished"
        val status = if (failed) "Failed" else "Finished"
        val notification = NotificationCompat.Builder(context, CHANNEL_AGENT_DONE)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(thread.title.ifBlank { "Agent" })
            .setContentText("$status · $detail")
            .setContentIntent(openThreadIntent(context, thread.serverId, thread.threadId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()

        NotificationManagerCompat.from(context).notify(
            TAG_DONE,
            notificationId(7600, "${thread.serverId}/${thread.threadId}"),
            notification,
        )
    }

    fun cancelApproval(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(TAG_APPROVAL, notificationId(7200, id))
    }

    fun cancelInput(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(TAG_INPUT, notificationId(7400, id))
    }

    fun postActionFailed(context: Context, intent: Intent) {
        if (!canPostAlerts(context)) return
        ensureChannels(context)

        val serverId = intent.getStringExtra(LiveNotificationActionReceiver.EXTRA_SERVER_ID)
        val threadId = intent.getStringExtra(LiveNotificationActionReceiver.EXTRA_THREAD_ID)
        val approvalId = intent.getStringExtra(LiveNotificationActionReceiver.EXTRA_APPROVAL_ID)
        val identity = listOfNotNull(intent.action, approvalId, serverId, threadId).joinToString(":")
        val notification = NotificationCompat.Builder(context, CHANNEL_AGENT_ATTENTION)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("Couldn't send")
            .setContentText("Couldn't send — tap to open")
            .setContentIntent(openThreadOrApp(context, serverId, threadId))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        NotificationManagerCompat.from(context).notify(
            TAG_ACTION_FAILED,
            notificationId(7800, identity),
            notification,
        )
    }

    fun openThreadIntent(context: Context, serverId: String, threadId: String): PendingIntent {
        require(serverId.isNotBlank()) { "serverId must not be blank" }
        require(threadId.isNotBlank()) { "threadId must not be blank" }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_NOTIFICATION_SERVER_ID, serverId)
            putExtra(MainActivity.EXTRA_NOTIFICATION_THREAD_ID, threadId)
            data = Uri.parse("litter-live://thread/${Uri.encode(serverId)}/${Uri.encode(threadId)}")
        }
        return PendingIntent.getActivity(
            context,
            requestCode("open-thread:$serverId:$threadId"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openThreadOrApp(context: Context, serverId: String?, threadId: String?): PendingIntent? {
        if (!serverId.isNullOrBlank() && !threadId.isNullOrBlank()) {
            return openThreadIntent(context, serverId, threadId)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data = Uri.parse("litter-live://open")
        }
        return PendingIntent.getActivity(
            context,
            requestCode("open-app"),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun approvalActionIntent(context: Context, action: String, approval: ApprovalView): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode("$action:${approval.id}"),
            Intent(context, LiveNotificationActionReceiver::class.java).apply {
                this.action = action
                putExtra(LiveNotificationActionReceiver.EXTRA_APPROVAL_ID, approval.id)
                putExtra(LiveNotificationActionReceiver.EXTRA_SERVER_ID, approval.serverId)
                approval.threadId?.let {
                    putExtra(LiveNotificationActionReceiver.EXTRA_THREAD_ID, it)
                }
                data = Uri.parse("litter-live-action://$action/${Uri.encode(approval.id)}")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionIntent(context: Context, action: String, identity: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode(identity),
            Intent(context, LiveNotificationActionReceiver::class.java).setAction(action).apply {
                data = Uri.parse("litter-live-action://$identity")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun canPostAlerts(context: Context): Boolean {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) return true
        LLog.w("LiveNotifications", "Notifications are disabled; skipping alert")
        return false
    }

    private fun notificationId(base: Int, key: String): Int = base + (key.hashCode() and 0xFFFF)

    private fun requestCode(identity: String): Int = identity.hashCode() and 0x7FFFFFFF

    private fun String?.displayOrAgent(): String = this?.takeIf { it.isNotBlank() } ?: "Agent"
}
