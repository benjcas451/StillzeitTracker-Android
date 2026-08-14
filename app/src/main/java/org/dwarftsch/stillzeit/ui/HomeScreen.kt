package org.dwarftsch.stillzeit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Welcher Dialog gerade offen ist. */
private sealed interface DialogZustand {
    data object FlascheNeu : DialogZustand
    data class FlascheBearbeiten(val eintrag: Entry) : DialogZustand
    data class DauerNeu(val seite: Seite) : DialogZustand
    data class DauerBearbeiten(val eintrag: Entry) : DialogZustand
    data object ZeitWahl : DialogZustand
    data class Loeschen(val eintrag: Entry) : DialogZustand
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEinstellungen: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogZustand?>(null) }

    LaunchedEffect(Unit) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }

    fun schnellAnlegen(seite: Seite) {
        when {
            seite.isFlasche -> dialog = DialogZustand.FlascheNeu
            // Bei gewählter Uhrzeit wird die Dauer direkt mit abgefragt.
            state.schnellZeit != null -> dialog = DialogZustand.DauerNeu(seite)
            else -> viewModel.anlegen(seite)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🤱 Stillzeit") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = viewModel::aktualisieren) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                    }
                    IconButton(onClick = onEinstellungen) {
                        Icon(Icons.Filled.Settings, contentDescription = "Einstellungen")
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbar) { daten -> Snackbar(daten) }
        },
    ) { innenAbstand ->
        Box(modifier = Modifier.padding(innenAbstand).fillMaxSize()) {
            when {
                state.laedt && state.eintraege.isEmpty() && state.fehler == null ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                state.fehler != null ->
                    FehlerAnsicht(
                        meldung = state.fehler.orEmpty(),
                        onErneut = viewModel::aktualisieren,
                    )

                else -> PullToRefreshBox(
                    isRefreshing = state.laedt,
                    onRefresh = viewModel::aktualisieren,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Inhalt(
                        state = state,
                        onZeitWaehlen = { dialog = DialogZustand.ZeitWahl },
                        onZeitZuruecksetzen = { viewModel.setzeSchnellZeit(null) },
                        onSchnellAnlegen = ::schnellAnlegen,
                        onBearbeiten = { eintrag ->
                            dialog = if (eintrag.seite.isFlasche) {
                                DialogZustand.FlascheBearbeiten(eintrag)
                            } else {
                                DialogZustand.DauerBearbeiten(eintrag)
                            }
                        },
                        onLoeschen = { dialog = DialogZustand.Loeschen(it) },
                    )
                }
            }
        }
    }

    // --- Dialoge -------------------------------------------------------------

    fun ungueltigeMenge() = viewModel.hinweis("Bitte eine gültige Menge (≥ 0) eingeben.")
    fun ungueltigeDauer() = viewModel.hinweis("Bitte eine gültige Dauer (≥ 0) eingeben.")

    when (val aktuell = dialog) {
        null -> Unit

        DialogZustand.FlascheNeu -> FlascheDialog(
            onAbbrechen = { dialog = null },
            onSpeichern = { menge, art ->
                dialog = null
                viewModel.anlegen(Seite.FLASCHE, menge = menge, flaschenArt = art)
            },
            onUngueltig = ::ungueltigeMenge,
        )

        is DialogZustand.FlascheBearbeiten -> FlascheDialog(
            bearbeiten = true,
            initialMenge = aktuell.eintrag.menge,
            initialFlaschenArt = aktuell.eintrag.flaschenArt,
            onAbbrechen = { dialog = null },
            onSpeichern = { menge, art ->
                dialog = null
                viewModel.flascheAendern(aktuell.eintrag, menge, art)
            },
            onUngueltig = ::ungueltigeMenge,
        )

        is DialogZustand.DauerNeu -> DauerDialog(
            seite = aktuell.seite,
            onAbbrechen = { dialog = null },
            onSpeichern = { dauer ->
                dialog = null
                viewModel.anlegen(aktuell.seite, dauerMinuten = dauer)
            },
            onUngueltig = ::ungueltigeDauer,
        )

        is DialogZustand.DauerBearbeiten -> DauerDialog(
            seite = aktuell.eintrag.seite,
            bearbeiten = true,
            initialDauerMinuten = aktuell.eintrag.dauerMinuten,
            onAbbrechen = { dialog = null },
            onSpeichern = { dauer ->
                dialog = null
                viewModel.dauerAendern(aktuell.eintrag, dauer)
            },
            onUngueltig = ::ungueltigeDauer,
        )

        DialogZustand.ZeitWahl -> ZeitDialog(
            initial = state.schnellZeit,
            onAbbrechen = { dialog = null },
            onUebernehmen = { zeit ->
                dialog = null
                viewModel.setzeSchnellZeit(zeit)
            },
        )

        is DialogZustand.Loeschen -> LoeschDialog(
            titel = "${aktuell.eintrag.titel()} um ${hhmm(aktuell.eintrag.createTime)}",
            onAbbrechen = { dialog = null },
            onLoeschen = {
                dialog = null
                viewModel.loeschen(aktuell.eintrag)
            },
        )
    }
}

// --- Inhalt ------------------------------------------------------------------

@Composable
private fun Inhalt(
    state: HomeUiState,
    onZeitWaehlen: () -> Unit,
    onZeitZuruecksetzen: () -> Unit,
    onSchnellAnlegen: (Seite) -> Unit,
    onBearbeiten: (Entry) -> Unit,
    onLoeschen: (Entry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        state.stats?.let { stats ->
            item(key = "stats") { StatistikKarte(stats) }
        }
        item(key = "zeit") {
            Spacer(Modifier.height(16.dp))
            ZeitAuswahl(
                zeit = state.schnellZeit,
                onWaehlen = onZeitWaehlen,
                onZuruecksetzen = onZeitZuruecksetzen,
            )
            Spacer(Modifier.height(10.dp))
            SchnellEingabe(onAnlegen = onSchnellAnlegen)
            Spacer(Modifier.height(14.dp))
        }

        if (state.eintraege.isEmpty()) {
            item(key = "leer") {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 48.dp)) {
                    Text(
                        "Noch keine Einträge",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        } else {
            var letzterTag: String? = null
            for (eintrag in state.eintraege) {
                val tag = tagesLabel(eintrag.createTime)
                if (tag != letzterTag) {
                    letzterTag = tag
                    item(key = "tag-$tag") { TagesUeberschrift(tag) }
                }
                item(key = "eintrag-${eintrag.id}") {
                    EintragsKachel(
                        eintrag = eintrag,
                        onBearbeiten = { onBearbeiten(eintrag) },
                        onLoeschen = { onLoeschen(eintrag) },
                    )
                }
            }
        }
    }
}

// --- Statistik-Karte ---------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatistikKarte(stats: TodayStats) {
    // Karte: weiß (Dark: Grau-850) mit weichem Schatten, Radius 16 (Guide 6).
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Heute", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatWert("Gesamt", "${stats.gesamt}")
                StatWert("Links", "${stats.links}", Seite.LINKS.statFarbe())
                StatWert("Rechts", "${stats.rechts}", Seite.RECHTS.statFarbe())
                StatWert("Beidseitig", "${stats.beidseitig}", Seite.BEIDSEITIG.statFarbe())
                StatWert("Flasche", "${stats.flasche}", Seite.FLASCHE.statFarbe())
                StatWert("Menge", "${stats.totalMl} ml", Seite.FLASCHE.statFarbe())
                StatWert("Zeit", "${stats.totalMinuten} min", MinzeHonig.farben.gruenText)
            }
        }
    }
}

@Composable
private fun StatWert(label: String, wert: String, farbe: Color? = null) {
    Column {
        Text(
            wert,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = farbe ?: MaterialTheme.colorScheme.onSurface,
        )
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// --- Zeit-Auswahl für die Schnell-Eingabe -------------------------------------

/**
 * Chips "Jetzt" / gewählte Uhrzeit. Gilt für alle Schnell-Eingabe-Buttons;
 * nach dem Speichern eines Eintrags wird auf "Jetzt" zurückgesetzt.
 */
@Composable
private fun ZeitAuswahl(
    zeit: LocalTime?,
    onWaehlen: () -> Unit,
    onZuruecksetzen: () -> Unit,
) {
    // Chips als Pills (radius-full); aktiv = Minze-300-Fläche mit 900er-Text.
    val chipFarben = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = zeit == null,
            onClick = onZuruecksetzen,
            shape = RoundedCornerShape(50),
            colors = chipFarben,
            label = { Text("Jetzt") },
        )
        FilterChip(
            selected = zeit != null,
            onClick = onWaehlen,
            shape = RoundedCornerShape(50),
            colors = chipFarben,
            leadingIcon = if (zeit == null) {
                { Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else {
                null
            },
            label = {
                Text(zeit?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "Zeit eintragen")
            },
        )
    }
}

// --- Schnell-Eingabe -----------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SchnellEingabe(onAnlegen: (Seite) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Seite.entries.forEach { seite ->
            // Kategorie-Buttons nach dem Chip-Muster: Pastellfläche (300) mit
            // 900er-Text derselben Farbe, Radius 12, Höhe 44 (Touch-Minimum).
            Button(
                onClick = { onAnlegen(seite) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = seite.buttonFlaeche(),
                    contentColor = seite.buttonInhalt(),
                ),
            ) {
                Icon(seite.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(seite.apiValue)
            }
        }
    }
}

// --- Eintragsliste -------------------------------------------------------------

@Composable
private fun TagesUeberschrift(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        // Auf Weiß braucht Grün die 700er-Stufe, der Pastellton 300 wäre
        // nicht lesbar (Guide 2.7).
        color = MinzeHonig.farben.sektionsTitel,
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EintragsKachel(
    eintrag: Entry,
    onBearbeiten: () -> Unit,
    onLoeschen: () -> Unit,
) {
    // Wischen nach links fragt – wie in der Flutter-App – erst per Dialog nach;
    // die Kachel springt deshalb immer zurück (confirmValueChange = false).
    val wischZustand = rememberSwipeToDismissBoxState(
        confirmValueChange = { wert ->
            if (wert == SwipeToDismissBoxValue.EndToStart) onLoeschen()
            false
        },
    )
    SwipeToDismissBox(
        state = wischZustand,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar nach dem Hinweis-Muster: zarte 100er-Fläche,
                // Icon in der text-tauglichen 700er-Stufe (Dark: 300er).
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(eintrag.seite.avatarFlaeche(), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(eintrag.seite.icon, contentDescription = null, tint = eintrag.seite.avatarInhalt())
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(eintrag.titel(), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        hhmm(eintrag.createTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (eintrag.seite.isFlasche) {
                        "${eintrag.menge ?: 0} ml"
                    } else {
                        eintrag.dauerMinuten?.let { "$it min" } ?: "offen"
                    },
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onBearbeiten) {
                    Icon(Icons.Filled.Edit, contentDescription = "Bearbeiten", modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onLoeschen) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Eintrag löschen",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

// --- Fehleransicht --------------------------------------------------------------

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(meldung, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onErneut, shape = MaterialTheme.shapes.medium) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Erneut versuchen")
        }
    }
}

// --- Datums-Helfer ---------------------------------------------------------------

internal fun Entry.titel(): String =
    if (seite.isFlasche && flaschenArt != null) "${seite.apiValue} · ${flaschenArt.apiValue}" else seite.apiValue

internal fun hhmm(zeit: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(zeit)

internal fun tagesLabel(zeit: Instant): String {
    val heute = LocalDate.now()
    val tag = zeit.atZone(ZoneId.systemDefault()).toLocalDate()
    return when (heute.toEpochDay() - tag.toEpochDay()) {
        0L -> "Heute"
        1L -> "Gestern"
        else -> tag.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    }
}
