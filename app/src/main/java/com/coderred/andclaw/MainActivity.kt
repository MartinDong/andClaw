package com.coderred.andclaw

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.coderred.andclaw.auth.OpenRouterAuth
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.ui.navigation.AndClawNavGraph
import com.coderred.andclaw.ui.theme.AndClawTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class StartupBundleUpdateStatus {
    CHECKING,
    UPDATING,
    DONE,
}

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
                    val isSetupCompleteRaw by app.preferencesManager.isSetupComplete
                        .collectAsState(initial = null)
                    val isOnboardingCompleteRaw by app.preferencesManager.isOnboardingComplete
                        .collectAsState(initial = null)

                    if (isSetupCompleteRaw == null || isOnboardingCompleteRaw == null) {
                        startupBundleUpdateScreen(step = SetupStep.CHECKING_PROOT)
                        return@Surface
                    }

                    val isSetupComplete = isSetupCompleteRaw == true
                    val isOnboardingComplete = isOnboardingCompleteRaw == true

                    var startupUpdateStatus by remember(isSetupComplete) {
                        mutableStateOf(
                            if (isSetupComplete) {
                                StartupBundleUpdateStatus.CHECKING
                            } else {
                                StartupBundleUpdateStatus.DONE
                            },
                        )
                    }
                    var startupUpdateStep by remember(isSetupComplete) {
                        mutableStateOf(SetupStep.CHECKING_PROOT)
                    }

                    LaunchedEffect(isSetupComplete) {
                        if (!isSetupComplete) {
                            startupUpdateStatus = StartupBundleUpdateStatus.DONE
                            return@LaunchedEffect
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.CHECKING
                        val needsBundleUpdate = withContext(Dispatchers.IO) {
                            app.setupManager.isBundleUpdateRequired()
                        }

                        if (!needsBundleUpdate) {
                            startupUpdateStatus = StartupBundleUpdateStatus.DONE
                            return@LaunchedEffect
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.UPDATING
                        startupUpdateStep = SetupStep.INSTALLING_TOOLS

                        runCatching {
                            withContext(Dispatchers.IO) {
                                app.setupManager.updateBundleIfNeeded { step ->
                                    runOnUiThread {
                                        startupUpdateStep = step
                                    }
                                }
                            }
                        }.onFailure { error ->
                            Log.e("MainActivity", "Failed to update bundled assets on startup", error)
                        }

                        startupUpdateStatus = StartupBundleUpdateStatus.DONE
                    }

                    if (isSetupComplete && startupUpdateStatus != StartupBundleUpdateStatus.DONE) {
                        val screenStep = when (startupUpdateStatus) {
                            StartupBundleUpdateStatus.CHECKING -> SetupStep.CHECKING_PROOT
                            StartupBundleUpdateStatus.UPDATING -> startupUpdateStep
                            StartupBundleUpdateStatus.DONE -> SetupStep.CHECKING_PROOT
                        }
                        startupBundleUpdateScreen(step = screenStep)
                    } else {
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

@androidx.compose.runtime.Composable
private fun startupBundleUpdateScreen(step: SetupStep) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(step.displayNameRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
