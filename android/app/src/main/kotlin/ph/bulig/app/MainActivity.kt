package ph.bulig.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import ph.bulig.app.screens.HomeScreen
import ph.bulig.app.theme.BuligColors
import ph.bulig.app.theme.BuligTheme
import ph.bulig.data.presentation.HomeStateFactory

/**
 * Slice 1: Home only.
 *
 * The state here is a placeholder built by the real [HomeStateFactory] rather
 * than hand-written sample data — so the screen renders exactly what the tested
 * factory produces, and wiring a repository in later changes the inputs without
 * touching this file.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            BuligTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.systemBars),
                    color = BuligColors.Canvas,
                ) {
                    val state = remember {
                        mutableStateOf(
                            HomeStateFactory.build(
                                myReports = emptyList(),
                                carriedForOthers = emptyList(),
                                // Offline is the normal case for this app, so it
                                // is what the first run shows.
                                isOnline = false,
                                isSyncing = false,
                                nearbyPeerCount = 0,
                            )
                        )
                    }

                    HomeScreen(
                        state = state.value,
                        onReportEmergency = { /* slice 2: type picker */ },
                        onOpenMesh = { /* slice 3: mesh status */ },
                        onOpenReport = { /* slice 3: report detail */ },
                    )
                }
            }
        }
    }
}
