package com.coderred.andclaw.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.service.GatewayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = PreferencesManager(context)

        // 자동 시작 설정과 환경 세팅 완료 여부 확인
        val shouldAutoStart = runBlocking {
            prefs.autoStartOnBoot.first() && prefs.isSetupComplete.first()
        }

        if (shouldAutoStart) {
            GatewayService.start(context)
        }
    }
}
