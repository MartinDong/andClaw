package com.coderred.andclaw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.coderred.andclaw.auth.OpenRouterAuth
import com.coderred.andclaw.ui.navigation.AndClawNavGraph
import com.coderred.andclaw.ui.theme.AndClawTheme

class MainActivity : ComponentActivity() {

    private var authCallbackUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleDeepLink(intent)

        val app = application as AndClawApp

        setContent {
            AndClawTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val isSetupComplete by app.preferencesManager.isSetupComplete
                        .collectAsState(initial = false)
                    val isOnboardingComplete by app.preferencesManager.isOnboardingComplete
                        .collectAsState(initial = false)

                    AndClawNavGraph(
                        navController = navController,
                        isSetupComplete = isSetupComplete,
                        isOnboardingComplete = isOnboardingComplete,
                        authCallbackUri = authCallbackUri,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == OpenRouterAuth.CALLBACK_SCHEME &&
            uri.host == OpenRouterAuth.CALLBACK_HOST
        ) {
            authCallbackUri = uri
        }
    }
}
