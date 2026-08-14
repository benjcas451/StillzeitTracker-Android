package org.dwarftsch.stillzeit.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.Seite
import java.time.LocalTime

/**
 * Dialog für Menge und Inhalt eines Flaschen-Eintrags (neu oder bearbeiten).
 */
@Composable
fun FlascheDialog(
    bearbeiten: Boolean = false,
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
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text(if (bearbeiten) "Flasche ändern" else "Flasche hinzufügen") },
        text = {
            Column {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    FlaschenArt.entries.forEachIndexed { index, art ->
                        SegmentedButton(
                            selected = flaschenArt == art,
                            onClick = { flaschenArt = art },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = FlaschenArt.entries.size,
                            ),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Text(art.apiValue)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = mengeText,
                    onValueChange = { mengeText = it },
                    shape = MaterialTheme.shapes.medium,
                    colors = mhEingabefeldFarben(),
                    label = { Text("Menge in ml") },
                    suffix = { Text("ml") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = ::speichern, shape = MaterialTheme.shapes.medium) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
    )
}

/** Dialog für die Dauer eines Still-Eintrags (neu oder bearbeiten). */
@Composable
fun DauerDialog(
    seite: Seite,
    bearbeiten: Boolean = false,
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
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text(if (bearbeiten) "Dauer ändern" else "${seite.apiValue} hinzufügen") },
        text = {
            OutlinedTextField(
                value = dauerText,
                onValueChange = { dauerText = it },
                shape = MaterialTheme.shapes.medium,
                colors = mhEingabefeldFarben(),
                label = { Text("Dauer") },
                suffix = { Text("min") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(onClick = ::speichern, shape = MaterialTheme.shapes.medium) { Text("Speichern") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
    )
}

/** Uhrzeit-Auswahl für die Schnell-Eingabe. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeitDialog(
    initial: LocalTime?,
    onAbbrechen: () -> Unit,
    onUebernehmen: (LocalTime) -> Unit,
) {
    val start = initial ?: LocalTime.now()
    val state = rememberTimePickerState(
        initialHour = start.hour,
        initialMinute = start.minute,
        is24Hour = true,
    )
    AlertDialog(
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text("Uhrzeit wählen") },
        text = { TimePicker(state = state) },
        confirmButton = {
            Button(
                onClick = { onUebernehmen(LocalTime.of(state.hour, state.minute)) },
                shape = MaterialTheme.shapes.medium,
            ) {
                Text("Übernehmen")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
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
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onAbbrechen,
        title = { Text("Eintrag löschen?") },
        text = { Text(titel) },
        confirmButton = {
            Button(
                onClick = onLoeschen,
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Löschen") }
        },
        dismissButton = {
            TextButton(
                onClick = onAbbrechen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Abbrechen") }
        },
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
        shape = MaterialTheme.shapes.extraLarge,
        onDismissRequest = onSchliessen,
        title = { Text(titel) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(text)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSchliessen,
                colors = ButtonDefaults.textButtonColors(contentColor = MinzeHonig.farben.gruenText),
            ) { Text("Schließen") }
        },
    )
}
