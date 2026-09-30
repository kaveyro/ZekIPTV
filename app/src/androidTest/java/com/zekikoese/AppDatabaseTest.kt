package com.zekikoese

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun channelsRoundTripKeepsOrderAndNulls() {
        assertFalse(db.hasChannels())
        val channels = listOf(
            Channel("B", "http://h/2", logo = "http://l/2.png", group = "Sport", tvgId = "b", userAgent = "ua", referrer = "ref"),
            Channel("A", "http://h/1"),
            Channel("C", "http://h/3", group = "Sport")
        )
        db.replaceChannels(channels)
        assertTrue(db.hasChannels())
        val read = db.readChannels()
        assertEquals(channels, read)
        // Gleiche Gruppennamen teilen sich eine String-Instanz (Speicher bei großen Listen).
        assertSame(read[0].group, read[2].group)
    }

    @Test
    fun replaceChannelsOverwritesAndRemembersEmptyList() {
        db.replaceChannels(listOf(Channel("A", "http://h/1")))
        db.replaceChannels(emptyList())
        assertTrue(db.hasChannels())
        assertEquals(emptyList<Channel>(), db.readChannels())
    }

    @Test
    fun largePlaylistIsStoredCompletely() {
        val channels = List(60_000) { Channel("Sender $it", "http://h/live/u/p/$it.ts", group = "G${it % 50}") }
        db.replaceChannels(channels)
        assertEquals(channels, db.readChannels())
    }

    @Test
    fun epgRoundTripAndExpiry() {
        assertNull(db.readEpg(Long.MAX_VALUE))
        val programmes = mapOf(
            "das.erste" to listOf(EpgProgramme("das.erste", 1_000, 2_000, "Tagesschau"), EpgProgramme("das.erste", 2_000, 3_000, "Film")),
            "zdf" to listOf(EpgProgramme("zdf", 1_500, 2_500, "heute"))
        )
        db.replaceEpg(programmes, mapOf("daserste" to "das.erste"))
        val read = db.readEpg(60_000)!!
        assertEquals(programmes, read.first)
        assertEquals(mapOf("daserste" to "das.erste"), read.second)
        // maxAge < 0: jeder Cache gilt als veraltet
        assertNull(db.readEpg(-1))
        db.clearEpg()
        assertNull(db.readEpg(Long.MAX_VALUE))
    }
}
