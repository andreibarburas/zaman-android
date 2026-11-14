package com.brbrs.zaman.ui.connect

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.ui.settings.AccountViewModel
import com.brbrs.zaman.ui.settings.LoginUiState
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.inkMuted

/**
 * First-run connect flow: the screen a brand-new user lands on right after onboarding,
 * same role as Tavla's LoginScreen. A full-screen, single-purpose step — not the
 * Settings tab, which a connected user only comes back to in order to manage the
 * connection, not to make it the first time.
 */
@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    var serverInput by remember { mutableStateOf("") }

    // Land here already in "enter a server" mode — no extra tap on a "Connect to
    // Nextcloud" button first, this screen's whole purpose is that one action.
    LaunchedEffect(Unit) {
        if (viewModel.uiState.value is LoginUiState.SignedOut) viewModel.startEnteringServer()
    }
    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.SignedIn) onConnected()
    }
    LaunchedEffect(uiState) {
        (uiState as? LoginUiState.AwaitingApproval)?.browserUrl?.let { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    val awaitingApproval = uiState is LoginUiState.AwaitingApproval
    val busy = isConnecting || awaitingApproval
    val errorMessage = (uiState as? LoginUiState.Error)?.message

    Scaffold { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).statusBarsPadding().imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    "Zaman",
                    style = ZamanType.headlineLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Enter your Nextcloud server address",
                    style = ZamanType.bodyMedium,
                    color = inkMuted(),
                    textAlign = TextAlign.Center,
                )

                OutlinedTextField(
                    value = serverInput,
                    onValueChange = { serverInput = it },
                    label = { Text("Nextcloud URL") },
                    placeholder = { Text("cloud.example.com") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (serverInput.isNotBlank() && !busy) viewModel.connect(serverInput) {}
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )

                AnimatedVisibility(errorMessage != null) {
                    Text(
                        errorMessage.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = ZamanType.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }

                Button(
                    onClick = { viewModel.connect(serverInput) {} },
                    enabled = serverInput.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Connect to Nextcloud", style = ZamanType.titleMedium)
                    }
                }

                if (awaitingApproval) {
                    Text(
                        "Approve Zaman in the browser tab that just opened, then come back here.",
                        style = ZamanType.bodySmall,
                        color = inkMuted(),
                        textAlign = TextAlign.Center,
                    )
                }

                Text(
                    "Zaman talks directly to your own Nextcloud over CalDAV — no third-party " +
                        "sync service, no DAVx5/ICSx bridge.",
                    style = ZamanType.labelSmall,
                    color = inkMuted(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
