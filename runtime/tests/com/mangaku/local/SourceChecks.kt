package com.mangaku.local

import android.app.Instrumentation
import android.os.Bundle
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.util.concurrent.Executors

class SourceChecks: Instrumentation() {
    private var args=Bundle()
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments); args=arguments ?: Bundle(); start() }
    override fun onStart() {
        try {
            check(arabicSources.size==55 && arabicSources.map { it.provider }.toSet().size==55)
            check(chapterNumber("الفصل ١٠.٥ - خاص")=="10.5")
            check(chapterNumber("Special")=="")
            val s=arabicSources.first { it.id=="manga3asq" }
            val doc=Jsoup.parse("""<ul><li class="wp-manga-chapter"><a href="/manga/example/10-5">الفصل 10.5</a></li><li class="wp-manga-chapter"><a href="/manga/example/special">Special</a></li></ul>""",s.base)
            val chapters=HtmlSources.parseChapters(s,doc)
            check(chapters.size==2 && chapters[0].number=="10.5" && chapters[1].number=="")
            check(chapters.map { it.json(s).getString("id") }.toSet().size==2)
            check(runCatching { HtmlSources.checkDocument(Jsoup.parse("<title>Login – Duskoryvile</title>")) }.isFailure)
            HtmlSources.checkDocument(Jsoup.parse("<title>Manga catalog</title><a href='/login'>Login</a>"))
            val json="""{"rows":[[1,"a ] bracket",{"escaped":"\\\""}]],"other":4}"""
            check(JSONArray(HtmlSources.balanced(json,json.indexOf('['))).length()==1)
            val astro=ApiSources.unwrapAstro(JSONObject("""{"imageUrls":[1,[[0,"https://example.org/a"],[0,"https://example.org/b"]]]}""")) as JSONObject
            check(astro.getJSONArray("imageUrls").getString(1)=="https://example.org/b")
            // Aggregated ranking: known counts first (desc, then latest desc); pending counts stay last, never treated as zero.
            data class Rank(val tag:String,val count:Int?,val latest:String?)
            val ranked=AggregateRanking.order(
                listOf(Rank("pendingA",null,null),Rank("few",3,"3"),Rank("many",120,"120.5"),Rank("tieOld",50,"49.5"),Rank("pendingB",null,null),Rank("tieNew",50,"51")),
                { it.count },{ it.latest }).map { it.tag }
            check(ranked==listOf("many","tieNew","tieOld","few","pendingA","pendingB")) { "Unexpected ranking: $ranked" }
            // A late count (0) still outranks a result whose count never arrived.
            val late=AggregateRanking.order(listOf(Rank("unknown",null,null),Rank("zero",0,null)),{ it.count },{ it.latest }).map { it.tag }
            check(late==listOf("zero","unknown")) { "Late/unknown ordering wrong: $late" }
            for(url in listOf("file:///data/a","https://127.0.0.1/x","https://localhost/x","https://user:pass@example.org/x")) {
                check(runCatching { SourceHttp.validate(url) }.isFailure)
            }
            val dir=File(context.filesDir,"mapping-check-${System.nanoTime()}")
            LocalStore(dir).use { db ->
                val m=JSONObject().put("id","sources-test").put("title","Source title").put("pageCount",0).put("addedAtMillis",1).put("sourceUrl",s.url("/manga/example/"))
                    .put("builtinSources",JSONObject().put(s.provider,"/manga/example/")).put("notes","keep").put("currentChapter","10.5")
                db.put(m); val backup=db.export(); db.restore(backup)
                check(db.record("sources-test")!!.getJSONObject("builtinSources").getString(s.provider)=="/manga/example/")
                check(!JSONArray(db.legacyLibrary()).getJSONObject(0).has("builtinSources"))
                check(db.record("sources-test")!!.getString("notes")=="keep")
                check(runCatching { db.put(m.copy().put("id","duplicate")) }.isFailure)
            }
            if(args.getString("live")!="true") { finish(-1,Bundle().apply { putString("stream","PASS source checks: catalog, chapter identity, decimal/special chapters, escaped JSON, Astro decoding, aggregate ranking and late results, URL boundary, backup and unique mapping") }); return }
            val filter=args.getString("only")?.split(',')?.toSet()
            val selected=arabicSources.filter { filter==null || it.id in filter }
            require(selected.isNotEmpty() && (filter==null || selected.size==filter.size)) { "Unknown source ID in selection" }
            val matrix=JSONArray(); val pool=Executors.newFixedThreadPool(3)
            val jobs=selected.map { source -> pool.submit {
                val row=JSONObject().put("id",source.id).put("testedAt",System.currentTimeMillis()).put("stage","browse")
                try {
                    val result=source.browse(""); row.put("titles",result.manga.size); check(result.manga.isNotEmpty()) { "Empty catalog" }
                    row.put("stage","search"); val found=source.browse(result.manga.first().title.take(80)); row.put("searchResults",found.manga.size)
                    row.put("stage","details"); var details=source.details(result.manga.first())
                    for(candidate in result.manga.drop(1).take(4)) { if(details.chapters.isNotEmpty()) break; details=source.details(candidate) }
                    row.put("mangaUrl",details.manga.url).put("chapters",details.chapters.size); check(details.chapters.isNotEmpty()) { "No chapters in five sampled titles" }
                    row.put("stage","pages"); val chapter=details.chapters.firstOrNull { !it.name.startsWith("🔒") } ?: error("Only locked chapters")
                    row.put("chapterUrl",chapter.url); val pages=source.pages(chapter.url); row.put("pages",pages.size)
                    row.put("stage","image"); val bytes=SourceImages.load(source,pages.first()); val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    check(bounds.outWidth>0 && bounds.outHeight>0) { "Image response could not decode" }
                    row.put("imageWidth",bounds.outWidth).put("imageHeight",bounds.outHeight)
                    if(found.manga.isEmpty()) row.put("status","partial").put("warning","Reading passed but search returned no results") else row.put("status","passed")
                } catch(e:Throwable) { row.put("status","failed").put("error",e.message ?: e.javaClass.simpleName) }
                synchronized(matrix) { matrix.put(row); LocalStore.atomic(File(context.filesDir,"source-live-results.json"),matrix.toString(2)); sendStatus(1,Bundle().apply { putString("stream","${source.id}: ${row.getString("status")} (${row.getString("stage")})\n") }) }
            } }
            jobs.forEach { it.get() }; pool.shutdown()
            finish(-1,Bundle().apply { putString("stream","SOURCE LIVE MATRIX ${matrix.length()} sources: ${matrix.objects().count { it.string("status")=="passed" }} passed end to end\n"+matrix.toString()) })
        } catch(e:Throwable) { finish(0,Bundle().apply { putString("stream","FAILED source checks\n${e.stackTraceToString()}") }) }
    }
}
