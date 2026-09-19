package com.mangaku.local

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object Credentials {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("mangaku-local-sync",null) as? SecretKey) ?: KeyGenerator.getInstance("AES","AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("mangaku-local-sync",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build()); generateKey()
        }
    }
    fun save(context: Context, token: String) {
        require(token.length in 16..8192 && !token.any { it.isWhitespace() }) { "Invalid access token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val data = Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)
        check(context.getSharedPreferences("sync-secret",0).edit().putString("token",data).commit())
    }
    fun read(context: Context): String? {
        val data = context.getSharedPreferences("sync-secret",0).getString("token",null) ?: return null
        val bytes = Base64.decode(data,Base64.NO_WRAP)
        return String(Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12))); doFinal(bytes.copyOfRange(12,bytes.size)) },Charsets.UTF_8)
    }
    fun clear(context: Context) { check(context.getSharedPreferences("sync-secret",0).edit().clear().commit()) }
}

interface TrackingProvider {
    fun viewer(): JSONObject
    fun entries(userId: Int): List<JSONObject>
    fun save(mediaId: Int, state: JSONObject): JSONObject
}
class ApiFailure(val retryAfter: Long, message: String): Exception(message)
class AniList(private val token: String): TrackingProvider {
    private var lastRequest = 0L
    private fun request(query: String, variables: JSONObject = JSONObject()): JSONObject {
        val delay = 2100 - (System.currentTimeMillis()-lastRequest)
        if (delay > 0) Thread.sleep(delay)
        val conn = URL("https://graphql.anilist.co").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 20000; conn.instanceFollowRedirects = false; conn.doOutput = true
            conn.setRequestProperty("Authorization","Bearer $token"); conn.setRequestProperty("Content-Type","application/json"); conn.setRequestProperty("Accept","application/json")
            conn.outputStream.use { it.write(JSONObject().put("query",query).put("variables",variables).toString().toByteArray()) }
            lastRequest = System.currentTimeMillis()
            val status = conn.responseCode
            if(status !in 200..299) throw ApiFailure((conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 60).coerceIn(1,86400)*1000, "AniList HTTP $status")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            require(text.length <= 8*1024*1024) { "AniList response too large" }
            val response = JSONObject(text)
            if(response.has("errors")) throw ApiFailure(60000,"AniList rejected the request; check account permissions")
            return response.getJSONObject("data")
        } finally { conn.disconnect() }
    }
    override fun viewer() = request("query { Viewer { id name } }").getJSONObject("Viewer")
    override fun entries(userId: Int): List<JSONObject> {
        val out = mutableListOf<JSONObject>(); var page = 1
        do {
            val result = request("query (\$user: Int!, \$page: Int!) { Page(page: \$page, perPage: 50) { pageInfo { hasNextPage } mediaList(userId: \$user, type: MANGA) { mediaId progress progressVolumes status score(format: POINT_100) notes media { title { userPreferred } coverImage { large } } } }",JSONObject().put("user",userId).put("page",page)).getJSONObject("Page")
            out.addAll(result.getJSONArray("mediaList").objects())
            val more = result.getJSONObject("pageInfo").getBoolean("hasNextPage")
            page++; require(page <= 2001) { "AniList list exceeds supported size" }
        } while(more)
        return out
    }
    override fun save(mediaId: Int, state: JSONObject): JSONObject {
        val v = state.copy().put("mediaId",mediaId).put("score",kotlin.math.round(state.getDouble("score")).toInt())
        return request("mutation (\$mediaId: Int!, \$progress: Int!, \$progressVolumes: Int!, \$status: MediaListStatus!, \$score: Int!, \$notes: String) { SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, progressVolumes: \$progressVolumes, status: \$status, scoreRaw: \$score, notes: \$notes) { mediaId progress progressVolumes status score(format: POINT_100) notes } }",v).getJSONObject("SaveMediaListEntry")
    }
}

class SyncEngine(private val store: LocalStore, private val provider: TrackingProvider) {
    companion object {
        fun remoteState(o: JSONObject) = JSONObject().put("progress",o.optInt("progress")).put("progressVolumes",o.optInt("progressVolumes")).put("status",o.optString("status","PLANNING")).put("score",o.optDouble("score",0.0)).put("notes",if(o.isNull("notes")) "" else o.optString("notes"))
        fun localState(o: JSONObject): JSONObject {
            val chapter = o.optString("currentChapter").ifBlank { "0" }.toBigDecimalOrNull() ?: throw IllegalArgumentException("Special chapter requires manual AniList progress")
            val volume = o.optString("currentVolume").ifBlank { "0" }.toBigDecimalOrNull() ?: throw IllegalArgumentException("Invalid volume")
            require(chapter.signum() >= 0 && chapter <= java.math.BigDecimal(Int.MAX_VALUE) && volume.signum() >= 0 && volume <= java.math.BigDecimal(Int.MAX_VALUE))
            val rating = o.optString("rating").ifBlank { "0" }.toDoubleOrNull() ?: throw IllegalArgumentException("Rating must be a number from 0 to 100")
            require(rating.isFinite() && rating in 0.0..100.0)
            require(o.optString("notes").length <= 6000) { "AniList notes are limited to 6000 characters; local notes were preserved" }
            val status = when(o.optString("status")) { "READING" -> "CURRENT"; "FINISHED" -> "COMPLETED"; "ON_HOLD" -> "PAUSED"; "DROPPED" -> "DROPPED"; "REREADING" -> "REPEATING"; else -> "PLANNING" }
            return JSONObject().put("progress",chapter.toInt()).put("progressVolumes",volume.toInt()).put("status",status).put("score",rating).put("notes",o.optString("notes"))
        }
        // Only monotonic progress from the same baseline merges automatically.
        // A reset, status, note, score, or volume disagreement remains a visible conflict.
        fun reconcile(base: JSONObject?, local: JSONObject, remote: JSONObject): JSONObject? {
            if(same(local,remote)) return local.copy()
            if(base == null) return null
            val result = local.copy()
            for(k in remote.keysList()) {
                val l = local.opt(k); val r = remote.opt(k); val b = base.opt(k)
                when { same(l,r) -> Unit; same(l,b) -> result.put(k,r); same(r,b) -> Unit
                    k == "progress" && local.getInt(k) >= base.getInt(k) && remote.getInt(k) >= base.getInt(k) -> result.put(k,maxOf(local.getInt(k),remote.getInt(k)))
                    else -> return null }
            }
            return result
        }
    }
    fun run() {
        val mode = synchronized(store) { store.meta("sync_mode") ?: "none" }
        if(mode == "none") return
        val viewer = provider.viewer(); val account = viewer.getInt("id").toString()
        synchronized(store) { require(store.meta("account_id") == account) { "Account changed; reconnect before syncing" }; store.meta("sync_error","") }
        val entries = provider.entries(account.toInt())
        val seen = mutableSetOf<Int>()
        for(entry in entries) {
            val mediaId = entry.getInt("mediaId"); seen.add(mediaId)
            synchronized(store) {
                require(store.meta("sync_mode") == mode && store.meta("account_id") == account) { "Sync settings changed" }
                if(store.library().objects().none { it.optInt("anilistId") == mediaId }) {
                    val o = JSONObject().put("id",UUID.randomUUID().toString()).put("title",entry.getJSONObject("media").getJSONObject("title").getString("userPreferred")).put("pageCount",0).put("addedAtMillis",System.currentTimeMillis()).put("source","SEARCH").put("anilistId",mediaId).put("coverUrl",entry.getJSONObject("media").getJSONObject("coverImage").optString("large"))
                    store.transaction { applyRemote(LocalStore.normalize(o),remoteState(entry)); baseline(o.getString("id"),account,remoteState(entry)) }
                }
            }
            syncOne(mediaId,remoteState(entry),mode,account)
        }
        val missing = synchronized(store) { store.library().objects().filter { it.optInt("anilistId") > 0 && it.optInt("anilistId") !in seen } }
        for(o in missing) syncOne(o.getInt("anilistId"),JSONObject().put("progress",0).put("progressVolumes",0).put("status","PLANNING").put("score",0.0).put("notes","").put("_missing",true),mode,account)
        synchronized(store) { store.meta("last_sync",System.currentTimeMillis().toString()); store.mirrors() }
    }
    private fun baseline(id: String, account: String, state: JSONObject) { store.db.execSQL("INSERT OR REPLACE INTO sync_baseline VALUES (?,?,?)",arrayOf(id,account,state.toString())) }
    private fun applyRemote(o: JSONObject, state: JSONObject) {
        val oldNumeric = o.optString("currentChapter").toBigDecimalOrNull()
        if(oldNumeric == null || oldNumeric.toInt() != state.getInt("progress")) o.put("currentChapter",state.getInt("progress").toString())
        o.put("currentVolume",state.getInt("progressVolumes").toString()).put("rating",state.getDouble("score").toString()).put("notes",state.getString("notes"))
        o.put("status", when(state.getString("status")) { "COMPLETED" -> "FINISHED"; "PLANNING" -> "PLAN_TO_READ"; "PAUSED" -> "ON_HOLD"; "DROPPED" -> "DROPPED"; "REPEATING" -> "REREADING"; else -> "READING" })
        store.put(o,false)
    }
    private fun syncOne(mediaId: Int, remote: JSONObject, mode: String, account: String) {
        var pending: JSONObject? = null; var id = ""; var revision = 0L
        synchronized(store) {
            val o = store.library().objects().firstOrNull { it.optInt("anilistId") == mediaId } ?: return; id = o.getString("id")
            val wait = store.db.rawQuery("SELECT next_attempt_at FROM sync_queue WHERE canonical_manga_id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getLong(0) else 0 }
            if(wait > System.currentTimeMillis()) return
            val local = try { localState(o) } catch(e: IllegalArgumentException) { store.meta("sync_error",e.message ?: "Progress requires review"); return }
            val ignored = store.meta("keep_local:$id")
            if(mode == "import_only" && ignored != null && same(remote,JSONObject(ignored))) return
            val base = store.db.rawQuery("SELECT state_json FROM sync_baseline WHERE canonical_manga_id=? AND account_id=?",arrayOf(id,account)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
            val createRemote = remote.optBoolean("_missing") && store.meta("create_remote:$id") == account
            val resolved = if(createRemote) local else if(remote.optBoolean("_missing")) null else reconcile(base,local,remote)
            if(resolved == null || (mode == "import_only" && !same(local,remote) && (base == null || !same(local,base)))) {
                store.db.execSQL("INSERT OR REPLACE INTO conflicts VALUES (?,?,?,?,?)",arrayOf(id,local.toString(),remote.toString(),remote.toString(),"Local and AniList changes need review")); return
            }
            store.transaction {
                if(!same(local,resolved)) applyRemote(o,resolved)
                baseline(id,account,remote)
                store.db.execSQL("DELETE FROM conflicts WHERE canonical_manga_id=?",arrayOf(id))
                if(mode == "two_way" && (!same(resolved,remote) || createRemote)) { store.queue(id); pending = resolved; revision = store.revision(id) }
                else store.db.execSQL("DELETE FROM sync_queue WHERE canonical_manga_id=?",arrayOf(id))
            }
        }
        if(pending != null) try {
            synchronized(store) { require(store.meta("sync_mode") == "two_way" && store.meta("account_id") == account) { "Sync settings changed" } }
            val saved = remoteState(provider.save(mediaId,pending!!))
            synchronized(store) {
                store.transaction {
                    if(store.record(id)?.optInt("anilistId") != mediaId) return@transaction
                    baseline(id,account,saved)
                    store.db.execSQL("DELETE FROM meta WHERE key=?",arrayOf("create_remote:$id"))
                    store.db.execSQL("DELETE FROM sync_queue WHERE canonical_manga_id=? AND revision=?",arrayOf(id,revision))
                }
            }
        } catch(e: Exception) {
            synchronized(store) {
                val attempts = store.db.rawQuery("SELECT attempts FROM sync_queue WHERE canonical_manga_id=?",arrayOf(id)).use { if(it.moveToFirst()) it.getInt(0)+1 else 1 }
                val delay = if(e is ApiFailure) e.retryAfter else minOf(21600000L,60000L*(1L shl minOf(attempts,8)))
                store.db.execSQL("UPDATE sync_queue SET attempts=?,next_attempt_at=?,last_error=? WHERE canonical_manga_id=?",arrayOf(attempts,System.currentTimeMillis()+delay,e.message ?: "Sync failed",id))
                throw e
            }
        }
    }
    fun resolve(id: String, useRemote: Boolean) = synchronized(store) {
        store.transaction {
            val row = store.db.rawQuery("SELECT remote_json FROM conflicts WHERE canonical_manga_id=?",arrayOf(id)).use { check(it.moveToFirst()); JSONObject(it.getString(0)) }
            if(useRemote && row.optBoolean("_missing")) {
                store.put(store.record(id)!!.put("anilistId",0),false)
                return@transaction
            }
            if(useRemote) applyRemote(store.record(id)!!,row)
            if(!useRemote && store.meta("sync_mode") == "import_only") store.meta("keep_local:$id",row.toString())
            if(!useRemote && row.optBoolean("_missing")) store.meta("create_remote:$id",store.meta("account_id")!!)
            baseline(id,store.meta("account_id")!!,row)
            store.db.execSQL("DELETE FROM conflicts WHERE canonical_manga_id=?",arrayOf(id))
            if(!useRemote && store.meta("sync_mode") == "two_way") store.queue(id)
        }
    }
}

class SyncJob: JobService() {
    override fun onStartJob(params: JobParameters): Boolean { Bridge.sync { retry -> jobFinished(params,retry) }; return true }
    override fun onStopJob(params: JobParameters) = true
    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val info = JobInfo.Builder(24002,ComponentName(context,SyncJob::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60*1000L).setBackoffCriteria(60000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            scheduler.schedule(info)
        }
    }
}
