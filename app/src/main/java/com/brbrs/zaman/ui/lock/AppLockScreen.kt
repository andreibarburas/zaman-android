package com.brbrs.zaman.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.inkMuted

/**
 * Full-screen biometric gate shown in front of the rest of the app (ZamanNavHost)
 * whenever the user has biometric lock enabled and the process hasn't been
 * unlocked yet this session. Same pattern as the rest of the BarburasLab suite:
 * MainActivity is a FragmentActivity so BiometricPrompt has a FragmentManager to attach to.
 */
@Composable
fun AppLockScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var error by remember { mutableStateOf<String?>(null) }

    fun showPrompt() {
        val host = activity ?: return
        val biometricManager = BiometricManager.from(host)
        val canAuthenticate = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuthenticate != BiometricManager.BIOMETRIC_SUCCESS) {
            // No enrolled biometrics/PIN — don't lock the user out of their own calendar.
            onUnlocked()
            return
        }
        val executor = ContextCompat.getMainExecutor(host)
        val prompt = BiometricPrompt(
            host,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onUnlocked()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User-cancelled codes aren't real errors — just leave the gate up.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        error = errString.toString()
                    }
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Zaman")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    LaunchedEffect(Unit) { showPrompt() }

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.padding(bottom = 16.dp))
            Text("Zaman is locked", style = ZamanType.titleMedium)
            error?.let {
                Text(it, style = ZamanType.bodySmall, color = inkMuted(), modifier = Modifier.padding(top = 8.dp))
            }
            Button(onClick = { error = null; showPrompt() }, modifier = Modifier.padding(top = 20.dp)) {
                Text("Unlock")
            }
        }
    }
}
