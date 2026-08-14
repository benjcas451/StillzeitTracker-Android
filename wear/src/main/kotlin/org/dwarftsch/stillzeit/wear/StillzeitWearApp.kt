package org.dwarftsch.stillzeit.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberPickerState
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// --- Farben (identisch zur Flutter-App) --------------------------------------

private val LinksFarbe = Color(0xFF3182CE)
private val RechtsFarbe = Color(0xFF805AD5)
private val BeidseitigFarbe = Color(0xFF319795)
private val FlascheFarbe = Color(0xFFDD6B20)

private val StillzeitFarben = Colors(
    primary = FlascheFarbe,
    primaryVariant = Color(0xFFB45309),
    secondary = BeidseitigFarbe,
    secondaryVariant = Color(0xFF1F6F6F),
    background = Color.Black,
    surface = Color(0xFF1F1D1B),
    error = Color(0xFFCF6679),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFBFBFBF),
    onError = Color.Black,
)

private fun farbeFuer(seite: String): Color = when (seite) {
    Seite.LINKS -> LinksFarbe
    Seite.RECHTS -> RechtsFarbe
    Seite.BEIDSEITIG -> BeidseitigFarbe
    else -> FlascheFarbe
}

private fun iconFuer(seite: String): Int = when (seite) {
    Seite.LINKS -> R.drawable.ic_links
    Seite.RECHTS -> R.drawable.ic_rechts
    Seite.BEIDSEITIG -> R.drawable.ic_beidseitig
    else -> R.drawable.ic_flasche
}

private val UhrzeitFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

// --- Navigation ---------------------------------------------------------------

private sealed interface Ansicht {
    data object Liste : Ansicht
    data object ZeitWahl : Ansicht
    data object NeueFlasche : Ansicht
    data object Verbindung : Ansicht
    data class Bearbeiten(val eintrag: WatchEntry) : Ansicht
}

@Composable
fun StillzeitWearApp(model: WatchModel) {
    var ansicht by remember { mutableStateOf<Ansicht>(Ansicht.Liste) }
    var gewaehlteZeit by remember { mutableStateOf<LocalTime?>(null) }

    MaterialTheme(colors = StillzeitFarben) {
        // Auf der Uhr löst die Wischgeste nach rechts den Zurück-Befehl aus.
        BackHandler(enabled = ansicht != Ansicht.Liste) { ansicht = Ansicht.Liste }

        when (val aktuell = ansicht) {
            Ansicht.Liste -> ListenAnsicht(
                model = model,
                gewaehlteZeit = gewaehlteZeit,
                onJetzt = { gewaehlteZeit = null },
                onZeitWaehlen = { ansicht = Ansicht.ZeitWahl },
                onSchnellEingabe = { seite ->
                    if (seite == Seite.FLASCHE) {
                        ansicht = Ansicht.NeueFlasche
                    } else {
                        model.anlegen(seite, gewaehlteZeit)
                        gewaehlteZeit = null
                    }
                },
                onBearbeiten = { ansicht = Ansicht.Bearbeiten(it) },
                onVerbindung = { ansicht = Ansicht.Verbindung },
            )

            Ansicht.Verbindung -> VerbindungsAnsicht(
                model = model,
                onFertig = { ansicht = Ansicht.Liste },
            )

            Ansicht.ZeitWahl -> ZeitWahlAnsicht(
                start = gewaehlteZeit ?: LocalTime.now(),
                onUebernehmen = {
                    gewaehlteZeit = it
                    ansicht = Ansicht.Liste
                },
            )

            Ansicht.NeueFlasche -> FlaschenAnsicht(
                titel = "Flasche",
                werte = mengenWerte(ab = 10),
                startMenge = 90,
                startArt = FlaschenArt.PRE,
                onSpeichern = { menge, art ->
                    model.anlegen(
                        seite = Seite.FLASCHE,
                        zeit = gewaehlteZeit,
                        menge = menge,
                        flaschenArt = art,
                    )
                    gewaehlteZeit = null
                    ansicht = Ansicht.Liste
                },
            )

            is Ansicht.Bearbeiten -> {
                val eintrag = aktuell.eintrag
                if (eintrag.istFlasche) {
                    FlaschenAnsicht(
                        titel = eintrag.titel,
                        werte = werteMit(mengenWerte(ab = 0), eintrag.menge ?: 0),
                        startMenge = eintrag.menge ?: 0,
                        startArt = eintrag.flaschenArt ?: FlaschenArt.PRE,
                        onSpeichern = { menge, art ->
                            model.aendern(eintrag, menge, art)
                            ansicht = Ansicht.Liste
                        },
                    )
                } else {
                    DauerAnsicht(
                        titel = eintrag.seite,
                        startDauer = eintrag.dauerMinuten ?: 0,
                        onSpeichern = { minuten ->
                            model.aendern(eintrag, minuten)
                            ansicht = Ansicht.Liste
                        },
                    )
                }
            }
        }
    }
}

/** Mengen in 10-ml-Schritten, wie in der Apple-Watch-App. */
private fun mengenWerte(ab: Int): List<Int> = (ab..300 step 10).toList()

/**
 * Nimmt den gespeicherten Wert mit in die Auswahl auf, falls er nicht ins
 * Raster passt.
 *
 * Auf dem Telefon sind ml und Minuten frei eintippbar — ein Textfeld ohne
 * Schrittweite und ohne Obergrenze. Ohne diesen Schritt ließe sich ein Wert
 * wie 95 ml auf der Uhr gar nicht auswählen und würde beim Speichern
 * verfälscht zurückgeschrieben.
 */
private fun werteMit(raster: List<Int>, aktuell: Int): List<Int> =
    if (aktuell < 0 || raster.contains(aktuell)) raster else (raster + aktuell).sorted()

// --- Hauptansicht -------------------------------------------------------------

@Composable
private fun ListenAnsicht(
    model: WatchModel,
    gewaehlteZeit: LocalTime?,
    onJetzt: () -> Unit,
    onZeitWaehlen: () -> Unit,
    onSchnellEingabe: (String) -> Unit,
    onBearbeiten: (WatchEntry) -> Unit,
    onVerbindung: () -> Unit,
) {
    val listenZustand = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listenZustand) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listenZustand,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { LetzterEintragKarte(model.letzterEintrag) }
            item { ZeitAuswahl(gewaehlteZeit, onJetzt, onZeitWaehlen) }
            item { SchnellEingabe(onSchnellEingabe) }

            if (model.laedt) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
            }

            model.fehler?.let { meldung ->
                item { Verbindungshinweis(meldung, model.laedt, model::aktualisieren) }
            }

            model.hinweis?.let { meldung ->
                item {
                    Text(
                        text = meldung,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    )
                }
            }

            item {
                Text(
                    text = "Letzte Einträge",
                    style = MaterialTheme.typography.title3,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }

            if (model.eintraege.isEmpty()) {
                item {
                    Text(
                        text = "Noch keine Einträge",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
            } else {
                items(model.eintraege, key = { it.id }) { eintrag ->
                    EintragsZeile(eintrag) { onBearbeiten(eintrag) }
                }
            }

            item {
                CompactChip(
                    onClick = model::aktualisieren,
                    enabled = !model.laedt,
                    label = { Text("Aktualisieren") },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_aktualisieren),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // Zeigt, ob die Uhr direkt mit dem Server spricht oder über das
            // Handy geht — und führt zum Übernehmen der Verbindung.
            item {
                CompactChip(
                    onClick = onVerbindung,
                    label = { Text(model.statusText, maxLines = 1) },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_verbindung),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun LetzterEintragKarte(eintrag: WatchEntry?) {
    val farbe = if (eintrag == null) Color.DarkGray else farbeFuer(eintrag.seite)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(farbe.copy(alpha = 0.18f))
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (eintrag == null) {
            Text("Noch kein Eintrag", style = MaterialTheme.typography.title3)
            return@Column
        }
        Text(
            text = "Letzter Eintrag",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconFuer(eintrag.seite)),
                contentDescription = null,
                tint = farbe,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = " ${eintrag.titel}  ${eintrag.zeit.format(UhrzeitFormat)}",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ZeitAuswahl(
    gewaehlteZeit: LocalTime?,
    onJetzt: () -> Unit,
    onZeitWaehlen: () -> Unit,
) {
    val jetztAktiv = gewaehlteZeit == null
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AuswahlKachel(
            text = "Jetzt",
            icon = if (jetztAktiv) R.drawable.ic_jetzt else R.drawable.ic_uhrzeit,
            aktiv = jetztAktiv,
            onClick = onJetzt,
        )
        AuswahlKachel(
            text = gewaehlteZeit?.format(UhrzeitFormat) ?: "Uhrzeit",
            icon = R.drawable.ic_uhrzeit,
            aktiv = !jetztAktiv,
            onClick = onZeitWaehlen,
        )
    }
}

@Composable
private fun RowScope.AuswahlKachel(
    text: String,
    icon: Int,
    aktiv: Boolean,
    onClick: () -> Unit,
) {
    val farbe = if (aktiv) FlascheFarbe else Color.Gray
    Row(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(farbe.copy(alpha = 0.24f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = farbe,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = " $text",
            style = MaterialTheme.typography.caption1,
            maxLines = 1,
        )
    }
}

@Composable
private fun SchnellEingabe(onSchnellEingabe: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AktionsKachel(Seite.LINKS, onSchnellEingabe)
            AktionsKachel(Seite.RECHTS, onSchnellEingabe)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AktionsKachel(Seite.BEIDSEITIG, onSchnellEingabe)
            AktionsKachel(Seite.FLASCHE, onSchnellEingabe)
        }
    }
}

@Composable
private fun RowScope.AktionsKachel(seite: String, onClick: (String) -> Unit) {
    val farbe = farbeFuer(seite)
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(farbe.copy(alpha = 0.24f))
            .clickable { onClick(seite) }
            .padding(vertical = 5.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconFuer(seite)),
            contentDescription = null,
            tint = farbe,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = seite,
            style = MaterialTheme.typography.caption2,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Verbindungshinweis(
    meldung: String,
    laedt: Boolean,
    onErneutVerbinden: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = meldung,
            style = MaterialTheme.typography.caption1,
            color = FlascheFarbe,
            textAlign = TextAlign.Center,
        )
        CompactChip(
            onClick = onErneutVerbinden,
            enabled = !laedt,
            label = { Text("Erneut verbinden") },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun EintragsZeile(eintrag: WatchEntry, onClick: () -> Unit) {
    val farbe = farbeFuer(eintrag.seite)
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.chipColors(
            backgroundColor = MaterialTheme.colors.surface,
            contentColor = MaterialTheme.colors.onSurface,
        ),
        icon = {
            Icon(
                painter = painterResource(iconFuer(eintrag.seite)),
                contentDescription = null,
                tint = farbe,
                modifier = Modifier.size(20.dp),
            )
        },
        label = {
            Text(
                text = eintrag.titel,
                style = MaterialTheme.typography.button,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Zeigt an, dass sich Dauer bzw. Menge hier nachträglich ändern lässt.
            Icon(
                painter = painterResource(R.drawable.ic_bearbeiten),
                contentDescription = "Bearbeiten",
                tint = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
        },
        secondaryLabel = {
            Text(
                text = "${eintrag.zeit.format(UhrzeitFormat)} · ${eintrag.wertText}",
                style = MaterialTheme.typography.caption2,
                maxLines = 1,
            )
        },
    )
}

// --- Server-Verbindung --------------------------------------------------------

/**
 * Übernimmt die auf dem Telefon eingerichtete Server-Verbindung, sodass die
 * Uhr anschließend selbst mit dem Server spricht.
 */
@Composable
private fun VerbindungsAnsicht(model: WatchModel, onFertig: () -> Unit) {
    val listenZustand = rememberScalingLazyListState()
    val verbunden = model.verbindung != null

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listenZustand) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listenZustand,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Server-Verbindung",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                )
            }

            item {
                Text(
                    text = model.statusText,
                    style = MaterialTheme.typography.body2,
                    color = if (verbunden) FlascheFarbe else MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            item {
                Text(
                    text = if (verbunden) {
                        "Anfragen gehen direkt an den Server. Ist er nicht " +
                            "erreichbar, springt die Uhr automatisch auf das Handy um."
                    } else {
                        "Alle Anfragen laufen über das Handy. Ist dort ein " +
                            "Server eingerichtet, kann die Uhr die Verbindung übernehmen."
                    },
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            if (model.laedt) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(34.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    }
                }
            }

            model.fehler?.let { meldung ->
                item {
                    Text(
                        text = meldung,
                        style = MaterialTheme.typography.caption1,
                        color = FlascheFarbe,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }

            item {
                Chip(
                    onClick = model::verbindungImportieren,
                    enabled = !model.laedt,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ChipDefaults.primaryChipColors(backgroundColor = FlascheFarbe),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_importieren),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    label = {
                        Text(
                            text = if (verbunden) "Erneut importieren" else "Verbindung importieren",
                            maxLines = 1,
                        )
                    },
                )
            }

            if (verbunden) {
                item {
                    Chip(
                        onClick = model::verbindungEntfernen,
                        enabled = !model.laedt,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        colors = ChipDefaults.secondaryChipColors(),
                        icon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_entfernen),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("Verbindung entfernen", maxLines = 1) },
                    )
                }
            }

            item {
                CompactChip(
                    onClick = onFertig,
                    label = { Text("Fertig") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
                )
            }
        }
    }
}

// --- Auswahl-Ansichten --------------------------------------------------------

@Composable
private fun ZeitWahlAnsicht(start: LocalTime, onUebernehmen: (LocalTime) -> Unit) {
    val stunden = rememberPickerState(
        initialNumberOfOptions = 24,
        initiallySelectedOption = start.hour,
    )
    val minuten = rememberPickerState(
        initialNumberOfOptions = 60,
        initiallySelectedOption = start.minute,
    )

    AuswahlGeruest(titel = "Uhrzeit", speichernText = "Übernehmen", onSpeichern = {
        onUebernehmen(LocalTime.of(stunden.selectedOption, minuten.selectedOption))
    }) {
        Row(
            modifier = Modifier.fillMaxWidth().height(76.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Picker(
                state = stunden,
                contentDescription = "Stunde",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { index ->
                Text(zweistellig(index), style = MaterialTheme.typography.display3)
            }
            Text(":", style = MaterialTheme.typography.display3)
            Picker(
                state = minuten,
                contentDescription = "Minute",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { index ->
                Text(zweistellig(index), style = MaterialTheme.typography.display3)
            }
        }
    }
}

@Composable
private fun DauerAnsicht(titel: String, startDauer: Int, onSpeichern: (Int) -> Unit) {
    val werte = werteMit((0..120).toList(), startDauer)
    val auswahl = rememberPickerState(
        initialNumberOfOptions = werte.size,
        initiallySelectedOption = werte.indexOf(startDauer).coerceAtLeast(0),
    )

    AuswahlGeruest(titel = titel, speichernText = "Speichern", onSpeichern = {
        onSpeichern(werte[auswahl.selectedOption])
    }) {
        Picker(
            state = auswahl,
            contentDescription = "Dauer in Minuten",
            modifier = Modifier.fillMaxWidth().height(70.dp),
        ) { index ->
            Text("${werte[index]} Min.", style = MaterialTheme.typography.display3)
        }
    }
}

@Composable
private fun FlaschenAnsicht(
    titel: String,
    werte: List<Int>,
    startMenge: Int,
    startArt: String,
    onSpeichern: (Int, String) -> Unit,
) {
    var art by remember { mutableStateOf(startArt) }
    val auswahl = rememberPickerState(
        initialNumberOfOptions = werte.size,
        initiallySelectedOption = werte.indexOf(startMenge).coerceAtLeast(0),
    )

    AuswahlGeruest(titel = titel, speichernText = "Speichern", onSpeichern = {
        onSpeichern(werte[auswahl.selectedOption], art)
    }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FlaschenArt.alle.forEach { moeglich ->
                AuswahlKachel(
                    text = moeglich,
                    icon = R.drawable.ic_flasche,
                    aktiv = art == moeglich,
                    onClick = { art = moeglich },
                )
            }
        }
        Picker(
            state = auswahl,
            contentDescription = "Menge in Millilitern",
            modifier = Modifier.fillMaxWidth().height(50.dp),
        ) { index ->
            Text("${werte[index]} ml", style = MaterialTheme.typography.display3)
        }
    }
}

/** Gemeinsames Gerüst der Auswahl-Ansichten: Titel, Inhalt, Speichern-Button. */
@Composable
private fun AuswahlGeruest(
    titel: String,
    speichernText: String,
    onSpeichern: () -> Unit,
    inhalt: @Composable () -> Unit,
) {
    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = titel,
                style = MaterialTheme.typography.title3,
                maxLines = 1,
            )
            inhalt()
            CompactChip(
                onClick = onSpeichern,
                label = { Text(speichernText, maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(backgroundColor = FlascheFarbe),
            )
        }
    }
}

private fun zweistellig(wert: Int): String = wert.toString().padStart(2, '0')
