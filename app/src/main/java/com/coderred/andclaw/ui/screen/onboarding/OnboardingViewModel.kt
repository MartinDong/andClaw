package com.coderred.andclaw.ui.screen.onboarding

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.auth.OpenRouterAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingState(
    val isConnecting: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = (application as AndClawApp).preferencesManager

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    fun launchAuth(context: Context) {
        val authUri = OpenRouterAuth.buildAuthUri(context)

        // Chrome Custom Tab (Google OAuth 허용, WebView는 차단됨)
        try {
            val customTabsIntent = CustomTabsIntent.Builder().build()
            // Chrome이 설치되어 있으면 Chrome 사용 (Firefox에서 커스텀 스킴 리다이렉트 안 됨)
            customTabsIntent.intent.setPackage("com.android.chrome")
            customTabsIntent.launchUrl(context, authUri)
        } catch (_: Exception) {
            // Chrome 없으면 기본 브라우저로 fallback
            try {
                val customTabsIntent = CustomTabsIntent.Builder().build()
                customTabsIntent.launchUrl(context, authUri)
            } catch (_: Exception) {
                // Custom Tab도 안 되면 일반 브라우저
                context.startActivity(Intent(Intent.ACTION_VIEW, authUri))
            }
        }
    }

    fun handleAuthCallback(uri: Uri) {
        val code = OpenRouterAuth.extractCode(uri) ?: return
        _state.value = _state.value.copy(isConnecting = true, error = null)

        viewModelScope.launch {
            try {
                val apiKey = OpenRouterAuth.exchangeCodeForKey(
                    code,
                    getApplication<Application>(),
                )
                prefs.setApiProvider("openrouter")
                prefs.setSelectedModelId("openrouter/free")
                prefs.setApiKey(apiKey)
                prefs.setOnboardingComplete(true)
                _state.value = _state.value.copy(isConnecting = false, isSuccess = true)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isConnecting = false,
                    error = e.message,
                )
            }
        }
    }

    fun saveManualApiKey(apiKey: String) {
        viewModelScope.launch {
            prefs.setApiProvider("openrouter")
            prefs.setSelectedModelId("openrouter/free")
            prefs.setApiKey(apiKey)
            prefs.setOnboardingComplete(true)
            _state.value = _state.value.copy(isSuccess = true)
        }
    }

    fun skip() {
        viewModelScope.launch {
            prefs.setOnboardingComplete(true)
        }
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
