package com.example.pokemonivreader

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ReaderWebEndpointsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun productionDefaultsRemainUnchanged() {
        assertEquals("https://pokemoniv-web.duckdns.org", ReaderWebEndpoints.ORIGIN)
        assertEquals("https://pokemoniv-web.duckdns.org/app", ReaderWebEndpoints.APP)
        assertEquals("https://pokemoniv-web.duckdns.org/reader-api/v1/cleanup", ReaderWebEndpoints.CLEANUP)
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = false))
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = true))
        assertEquals(
            "https://pokemoniv-web.duckdns.org/app-api/reader-sync",
            ReaderIndividualSync.verifiedEndpoint(ReaderWebEndpoints.ORIGIN),
        )
    }

    @Test fun releaseModeAlwaysIgnoresOverrideAndRejectsSetter() {
        val saved = ReaderWebEndpoints.setDebugSyncOrigin(
            context,
            "https://pokemoniv-staging.duckdns.org",
            debuggable = true,
        )
        assertEquals("https://pokemoniv-staging.duckdns.org", saved)
        assertEquals(saved, ReaderWebEndpoints.syncOrigin(context, debuggable = true))

        // Release builds must always use the production default and reject setting any override.
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = false))
        assertThrows(IllegalStateException::class.java) {
            ReaderWebEndpoints.setDebugSyncOrigin(
                context,
                "https://pokemoniv-test.duckdns.org",
                debuggable = false,
            )
        }
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = false))
    }

    @Test fun debugModeAcceptsAllowedIsolatedHttpsOriginsAndCanResetToDefault() {
        val accepted = listOf(
            "https://localhost:8443" to "https://localhost:8443",
            "https://127.0.0.1:8443/" to "https://127.0.0.1:8443",
            "https://POKEMONIV-STAGING.duckdns.org:443/" to "https://pokemoniv-staging.duckdns.org",
            "https://pokemoniv-smoke-1234.a.run.app" to "https://pokemoniv-smoke-1234.a.run.app",
            "https://pokemoniv-dev.up.railway.app" to "https://pokemoniv-dev.up.railway.app",
        )
        for ((input, expected) in accepted) {
            assertEquals(input, expected, ReaderWebEndpoints.parseIsolatedHttpsOrigin(input))
            val saved = ReaderWebEndpoints.setDebugSyncOrigin(context, input, debuggable = true)
            assertEquals(expected, saved)
            assertEquals(expected, ReaderWebEndpoints.syncOrigin(context, debuggable = true))
            assertEquals("$expected/app-api/reader-sync", ReaderIndividualSync.verifiedEndpoint(saved))
        }

        ReaderWebEndpoints.clearDebugSyncOrigin(context)
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = true))
    }

    @Test fun rejectsPlaintextHttpArbitraryHostsProductionAndMalformedUrls() {
        val rejected = listOf(
            "",
            "   ",
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://pokemoniv-staging.duckdns.org",
            "http://pokemoniv-web.duckdns.org",
            "https://evil.example.com",
            "https://evil.duckdns.org",
            "https://other-app.a.run.app",
            "https://other.up.railway.app",
            "https://169.254.169.254",
            "https://192.168.1.10",
            "https://pokemoniv-web.duckdns.org",
            "https://pokemoniv-production.up.railway.app",
            "https://pokemoniv-staging.duckdns.org@evil.example.com",
            "https://user:pass@pokemoniv-staging.duckdns.org",
            "https://pokemoniv-staging.duckdns.org/app-api",
            "https://pokemoniv-staging.duckdns.org?x=1",
            "https://pokemoniv-staging.duckdns.org#frag",
            "https://pokemoniv-staging.duckdns.org.",
            "https://pokemoniv-staging.duckdns.org:0",
            "https://pokemoniv-staging.duckdns.org:70000",
            "javascript:alert(1)",
            "file:///etc/hosts",
        )
        for (bad in rejected) {
            assertNull("Expected rejection for: $bad", ReaderWebEndpoints.parseIsolatedHttpsOrigin(bad))
            assertThrows(IllegalArgumentException::class.java) {
                ReaderWebEndpoints.setDebugSyncOrigin(context, bad, debuggable = true)
            }
            if (bad != ReaderWebEndpoints.ORIGIN) {
                assertThrows(IllegalArgumentException::class.java) {
                    ReaderIndividualSync.verifiedEndpoint(bad)
                }
            }
        }
    }

    @Test fun tamperedDebugPreferenceFallsBackToProductionDefault() {
        context.getSharedPreferences("reader_debug_sync_origin_v1", Context.MODE_PRIVATE)
            .edit()
            .putString("isolated_https_origin", "http://evil.example.com")
            .commit()
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = true))
        assertEquals(ReaderWebEndpoints.ORIGIN, ReaderWebEndpoints.syncOrigin(context, debuggable = false))
    }
}
