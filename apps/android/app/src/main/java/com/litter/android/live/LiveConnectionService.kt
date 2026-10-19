package com.litter.android.live

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.litter.android.state.AppLifecycleController
import com.litter.android.state.AppModel
import com.litter.android.state.SavedServerStore
import com.litter.android.state.hasActiveTurn
import com.litter.android.state.latestAssistantSnippet
import com.litter.android.state.resolvedPreview
import com.litter.android.ui.common.runtimeLabel
import com.litter.android.util.LLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.codex_mobile_client.AppSnapshotRecord
import uniffi.codex_mobile_client.AppThreadSnapshot
import uniffi.codex_mobile_client.ThreadKey

@OptIn(FlowPreview::class)
class LiveConnectionService : LifecycleService() {
    companion object {
        @Volatile
        var isRunning = false
            private set

        const val LINGER_MS = 120_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private const val WAKE_LOCK_RENEWAL_MS = 25 * 60_000L

        @Volatile
        private var instance: LiveConnectionService? = null

        fun onAppBackgrounded() {
            instance?.onAppBackgroundedInternal()
        }
    }

    private lateinit var model: AppModel
    private var prev: WorkView? = null
    private var lingerJob: Job? = null
    private var wakeLockRenewalJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentHasWork = false
    private val turnStartedAt = mutableMapOf<String, Long>()
    private val postedApprovalIds = mutableSetOf<String>()
    private val postedInputIds = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        LiveConnectionPrefs.initialize(applicationContext)
        LiveNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            LiveNotifications.ONGOING_ID,
            LiveNotifications.buildOngoing(
                this,
                title = "Connecting",
                text = "Preparing live connection",
                whenMs = null,
                showStop = false,
                openIntent = null,
            ),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )

        model = AppModel.init(applicationContext)
        model.start()
        lifecycleScope.launch {
            model.snapshot.filterNotNull().map { it.toWorkView() }.collect(::onWork)
        }
        lifecycleScope.launch {
            model.snapshot.filterNotNull().sample(1_500).collect(::updateOngoing)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) {
            // A sticky restart follows process death; restore the persisted mode before reconnecting.
            if (LiveConnectionPrefs.mode == Mode.OFF || SavedServerStore.remembered(this).isEmpty()) {
                stopSelf()
                return START_NOT_STICKY
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    AppLifecycleController().reconnectSavedServers(applicationContext, model)
                }.onFailure { error ->
                    LLog.e("LiveService", "sticky reconnect failed", error)
                }
            }
        }
        return START_STICKY
    }

    private fun onWork(next: WorkView) {
        val events = diffWork(prev, next)
        prev = next
        currentHasWork = next.hasWork()

        events.forEach(::handleEvent)
        updateWakeLock(currentHasWork)
        updateLinger(currentHasWork)
    }

    private fun onAppBackgroundedInternal() {
        if (::model.isInitialized) model.snapshot.value?.let(::updateOngoing)
    }

    private fun handleEvent(event: WorkEvent) {
        when (event) {
            is WorkEvent.ApprovalRequested -> {
                if (!LiveConnectionController.appInForeground) {
                    LiveNotifications.postApproval(this, event.approval, event.threadTitle)
                    postedApprovalIds += event.approval.id
                }
            }
            is WorkEvent.ApprovalResolved -> {
                LiveNotifications.cancelApproval(this, event.id)
                postedApprovalIds -= event.id
            }
            is WorkEvent.InputRequested -> {
                if (!LiveConnectionController.appInForeground) {
                    LiveNotifications.postInput(this, event.input, event.threadTitle)
                    postedInputIds += event.input.id
                }
            }
            is WorkEvent.InputResolved -> {
                LiveNotifications.cancelInput(this, event.id)
                postedInputIds -= event.id
            }
            is WorkEvent.TurnFinished -> {
                if (!LiveConnectionController.appInForeground) {
                    LiveNotifications.postDone(this, event.thread, failed = false)
                }
            }
            is WorkEvent.TurnFailed -> {
                if (!LiveConnectionController.appInForeground) {
                    LiveNotifications.postDone(this, event.thread, failed = true)
                }
            }
        }
    }

    private fun updateOngoing(snapshot: AppSnapshotRecord) {
        val work = snapshot.toWorkView()
        val activeThreads = snapshot.threads.filter { it.hasActiveTurn }
        val activeKeys = activeThreads.mapTo(mutableSetOf()) { threadKey(it.key.serverId, it.key.threadId) }
        val now = System.currentTimeMillis()
        turnStartedAt.keys.retainAll(activeKeys)
        activeKeys.forEach { turnStartedAt.putIfAbsent(it, now) }

        val mostRecentActive = activeThreads.maxByOrNull { it.info.updatedAt ?: 0L }
        val title = when (activeThreads.size) {
            0 -> if (snapshot.pendingApprovals.isNotEmpty() || snapshot.pendingUserInputs.isNotEmpty()) {
                "Waiting for you"
            } else {
                "Connected"
            }
            1 -> "${mostRecentActive?.agentLabel() ?: "Agent"} is working"
            else -> "${activeThreads.size} agents working"
        }
        val text = mostRecentActive?.let { it.latestAssistantSnippet ?: it.resolvedPreview }
            ?.oneLineSnippet()
            ?: snapshot.pendingApprovals.firstOrNull()?.let { approval ->
                work.approvals[approval.id]?.summary
            }
            ?: snapshot.pendingUserInputs.firstOrNull()?.questions?.firstOrNull()?.question
                ?.oneLineSnippet()
            ?: if (activeThreads.isEmpty()) "Agent activity will appear here" else "Working…"
        val whenMs = activeThreads.mapNotNull { threadStartedAt(it.key.serverId, it.key.threadId) }.minOrNull()
        val target = mostRecentActive?.key
            ?: snapshot.activeThread
            ?: snapshot.pendingApprovals.firstNotNullOfOrNull { approval ->
                approval.threadId?.let { threadKeyRecord(approval.serverId, it) }
            }
            ?: snapshot.pendingUserInputs.firstOrNull()?.let { threadKeyRecord(it.serverId, it.threadId) }
        val openIntent = target?.let {
            LiveNotifications.openThreadIntent(this, it.serverId, it.threadId)
        }

        NotificationManagerCompat.from(this).notify(
            LiveNotifications.ONGOING_ID,
            LiveNotifications.buildOngoing(
                this,
                title = title,
                text = text,
                whenMs = whenMs,
                showStop = activeThreads.isNotEmpty(),
                openIntent = openIntent,
            ),
        )

        if (!LiveConnectionController.appInForeground) reconcilePendingNotifications(work)
    }

    private fun reconcilePendingNotifications(work: WorkView) {
        work.approvals.values.forEach { approval ->
            if (postedApprovalIds.add(approval.id)) {
                LiveNotifications.postApproval(
                    this,
                    approval,
                    work.threadTitle(approval.serverId, approval.threadId),
                )
            }
        }
        (postedApprovalIds - work.approvals.keys).forEach { id ->
            LiveNotifications.cancelApproval(this, id)
            postedApprovalIds -= id
        }

        work.inputs.values.forEach { input ->
            if (postedInputIds.add(input.id)) {
                LiveNotifications.postInput(
                    this,
                    input,
                    work.threadTitle(input.serverId, input.threadId),
                )
            }
        }
        (postedInputIds - work.inputs.keys).forEach { id ->
            LiveNotifications.cancelInput(this, id)
            postedInputIds -= id
        }
    }

    private fun updateLinger(hasWork: Boolean) {
        if (hasWork || LiveConnectionPrefs.mode != Mode.WHILE_WORKING) {
            lingerJob?.cancel()
            lingerJob = null
            return
        }
        if (lingerJob?.isActive == true) return

        lingerJob = lifecycleScope.launch {
            delay(LINGER_MS)
            val latestHasWork = model.snapshot.value?.toWorkView()?.hasWork() ?: currentHasWork
            if (!latestHasWork && LiveConnectionPrefs.mode == Mode.WHILE_WORKING) stopSelf()
        }
    }

    private fun updateWakeLock(hasWork: Boolean) {
        if (!hasWork) {
            wakeLockRenewalJob?.cancel()
            wakeLockRenewalJob = null
            releaseWakeLock()
            return
        }

        acquireWakeLock()
        if (wakeLockRenewalJob?.isActive == true) return
        wakeLockRenewalJob = lifecycleScope.launch {
            while (true) {
                delay(WAKE_LOCK_RENEWAL_MS)
                if (!currentHasWork) return@launch
                acquireWakeLock(forceRenewal = true)
            }
        }
    }

    private fun acquireWakeLock(forceRenewal: Boolean = false) {
        val lock = wakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "litter:live")
            .apply { setReferenceCounted(false) }
            .also { wakeLock = it }
        if (forceRenewal && lock.isHeld) lock.release()
        if (!lock.isHeld) lock.acquire(WAKE_LOCK_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { error -> LLog.w("LiveService", "wake lock release failed: ${error.message}") }
        }
    }

    override fun onDestroy() {
        isRunning = false
        if (instance === this) instance = null
        lingerJob?.cancel()
        wakeLockRenewalJob?.cancel()
        releaseWakeLock()
        if (::model.isInitialized) model.stop()
        LiveConnectionController.onServiceDestroyed()

        val closeConnection = !LiveConnectionController.activityAlive ||
            (LiveConnectionController.userStopped && !LiveConnectionController.appInForeground)
        if (closeConnection && ::model.isInitialized) {
            Thread {
                runBlocking {
                    withTimeoutOrNull(2_500) { model.client.shutdownAlleycatEndpoint() }
                }
            }.start()
        }
        super.onDestroy()
    }

    private fun threadStartedAt(serverId: String, threadId: String): Long? =
        turnStartedAt[threadKey(serverId, threadId)]

    private fun threadKey(serverId: String, threadId: String): String = "$serverId/$threadId"

    private fun threadKeyRecord(serverId: String, threadId: String) =
        ThreadKey(serverId = serverId, threadId = threadId)

    private fun AppThreadSnapshot.agentLabel(): String = agentRuntimeKind.runtimeLabel

    private fun WorkView.hasWork(): Boolean =
        threads.values.any { it.active } || approvals.isNotEmpty() || inputs.isNotEmpty()

    private fun WorkView.threadTitle(serverId: String, threadId: String?): String? =
        threadId?.let { threads[threadKey(serverId, it)]?.title }

    private fun String.oneLineSnippet(): String =
        replace('\n', ' ').replace('\r', ' ').trim().let { value ->
            if (value.length > 120) "${value.take(117)}…" else value
        }
}
