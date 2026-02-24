package com.coderred.andclaw.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.coderred.andclaw.data.PreferencesManager
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

        fun schedule(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(MIN_DELAY_MS)
            val pendingIntent = buildPendingIntent(appContext)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
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

                // AlarmReceiver에서 직접 FGS를 시작하지 않고 WorkManager 경유로 복구를 시도한다.
                GatewayWatchdogRecoveryWorker.enqueue(appContext)
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
