package org.dwarftsch.stillzeit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.dwarftsch.stillzeit.ui.HomeScreen
import org.dwarftsch.stillzeit.ui.HomeViewModel
import org.dwarftsch.stillzeit.ui.SettingsScreen
import org.dwarftsch.stillzeit.ui.StillzeitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StillzeitTheme {
                val viewModel: HomeViewModel = viewModel()
                var zeigeEinstellungen by remember { mutableStateOf(false) }

                if (zeigeEinstellungen) {
                    // Beim Verlassen der Einstellungen die (womöglich neue)
                    // Datenquelle übernehmen — wie in der Flutter-App.
                    BackHandler {
                        zeigeEinstellungen = false
                        viewModel.datenquelleNeuAufbauen()
                    }
                    SettingsScreen(
                        settings = viewModel.settings,
                        certSource = viewModel.certSource,
                        onZurueck = {
                            zeigeEinstellungen = false
                            viewModel.datenquelleNeuAufbauen()
                        },
                    )
                } else {
                    HomeScreen(
                        viewModel = viewModel,
                        onEinstellungen = { zeigeEinstellungen = true },
                    )
                }
            }
        }
    }
}
