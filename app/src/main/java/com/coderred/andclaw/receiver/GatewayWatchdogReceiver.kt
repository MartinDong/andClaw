package com.coderred.andclaw.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.service.GatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GatewayWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_WATCHDOG = "com.coderred.andclaw.action.GATEWAY_WATCHDOG"
        private const val REQUEST_CODE = 21001
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val MIN_DELAY_MS = 5_000L

        fun intervalMs(): Long = WATCHDOG_INTERVAL_MS

        fun schedule(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(MIN_DELAY_MS)
            val pendingIntent = buildPendingIntent(appContext)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (canUseExactAlarm(alarmManager)) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(appContext))
        }

        internal fun shouldUseExactAlarm(sdkInt: Int, canScheduleExact: Boolean): Boolean {
            if (sdkInt < Build.VERSION_CODES.M) return false
            if (sdkInt < Build.VERSION_CODES.S) return true
            return canScheduleExact
        }

        private fun canUseExactAlarm(alarmManager: AlarmManager): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    shouldUseExactAlarm(
                        sdkInt = Build.VERSION.SDK_INT,
                        canScheduleExact = alarmManager.canScheduleExactAlarms(),
                    )
                } catch (_: SecurityException) {
                    false
                }
            } else {
                shouldUseExactAlarm(
                    sdkInt = Build.VERSION.SDK_INT,
                    canScheduleExact = true,
                )
            }
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, GatewayWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var shouldKeepRunning = false
            var shouldScheduleNext = true
            try {
                val prefs = PreferencesManager(appContext)
                shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
                if (!shouldKeepRunning) {
                    cancel(appContext)
                    shouldScheduleNext = false
                    return@launch
                }

                val status = GatewayService.processManager?.gatewayState?.value?.status
                val needsRecovery =
                    status == null ||
                        status == GatewayStatus.STOPPED ||
                        status == GatewayStatus.ERROR
                if (needsRecovery) {
                    // AlarmReceiver에서 직접 FGS를 시작하지 않고 WorkManager 경유로 복구를 시도한다.
                    GatewayWatchdogRecoveryWorker.enqueue(appContext)
                }
            } catch (error: Exception) {
                Log.e("GatewayWatchdog", "Watchdog check failed", error)
            } finally {
                if (shouldScheduleNext) {
                    schedule(appContext)
                }
                pendingResult.finish()
            }
        }
    }
}
