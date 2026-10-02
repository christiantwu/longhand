package io.github.christiantwu.longhand.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val vm: AppViewModel = viewModel()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // Wait for the first settings read so the right start screen is chosen.
    val loaded = settings ?: return Box(Modifier.fillMaxSize())
    val nav = rememberNavController()
    // Decided once: finishing setup navigates explicitly rather than swapping the graph.
    val start = remember { if (loaded.setupDone) "list" else "setup" }
    NavHost(nav, startDestination = start) {
        composable("setup") {
            SetupScreen(vm, onDone = {
                nav.navigate("list") { popUpTo("setup") { inclusive = true } }
            })
        }
        composable("list") {
            RecordingsScreen(
                vm,
                onOpen = { nav.navigate("transcript/$it") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(vm, onBack = { nav.popBackStack() }, onLicences = { nav.navigate("licences") })
        }
        composable("licences") {
            LicencesScreen(onBack = { nav.popBackStack() })
        }
        composable("transcript/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
            TranscriptScreen(onBack = { nav.popBackStack() })
        }
    }
}
