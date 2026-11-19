package com.example.pokemonivreader

/**
 * Primary Web selection service. Keep browser handoff and public ranking lookup
 * on the same origin so manual and automatic selection cannot drift apart.
 *
 * VisualCloudBackup deliberately retains its separate connection endpoint:
 * existing backup tokens belong to the original server and cannot be silently
 * reused with a different PVP_API_KEY.
 */
internal object ReaderWebEndpoints {
    const val ORIGIN = "https://pokemoniv-web.duckdns.org"
    const val APP = ORIGIN + "/app"
    const val CLEANUP = ORIGIN + "/reader-api/v1/cleanup"
}
