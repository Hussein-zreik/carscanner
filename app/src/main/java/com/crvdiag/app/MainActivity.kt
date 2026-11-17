package com.crvdiag.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.crvdiag.app.codes.CodesScreen
import com.crvdiag.app.codes.CodesState
import com.crvdiag.app.codes.CodesViewModel
import com.crvdiag.app.connection.CarLinkState
import com.crvdiag.app.connection.ConnectionScreen
import com.crvdiag.app.connection.ConnectionViewModel
import com.crvdiag.app.connection.SavedLink
import com.crvdiag.app.console.AdvancedScreen
import com.crvdiag.app.dashboard.DashboardScreen
import com.crvdiag.app.dashboard.linkSummary
import com.crvdiag.app.live.LiveDataScreen
import com.crvdiag.app.live.LiveViewModel
import com.crvdiag.app.ui.AppShell
import com.crvdiag.app.ui.Destination
import com.crvdiag.app.ui.theme.CrvDiagTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CrvDiagTheme {
                // Activity-scoped: one connection and one codes state shared by every screen.
                val connection: ConnectionViewModel = viewModel()
                val codes: CodesViewModel = viewModel()
                val live: LiveViewModel = viewModel()
                var current by rememberSaveable { mutableStateOf(Destination.Dashboard) }
                val link by connection.state.collectAsState()
                val carLink by connection.carLink.collectAsState()

                // Read codes automatically once the car link is ready (read-only requests).
                LaunchedEffect(carLink) {
                    if (carLink is CarLinkState.Ready && codes.state.value !is CodesState.Busy) {
                        codes.read(connection.elmOrNull(), carLink, connection.log)
                    }
                }

                if (current != Destination.Dashboard) BackHandler { current = Destination.Dashboard }
                val linkStatus = linkSummary(link, carLink).second
                AppShell(current, { current = it }, linkStatus) {
                    when (current) {
                        Destination.Dashboard -> DashboardScreen(
                            connection, codes,
                            onNavigate = { current = it },
                            onQuickConnect = {
                                // Bluetooth or Android 17+ may need a permission prompt: use the Connection screen.
                                if (connection.savedLink.value is SavedLink.Wifi && android.os.Build.VERSION.SDK_INT < 37) {
                                    connection.connectLast()
                                } else {
                                    current = Destination.Connection
                                }
                            },
                        )
                        Destination.Codes -> CodesScreen(connection, codes, onOpenConnection = { current = Destination.Connection })
                        Destination.Live -> LiveDataScreen(connection, live)
                        Destination.Connection -> ConnectionScreen(connection)
                        Destination.Advanced -> AdvancedScreen(connection)
                    }
                }
            }
        }
    }
}
