package com.brbrs.zaman.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.MaterialTheme
import com.brbrs.zaman.ui.theme.GlassCard
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.inkMuted

/**
 * First-run welcome. Shown once; a returning user who already finished this (or is
 * already connected) is bounced straight to [onContinue] instead, same pattern as
 * Tavla's OnboardingScreen. [onGetStarted] (defaults to [onContinue]) is where an
 * actual first-time "Get started" tap goes — for Zaman that's the connect flow.
 */
@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    onGetStarted: () -> Unit = onContinue,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val done by viewModel.onboardingDone.collectAsState()
    val isLoggedIn by viewModel.isLoggedIn.collectAsState()
    val skip = done == true || isLoggedIn == true

    LaunchedEffect(skip) { if (skip) onContinue() }
    if (skip || done == null || isLoggedIn == null) return // blank for one frame rather than flashing content that's about to be skipped

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                "Zaman",
                style = ZamanType.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Your Nextcloud calendar, made for your phone.",
                style = ZamanType.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Calendars, Koto's tasks and Tavla's due dates, all in one agenda, " +
                    "talking directly to your own Nextcloud.",
                style = ZamanType.bodyLarge,
                color = inkMuted(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OnboardingPoint(
                    Icons.Outlined.CloudOff, "Your calendars, your server",
                    "Zaman talks directly to Nextcloud over CalDAV — no DAVx5/ICSx bridge, no third-party sync service.",
                )
                OnboardingPoint(
                    Icons.Outlined.Link, "Works with the rest of the suite",
                    "Koto's due tasks and Tavla's card due-dates show up right in your agenda, read-only, from the same account.",
                )
                OnboardingPoint(
                    Icons.Outlined.Palette, "Full read/write",
                    "Toggle calendars and subscriptions on or off, and create, edit or delete events from Zaman itself.",
                )
                OnboardingPoint(
                    Icons.Outlined.Fingerprint, "Open source & private",
                    "Free, GPLv3 and ad-free. Optional biometric app lock keeps your calendar to yourself.",
                )
            }

            Spacer(Modifier.height(36.dp))

            Button(
                onClick = { viewModel.markDone(); onGetStarted() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) {
                Text("Get started", style = ZamanType.titleMedium)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OnboardingPoint(icon: ImageVector, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = ZamanType.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(body, style = ZamanType.bodySmall, color = inkMuted())
                }
            }
        }
    }
}
