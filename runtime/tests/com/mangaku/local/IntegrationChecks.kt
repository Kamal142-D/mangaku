package com.mangaku.local

import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exercises the installed release's actual obfuscated repository and serializer. */
class IntegrationChecks: Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val id = "integration-${UUID.randomUUID()}"
        try {
            waitForIdleSync()
            val loader = targetContext.classLoader
            val bridge = loader.loadClass("com.mangaku.local.Bridge")
            val singleton = bridge.getField("INSTANCE").get(null)
            val store = bridge.getMethod("getStore").invoke(singleton)!!
            val storeType = store.javaClass
            check(storeType.getMethod("meta",String::class.java).invoke(store,"sync_mode") in listOf(null,"none")) { "Integration checks require sync off" }
            val repositoryType = loader.loadClass("q2.B0")
            val repository = repositoryType.getConstructor(Context::class.java,List::class.java).newInstance(targetContext,emptyList<Any>())
            val snapshot = storeType.getMethod("legacyLibrary").invoke(store) as String
            val added = JSONObject().put("id",id).put("title","Integration offline manga").put("pageCount",0).put("addedAtMillis",1L).put("currentChapter","10.5")
            val incoming = JSONArray(snapshot).put(added)
            bridge.getMethod("save",Any::class.java,String::class.java).invoke(null,repository,incoming.toString())
            fun record() = storeType.getMethod("record",String::class.java).invoke(store,id) as? JSONObject
            check(record()?.getString("currentChapter")=="10.5") { "Recovered save did not persist" }
            check(bridge.getMethod("getError").invoke(singleton)==null) { "Bridge save reported a failure" }
            val flow = repositoryType.getField("b").get(repository)
            val items = flow.javaClass.getMethod("getValue").invoke(flow) as List<*>
            check(items.any { it!!.javaClass.getField("a").get(it)==id }) { "Recovered UI did not receive the SQLite update" }
            storeType.getMethod("put",JSONObject::class.java,Boolean::class.javaPrimitiveType).invoke(store,record()!!.put("notes","Preserved native note").put("currentVolume","3")
                .put("builtinSources",JSONObject().put("keiyoushi:ar:ariatoon","/integration/$id")),true)
            val sourceType=loader.loadClass("com.mangaku.local.Source")
            val mangaType=loader.loadClass("com.mangaku.local.SourceManga")
            val detailsType=loader.loadClass("com.mangaku.local.SourceDetails")
            val source=sourceType.declaredConstructors.first { it.parameterCount==5 }.newInstance("ariatoon","AriaToon","",listOf("https://ariatoon.com"),false)
            val remote=mangaType.declaredConstructors.first { it.parameterCount==6 }.newInstance("https://ariatoon.com/integration/$id","Different provider title","","","","")
            val details=detailsType.declaredConstructors.first { it.parameterCount==2 }.newInstance(remote,emptyList<Any>())
            val sourceBridge=loader.loadClass("com.mangaku.local.SourceBridge")
            val sourceSingleton=sourceBridge.getField("INSTANCE").get(null)
            val addSource=sourceBridge.methods.single { it.name.startsWith("add") && it.parameterCount==3 }
            val countBefore=JSONArray(storeType.getMethod("legacyLibrary").invoke(store) as String).length()
            check(addSource.invoke(sourceSingleton,source,details,id)==id)
            check(record()!!.getString("title")=="Integration offline manga") { "Provider selection replaced the library title" }
            check(JSONArray(storeType.getMethod("legacyLibrary").invoke(store) as String).length()==countBefore) { "Provider selection created a duplicate manga" }
            bridge.getMethod("publishAll").invoke(singleton)
            repositoryType.getMethod("C").invoke(repository)
            check(record()!!.getString("notes")=="Preserved native note") { "Old serializer erased new fields" }
            check(record()!!.getJSONObject("builtinSources").getString("keiyoushi:ar:ariatoon")=="/integration/$id") { "Old serializer erased source mappings" }
            check(record()!!.getString("currentChapter")=="10.5") { "Source mapping changed reading progress" }
            val mirrored = JSONArray(File(targetContext.filesDir,"manga_index.json").readText())
            check((0 until mirrored.length()).any { mirrored.getJSONObject(it).getString("id")==id })
            val archive = File(targetContext.cacheDir,"$id.zip")
            fun zip(name: String) { ZipOutputStream(archive.outputStream()).use { it.putNextEntry(ZipEntry(name)); it.write("page".toByteArray()); it.closeEntry() } }
            zip("local/chapter/page.jpg")
            repositoryType.getMethod("G",File::class.java,String::class.java).invoke(repository,archive,id)
            check(File(targetContext.filesDir,"manga/$id/local/chapter/page.jpg").readText()=="page")
            zip("../$id-escape.txt")
            repositoryType.getMethod("G",File::class.java,String::class.java).invoke(repository,archive,id)
            check(!File(targetContext.filesDir,"manga/$id-escape.txt").exists()) { "Recovered ZIP restore escaped the manga directory" }
            archive.delete()
            bridge.getMethod("deleteManga",String::class.java).invoke(null,id)
            check(record()==null)
            check(runCatching { addSource.invoke(sourceSingleton,source,details,id) }.isFailure) { "Deleted target was silently recreated" }
            check(record()==null)
            check((flow.javaClass.getMethod("getValue").invoke(flow) as List<*>).none { it!!.javaClass.getField("a").get(it)==id })
            finish(-1,Bundle().apply { putString("stream","PASS installed release integration: recovered save, SQLite, serializer, UI state publication, notes and source mappings preservation, safe ZIP restore, deletion") })
        } catch(e: Throwable) {
            finish(0,Bundle().apply { putString("stream","FAILED installed release integration\n${e.stackTraceToString()}") })
        }
    }
}
