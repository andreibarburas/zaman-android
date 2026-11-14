package com.brbrs.zaman.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.brbrs.zaman.ui.connect.ConnectScreen
import com.brbrs.zaman.ui.lock.AppLockScreen
import com.brbrs.zaman.ui.lock.AppLockViewModel
import com.brbrs.zaman.ui.onboarding.OnboardingScreen

private object RootRoute {
    const val ONBOARDING = "onboarding"
    const val CONNECT = "connect"
    const val MAIN = "main"
}

/**
 * The app's actual entry graph: onboarding (self-skipping for returning/connected
 * users, same as Tavla) -> a dedicated first-run connect step (not buried in the
 * Settings tab) -> the normal tabbed app, gated by the optional biometric lock.
 */
@Composable
fun ZamanRootNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = RootRoute.ONBOARDING) {
        composable(RootRoute.ONBOARDING) {
            OnboardingScreen(
                onContinue = {
                    navController.navigate(RootRoute.MAIN) {
                        popUpTo(RootRoute.ONBOARDING) { inclusive = true }
                    }
                },
                onGetStarted = {
                    navController.navigate(RootRoute.CONNECT) {
                        popUpTo(RootRoute.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(RootRoute.CONNECT) {
            ConnectScreen(
                onConnected = {
                    navController.navigate(RootRoute.MAIN) {
                        popUpTo(RootRoute.CONNECT) { inclusive = true }
                    }
                },
            )
        }
        composable(RootRoute.MAIN) {
            GatedMain()
        }
    }
}

/** AppLock gate in front of the normal tabbed app, same as before — just relocated here. */
@Composable
private fun GatedMain() {
    val lockViewModel: AppLockViewModel = hiltViewModel()
    val lockEnabled by lockViewModel.lockEnabled.collectAsState()
    val unlocked by lockViewModel.unlocked.collectAsState()

    // Re-lock whenever the app leaves the foreground, same as the rest
    // of the suite's biometric-gated screens.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                lockViewModel.markLocked()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (lockEnabled && !unlocked) {
        AppLockScreen(onUnlocked = { lockViewModel.markUnlocked() })
    } else {
        ZamanNavHost()
    }
}
