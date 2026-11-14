package com.brbrs.zaman

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.prefs.ThemeMode
import com.brbrs.zaman.ui.navigation.ZamanRootNavHost
import com.brbrs.zaman.ui.settings.DisplayViewModel
import com.brbrs.zaman.data.repo.CalDavSyncWorker
import com.brbrs.zaman.ui.settings.SyncSettingsViewModel
import com.brbrs.zaman.ui.theme.EffectiveTheme
import com.brbrs.zaman.ui.theme.FontPrefs
import com.brbrs.zaman.ui.theme.ZamanTheme
import dagger.hilt.android.AndroidEntryPoint

// MainActivity extends FragmentActivity, not ComponentActivity, required for
// BiometricPrompt (suite-wide rule — see engineering-learnings.md).
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicitly opt into edge-to-edge instead of relying on targetSdk 36's enforcement —
        // that enforcement only actually kicks in on a device running Android 15+; on anything
        // older the window still reserves its own status-bar space the old way, which then
        // doubled up with the statusBarsPadding() every screen applies itself, reproducing the
        // exact oversized top margin that fix was meant to remove. Calling this here makes every
        // screen's own inset handling correct on every supported device (minSdk 26+), not just 15+.
        enableEdgeToEdge()
        setContent {
            // Event reminders (Settings has no separate toggle for this — it's implied by
            // whether the event itself has a VALARM) need POST_NOTIFICATIONS on API 33+.
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* declining just means reminders silently don't post — no further prompt */ }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Settings -> Display: theme, text size, custom font. Read once here at the
            // root so every screen underneath (including the lock screen and onboarding,
            // which load before the user ever reaches Settings) already reflects it.
            val displayViewModel: DisplayViewModel = hiltViewModel()
            val themeMode by displayViewModel.themeMode.collectAsState()
            val textScale by displayViewModel.textScale.collectAsState()
            val customFontEnabled by displayViewModel.customFontEnabled.collectAsState()
            FontPrefs.useCustomFont = customFontEnabled

            // Reschedules the periodic background sync (CalDavSyncWorker) to match the user's
            // own Settings → Sync interval choice. Reading it here instead of a one-shot call in
            // onCreate means: (1) it's correct on every launch even for an existing install that
            // never had this setting before (falls back to SyncInterval.DEFAULT = 15 min, the old
            // hardcoded behavior), and (2) a change made in Settings takes effect without waiting
            // for the next app restart, since this LaunchedEffect re-fires whenever the StateFlow
            // value changes.
            val syncSettingsViewModel: SyncSettingsViewModel = hiltViewModel()
            val syncInterval by syncSettingsViewModel.interval.collectAsState()
            LaunchedEffect(syncInterval) {
                CalDavSyncWorker.enqueuePeriodic(this@MainActivity, syncInterval.minutes)
            }

            val systemDark = isSystemInDarkTheme()
            val effectiveTheme = when (themeMode) {
                ThemeMode.LIGHT -> EffectiveTheme.LIGHT
                ThemeMode.DARK -> EffectiveTheme.DARK
                ThemeMode.OLED -> EffectiveTheme.OLED
                ThemeMode.SYSTEM -> if (systemDark) EffectiveTheme.DARK else EffectiveTheme.LIGHT
            }

            ZamanTheme(effectiveTheme = effectiveTheme, textScaleMultiplier = textScale.multiplier) {
                ZamanRootNavHost()
            }
        }
    }
}
