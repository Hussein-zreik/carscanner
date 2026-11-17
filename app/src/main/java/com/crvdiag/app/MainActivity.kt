package com.crvdiag.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crvdiag.app.codes.CodesScreen
import com.crvdiag.app.console.ConsoleScreen
import com.crvdiag.app.console.ConsoleViewModel
import com.crvdiag.app.ui.theme.CrvDiagTheme

private enum class Screen { Home, Console, Codes }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrvDiagTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Home) }
                // One connection for the whole app, shared by every screen.
                val connection: ConsoleViewModel = viewModel()
                when (screen) {
                    Screen.Home -> HomeScreen(
                        onOpenConsole = { screen = Screen.Console },
                        onOpenCodes = { screen = Screen.Codes },
                    )
                    Screen.Console -> {
                        BackHandler { screen = Screen.Home }
                        ConsoleScreen(onBack = { screen = Screen.Home }, vm = connection)
                    }
                    Screen.Codes -> {
                        BackHandler { screen = Screen.Home }
                        CodesScreen(
                            connection = connection,
                            onOpenConsole = { screen = Screen.Console },
                            onBack = { screen = Screen.Home },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenConsole: () -> Unit, onOpenCodes: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("CR-V Diag") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("2011 Honda CR-V EX 4WD", style = MaterialTheme.typography.titleMedium)
                    Text("2.4L K24Z6 · 5-speed automatic", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Adapter", style = MaterialTheme.typography.titleMedium)
                    Text("Bluetooth (paired V03H4) or Wi-Fi (192.168.0.10:35000)")
                    Button(onClick = onOpenConsole) { Text("Open adapter console") }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Text("Check-engine light, stored and pending codes, readiness, freeze frame")
                    Button(onClick = onOpenCodes) { Text("Trouble codes & readiness") }
                }
            }
        }
    }
}

@Preview(widthDp = 800, heightDp = 480)
@Composable
private fun HomeScreenPreview() {
    CrvDiagTheme { HomeScreen(onOpenConsole = {}, onOpenCodes = {}) }
}
