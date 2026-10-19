package com.litter.android.live

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.litter.android.state.AppModel
import com.litter.android.state.SavedServerStore
import com.litter.android.state.hasActiveTurn
import com.litter.android.util.LLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import uniffi.codex_mobile_client.AppInterruptTurnRequest
import uniffi.codex_mobile_client.ApprovalDecisionValue
import uniffi.codex_mobile_client.AppSnapshotRecord

object LiveConnectionController {
    @Volatile
    var appInForeground = false
        private set

    @Volatile
    var activityAlive = false

    /** Set by Disconnect; blocks every automatic start until the user next opens the app. */
    @Volatile
    var userStopped = false
        private set

    @Volatile
    private var backgroundedAt: Long? = null

    @Volatile
    private var serviceGapSinceBackground = false

    /** True when the service kept the connection alive throughout the background interval. */
    val keptAliveThroughBackground: Boolean
        get() = backgroundedAt != null && !serviceGapSinceBackground && LiveConnectionService.isRunning

    private var foregroundWatch: Job? = null

    /** MainActivity.onResume: clear user stop, evaluate the start policy, and watch for new work. */
    fun onActivityResumed(context: Context, model: AppModel, scope: CoroutineScope) {
        appInForeground = true
        userStopped = false
        if (wantRunning(context, model)) ensureRunning(context)

        foregroundWatch?.cancel()
        foregroundWatch = scope.launch {
            model.snapshot.map(::hasWork).distinctUntilChanged().collect { working ->
                if (working && wantRunning(context, model)) ensureRunning(context)
            }
        }
    }

    /** Called after AppLifecycleController.onResume has checked [keptAliveThroughBackground]. */
    fun clearBackgroundTracking() {
        backgroundedAt = null
        serviceGapSinceBackground = false
    }

    /** MainActivity.onPause fallback start; the activity is still eligible to start an FGS. */
    fun onActivityPaused(context: Context, model: AppModel) {
        appInForeground = false
        foregroundWatch?.cancel()
        foregroundWatch = null
        backgroundedAt = System.currentTimeMillis()
        serviceGapSinceBackground = false
        if (wantRunning(context, model)) ensureRunning(context)
        LiveConnectionService.onAppBackgrounded()
        if (!LiveConnectionService.isRunning) serviceGapSinceBackground = true
    }

    fun onServiceDestroyed() {
        if (!appInForeground) serviceGapSinceBackground = true
    }

    /** Notification action Disconnect. */
    fun disconnectFromUser(context: Context) {
        userStopped = true
        context.stopService(Intent(context, LiveConnectionService::class.java))
    }

    /** Interrupt every active turn and decline outstanding approvals. */
    suspend fun stopAgents(context: Context) {
        val model = AppModel.init(context.applicationContext)
        val snapshot = model.snapshot.value ?: return

        snapshot.pendingApprovals.forEach { approval ->
            runCatching {
                model.store.respondToApproval(approval.id, ApprovalDecisionValue.DECLINE)
            }.onFailure { error ->
                LLog.w("LiveConnection", "decline approval ${approval.id} failed: ${error.message}")
            }
        }

        snapshot.threads.forEach { thread ->
            val turnId = thread.activeTurnId?.takeIf(String::isNotBlank) ?: return@forEach
            runCatching {
                model.client.interruptTurn(
                    thread.key.serverId,
                    AppInterruptTurnRequest(threadId = thread.key.threadId, turnId = turnId),
                )
            }.onFailure { error ->
                LLog.w("LiveConnection", "interrupt ${thread.key} failed: ${error.message}")
            }
        }
    }

    fun wantRunning(context: Context, model: AppModel): Boolean = shouldRun(
        mode = LiveConnectionPrefs.mode,
        userStopped = userStopped,
        hasWork = hasWork(model.snapshot.value),
        hasSavedServers = SavedServerStore.remembered(context).isNotEmpty(),
    )

    fun ensureRunning(context: Context): Boolean {
        if (userStopped || LiveConnectionPrefs.mode == Mode.OFF) return false
        if (LiveConnectionService.isRunning) return true
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveConnectionService::class.java),
            )
            true
        } catch (error: Exception) {
            LLog.w("LiveConnection", "could not start live service: ${error.message}")
            false
        }
    }

    private fun hasWork(snapshot: AppSnapshotRecord?): Boolean = snapshot != null &&
        (snapshot.threads.any { it.hasActiveTurn } ||
            snapshot.pendingApprovals.isNotEmpty() ||
            snapshot.pendingUserInputs.isNotEmpty())
}
