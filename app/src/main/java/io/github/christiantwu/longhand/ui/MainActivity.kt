package io.github.christiantwu.longhand.ui

import android.net.Uri
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
                onOpen = { id, at, q ->
                    val args = listOfNotNull(at?.let { "at=$it" }, q?.let { "q=" + Uri.encode(it) })
                    nav.navigate("transcript/$id" + if (args.isEmpty()) "" else args.joinToString("&", prefix = "?"))
                },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(vm, onBack = { nav.popBackStack() }, onCorrections = { nav.navigate("corrections") },
                onLicences = { nav.navigate("licences") })
        }
        composable("corrections") {
            CorrectionsScreen(vm, onBack = { nav.popBackStack() })
        }
        composable("licences") {
            LicencesScreen(onBack = { nav.popBackStack() })
        }
        // From a search result: the matching line's start (-1 for none) and the text searched for.
        composable(
            "transcript/{id}?at={at}&q={q}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("at") { type = NavType.LongType; defaultValue = -1L },
                navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            TranscriptScreen(
                onBack = { nav.popBackStack() },
                onCallsWith = { person ->
                    vm.person.value = person
                    nav.popBackStack("list", inclusive = false)
                },
            )
        }
    }
}
