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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.dwarftsch.stillzeit.Entry
import org.dwarftsch.stillzeit.FlaschenArt
import org.dwarftsch.stillzeit.R
import org.dwarftsch.stillzeit.Seite
import org.dwarftsch.stillzeit.TodayStats
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Welcher Dialog gerade offen ist. */
private sealed interface DialogZustand {
    data class FlascheBearbeiten(val eintrag: Entry) : DialogZustand
    data class DauerBearbeiten(val eintrag: Entry) : DialogZustand
    data class MengeBearbeiten(val eintrag: Entry) : DialogZustand
    data object ZeitWahl : DialogZustand
    data class Loeschen(val eintrag: Entry) : DialogZustand
}

/**
 * Startseite wie im Web (Minze & Honig 2.0): Kopf mit Titel und Symbolen,
 * darunter „Heute“ als Statistik, das Formular „Neuer Eintrag“ mit genau
 * einer Hauptaktion und die Einträge als Liste mit Haarlinien.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEinstellungen: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogZustand?>(null) }
    val fokus = LocalFocusManager.current

    // Formular „Neuer Eintrag“ – auf Bildschirmebene gehalten, damit es beim
    // Scrollen der Liste und beim Drehen erhalten bleibt.
    var seite by rememberSaveable { mutableStateOf<Seite?>(null) }
    var mengeText by rememberSaveable { mutableStateOf("") }
    var flaschenArt by rememberSaveable { mutableStateOf(FlaschenArt.PRE) }
    var dauerText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.meldungen.collect { snackbar.showSnackbar(it) }
    }

    // Brei/Wasser abgeschaltet, während eine der beiden Seiten gewählt war.
    LaunchedEffect(state.breiWasserAktiv) {
        if (!state.breiWasserAktiv && seite?.istBreiWasser == true) seite = null
    }

    fun ungueltigeMenge() = viewModel.hinweis("Bitte eine gültige Menge eingeben (0 oder mehr)")
    fun ungueltigeDauer() = viewModel.hinweis("Bitte eine gültige Dauer eingeben (0 oder mehr)")

    /**
     * Legt den Eintrag aus dem Formular an. Dieselben Aufrufe wie bisher die
     * Schnell-Eingabe samt Dialogen: Flasche mit Menge und Art, Brei/Wasser
     * mit Menge, Stillen optional mit Dauer.
     */
    fun anlegen() {
        val gewaehlt = seite ?: run {
            viewModel.hinweis("Bitte eine Seite auswählen")
            return
        }
        if (gewaehlt.hatMenge) {
            val menge = mengeText.trim().toIntOrNull()
            if (menge == null || menge < 0) {
                ungueltigeMenge()
                return
            }
            if (gewaehlt.isFlasche) {
                viewModel.anlegen(gewaehlt, menge = menge, flaschenArt = flaschenArt)
            } else {
                viewModel.anlegen(gewaehlt, menge = menge)
            }
        } else {
            val roh = dauerText.trim()
            val dauer = roh.toIntOrNull()
            if (roh.isNotEmpty() && (dauer == null || dauer < 0)) {
                ungueltigeDauer()
                return
            }
            viewModel.anlegen(gewaehlt, dauerMinuten = dauer)
        }
        fokus.clearFocus()
        seite = null
        mengeText = ""
        dauerText = ""
        flaschenArt = FlaschenArt.PRE
    }

    Scaffold(
        topBar = {
            MhKopf(
                titel = "Stillzeit",
                symbole = {
                    MhSymbolButton(R.drawable.ic_mh_refresh, "Aktualisieren", viewModel::aktualisieren)
                    MhSymbolButton(R.drawable.ic_mh_sliders, "Einstellungen", onEinstellungen)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Mh.farben.bg,
    ) { innenAbstand ->
        Box(modifier = Modifier.padding(innenAbstand).fillMaxSize()) {
            when {
                state.laedt && state.eintraege.isEmpty() && state.fehler == null -> MhLaden()

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
                        formular = Formular(seite, mengeText, flaschenArt, dauerText),
                        onSeite = { neu ->
                            if (neu != seite) {
                                mengeText = ""
                                dauerText = ""
                            }
                            seite = neu
                        },
                        onMenge = { mengeText = it },
                        onFlaschenArt = { flaschenArt = it },
                        onDauer = { dauerText = it },
                        onJetzt = { viewModel.setzeSchnellZeit(null) },
                        onZeitWaehlen = { dialog = DialogZustand.ZeitWahl },
                        onAnlegen = ::anlegen,
                        onBearbeiten = { eintrag ->
                            dialog = when {
                                eintrag.seite.isFlasche -> DialogZustand.FlascheBearbeiten(eintrag)
                                eintrag.seite.istBreiWasser -> DialogZustand.MengeBearbeiten(eintrag)
                                else -> DialogZustand.DauerBearbeiten(eintrag)
                            }
                        },
                        onLoeschen = { dialog = DialogZustand.Loeschen(it) },
                    )
                }
            }
        }
    }

    // --- Dialoge -------------------------------------------------------------

    when (val aktuell = dialog) {
        null -> Unit

        is DialogZustand.FlascheBearbeiten -> FlascheDialog(
            initialMenge = aktuell.eintrag.menge,
            initialFlaschenArt = aktuell.eintrag.flaschenArt,
            onAbbrechen = { dialog = null },
            onSpeichern = { menge, art ->
                dialog = null
                viewModel.flascheAendern(aktuell.eintrag, menge, art)
            },
            onUngueltig = ::ungueltigeMenge,
        )

        is DialogZustand.MengeBearbeiten -> MengeDialog(
            seite = aktuell.eintrag.seite,
            initialMenge = aktuell.eintrag.menge,
            onAbbrechen = { dialog = null },
            onSpeichern = { menge ->
                dialog = null
                viewModel.mengeAendern(aktuell.eintrag, menge)
            },
            onUngueltig = ::ungueltigeMenge,
        )

        is DialogZustand.DauerBearbeiten -> DauerDialog(
            seite = aktuell.eintrag.seite,
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

/** Eingaben des Formulars „Neuer Eintrag“. */
private data class Formular(
    val seite: Seite?,
    val mengeText: String,
    val flaschenArt: FlaschenArt,
    val dauerText: String,
)

// --- Inhalt ------------------------------------------------------------------

@Composable
private fun Inhalt(
    state: HomeUiState,
    formular: Formular,
    onSeite: (Seite) -> Unit,
    onMenge: (String) -> Unit,
    onFlaschenArt: (FlaschenArt) -> Unit,
    onDauer: (String) -> Unit,
    onJetzt: () -> Unit,
    onZeitWaehlen: () -> Unit,
    onAnlegen: () -> Unit,
    onBearbeiten: (Entry) -> Unit,
    onLoeschen: (Entry) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Mh.rand, end = Mh.rand, bottom = Mh.abstand8),
    ) {
        // Offline-Hinweis über allem: der Nutzer soll sofort sehen, dass er
        // zwar weiterarbeiten kann, der Stand aber noch nicht beim Server ist.
        if (state.offlineGrund != null || state.ausstehend > 0) {
            item(key = "offline") {
                OfflineHinweis(grund = state.offlineGrund, ausstehend = state.ausstehend)
            }
        }
        state.stats?.let { stats ->
            item(key = "heute") {
                MhGruppenLabel("Heute")
                HeuteStatistik(stats)
            }
        }
        item(key = "neu") {
            MhGruppenLabel("Neuer Eintrag", Modifier.padding(top = Mh.abstand2))
            NeuerEintrag(
                formular = formular,
                breiWasserAktiv = state.breiWasserAktiv,
                zeit = state.schnellZeit,
                onSeite = onSeite,
                onMenge = onMenge,
                onFlaschenArt = onFlaschenArt,
                onDauer = onDauer,
                onJetzt = onJetzt,
                onZeitWaehlen = onZeitWaehlen,
                onAnlegen = onAnlegen,
            )
        }
        item(key = "eintraege") {
            MhGruppenLabel("Einträge", Modifier.padding(top = Mh.abstand2))
        }

        if (state.eintraege.isEmpty()) {
            item(key = "leer") { MhLeer("Noch keine Einträge") }
        } else {
            val eintraege = state.eintraege
            eintraege.forEachIndexed { index, eintrag ->
                val tag = tagesLabel(eintrag.createTime)
                val vorher = eintraege.getOrNull(index - 1)
                if (vorher == null || tagesLabel(vorher.createTime) != tag) {
                    // Schlüssel über die Eintrags-ID, nicht über das Tages-
                    // Label: taucht derselbe Tag (etwa bei nachgetragenen
                    // Uhrzeiten) in zwei Blöcken auf, wäre ein Label-Schlüssel
                    // doppelt – LazyColumn wirft darauf eine Exception und die
                    // App stürzt beim Aufbau der Liste ab.
                    item(key = "tag-${eintrag.id}") { TagesTrenner(tag, erster = vorher == null) }
                }
                val naechster = eintraege.getOrNull(index + 1)
                val linie = naechster != null && tagesLabel(naechster.createTime) == tag
                item(key = "eintrag-${eintrag.id}") {
                    EintragsZeile(
                        eintrag = eintrag,
                        ausstehend = eintrag.id in state.ausstehendeIds,
                        linie = linie,
                        onBearbeiten = { onBearbeiten(eintrag) },
                        onLoeschen = { onLoeschen(eintrag) },
                    )
                }
            }
        }
    }
}

// --- Heute ---------------------------------------------------------------------

/** Eine Kennzahl der Tagesübersicht. */
private data class Kennzahl(val wert: Int, val label: String, val einheit: String? = null)

/**
 * „Heute“ wie im Web: Zahlen dünn in Textfarbe, Einheit klein in
 * Nebentextfarbe, drei feste Spalten, Gruppen durch Haarlinien getrennt.
 */
@Composable
private fun HeuteStatistik(stats: TodayStats) {
    // Bei sehr großer Schrift zwei statt drei Spalten, sonst brechen die
    // Bezeichnungen mitten im Wort.
    val spalten = if (LocalDensity.current.fontScale >= 1.5f) 2 else 3
    Column(Modifier.fillMaxWidth()) {
        KennzahlRaster(
            listOf(
                Kennzahl(stats.gesamt, "Gesamt"),
                Kennzahl(stats.links, "Links"),
                Kennzahl(stats.rechts, "Rechts"),
                Kennzahl(stats.beidseitig, "Beidseitig"),
                Kennzahl(stats.flasche, "Flasche"),
                Kennzahl(stats.totalMinuten, "Stilldauer", "min"),
            ),
            spalten = spalten,
        )
        MhTrennlinie(Modifier.padding(vertical = Mh.abstand4))
        KennzahlRaster(listOf(Kennzahl(stats.totalMl, "Flaschenmenge", "ml")), spalten = spalten)
        if (stats.breiWasserAktiv) {
            MhTrennlinie(Modifier.padding(vertical = Mh.abstand4))
            // Je Zeile Anzahl und Menge, wie im Web.
            KennzahlRaster(
                listOf(
                    Kennzahl(stats.brei, "Brei"),
                    Kennzahl(stats.totalGBrei, "Brei-Menge", "g"),
                    Kennzahl(stats.wasser, "Wasser"),
                    Kennzahl(stats.totalMlWasser, "Wasser-Menge", "ml"),
                ),
                spalten = spalten,
                paarweise = true,
            )
        }
    }
}

/** Kennzahlen in festen Spalten; [paarweise] setzt je zwei in eine Zeile. */
@Composable
private fun KennzahlRaster(zahlen: List<Kennzahl>, spalten: Int, paarweise: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand4)) {
        zahlen.chunked(if (paarweise) 2 else spalten).forEach { zeile ->
            Row(horizontalArrangement = Arrangement.spacedBy(Mh.abstand3)) {
                for (i in 0 until spalten) {
                    val zahl = zeile.getOrNull(i)
                    Box(Modifier.weight(1f)) {
                        if (zahl != null) KennzahlAnzeige(zahl)
                    }
                }
            }
        }
    }
}

@Composable
private fun KennzahlAnzeige(zahl: Kennzahl) {
    val f = Mh.farben
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Row {
            // Was heute noch nicht vorkam, tritt zurück – bleibt aber lesbar.
            Text(
                "${zahl.wert}",
                style = MhTypo.number,
                color = if (zahl.wert == 0) f.textSecondary else f.textStrong,
                modifier = Modifier.alignByBaseline(),
                maxLines = 1,
            )
            if (zahl.einheit != null) {
                Text(
                    zahl.einheit,
                    style = MhTypo.small,
                    color = f.textSecondary,
                    modifier = Modifier.alignByBaseline().padding(start = 3.dp),
                    maxLines = 1,
                )
            }
        }
        Text(zahl.label, style = MhTypo.small.copy(hyphens = Hyphens.Auto), color = f.textSecondary, maxLines = 2)
    }
}

// --- Neuer Eintrag ---------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeuerEintrag(
    formular: Formular,
    breiWasserAktiv: Boolean,
    zeit: LocalTime?,
    onSeite: (Seite) -> Unit,
    onMenge: (String) -> Unit,
    onFlaschenArt: (FlaschenArt) -> Unit,
    onDauer: (String) -> Unit,
    onJetzt: () -> Unit,
    onZeitWaehlen: () -> Unit,
    onAnlegen: () -> Unit,
) {
    val fokus = LocalFocusManager.current
    val seite = formular.seite
    Column(verticalArrangement = Arrangement.spacedBy(Mh.abstand5)) {
        MhFeld("Seite") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Mh.abstand2),
                verticalArrangement = Arrangement.spacedBy(Mh.abstand2),
            ) {
                // Brei/Wasser nur anbieten, wenn die Option aktiv ist –
                // ausblenden statt deaktivieren (der Server würde sonst mit
                // 400 ablehnen).
                Seite.entries.filter { !it.istBreiWasser || breiWasserAktiv }.forEach { s ->
                    MhAuswahl(
                        text = s.apiValue,
                        gewaehlt = seite == s,
                        onClick = { onSeite(s) },
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                }
            }
        }

        if (seite != null && seite.hatMenge) {
            val einheit = seite.mengenEinheit ?: "ml"
            MhFeld("Menge ($einheit)") {
                OutlinedTextField(
                    value = formular.mengeText,
                    onValueChange = onMenge,
                    placeholder = {
                        Text(if (einheit == "g") "z. B. 90 (0 = leer)" else "z. B. 120 (0 = leer)", style = MhTypo.body)
                    },
                    suffix = { Text(einheit, style = MhTypo.body) },
                    singleLine = true,
                    textStyle = MhTypo.body,
                    colors = mhFeldFarben(),
                    shape = MhFeldForm,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { fokus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (seite == Seite.FLASCHE) {
            MhFeld("Art") {
                Row(horizontalArrangement = Arrangement.spacedBy(Mh.abstand2)) {
                    FlaschenArt.entries.forEach { art ->
                        MhAuswahl(
                            text = art.apiValue,
                            gewaehlt = formular.flaschenArt == art,
                            onClick = { onFlaschenArt(art) },
                            modifier = Modifier.widthIn(min = 72.dp),
                        )
                    }
                }
            }
        }

        if (seite != null && seite.hatDauer) {
            MhFeld("Dauer (Minuten)", hinweis = "Optional, lässt sich später in der Liste nachtragen.") {
                OutlinedTextField(
                    value = formular.dauerText,
                    onValueChange = onDauer,
                    placeholder = { Text("z. B. 12", style = MhTypo.body) },
                    suffix = { Text("min", style = MhTypo.body) },
                    singleLine = true,
                    textStyle = MhTypo.body,
                    colors = mhFeldFarben(),
                    shape = MhFeldForm,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { fokus.clearFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        MhFeld("Uhrzeit") {
            MhSegmente(
                optionen = listOf("Jetzt", "Andere Uhrzeit"),
                gewaehlt = if (zeit == null) 0 else 1,
                onWahl = { if (it == 0) onJetzt() else onZeitWaehlen() },
            )
            if (zeit != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = Mh.touch),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MhSymbol(R.drawable.ic_mh_clock, null, farbe = Mh.farben.textSecondary)
                    Spacer(Modifier.width(Mh.abstand2))
                    Text(
                        "Heute um ${zeit.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                        style = MhTypo.body,
                        color = Mh.farben.text,
                        modifier = Modifier.weight(1f),
                    )
                    MhTextButton("Ändern", onZeitWaehlen)
                }
            }
        }

        // Immer bedienbar wie im Web: ohne gewählte Seite kommt ein Hinweis.
        MhHauptButton("Eintrag anlegen", onAnlegen, modifier = Modifier.fillMaxWidth())
    }
}

// --- Eintragsliste -------------------------------------------------------------

/** Tagestrenner wie im Web: Label in Nebentextfarbe über einer Haarlinie. */
@Composable
private fun TagesTrenner(text: String, erster: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = if (erster) 0.dp else Mh.abstand6)) {
        Text(
            text,
            style = MhTypo.label,
            color = Mh.farben.textSecondary,
            modifier = Modifier.padding(bottom = Mh.abstand2).semantics { heading() },
        )
        MhTrennlinie()
    }
}

/** Farbpunkt je Art wie im Web: Stillen Minze, Flasche Honig, Brei/Wasser Flieder. */
@Composable
internal fun Seite.punktFarbe(): Color = when {
    hatDauer -> Mh.farben.data1
    isFlasche -> Mh.farben.data2
    else -> Mh.farben.data3
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EintragsZeile(
    eintrag: Entry,
    /** Der Eintrag wartet noch auf die Übertragung zum Server. */
    ausstehend: Boolean,
    /** Haarlinie unter der Zeile (nicht unter der letzten eines Tages). */
    linie: Boolean,
    onBearbeiten: () -> Unit,
    onLoeschen: () -> Unit,
) {
    val f = Mh.farben
    // Wischen nach links fragt – wie in der Flutter-App – erst per Dialog nach;
    // die Zeile springt deshalb immer zurück (confirmValueChange = false).
    val wischZustand = rememberSwipeToDismissBoxState(
        confirmValueChange = { wert ->
            if (wert == SwipeToDismissBoxValue.EndToStart) onLoeschen()
            false
        },
    )
    Column {
        SwipeToDismissBox(
            state = wischZustand,
            enableDismissFromStartToEnd = false,
            backgroundContent = {
                Box(
                    modifier = Modifier.fillMaxSize().background(f.errorBg),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    MhSymbol(
                        R.drawable.ic_mh_trash,
                        null,
                        farbe = f.danger,
                        modifier = Modifier.padding(end = Mh.abstand5),
                    )
                }
            },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(f.bg)
                    .heightIn(min = 60.dp)
                    .padding(vertical = Mh.abstand1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hhmm(eintrag.createTime),
                    style = MhTypo.small.copy(fontFeatureSettings = "tnum"),
                    color = f.textSecondary,
                    modifier = Modifier.widthIn(min = 52.dp),
                )
                MhPunkt(eintrag.seite.punktFarbe())
                Spacer(Modifier.width(Mh.abstand2))
                Column(Modifier.weight(1f)) {
                    Text(
                        eintrag.titel(),
                        style = MhTypo.body.copy(fontWeight = FontWeight.Medium),
                        color = f.textStrong,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            eintrag.wertText(),
                            style = MhTypo.small.copy(fontFeatureSettings = "tnum"),
                            color = f.textSecondary,
                        )
                        if (ausstehend) {
                            Spacer(Modifier.width(Mh.abstand2))
                            MhSymbol(
                                R.drawable.ic_mh_clock,
                                "Wartet auf Übertragung",
                                groesse = 14.dp,
                                farbe = f.warningFg,
                            )
                        }
                    }
                }
                MhSymbolButton(R.drawable.ic_mh_edit, "Bearbeiten", onBearbeiten)
                MhSymbolButton(R.drawable.ic_mh_trash, "Eintrag löschen", onLoeschen, farbe = f.textTertiary)
            }
        }
        if (linie) MhTrennlinie()
    }
}

/** Menge mit Einheit bzw. Dauer; „Dauer offen“, solange keine eingetragen ist. */
private fun Entry.wertText(): String =
    if (seite.hatMenge) {
        // Einheit aus dem API-Feld, lokal aus der Seite abgeleitet.
        "${menge ?: 0} ${anzeigeEinheit ?: "ml"}"
    } else {
        dauerMinuten?.let { "$it min" } ?: "Dauer offen"
    }

// --- Fehleransicht --------------------------------------------------------------

@Composable
private fun FehlerAnsicht(meldung: String, onErneut: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Mh.abstand8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MhSymbol(R.drawable.ic_mh_alert, null, groesse = 32.dp, farbe = Mh.farben.danger)
        Spacer(Modifier.height(Mh.abstand4))
        Text(meldung, style = MhTypo.body, color = Mh.farben.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Mh.abstand6))
        MhHauptButton("Erneut versuchen", onErneut, symbol = R.drawable.ic_mh_refresh)
    }
}

/**
 * Hinweis über der Liste: Verbindung weg, App weiter benutzbar.
 *
 * [grund] null bedeutet „wieder online, aber es wartet noch etwas auf die
 * Übertragung“.
 */
@Composable
private fun OfflineHinweis(grund: String?, ausstehend: Int) {
    val titel = if (grund == null) "Übertragung läuft" else "Offline – $grund"
    val text = when {
        ausstehend == 0 -> "Angezeigt wird der zuletzt geladene Stand."
        ausstehend == 1 ->
            "Eine Änderung wartet auf die Übertragung und geht raus, sobald die Verbindung steht."
        else ->
            "$ausstehend Änderungen warten auf die Übertragung und gehen raus, " +
                "sobald die Verbindung steht."
    }
    MhHinweis(
        text = text,
        titel = titel,
        art = MhHinweisArt.Warnung,
        symbol = if (grund == null) R.drawable.ic_mh_upload else R.drawable.ic_mh_cloud_off,
        modifier = Modifier.padding(top = Mh.abstand2),
    )
}

// --- Datums-Helfer ---------------------------------------------------------------

internal fun Entry.titel(): String =
    if (seite.isFlasche && flaschenArt != null) "${seite.apiValue} · ${flaschenArt.apiValue}" else seite.apiValue

internal fun hhmm(zeit: Instant): String =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(zeit)

/** „Heute · 23.11.2026“, „Gestern · 22.11.2026“, sonst nur das Datum – wie im Web. */
internal fun tagesLabel(zeit: Instant): String {
    val heute = LocalDate.now()
    val tag = zeit.atZone(ZoneId.systemDefault()).toLocalDate()
    val datum = tag.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    return when (heute.toEpochDay() - tag.toEpochDay()) {
        0L -> "Heute · $datum"
        1L -> "Gestern · $datum"
        else -> datum
    }
}
