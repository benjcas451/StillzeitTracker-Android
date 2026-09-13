package org.dwarftsch.stillzeit.data

import android.content.Context

/**
 * Erstellt die aktuell konfigurierte Datenquelle.
 *
 * Wird von der Oberfläche und vom Wear-Service verwendet. Damit landen
 * Einträge von der Uhr immer im selben lokalen bzw. serverseitigen
 * Datenbestand wie Einträge vom Telefon.
 *
 * [offlineFaehig] legt die Warteschlange darüber, die bei einem
 * Verbindungsabbruch einspringt. Die Oberfläche will das; der Wear-Service
 * bewusst **nicht** — die Uhr führt eine eigene Outbox und bekäme sonst ein
 * „erledigt“ gemeldet, während der Eintrag noch beim Telefon liegt. Scheitert
 * die Übertragung, meldet der Service das weiterhin an die Uhr, die den
 * Eintrag dann selbst aufbewahrt und erneut schickt.
 */
fun createConfiguredEntryService(
    context: Context,
    settings: AppSettings,
    certSource: CertSource,
    offlineFaehig: Boolean = false,
): EntryService {
    val dienst = createServerOderDemoService(context, settings, certSource)
    val zugang = aktuellerZugang(settings)
    if (!offlineFaehig || zugang == null) return dienst
    return OfflineService(dienst, OfflineSpeicher(context, zugang))
}

/**
 * Kennung des aktuellen Zugangs (Modus + Basis-URL); null im Demo-Modus, der
 * ohnehin lokal arbeitet und keine Warteschlange braucht.
 */
private fun aktuellerZugang(settings: AppSettings): String? = when (settings.mode) {
    DataSourceMode.API -> "api|${settings.apiBaseUrl}"
    DataSourceMode.API_KEY -> "apiKey|${settings.apiKeyBaseUrl}"
    DataSourceMode.CLOUDFLARE -> "cloudflare|${settings.cloudflareBaseUrl}"
    DataSourceMode.DEMO -> null
}

private fun createServerOderDemoService(
    context: Context,
    settings: AppSettings,
    certSource: CertSource,
): EntryService =
    when (settings.mode) {
        DataSourceMode.API -> ApiService(
            certSource = certSource,
            baseUrl = settings.apiBaseUrl,
            // Optional: manche Server verlangen zusätzlich zum Zertifikat
            // einen API-Key. Leer bedeutet „nur mTLS“.
            apiKey = settings.mtlsApiKey.ifEmpty { null },
        )
        DataSourceMode.API_KEY -> ApiService(
            baseUrl = settings.apiKeyBaseUrl,
            apiKey = settings.apiKey,
        )
        // Cloudflare Access sichert den Zugang am Rand; der Zusatz-Key ist wie
        // im mTLS-Modus optional und geht nur raus, wenn er hinterlegt ist.
        DataSourceMode.CLOUDFLARE -> ApiService(
            baseUrl = settings.cloudflareBaseUrl,
            apiKey = settings.cloudflareApiKey.ifEmpty { null },
            cfToken = settings.cfServiceToken(),
        )
        DataSourceMode.DEMO -> DemoService(context) { settings.breiWasserAktiviert }
    }
