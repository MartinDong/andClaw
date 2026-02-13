package com.coderred.andclaw.ui.screen.dashboard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.data.ChannelConfig
import com.coderred.andclaw.data.GatewayState
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.data.SessionLogEntry
import com.coderred.andclaw.service.GatewayService
import com.coderred.andclaw.data.OpenRouterModel
import com.coderred.andclaw.data.parseOpenRouterModels
import com.coderred.andclaw.proot.GatewayWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AndClawApp

    val gatewayState: StateFlow<GatewayState> = app.processManager.gatewayState
        .stateIn(viewModelScope, SharingStarted.Eagerly, GatewayState())

    val logLines: StateFlow<List<String>> = app.processManager.logLines
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val apiProvider: StateFlow<String> = app.preferencesManager.apiProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "openrouter")

    val apiKey: StateFlow<String> = app.preferencesManager.apiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val selectedModel: StateFlow<String> = app.preferencesManager.selectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val channelConfig: StateFlow<ChannelConfig> = app.preferencesManager.channelConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelConfig())

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _memoryUsageMb = MutableStateFlow(0L)
    val memoryUsageMb: StateFlow<Long> = _memoryUsageMb.asStateFlow()

    private val _dashboardLoading = MutableStateFlow(false)
    val dashboardLoading: StateFlow<Boolean> = _dashboardLoading.asStateFlow()

    private val _availableModels = MutableStateFlow<List<OpenRouterModel>>(emptyList())
    val availableModels: StateFlow<List<OpenRouterModel>> = _availableModels.asStateFlow()

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels.asStateFlow()

    private val _modelLoadError = MutableStateFlow<String?>(null)
    val modelLoadError: StateFlow<String?> = _modelLoadError.asStateFlow()

    private val _sessionLogs = MutableStateFlow<List<SessionLogEntry>>(emptyList())
    val sessionLogs: StateFlow<List<SessionLogEntry>> = _sessionLogs.asStateFlow()

    private val _isLoadingSessionLogs = MutableStateFlow(false)
    val isLoadingSessionLogs: StateFlow<Boolean> = _isLoadingSessionLogs.asStateFlow()

    val pairingRequests: StateFlow<List<PairingRequest>> = app.processManager.pairingRequests
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── WhatsApp QR ──
    private val _whatsappQrState = MutableStateFlow<WhatsAppQrState>(WhatsAppQrState.Idle)
    val whatsappQrState: StateFlow<WhatsAppQrState> = _whatsappQrState.asStateFlow()

    private var wsClient: GatewayWsClient? = null
    private var whatsappQrJob: Job? = null
    private var restartJob: Job? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                updateBatteryInfo()
                updateMemoryInfo()
                delay(5000)
            }
        }
    }

    fun startGateway() {
        GatewayService.start(getApplication())
    }

    fun stopGateway() {
        GatewayService.stop(getApplication())
    }

    fun restartGateway() {
        stopGateway()
        viewModelScope.launch {
            delay(1000)
            startGateway()
        }
    }

    fun openDashboard(context: Context) {
        try {
            val configFile = java.io.File(app.prootManager.rootfsDir, "root/.openclaw/openclaw.json")
            val token = if (configFile.exists()) {
                val json = org.json.JSONObject(configFile.readText())
                json.optJSONObject("gateway")?.optJSONObject("auth")?.optString("token", "") ?: ""
            } else ""

            val url = if (token.isNotBlank()) {
                "http://localhost:18789/?token=${Uri.encode(token)}"
            } else {
                "http://localhost:18789/"
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:18789/"))
            )
        }
    }

    fun setSelectedModel(model: OpenRouterModel) {
        viewModelScope.launch(Dispatchers.IO) {
            app.preferencesManager.setSelectedModel(model)
            val provider = app.preferencesManager.apiProvider.first()
            app.processManager.ensureOpenClawConfig(
                apiProvider = provider,
                selectedModel = model.id,
                modelReasoning = model.supportsReasoning,
                modelImages = model.supportsImages,
                modelContext = model.contextLength,
                modelMaxOutput = model.maxOutputTokens,
            )
            restartGatewayIfRunning()
        }
    }

    private fun restartGatewayIfRunning(delayMs: Long = 700L) {
        if (!app.processManager.isRunning) return
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            delay(delayMs)
            val context = getApplication<Application>()
            val intent = Intent(context, GatewayService::class.java).apply {
                action = GatewayService.ACTION_RESTART
            }
            context.startForegroundService(intent)
        }
    }

    fun loadSessionLogs() {
        if (_isLoadingSessionLogs.value) return
        _isLoadingSessionLogs.value = true

        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    app.processManager.getSessionLogEntries()
                }
                _sessionLogs.value = entries
            } catch (_: Exception) {
                _sessionLogs.value = emptyList()
            } finally {
                _isLoadingSessionLogs.value = false
            }
        }
    }

    fun fetchModels() {
        if (_isLoadingModels.value) return
        _isLoadingModels.value = true
        _modelLoadError.value = null

        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val url = URL("https://openrouter.ai/api/v1/models")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Accept", "application/json")
                    try {
                        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseOpenRouterModels(body)
                    } finally {
                        conn.disconnect()
                    }
                }
                _availableModels.value = models
            } catch (e: Exception) {
                _modelLoadError.value = e.message
            } finally {
                _isLoadingModels.value = false
            }
        }
    }

    // ── Pairing ──

    fun approvePairing(channel: String, code: String) {
        viewModelScope.launch {
            app.processManager.approvePairing(channel, code)
            app.processManager.refreshPairingRequests()
        }
    }

    fun denyPairing(channel: String, code: String) {
        viewModelScope.launch {
            app.processManager.denyPairing(channel, code)
            app.processManager.refreshPairingRequests()
        }
    }

    // ── WhatsApp QR ──

    fun startWhatsAppQr() {
        whatsappQrJob?.cancel()
        _whatsappQrState.value = WhatsAppQrState.Loading
        whatsappQrJob = viewModelScope.launch {
            try {
                val client = GatewayWsClient(app.prootManager)
                wsClient = client

                val connected = withContext(Dispatchers.IO) { client.connect() }
                if (!connected) {
                    _whatsappQrState.value = WhatsAppQrState.Error("WebSocket connection failed")
                    return@launch
                }

                val qrData = withContext(Dispatchers.IO) { client.startWhatsAppLogin() }
                if (qrData == null) {
                    _whatsappQrState.value = WhatsAppQrState.Error("Failed to get QR code")
                    client.close()
                    return@launch
                }

                val isDataUrl = qrData.startsWith("data:image/")
                _whatsappQrState.value = WhatsAppQrState.QrReady(qrData, isDataUrl)

                // 로그인 완료 대기
                val success = withContext(Dispatchers.IO) { client.waitWhatsAppLogin() }
                if (success) {
                    _whatsappQrState.value = WhatsAppQrState.Connected
                    delay(3000)
                    _whatsappQrState.value = WhatsAppQrState.Idle
                } else {
                    _whatsappQrState.value = WhatsAppQrState.Error("Login timed out")
                }

                client.close()
                wsClient = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _whatsappQrState.value = WhatsAppQrState.Error(e.message ?: "Unknown error")
                wsClient?.close()
                wsClient = null
            }
        }
    }

    fun cancelWhatsAppQr() {
        whatsappQrJob?.cancel()
        whatsappQrJob = null
        wsClient?.close()
        wsClient = null
        _whatsappQrState.value = WhatsAppQrState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        cancelWhatsAppQr()
    }

    private fun updateBatteryInfo() {
        val context: Context = getApplication()
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            _batteryLevel.value = if (scale > 0) (level * 100 / scale) else 0

            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    private fun updateMemoryInfo() {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        _memoryUsageMb.value = usedMem
    }
}

sealed class WhatsAppQrState {
    data object Idle : WhatsAppQrState()
    data object Loading : WhatsAppQrState()
    data class QrReady(val qrData: String, val isDataUrl: Boolean) : WhatsAppQrState()
    data object Connected : WhatsAppQrState()
    data class Error(val message: String) : WhatsAppQrState()
}
