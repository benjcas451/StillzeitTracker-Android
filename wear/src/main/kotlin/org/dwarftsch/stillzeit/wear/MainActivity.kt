package org.dwarftsch.stillzeit.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {

    private lateinit var model: WatchModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = WatchModel(this)
        setContent { StillzeitWearApp(model) }
    }

    /**
     * Beim Öffnen und nach jeder Rückkehr in den Vordergrund neu laden — die
     * Uhr cached nichts über die Sitzung hinaus.
     */
    override fun onResume() {
        super.onResume()
        model.aktualisieren()
    }

    override fun onDestroy() {
        model.schliessen()
        super.onDestroy()
    }
}
