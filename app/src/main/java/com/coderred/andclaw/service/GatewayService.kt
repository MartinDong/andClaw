package com.coderred.andclaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.MainActivity
import com.coderred.andclaw.R
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.proot.ProcessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.coderred.andclaw.data.PairingRequest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "andclaw_gateway"
        const val PAIRING_CHANNEL_ID = "andclaw_pairing"
        const val NOTIFICATION_ID = 1
        const val PAIRING_NOTIFICATION_ID = 2
        const val ACTION_START = "com.coderred.andclaw.action.START"
        const val ACTION_STOP = "com.coderred.andclaw.action.STOP"
        const val ACTION_RESTART = "com.coderred.andclaw.action.RESTART"

        private var _instance: GatewayService? = null

        /** 현재 서비스의 ProcessManager. 서비스가 살아있을 때만 non-null. */
        val processManager: ProcessManager?
            get() = _instance?.pm

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var pm: ProcessManager
    private lateinit var prefs: PreferencesManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        _instance = this

        val app = application as AndClawApp
        pm = app.processManager
        prefs = app.preferencesManager

        createNotificationChannel()
        createPairingNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(getString(R.string.notification_waiting)))

        // 상태 변화 → 알림 업데이트
        serviceScope.launch {
            pm.gatewayState.collectLatest { state ->
                val text = when (state.status) {
                    GatewayStatus.RUNNING -> getString(R.string.notification_running, formatUptime(state.uptime))
                    GatewayStatus.STARTING -> getString(R.string.notification_starting)
                    GatewayStatus.STOPPING -> getString(R.string.notification_stopping)
                    GatewayStatus.ERROR -> getString(R.string.notification_error, state.errorMessage?.take(50) ?: getString(R.string.notification_unknown))
                    GatewayStatus.STOPPED -> getString(R.string.notification_stopped)
                }
                updateNotification(text)
            }
        }

        // 페어링 요청 → 푸시 알림
        serviceScope.launch {
            pm.pairingRequests.collect { requests ->
                if (requests.isNotEmpty()) {
                    postPairingNotification(requests)
                } else {
                    cancelPairingNotification()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                // 번들 업데이트 체크 (앱 업데이트 후 첫 실행 시)
                val app = application as AndClawApp
                app.setupManager.updateBundleIfNeeded()
                // DataStore에서 API 설정 읽기
                val apiProvider = runBlocking { prefs.apiProvider.first() }
                val apiKey = runBlocking { prefs.apiKey.first() }
                val selectedModel = runBlocking { prefs.selectedModel.first() }
                val channelConfig = runBlocking { prefs.channelConfig.first() }
                val modelReasoning = runBlocking { prefs.selectedModelReasoning.first() }
                val modelImages = runBlocking { prefs.selectedModelImages.first() }
                val modelContext = runBlocking { prefs.selectedModelContext.first() }
                val modelMaxOutput = runBlocking { prefs.selectedModelMaxOutput.first() }
                val braveSearchApiKey = runBlocking { prefs.braveSearchApiKey.first() }
                pm.start(apiProvider, apiKey, selectedModel, channelConfig, modelReasoning, modelImages, modelContext, modelMaxOutput, braveSearchApiKey)
                // 게이트웨이 실행 상태 기록 (앱 업데이트 후 자동 재시작용)
                runBlocking { prefs.setGatewayWasRunning(true) }
            }
            ACTION_STOP -> {
                pm.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                // 사용자가 명시적으로 중지 → 업데이트 후 자동 재시작 안 함
                runBlocking { prefs.setGatewayWasRunning(false) }
            }
            ACTION_RESTART -> {
                val apiProvider = runBlocking { prefs.apiProvider.first() }
                val apiKey = runBlocking { prefs.apiKey.first() }
                val selectedModel = runBlocking { prefs.selectedModel.first() }
                val channelConfig = runBlocking { prefs.channelConfig.first() }
                val modelReasoning = runBlocking { prefs.selectedModelReasoning.first() }
                val modelImages = runBlocking { prefs.selectedModelImages.first() }
                val modelContext = runBlocking { prefs.selectedModelContext.first() }
                val modelMaxOutput = runBlocking { prefs.selectedModelMaxOutput.first() }
                val braveSearchApiKey = runBlocking { prefs.braveSearchApiKey.first() }
                pm.restart(apiProvider, apiKey, selectedModel, channelConfig, modelReasoning, modelImages, modelContext, modelMaxOutput, braveSearchApiKey)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pm.stop()
        releaseWakeLock()
        serviceScope.cancel()
        _instance = null
        super.onDestroy()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GatewayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("andClaw")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.notification_action_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ── Pairing Notification ──

    private fun createPairingNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PAIRING_CHANNEL_ID,
                getString(R.string.pairing_notification_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for new pairing requests"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun postPairingNotification(requests: List<PairingRequest>) {
        val first = requests.first()
        val channelName = first.channel.replaceFirstChar { it.uppercase() }
        val displayName = if (first.username.isNotBlank()) first.username else first.code
        val text = getString(R.string.pairing_notification_text, channelName, displayName)

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, PAIRING_CHANNEL_ID)
            .setContentTitle(getString(R.string.pairing_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(PAIRING_NOTIFICATION_ID, notification)
    }

    private fun cancelPairingNotification() {
        getSystemService(NotificationManager::class.java)
            .cancel(PAIRING_NOTIFICATION_ID)
    }

    // ── WakeLock ──

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("deprecation")
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "andClaw::GatewayWakeLock"
            ).apply {
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun formatUptime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
