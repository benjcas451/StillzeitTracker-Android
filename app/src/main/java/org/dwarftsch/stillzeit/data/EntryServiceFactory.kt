package org.dwarftsch.stillzeit.data

import android.content.Context

/**
 * Erstellt die aktuell konfigurierte Datenquelle.
 *
 * Wird von der Oberfläche und vom Wear-Service verwendet. Damit landen
 * Einträge von der Uhr immer im selben lokalen bzw. serverseitigen
 * Datenbestand wie Einträge vom Telefon.
 */
fun createConfiguredEntryService(context: Context, settings: AppSettings, certSource: CertSource): EntryService =
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
        DataSourceMode.DEMO -> DemoService(context) { settings.breiWasserAktiviert }
    }
