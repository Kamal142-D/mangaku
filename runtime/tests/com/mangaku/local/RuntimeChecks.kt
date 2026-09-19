package com.mangaku.local

import android.app.Instrumentation
import android.os.Bundle
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Runs on real Android SQLite/AtomicFile/Keystore, with an in-memory AniList fake. */
class RuntimeChecks: Instrumentation() {
    private val passed = mutableListOf<String>()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun scenario(name: String, test: () -> Unit) { test(); passed.add(name) }
    private fun rejected(block: () -> Unit) { var failed = false; try { block() } catch(_: Exception) { failed = true }; check(failed) { "Expected operation to fail" } }
    private fun directory() = File(context.filesDir,"checks-${UUID.randomUUID()}").also { it.mkdirs() }
    private fun manga(id: String = "manga-a", chapter: String = "50") = JSONObject().put("id",id).put("title","Same title").put("pageCount",12).put("addedAtMillis",1L).put("currentChapter",chapter).put("source","ZIP").put("notes","Local notes").put("anilistId",0)
    private fun seed(dir: File, vararg records: JSONObject) { LocalStore.atomic(File(dir,"manga_index.json"),JSONArray(records.toList()).toString()) }
    private fun history(id: String) = JSONArray().put(JSONObject().put("mangaId",id).put("chapterKey","chapter-10.5").put("chapterNumber","10.5").put("readAtMillis",123L))
    private fun count(s: LocalStore, table: String) = s.db.rawQuery("SELECT count(*) FROM $table",null).use { it.moveToFirst(); it.getInt(0) }
    private fun state(progress: Int) = JSONObject().put("progress",progress).put("progressVolumes",0).put("status","CURRENT").put("score",0.0).put("notes","Local notes")
    private class Fake(var remote: JSONObject): TrackingProvider {
        var saves = 0; var failure = false; var missing = false; var duringSave: (() -> Unit)? = null
        override fun viewer() = JSONObject().put("id",42).put("name","Test reader")
        override fun entries(userId: Int) = if(missing) emptyList() else listOf(remote.copy().put("mediaId",7).put("media",JSONObject().put("title",JSONObject().put("userPreferred","Remote title")).put("coverImage",JSONObject().put("large","https://example.invalid/cover.jpg"))))
        override fun save(mediaId: Int, state: JSONObject): JSONObject {
            if(failure) throw ApiFailure(60000,"Simulated offline failure")
            saves++; duringSave?.invoke(); remote = state.copy(); return state.copy()
        }
    }
    override fun onStart() {
        try {
            scenario("migration preserves identity, fields, fractional chapters and history") {
                val dir = directory(); val a = manga().put("categories",JSONArray().put("Favorites")).put("chapterPages",JSONObject().put("n:10.5",4)).put("cachedChapters",JSONArray().put(JSONObject().put("name","Special").put("url","https://example.invalid/special").put("id","special").put("number","10.5"))).put("localChapters",JSONArray().put(JSONObject().put("id","chapter-a").put("title","Imported").put("number","1").put("pageCount",4).put("addedAtMillis",1)))
                seed(dir,a,manga("manga-b")); LocalStore.atomic(File(dir,"reading_history.json"),history("manga-a").toString())
                val original = File(dir,"manga_index.json").readText()
                LocalStore(dir).use { s ->
                    check(s.library().length() == 2); check(same(s.record("manga-a"),LocalStore.normalize(a))); check(s.history().length()==1); check(count(s,"chapters")==1)
                    check(File(dir,"migration-v1/manga_index.json").readText()==original)
                    s.put(s.record("manga-a")!!.put("currentChapter","52"))
                }
                LocalStore.atomic(File(dir,"manga_index.json"),"[]")
                LocalStore(dir).use { check(it.library().length()==2); check(it.record("manga-a")!!.getString("currentChapter")=="52"); check(it.meta("migration")=="2"); check(JSONArray(File(dir,"manga_index.json").readText()).length()==2) }
            }
            scenario("invalid source cannot become an empty replacement library") {
                val dir = directory(); val file = File(dir,"manga_index.json"); file.writeText("[broken")
                rejected { LocalStore(dir) }; check(file.readText()=="[broken")
                seed(dir,manga()); LocalStore(dir).use { check(it.library().length()==1) }
            }
            scenario("migration transaction rolls back invalid history and can rerun") {
                val dir = directory(); seed(dir,manga()); LocalStore.atomic(File(dir,"reading_history.json"),history("missing").toString())
                rejected { LocalStore(dir) }
                LocalStore.atomic(File(dir,"reading_history.json"),history("manga-a").toString())
                LocalStore(dir).use { check(it.library().length()==1); check(it.history().length()==1) }
            }
            scenario("export restore round trip and failed restore rollback") {
                LocalStore(directory()).use { s ->
                    s.put(manga().put("currentVolume","4").put("currentPage",5).put("notes","Keep me")); s.appendHistory(history("manga-a"))
                    val backup=s.export(); val before=s.library(); val beforeHistory=s.history()
                    s.put(s.record("manga-a")!!.put("notes","Changed")); s.restore(backup); check(same(before,s.library())); check(same(beforeHistory,s.history()))
                    val bad=JSONObject(backup).put("history",history("missing")).toString(); rejected { s.restore(bad) }; check(same(before,s.library())); check(same(beforeHistory,s.history()))
                    check(File(s.files,"migration-v1/manga_index.json").exists()); check(!JSONObject(backup).has("token"))
                }
            }
            scenario("rollback, read-only disk failure, and unique mapping are safe") {
                LocalStore(directory()).use { s ->
                    s.put(manga().put("anilistId",7)); val before=s.library()
                    rejected { s.transaction { s.put(s.record("manga-a")!!.put("currentChapter","99")); error("Power loss before commit") } }; check(same(before,s.library()))
                    rejected { s.put(manga("manga-b").put("anilistId",7)) }; check(same(before,s.library()))
                    s.db.execSQL("PRAGMA query_only=ON"); rejected { s.put(manga().put("currentChapter","88")) }; s.db.execSQL("PRAGMA query_only=OFF"); check(same(before,s.library()))
                }
            }
            scenario("stale snapshots preserve unrelated edits and require intentional deletes") {
                LocalStore(directory()).use { s ->
                    s.put(manga()); val base=s.library().toString()
                    s.put(s.record("manga-a")!!.put("notes","Other window")); s.put(manga("manga-b"))
                    val incoming=JSONArray(base); incoming.getJSONObject(0).put("currentChapter","52")
                    s.merge(base,incoming.toString()); check(s.record("manga-a")!!.getString("notes")=="Other window"); check(s.record("manga-b")!=null)
                    s.merge(s.library().toString(),"[]"); check(s.library().length()==2)
                    s.deleteManga("manga-b"); check(s.library().length()==1)
                    rejected { s.merge("[]",JSONArray().put(manga("manga-b")).toString()) }; check(s.library().length()==1)
                }
            }
            scenario("concurrent forward progress merges; concurrent reset does not") {
                LocalStore(directory()).use { s ->
                    s.put(manga()); val base=s.library().toString(); s.put(s.record("manga-a")!!.put("currentChapter","52"))
                    val incoming=JSONArray(base); incoming.getJSONObject(0).put("currentChapter","51"); s.merge(base,incoming.toString()); check(s.record("manga-a")!!.getString("currentChapter")=="52")
                    incoming.getJSONObject(0).put("currentChapter","1"); rejected { s.merge(base,incoming.toString()) }; check(s.record("manga-a")!!.getString("currentChapter")=="52")
                    val fresh=s.library().toString(); val reset=JSONArray(fresh); reset.getJSONObject(0).put("currentChapter","1"); s.merge(fresh,reset.toString()); check(s.record("manga-a")!!.getString("currentChapter")=="1")
                }
            }
            scenario("sync off performs no remote calls") {
                LocalStore(directory()).use { s -> SyncEngine(s,object: TrackingProvider {
                    override fun viewer(): JSONObject = error("Unexpected network request")
                    override fun entries(userId: Int): List<JSONObject> = error("Unexpected network request")
                    override fun save(mediaId: Int,state: JSONObject): JSONObject = error("Unexpected network request")
                }).run() }
            }
            scenario("first sync conflicts, explicit resolution, durable retry and revision acknowledgement") {
                val dir=directory(); val fake=Fake(state(49))
                LocalStore(dir).use { s ->
                    s.meta("sync_mode","two_way"); s.meta("account_id","42"); s.put(manga().put("anilistId",7))
                    val engine=SyncEngine(s,fake); engine.run(); check(fake.saves==0); check(count(s,"conflicts")==1)
                    engine.resolve("manga-a",false); fake.failure=true; rejected { engine.run() }; check(count(s,"sync_queue")==1)
                }
                LocalStore(dir).use { s ->
                    check(count(s,"sync_queue")==1); s.db.execSQL("UPDATE sync_queue SET next_attempt_at=0"); fake.failure=false
                    fake.duringSave = { s.put(s.record("manga-a")!!.put("notes","Edited in flight")) }
                    SyncEngine(s,fake).run(); check(fake.saves==1); check(count(s,"sync_queue")==1); check(s.record("manga-a")!!.getString("notes")=="Edited in flight")
                    fake.duringSave=null; SyncEngine(s,fake).run(); check(fake.saves==2); check(count(s,"sync_queue")==0)
                }
            }
            scenario("import only never pushes; progress and notes conflicts remain reviewable") {
                LocalStore(directory()).use { s ->
                    s.meta("sync_mode","import_only"); s.meta("account_id","42"); val fake=Fake(state(12).put("status","PAUSED"))
                    SyncEngine(s,fake).run(); check(fake.saves==0); val imported=s.library().getJSONObject(0); check(imported.getString("status")=="ON_HOLD"); check(s.legacyLibrary().contains("READING"))
                    s.put(imported.put("notes","Local edit")); fake.remote.put("notes","Remote edit"); SyncEngine(s,fake).run(); check(count(s,"conflicts")==1); check(fake.saves==0)
                    SyncEngine(s,fake).resolve(imported.getString("id"),true); check(s.record(imported.getString("id"))!!.getString("notes")=="Remote edit")
                }
                check(SyncEngine.reconcile(state(50),state(51),state(52))!!.getInt("progress")==52)
                check(SyncEngine.reconcile(state(50),state(1),state(52))==null)
                check(SyncEngine.reconcile(null,state(1),state(52))==null)
            }
            scenario("atomic rollback, archive containment and token encryption") {
                val dir=directory(); val file=File(dir,"safe.json"); LocalStore.atomic(file,"good")
                val atom=AtomicFile(file); val out=atom.startWrite(); out.write("interrupted".toByteArray()); atom.failWrite(out); check(file.readText()=="good")
                check(Bridge.safeEntry(dir,"chapter/page.jpg").path.startsWith(dir.canonicalPath + File.separator))
                rejected { Bridge.safeEntry(dir,"../escape") }; rejected { Bridge.safeEntry(dir,"..\\escape") }
                Credentials.save(context,"a-test-token-with-no-real-account"); check(Credentials.read(context)=="a-test-token-with-no-real-account"); Credentials.clear(context); check(Credentials.read(context)==null)
            }
            scenario("missing AniList entry requires consent and unlink never resets progress") {
                LocalStore(directory()).use { s ->
                    s.meta("sync_mode","two_way"); s.meta("account_id","42"); s.put(manga().put("anilistId",7))
                    val fake=Fake(state(0)).apply { missing=true }; val engine=SyncEngine(s,fake)
                    engine.run(); check(count(s,"conflicts")==1); check(fake.saves==0)
                    engine.resolve("manga-a",true); check(s.record("manga-a")!!.getString("currentChapter")=="50"); check(s.record("manga-a")!!.getInt("anilistId")==0); check(count(s,"conflicts")==0)
                    s.put(s.record("manga-a")!!.put("anilistId",7)); engine.run(); engine.resolve("manga-a",false); engine.run(); check(fake.saves==1)
                }
            }
            scenario("keep local in import-only stays resolved; relinking clears the baseline") {
                LocalStore(directory()).use { s ->
                    s.meta("sync_mode","import_only"); s.meta("account_id","42"); s.put(manga().put("anilistId",7))
                    val fake=Fake(state(12)); val engine=SyncEngine(s,fake)
                    engine.run(); engine.resolve("manga-a",false); engine.run(); check(count(s,"conflicts")==0); check(fake.saves==0)
                    fake.remote=state(13); engine.run(); check(count(s,"conflicts")==1)
                    s.put(s.record("manga-a")!!.put("anilistId",8)); check(count(s,"conflicts")==0); check(count(s,"sync_baseline")==0); check(s.meta("keep_local:manga-a")==null)
                }
            }
            scenario("relink during network save cannot acknowledge the new provider mapping") {
                LocalStore(directory()).use { s ->
                    s.meta("sync_mode","two_way"); s.meta("account_id","42"); s.put(manga().put("anilistId",7))
                    val fake=Fake(state(12)); val engine=SyncEngine(s,fake); engine.run(); engine.resolve("manga-a",false)
                    fake.duringSave={s.put(s.record("manga-a")!!.put("anilistId",8))}; engine.run()
                    check(count(s,"sync_baseline")==0); check(count(s,"sync_queue")==1); check(s.record("manga-a")!!.getInt("anilistId")==8)
                }
            }
            scenario("a newer database version is preserved and never downgraded") {
                val dir=directory(); LocalStore(dir).use { it.put(manga()); it.db.version=3 }
                rejected { LocalStore(dir) }
                android.database.sqlite.SQLiteDatabase.openDatabase(File(dir,"local-v2/library.db").path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                    check(db.version==3); db.rawQuery("SELECT current_chapter FROM user_manga",null).use { check(it.moveToFirst()); check(it.getString(0)=="50") }
                }
            }
            finish(-1,Bundle().apply { putString("stream","PASS ${passed.size} scenarios\n"+passed.joinToString("\n")) })
        } catch(e: Throwable) {
            finish(0,Bundle().apply { putString("stream","FAILED after ${passed.size} scenarios\n${e.stackTraceToString()}") })
        }
    }
}
