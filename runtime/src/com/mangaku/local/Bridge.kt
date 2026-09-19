package com.mangaku.local

import android.app.Activity
import android.app.Application
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object Bridge {
    lateinit var app: Application
    var store: LocalStore? = null
        private set
    var error: String? = null
        private set
    private val owners = WeakHashMap<Any,String>()
    private val loaded = ThreadLocal<String>()
    val worker = Executors.newSingleThreadExecutor()
    val busy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic fun init(application: Application) {
        app = application
        try { store = LocalStore(app.filesDir); SyncJob.schedule(app) }
        catch(e: Exception) { fail(e) }
        app.registerActivityLifecycleCallbacks(object: Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if(activity.javaClass.name == "com.mangaku.app.MainActivity" && error != null) AlertDialog.Builder(activity).setTitle("Data needs attention").setMessage(error + "\nYour saved files have been preserved.").setPositiveButton("Data & sync") { _,_ -> open(activity) }.setNegativeButton("Close",null).show()
            }
            override fun onActivityCreated(a: Activity,b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity,b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }
    fun fail(e: Throwable) {
        error = e.message ?: "Could not save data"
        Log.e("MangakuLocal","Local operation failed",e)
        main.post { Toast.makeText(app,"Not saved: $error",Toast.LENGTH_LONG).show() }
    }
    fun <T> use(block: (LocalStore) -> T): T {
        val s = store ?: throw IllegalStateException(error ?: "Local storage unavailable")
        return synchronized(s) { block(s) }
    }
    @JvmStatic fun register(owner: Any) { if(store == null) return; use { synchronized(owners) { owners[owner] = loaded.get() ?: it.library().toString(); loaded.remove() } } }
    @JvmStatic fun read(file: File): String {
        if(file.parentFile?.canonicalFile == app.filesDir.canonicalFile) {
            if(file.name == "manga_index.json") return use { loaded.set(it.library().toString()); it.legacyLibrary() }
            if(file.name == "reading_history.json") return use { it.history().toString() }
        }
        return android.util.AtomicFile(file).openRead().bufferedReader().use { it.readText() }
    }
    @JvmStatic fun write(file: File, text: String) {
        try {
            if(file.parentFile?.canonicalFile == app.filesDir.canonicalFile && file.name == "reading_history.json") {
                use { s -> s.transaction { s.appendHistory(JSONArray(text)) }; s.mirrors() }
            } else {
                check(file.name != "manga_index.json" || file.parentFile?.canonicalFile != app.filesDir.canonicalFile) { "Unregistered library writer" }
                LocalStore.atomic(file,text)
            }
        } catch(e: Exception) { fail(e); throw e }
    }
    @JvmStatic fun save(owner: Any, text: String) {
        try {
            use { s ->
                val base = synchronized(owners) { owners[owner] } ?: throw IllegalStateException("Library snapshot is not registered")
                val previous = LocalStore.parse(base)
                val incoming = JSONArray(text)
                // The old serializer does not know the new fields. Preserve them across old UI edits.
                for(o in incoming.objects()) previous[o.getString("id")]?.let { old ->
                    for(k in listOf("notes","currentVolume","currentPage")) o.put(k,old.get(k))
                    old.optJSONObject("builtinSources")?.let { o.put("builtinSources",it) }
                    val chapters=old.getJSONArray("cachedChapters").objects().associateBy { it.getString("url") }
                    for(c in o.optJSONArray("cachedChapters")?.objects().orEmpty()) chapters[c.getString("url")]?.let { before ->
                        before.keysList().filter { !c.has(it) }.forEach { c.put(it,before.get(it)) }
                    }
                    if(old.getString("status") in listOf("ON_HOLD","DROPPED","REREADING") && o.optString("status","READING") == "READING") o.put("status",old.getString("status"))
                }
                s.merge(base,incoming.toString())
                publishAll(); s.mirrors(); error = null
            }
        } catch(e: Exception) { fail(e); try { publishAll() } catch(_: Exception) {} }
    }
    fun publishAll() = use { s ->
        val json = s.legacyLibrary()
        synchronized(owners) {
            if(owners.isEmpty()) return@synchronized
            val serializerType = Class.forName("kotlinx.serialization.KSerializer")
            val serializer = Class.forName("com.mangaku.app.data.Manga\$\$serializer").getField("INSTANCE").get(null)
            val listSerializer = Class.forName("x3.c").getConstructor(serializerType).newInstance(serializer)
            val codec = Class.forName("q2.B0").getField("d").get(null)
            val list = codec.javaClass.getMethod("a",String::class.java,serializerType).invoke(codec,json,listSerializer)
            for(owner in owners.keys.toList()) {
                val flow = owner.javaClass.getField("b").get(owner)
                flow.javaClass.getMethod("l",Any::class.java,Any::class.java).invoke(flow,null,list)
                owners[owner] = s.library().toString()
            }
        }
    }
    @JvmStatic fun open(context: Context) { context.startActivity(Intent(context,DataActivity::class.java).addFlags(if(context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun connect(token: String) {
        check(!busy.get()) { "Wait for the current sync" }
        val viewer = AniList(token).viewer()
        use { s ->
            s.transaction {
                s.meta("sync_mode","none")
                if(s.meta("account_id") != viewer.getInt("id").toString()) {
                    s.db.execSQL("DELETE FROM sync_baseline"); s.db.execSQL("DELETE FROM sync_queue"); s.db.execSQL("DELETE FROM conflicts")
                    s.db.execSQL("DELETE FROM meta WHERE key LIKE 'keep_local:%' OR key LIKE 'create_remote:%'")
                }
                Credentials.save(app,token)
                s.meta("account_id",viewer.getInt("id").toString()); s.meta("account_name",viewer.getString("name"))
            }
        }
    }
    fun mode(value: String) {
        check(!busy.get()) { "Wait for the current sync" }
        require(value in listOf("none","import_only","two_way"))
        use { s -> s.transaction {
            if(value != "none") require(s.meta("account_id") != null && Credentials.read(app) != null) { "Connect AniList first" }
            s.meta("sync_mode",value)
            if(value == "two_way") s.library().objects().filter { it.optInt("anilistId") > 0 }.forEach { s.queue(it.getString("id")) }
        } }
    }
    fun sync(done: (Boolean) -> Unit = {}) {
        if(!busy.compareAndSet(false,true)) { done(false); return }
        worker.execute {
            var retry = false
            try {
                if(use { it.meta("sync_mode") ?: "none" } != "none") {
                    val token = Credentials.read(app) ?: throw IllegalStateException("Connect AniList first")
                    SyncEngine(store!!,AniList(token)).run(); publishAll()
                }
            } catch(e: Exception) { retry = e is ApiFailure || e is java.io.IOException; store?.let { s -> synchronized(s) { s.meta("sync_error",e.message ?: "Sync failed") } } }
            finally { busy.set(false); main.post { done(retry) } }
        }
    }
    @JvmStatic fun safeEntry(parent: File, name: String): File {
        val root = parent.canonicalFile
        val target = File(root,name.replace('\\','/')).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Unsafe archive path" }
        return target
    }
    @JvmStatic fun deleteManga(id: String) {
        try {
            use { s ->
                val directory = safeEntry(File(app.filesDir,"manga"),id)
                s.deleteManga(id); publishAll(); s.mirrors()
                if(directory.exists() && !directory.deleteRecursively()) error = "Manga removed; some downloaded files could not be cleaned up"
            }
        } catch(e: Exception) { fail(e); throw e }
    }
}
