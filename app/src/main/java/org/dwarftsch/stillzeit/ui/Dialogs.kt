package org.dwarftsch.stillzeit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import java.time.LocalTime

// Dialoge nach Minze & Honig 2.0: neutrale Fläche, Titel H2, Felder mit
// Label darüber, Aktionen als Text (Abbrechen leise, Löschen in Rot).

/** Dialog für Menge und Inhalt eines Flaschen-Eintrags. */
@Composable
fun FlascheDialog(
    initialMenge: Int? = null,
    initialFlaschenArt: FlaschenArt? = null,
    onAbbrechen: () -> Unit,
    onSpeichern: (menge: Int, flaschenArt: FlaschenArt) -> Unit,
    onUngueltig: () -> Unit,
) {
    var mengeText by remember { mutableStateOf(initialMenge?.toString() ?: "") }
    var flaschenArt by remember { mutableStateOf(initialFlaschenArt ?: FlaschenArt.PRE) }

    fun speichern() {
        val menge = mengeText.trim().toIntOrNull()
        if (menge == null || menge < 0) {
            onUngueltig()
            return
        }
        onSpeichern(menge, flaschenArt)
    }

    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text("Flasche ändern", style = MhTypo.h2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand4)) {
                MhFeld("Art") {
                    Row(horizontalArrangement = Arrangement.spacedBy(Mh.abstand2)) {
                        FlaschenArt.entries.forEach { art ->
                            MhAuswahl(
                                text = art.apiValue,
                                gewaehlt = flaschenArt == art,
                                onClick = { flaschenArt = art },
                                modifier = Modifier.widthIn(min = 72.dp),
                            )
                        }
                    }
                }
                MengenFeld(mengeText, "ml") { mengeText = it }
            }
        },
        confirmButton = { MhTextButton("Speichern", ::speichern) },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen, farbe = Mh.farben.textSecondary) },
    )
}

/**
 * Dialog für die Menge eines Brei-/Wasser-Eintrags. Wie der Flaschen-Dialog,
 * nur ohne Pre/Mutter-Auswahl; die Einheit kommt aus der Seite (Brei in g,
 * Wasser in ml).
 */
@Composable
fun MengeDialog(
    seite: Seite,
    initialMenge: Int? = null,
    onAbbrechen: () -> Unit,
    onSpeichern: (menge: Int) -> Unit,
    onUngueltig: () -> Unit,
) {
    var mengeText by remember { mutableStateOf(initialMenge?.toString() ?: "") }
    val einheit = seite.mengenEinheit ?: "ml"

    fun speichern() {
        val menge = mengeText.trim().toIntOrNull()
        if (menge == null || menge < 0) {
            onUngueltig()
            return
        }
        onSpeichern(menge)
    }

    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text("${seite.apiValue}: Menge ändern", style = MhTypo.h2) },
        text = { MengenFeld(mengeText, einheit) { mengeText = it } },
        confirmButton = { MhTextButton("Speichern", ::speichern) },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen, farbe = Mh.farben.textSecondary) },
    )
}

/** Dialog für die Dauer eines Still-Eintrags. */
@Composable
fun DauerDialog(
    seite: Seite,
    initialDauerMinuten: Int? = null,
    onAbbrechen: () -> Unit,
    onSpeichern: (dauerMinuten: Int) -> Unit,
    onUngueltig: () -> Unit,
) {
    var dauerText by remember { mutableStateOf(initialDauerMinuten?.toString() ?: "") }

    fun speichern() {
        val dauer = dauerText.trim().toIntOrNull()
        if (dauer == null || dauer < 0) {
            onUngueltig()
            return
        }
        onSpeichern(dauer)
    }

    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text("${seite.apiValue}: Dauer ändern", style = MhTypo.h2) },
        text = {
            MhFeld("Dauer (Minuten)") {
                ZahlenFeld(dauerText, "min") { dauerText = it }
            }
        },
        confirmButton = { MhTextButton("Speichern", ::speichern) },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen, farbe = Mh.farben.textSecondary) },
    )
}

@Composable
private fun MengenFeld(wert: String, einheit: String, onAenderung: (String) -> Unit) {
    MhFeld("Menge ($einheit)") {
        ZahlenFeld(wert, einheit, onAenderung)
    }
}

/** Zahleneingabe mit Einheit rechts im Feld (Nebentextfarbe). */
@Composable
private fun ZahlenFeld(wert: String, einheit: String, onAenderung: (String) -> Unit) {
    OutlinedTextField(
        value = wert,
        onValueChange = onAenderung,
        suffix = { Text(einheit, style = MhTypo.body) },
        singleLine = true,
        textStyle = MhTypo.body,
        colors = mhFeldFarben(),
        shape = MhFeldForm,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Uhrzeit-Auswahl für einen neuen Eintrag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeitDialog(
    initial: LocalTime?,
    onAbbrechen: () -> Unit,
    onUebernehmen: (LocalTime) -> Unit,
) {
    val f = Mh.farben
    val start = initial ?: LocalTime.now()
    val state = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text("Uhrzeit wählen", style = MhTypo.h2) },
        text = {
            // Die großen Ziffern der Zeitauswahl dünn (400) wie alle großen
            // Zahlen; Material nimmt dafür displayLarge.
            MaterialTheme(
                typography = MaterialTheme.typography.copy(
                    displayLarge = MhTypo.number.copy(fontSize = 44.sp),
                ),
            ) {
                TimePicker(
                    state = state,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = f.surfaceSubtle,
                        clockDialSelectedContentColor = f.onPrimary,
                        clockDialUnselectedContentColor = f.text,
                        selectorColor = f.primary,
                        containerColor = f.surfaceRaised,
                        timeSelectorSelectedContainerColor = f.selectedBg,
                        timeSelectorUnselectedContainerColor = f.surfaceSubtle,
                        timeSelectorSelectedContentColor = f.onSelected,
                        timeSelectorUnselectedContentColor = f.text,
                    ),
                )
            }
        },
        confirmButton = {
            MhTextButton("Übernehmen", { onUebernehmen(LocalTime.of(state.hour, state.minute)) })
        },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen, farbe = Mh.farben.textSecondary) },
    )
}

/** Bestätigung vor dem Löschen eines Eintrags. */
@Composable
fun LoeschDialog(
    titel: String,
    onAbbrechen: () -> Unit,
    onLoeschen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAbbrechen,
        title = { Text("Eintrag löschen", style = MhTypo.h2) },
        text = { Text(titel, style = MhTypo.body) },
        confirmButton = { MhLoeschenButton("Löschen", onLoeschen) },
        dismissButton = { MhTextButton("Abbrechen", onAbbrechen, farbe = Mh.farben.textSecondary) },
    )
}

/** Scrollbarer Info-Dialog (Aufbau API / Aufbau Datenbank). */
@Composable
fun InfoDialog(
    titel: String,
    text: String,
    onSchliessen: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSchliessen,
        title = { Text(titel, style = MhTypo.h2) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text, style = MhTypo.small)
            }
        },
        confirmButton = { MhTextButton("Schließen", onSchliessen) },
    )
}
