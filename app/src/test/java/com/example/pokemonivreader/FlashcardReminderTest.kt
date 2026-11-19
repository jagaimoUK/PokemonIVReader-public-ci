package com.example.pokemonivreader

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FlashcardReminderTest {
    @Test fun reminderNeverFiresBeforeDueAndUsesJapanLocalHour() {
        val zone=ZoneId.of("Asia/Tokyo")
        val now=Instant.parse("2026-10-29T00:00:00Z").toEpochMilli()
        val due=Instant.parse("2026-10-30T10:30:00Z").toEpochMilli()
        val expected=Instant.parse("2026-10-31T00:00:00Z").toEpochMilli()
        assertEquals(expected,FlashcardReminder.nextNotification(due,9,now,zone))
        val overdue=FlashcardReminder.nextNotification(now-86400000,18,now,zone)
        assertEquals(Instant.parse("2026-10-29T09:00:00Z").toEpochMilli(),overdue)
    }

    @Test fun workerWithoutStudiedCardsHasNothingToNotify() {
        val context=RuntimeEnvironment.getApplication()
        val prefs=FlashcardReminder.preferences(context)
        prefs.edit().clear().commit()
        assertFalse(prefs.getBoolean("enabled",false))
        assertEquals(0L,prefs.getLong("due",0))
    }

    @Test fun apkContainsOnlyBundledCardContentAndNoRemoteFrames() {
        val html=RuntimeEnvironment.getApplication().assets.open("flashcards.html").bufferedReader().use { it.readText() }
        assertTrue(html.contains("frame-src 'none'"))
        assertFalse(html.contains("/*CORE*/"))
        assertTrue(html.contains("indexedDB.open"))
        assertTrue(html.contains("POISON_FANG"))
    }
}
