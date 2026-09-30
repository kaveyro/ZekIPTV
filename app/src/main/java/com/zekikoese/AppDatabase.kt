package com.zekikoese

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite-Speicher für die großen Datenmengen: Senderliste und EPG-Cache.
 *
 * Große Anbieter-Playlists haben 50.000+ Sender; als JSON wären das 15+ MB, die beim Start
 * komplett eingelesen und als Objektbaum im Speicher aufgebaut werden müssten. Hier werden die
 * Zeilen stattdessen per Cursor gestreamt, Schreiben läuft in einer Transaktion mit
 * vorkompiliertem Statement. Bewusst ohne Room: kein Annotation-Processing im Build.
 */
class AppDatabase private constructor(context: Context, name: String?) :
    SQLiteOpenHelper(context.applicationContext, name, null, VERSION) {

    init {
        // WAL: UI liest, während der RefreshWorker schreibt.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE channels (pos INTEGER PRIMARY KEY, name TEXT NOT NULL, url TEXT NOT NULL, " +
                "logo TEXT, grp TEXT, tvg_id TEXT, ua TEXT, ref TEXT)"
        )
        db.execSQL("CREATE TABLE epg_programme (channel_id TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL)")
        db.execSQL("CREATE TABLE epg_name (name TEXT PRIMARY KEY, channel_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nur Cache-Daten: bei Schemawechsel neu aufbauen (Playlist/EPG werden neu geladen).
        listOf("channels", "epg_programme", "epg_name", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    // ---------- Kanäle ----------

    /** True, sobald je eine Senderliste gespeichert wurde (auch eine leere). */
    fun hasChannels(): Boolean = meta(KEY_CHANNELS_SAVED) != null

    fun replaceChannels(channels: List<Channel>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("channels", null, null)
            val insert = db.compileStatement(
                "INSERT INTO channels (pos, name, url, logo, grp, tvg_id, ua, ref) VALUES (?,?,?,?,?,?,?,?)"
            )
            channels.forEachIndexed { index, c ->
                insert.clearBindings()
                insert.bindLong(1, index.toLong())
                insert.bindString(2, c.name)
                insert.bindString(3, c.url)
                c.logo?.let { insert.bindString(4, it) }
                c.group?.let { insert.bindString(5, it) }
                c.tvgId?.let { insert.bindString(6, it) }
                c.userAgent?.let { insert.bindString(7, it) }
                c.referrer?.let { insert.bindString(8, it) }
                insert.executeInsert()
            }
            putMeta(db, KEY_CHANNELS_SAVED, System.currentTimeMillis().toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readChannels(): List<Channel> {
        // Gruppen/User-Agents wiederholen sich tausendfach — nur eine String-Instanz behalten.
        val pool = HashMap<String, String>()
        fun String?.pooled(): String? = this?.let { pool.getOrPut(it) { it } }
        return readableDatabase.rawQuery(
            "SELECT name, url, logo, grp, tvg_id, ua, ref FROM channels ORDER BY pos", null
        ).use { c ->
            ArrayList<Channel>(c.count).apply {
                while (c.moveToNext()) {
                    add(
                        Channel(
                            name = c.getString(0),
                            url = c.getString(1),
                            logo = c.getStringOrNull(2),
                            group = c.getStringOrNull(3).pooled(),
                            tvgId = c.getStringOrNull(4),
                            userAgent = c.getStringOrNull(5).pooled(),
                            referrer = c.getStringOrNull(6).pooled()
                        )
                    )
                }
            }
        }
    }

    // ---------- EPG-Cache ----------

    fun replaceEpg(programmes: Map<String, List<EpgProgramme>>, nameToId: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("epg_programme", null, null)
            db.delete("epg_name", null, null)
            val insert = db.compileStatement("INSERT INTO epg_programme (channel_id, start, stop, title) VALUES (?,?,?,?)")
            programmes.forEach { (id, list) ->
                list.forEach { p ->
                    insert.clearBindings()
                    insert.bindString(1, id)
                    insert.bindLong(2, p.startMs)
                    insert.bindLong(3, p.stopMs)
                    insert.bindString(4, p.title)
                    insert.executeInsert()
                }
            }
            val insertName = db.compileStatement("INSERT OR REPLACE INTO epg_name (name, channel_id) VALUES (?,?)")
            nameToId.forEach { (name, id) ->
                insertName.bindString(1, name)
                insertName.bindString(2, id)
                insertName.executeInsert()
            }
            putMeta(db, KEY_EPG_SAVED, System.currentTimeMillis().toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** EPG-Cache, falls vorhanden und jünger als [maxAgeMs]; sonst null. */
    fun readEpg(maxAgeMs: Long): Pair<Map<String, List<EpgProgramme>>, Map<String, String>>? {
        val savedAt = meta(KEY_EPG_SAVED)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > maxAgeMs) return null
        val db = readableDatabase
        val programmes = HashMap<String, MutableList<EpgProgramme>>()
        db.rawQuery("SELECT channel_id, start, stop, title FROM epg_programme ORDER BY channel_id, start", null).use { c ->
            var currentId: String? = null
            var currentList: MutableList<EpgProgramme>? = null
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (id != currentId) {
                    currentId = id
                    currentList = programmes.getOrPut(id) { ArrayList() }
                }
                currentList!!.add(EpgProgramme(id, c.getLong(1), c.getLong(2), c.getString(3)))
            }
        }
        val names = HashMap<String, String>()
        db.rawQuery("SELECT name, channel_id FROM epg_name", null).use { c ->
            while (c.moveToNext()) names[c.getString(0)] = c.getString(1)
        }
        return programmes to names
    }

    fun clearEpg() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("epg_programme", null, null)
            db.delete("epg_name", null, null)
            db.delete("meta", "key = ?", arrayOf(KEY_EPG_SAVED))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- Hilfen ----------

    private fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict(
            "meta", null,
            ContentValues().apply { put("key", key); put("value", value) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        private const val VERSION = 1
        private const val KEY_CHANNELS_SAVED = "channels_saved"
        private const val KEY_EPG_SAVED = "epg_saved"

        @Volatile
        private var instance: AppDatabase? = null

        /** Eine Instanz pro Prozess — ViewModel und RefreshWorker teilen sich die Verbindung. */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) { instance ?: AppDatabase(context, "zekiptv.db").also { instance = it } }

        /** Nur für Tests: flüchtige In-Memory-Datenbank. */
        internal fun inMemory(context: Context): AppDatabase = AppDatabase(context, null)
    }
}
