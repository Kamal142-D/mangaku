package com.mangaku.local

import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
internal fun JSONObject.copy() = JSONObject(toString())
internal fun JSONObject.keysList() = keys().asSequence().toList()
internal fun same(a: Any?, b: Any?): Boolean = when {
    a is Number && b is Number -> { val x = a.toString().toBigDecimalOrNull(); val y = b.toString().toBigDecimalOrNull(); x != null && y != null && x.compareTo(y) == 0 }
    a is JSONObject && b is JSONObject -> a.keysList().toSet() == b.keysList().toSet() && a.keysList().all { same(a.opt(it), b.opt(it)) }
    a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
    else -> a == b || a?.toString() == b?.toString()
}

/** SQLite owns the data. JSON is a compatibility boundary for the recovered UI. */
class LocalStore(val files: File) : AutoCloseable {
    val db: SQLiteDatabase
    val device: String
    companion object {
        val userKeys = setOf("status", "currentChapter", "currentVolume", "currentPage", "rating", "notes", "lastReadAtMillis", "readChapters", "chapterPages", "isFavorite", "categories", "readingMode", "autoDownload", "isPrivate")
        fun atomic(file: File, text: String) {
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try { out.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (e: Throwable) { atomic.failWrite(out); throw e }
        }
        fun normalize(raw: JSONObject): JSONObject {
            val o = raw.copy()
            require(o.getString("id").matches(Regex("[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,127}"))) { "Invalid manga ID" }
            require(o.get("title") is String && o.getString("title").length <= 4096) { "Invalid title" }
            require(o.opt("pageCount") is Number && o.opt("addedAtMillis") is Number && o.getInt("pageCount") >= 0 && o.getLong("addedAtMillis") >= 0) { "Incomplete manga record" }
            val defaults = JSONObject("""{"source":"ZIP","status":"READING","currentChapter":"","latestChapter":"","isPrivate":false,"sourceId":"","artist":"","anime":"","sourceUrl":"","author":"","rating":"","synopsis":"","coverUrl":"","readChapters":[],"sourceChapterCount":0,"seenChapterCount":0,"downloadedChapters":[],"chapterPages":{},"localChapters":[],"lastReadAtMillis":0,"autoDownload":false,"isFavorite":false,"categories":[],"readingMode":"DEFAULT","anilistId":0,"type":"MANGA","cachedChapters":[],"currentVolume":"","currentPage":0,"notes":""}""")
            for (k in defaults.keysList()) if (!o.has(k)) o.put(k, defaults.get(k))
            require(o.getString("source") in listOf("ZIP", "SEARCH")) { "Unknown source type" }
            require(o.getString("status") in listOf("READING", "PLAN_TO_READ", "FINISHED", "ON_HOLD", "DROPPED", "REREADING")) { "Unsupported status" }
            for (k in defaults.keysList()) {
                val v = o.get(k); val d = defaults.get(k)
                require((d is String && v is String) || (d is Number && v is Number) || (d is Boolean && v is Boolean) || (d is JSONArray && v is JSONArray) || (d is JSONObject && v is JSONObject)) { "Invalid field: $k" }
            }
            require(o.getLong("lastReadAtMillis") >= 0 && o.getInt("currentPage") >= 0 && o.getInt("anilistId") >= 0)
            require(o.getString("notes").length <= 100000)
            o.getJSONObject("chapterPages").keysList().forEach { require(o.getJSONObject("chapterPages").getInt(it) >= 0) }
            for(k in listOf("readChapters","downloadedChapters","categories")) { val a = o.getJSONArray(k); for(i in 0 until a.length()) require(a.get(i) is String) { "Invalid $k entry" } }
            for(c in o.getJSONArray("localChapters").objects()) {
                for(k in listOf("id","title","number")) require(c.get(k) is String)
                require(c.getString("id").matches(Regex("[A-Za-z0-9_:-][A-Za-z0-9_.:-]{0,127}")))
                require(c.opt("pageCount") is Number && c.getInt("pageCount") >= 0 && c.opt("addedAtMillis") is Number)
            }
            for(c in o.getJSONArray("cachedChapters").objects()) { for(k in listOf("name","url")) require(c.get(k) is String); for(k in listOf("id","number")) if(c.has(k)) require(c.get(k) is String) }
            if(o.has("builtinSources")) {
                val links=o.getJSONObject("builtinSources")
                require(links.length() <= 55)
                links.keysList().forEach { require(it.matches(Regex("keiyoushi:ar:[a-z0-9]+")) && links.get(it) is String && links.getString(it).length in 1..8192) { "Invalid built-in source mapping" } }
            }
            return o
        }
        fun parse(text: String): LinkedHashMap<String, JSONObject> {
            require(text.toByteArray().size <= 32 * 1024 * 1024) { "Library exceeds 32 MB" }
            val result = linkedMapOf<String, JSONObject>()
            val array = JSONArray(text)
            require(array.length() <= 100000)
            for (raw in array.objects()) {
                val o = normalize(raw); val id = o.getString("id")
                require(result.put(id, o) == null) { "Duplicate manga ID: $id" }
            }
            return result
        }
    }
    init {
        files.mkdirs()
        val folder = File(files, "local-v2").also { it.mkdirs() }
        db = SQLiteDatabase.openDatabase(File(folder, "library.db").path, null, SQLiteDatabase.CREATE_IF_NECESSARY) { throw IllegalStateException("Database damaged; preserved for recovery") }
        if(db.version > 2) { db.close(); error("Database belongs to a newer version; update the app") }
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
        db.execSQL("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS manga (canonical_manga_id TEXT PRIMARY KEY, metadata_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS user_manga (canonical_manga_id TEXT PRIMARY KEY REFERENCES manga ON DELETE CASCADE, status TEXT NOT NULL, current_chapter TEXT NOT NULL, current_volume TEXT NOT NULL, current_page INTEGER NOT NULL CHECK(current_page>=0), last_read_at INTEGER NOT NULL, rating TEXT NOT NULL, notes TEXT NOT NULL, updated_at INTEGER NOT NULL, device_id TEXT NOT NULL, revision INTEGER NOT NULL, extras_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS manga_provider_mapping (provider TEXT NOT NULL, provider_id TEXT NOT NULL, canonical_manga_id TEXT NOT NULL REFERENCES manga ON DELETE CASCADE, PRIMARY KEY(provider,provider_id), UNIQUE(canonical_manga_id,provider))")
        db.execSQL("CREATE TABLE IF NOT EXISTS chapters (provider TEXT NOT NULL, provider_chapter_id TEXT NOT NULL, canonical_manga_id TEXT NOT NULL REFERENCES manga ON DELETE CASCADE, chapter_number TEXT, volume TEXT, language TEXT, scanlation_group TEXT, published_at TEXT, raw_json TEXT NOT NULL, PRIMARY KEY(provider,provider_chapter_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_history (event_id TEXT PRIMARY KEY, canonical_manga_id TEXT NOT NULL REFERENCES manga ON DELETE CASCADE, read_at INTEGER NOT NULL, event_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_queue (canonical_manga_id TEXT PRIMARY KEY REFERENCES manga ON DELETE CASCADE, revision INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at INTEGER NOT NULL DEFAULT 0, last_error TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_baseline (canonical_manga_id TEXT PRIMARY KEY REFERENCES manga ON DELETE CASCADE, account_id TEXT NOT NULL, state_json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS conflicts (canonical_manga_id TEXT PRIMARY KEY REFERENCES manga ON DELETE CASCADE, local_json TEXT NOT NULL, remote_json TEXT NOT NULL, remote_baseline TEXT, reason TEXT NOT NULL)")
        device = meta("device_id") ?: UUID.randomUUID().toString().also { meta("device_id", it) }
        try { if (meta("migration") == null) migrate() } catch(e: Throwable) { db.close(); throw e }
        db.version = 2
        mirrors()
    }
    fun meta(key: String): String? = db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun meta(key: String, value: String) { db.execSQL("INSERT OR REPLACE INTO meta VALUES (?,?)", arrayOf(key, value)) }
    fun <T> transaction(block: () -> T): T { db.beginTransaction(); try { val value = block(); db.setTransactionSuccessful(); return value } finally { db.endTransaction() } }
    private fun migrate() {
        val index = File(files, "manga_index.json")
        val historyFile = File(files, "reading_history.json")
        val text = if (index.exists()) AtomicFile(index).openRead().bufferedReader().use { it.readText() } else "[]"
        val items = parse(text)
        val history = if (historyFile.exists()) JSONArray(AtomicFile(historyFile).openRead().bufferedReader().use { it.readText() }) else JSONArray()
        val backup = File(files, "migration-v1").also { it.mkdirs() }
        // Preserve pre-migration data before committing any database changes.
        if (!File(backup, "manga_index.json").exists()) atomic(File(backup, "manga_index.json"), text)
        if (!File(backup, "reading_history.json").exists()) atomic(File(backup, "reading_history.json"), history.toString())
        transaction {
            items.values.forEach { put(it, false) }
            appendHistory(history)
            meta("migration", "2")
        }
        mirrors()
    }
    fun library(id: String? = null): JSONArray {
        val out = JSONArray()
        db.rawQuery("SELECT m.canonical_manga_id, m.metadata_json,u.status,u.current_chapter,u.current_volume,u.current_page,u.last_read_at,u.rating,u.notes,u.extras_json FROM manga m JOIN user_manga u USING(canonical_manga_id)" + (if(id == null) " ORDER BY m.canonical_manga_id" else " WHERE m.canonical_manga_id=?"), if(id == null) null else arrayOf(id)).use { c ->
            while (c.moveToNext()) {
                val o = JSONObject(c.getString(1)); val extras = JSONObject(c.getString(9))
                extras.keysList().forEach { o.put(it, extras.get(it)) }
                o.put("id", c.getString(0)).put("status", c.getString(2)).put("currentChapter", c.getString(3)).put("currentVolume", c.getString(4)).put("currentPage", c.getInt(5)).put("lastReadAtMillis", c.getLong(6)).put("rating", c.getString(7)).put("notes", c.getString(8))
                out.put(o)
            }
        }
        return out
    }
    fun record(id: String) = library(id).optJSONObject(0)
    fun revision(id: String): Long = db.rawQuery("SELECT revision FROM user_manga WHERE canonical_manga_id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    fun put(raw: JSONObject, enqueue: Boolean = true) {
        if(!db.inTransaction()) { transaction { put(raw,enqueue) }; return }
        val o = normalize(raw); val id = o.getString("id"); val rev = revision(id) + 1
        val previous = record(id)
        if(previous != null && previous.getInt("anilistId") != o.getInt("anilistId")) clearSync(id)
        val metadata = o.copy(); userKeys.forEach { metadata.remove(it) }; metadata.remove("id")
        val extras = JSONObject(); userKeys.forEach { extras.put(it, o.get(it)) }
        db.execSQL("INSERT OR IGNORE INTO manga VALUES (?,?)", arrayOf(id, metadata.toString()))
        db.execSQL("UPDATE manga SET metadata_json=? WHERE canonical_manga_id=?", arrayOf(metadata.toString(),id))
        db.execSQL("INSERT OR REPLACE INTO user_manga VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(id,o.getString("status"),o.getString("currentChapter"),o.getString("currentVolume"),o.getInt("currentPage"),o.getLong("lastReadAtMillis"),o.getString("rating"),o.getString("notes"),System.currentTimeMillis(),device,rev,extras.toString()))
        db.execSQL("DELETE FROM manga_provider_mapping WHERE canonical_manga_id=?", arrayOf(id))
        if (o.getInt("anilistId") > 0) mapping(id,"anilist",o.getInt("anilistId").toString())
        o.optJSONObject("builtinSources")?.let { links -> links.keysList().forEach { mapping(id,it,links.getString(it)) } }
        val url = o.getString("sourceUrl")
        val md = Regex("^https://(?:www\\.)?mangadex\\.org/title/([0-9a-fA-F-]{36})(?:/.*)?$").matchEntire(url)
        if (md != null) mapping(id,"mangadex",md.groupValues[1].lowercase())
        else if (url.isNotBlank()) mapping(id,"source_url",url)
        for (chapter in o.getJSONArray("cachedChapters").objects()) {
            val provider = SourceBridge.find(url)?.provider ?: if (md != null && chapter.optString("id").isNotBlank()) "mangadex" else "legacy:$id"
            val key = chapter.optString("id").ifBlank { chapter.optString("url") }
            if (key.isNotBlank()) db.execSQL("INSERT OR REPLACE INTO chapters VALUES (?,?,?,?,?,?,?,?,?)", arrayOf(provider,key,id,chapter.optString("number"),chapter.optString("volume"),chapter.optString("language"),chapter.optString("scanlation_group"),chapter.optString("published_at"),chapter.toString()))
        }
        if (enqueue && meta("sync_mode") == "two_way" && o.getInt("anilistId") > 0) queue(id,rev)
    }
    private fun mapping(id: String, provider: String, remote: String) {
        val owner = db.rawQuery("SELECT canonical_manga_id FROM manga_provider_mapping WHERE provider=? AND provider_id=?", arrayOf(provider,remote)).use { if(it.moveToFirst()) it.getString(0) else null }
        require(owner == null || owner == id) { "Provider ID already belongs to another manga: $provider / $remote. Resolve duplicate mapping first." }
        db.execSQL("INSERT OR REPLACE INTO manga_provider_mapping VALUES (?,?,?)", arrayOf(provider,remote,id))
    }
    fun queue(id: String, rev: Long = revision(id)) { db.execSQL("INSERT OR REPLACE INTO sync_queue(canonical_manga_id,revision) VALUES (?,?)", arrayOf(id,rev)) }
    private fun clearSync(id: String) {
        for(table in listOf("sync_baseline","sync_queue","conflicts")) db.execSQL("DELETE FROM $table WHERE canonical_manga_id=?",arrayOf(id))
        db.execSQL("DELETE FROM meta WHERE key IN (?,?)",arrayOf("keep_local:$id","create_remote:$id"))
    }
    fun merge(baseText: String, incomingText: String): String = transaction {
        // ponytail: the recovered UI writes whole snapshots; replace this O(n) boundary with row edits when its source is restored.
        val base = parse(baseText); val incoming = parse(incomingText); val current = parse(library().toString())
        // Missing records in a stale UI snapshot are not deletion requests.
        // Actual deletion uses deleteManga(), separately from snapshot persistence.
        for ((id, next) in incoming) {
            require(meta("deleted:$id") == null) { "Manga deleted elsewhere; refresh before editing" }
            val old = base[id]; val now = current[id]
            if (old == null) { require(now == null || same(now,next)) { "Duplicate local ID" }; if(now == null) put(next); continue }
            if (same(old,next)) continue
            require(now != null) { "Manga deleted elsewhere; refresh before editing" }
            val merged = now.copy()
            for (key in next.keysList()) if (!same(old.opt(key),next.opt(key))) {
                val value = next.get(key)
                if (!same(old.opt(key),now.opt(key)) && !same(now.opt(key),value)) {
                    val a = now.optString(key).toBigDecimalOrNull(); val b = next.optString(key).toBigDecimalOrNull(); val start = old.optString(key).toBigDecimalOrNull()
                    if (key == "currentChapter" && a != null && b != null && start != null && a >= start && b >= start) merged.put(key,a.max(b).stripTrailingZeros().toPlainString())
                    else throw IllegalStateException("Concurrent edit to $key; saved data preserved. Refresh and retry.")
                } else merged.put(key,value)
            }
            put(merged)
        }
        library().toString()
    }
    fun appendHistory(array: JSONArray) {
        require(array.length() <= 100000)
        for (event in array.objects()) {
            val id = event.getString("mangaId")
            require(event.get("chapterKey") is String && event.get("chapterNumber") is String)
            require(record(id) != null) { "History refers to missing manga: $id" }
            val time = event.getLong("readAtMillis"); require(time >= 0)
            val key = "$id\u0000${event.getString("chapterKey")}\u0000$time"
            val eventId = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
            db.execSQL("INSERT OR IGNORE INTO reading_history VALUES (?,?,?,?)", arrayOf(eventId,id,time,event.toString()))
        }
    }
    fun history(): JSONArray = JSONArray().also { out -> db.rawQuery("SELECT event_json FROM reading_history ORDER BY read_at DESC",null).use { while(it.moveToNext()) out.put(JSONObject(it.getString(0))) } }
    fun export(): String = JSONObject().put("schemaVersion",2).put("exportedAt",System.currentTimeMillis()).put("library",library()).put("history",history()).toString(2)
    fun deleteManga(id: String) = transaction { clearSync(id); meta("deleted:$id","1"); db.execSQL("DELETE FROM manga WHERE canonical_manga_id=?",arrayOf(id)) }
    fun restore(text: String) {
        require(text.toByteArray().size <= 32*1024*1024)
        val backup = JSONObject(text); require(backup.getInt("schemaVersion") == 2) { "Unsupported backup version" }
        val records = parse(backup.getJSONArray("library").toString())
        val history = backup.getJSONArray("history")
        atomic(File(files,"before-restore-${System.currentTimeMillis()}.json"),export())
        transaction {
            // Import is a deliberate local replacement; tokens and account settings are never imported.
            db.execSQL("DELETE FROM manga")
            db.execSQL("DELETE FROM meta WHERE key LIKE 'deleted:%' OR key LIKE 'keep_local:%' OR key LIKE 'create_remote:%'")
            records.values.forEach { put(it) }
            appendHistory(history)
        }
        mirrors()
    }
    fun mirrors() { atomic(File(files,"manga_index.json"),legacyLibrary()); atomic(File(files,"reading_history.json"),history().toString()) }
    fun legacyLibrary(): String = JSONArray().also { out -> for (o in library().objects()) { o.remove("builtinSources"); o.remove("currentVolume"); o.remove("currentPage"); o.remove("notes"); if(o.getString("status") in listOf("ON_HOLD","DROPPED","REREADING")) o.put("status","READING"); for(c in o.getJSONArray("cachedChapters").objects()) c.keysList().filter { it !in listOf("id","name","url","number") }.forEach { c.remove(it) }; out.put(o) } }.toString()
    override fun close() = db.close()
}
