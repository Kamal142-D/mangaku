package com.mangaku.local

import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** Built into the APK; neither source code nor executable extensions are fetched at runtime. */
internal data class Source(val id: String, val name: String, val family: String, val mirrors: List<String>, val adult: Boolean) {
    val base get() = mirrors.first()
    val provider get() = "keiyoushi:ar:$id"
    fun owns(url: String) = runCatching { mirrors.any { URI(it).host.equals(URI(url).host, true) } }.getOrDefault(false)
    fun url(path: String) = absolute(base + "/", path)
    fun text(url: String, body: String? = null, json: Boolean = false, headers: Map<String,String> = emptyMap()) = String(SourceHttp.bytes(url, this, body, json, headers = headers), Charsets.UTF_8)
    fun doc(url: String, body: String? = null) = Jsoup.parse(text(url, body), url)
    fun json(url: String, body: JSONObject? = null, headers: Map<String,String> = emptyMap()) = JSONObject(text(url, body?.toString(), body != null, headers))
    fun browse(query: String, page: Int = 1): SourceResults {
        require(page in 1..10000 && query.length <= 500)
        return if(id in ApiSources.ids) ApiSources.browse(this, query, page) else HtmlSources.browse(this, query, page)
    }
    fun details(manga: SourceManga): SourceDetails = if(id in ApiSources.ids) ApiSources.details(this, manga) else HtmlSources.details(this, manga)
    fun pages(chapter: String): List<SourcePage> {
        val pages = if(id in ApiSources.ids) ApiSources.pages(this, chapter) else HtmlSources.pages(this, chapter)
        check(pages.isNotEmpty()) { "لم يعثر المصدر على صفحات. افتح الموقع للتحقق من الفصل أو تسجيل الدخول." }
        require(pages.size <= 5000) { "Too many pages" }
        pages.forEach { SourceHttp.validate(it.url) }
        return pages
    }
}
internal data class SourceManga(val url: String, val title: String, val cover: String = "", val description: String = "", val author: String = "", val artist: String = "")
internal data class SourceResults(val manga: List<SourceManga>, val next: Boolean = false)
internal data class SourceDetails(val manga: SourceManga, val chapters: List<SourceChapter>)
internal data class SourceChapter(val url: String, val name: String, val number: String = chapterNumber(name), val date: String = "", val group: String = "", val volume: String = "") {
    fun json(source: Source) = JSONObject().put("id", source.provider + ":" + relative(source, url)).put("url",url).put("name",name).put("number",number)
        .put("language","ar").put("published_at",date).put("scanlation_group",group).put("volume",volume)
}
internal data class SourcePage(val url: String, val referer: String, val extra: JSONObject? = null)
internal fun enc(value: String) = URLEncoder.encode(value,"UTF-8").replace("+","%20")
internal fun absolute(base: String, path: String): String = URL(URL(base),path.trim().replace(" ","%20")).toString()
internal fun relative(source: Source, url: String): String = if(source.owns(url)) URI(url).let { (it.rawPath ?: "/") + (it.rawQuery?.let { q -> "?$q" } ?: "") + (it.rawFragment?.let { f -> "#$f" } ?: "") } else url
internal fun chapterNumber(name: String): String {
    val digits = name.map { if(it in '٠'..'٩') '0' + (it - '٠') else it }.joinToString("")
    return Regex("(?:الفصل|chapter|ch\\.?|^)[\\s:#-]*(\\d+(?:\\.\\d+)?)",RegexOption.IGNORE_CASE).find(digits)?.groupValues?.get(1) ?: ""
}
internal fun JSONArray.strings() = (0 until length()).map { getString(it) }
internal fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()
internal fun JSONObject.string(key: String) = if(isNull(key)) "" else optString(key, "")
internal fun Element.image(): String = listOf("data-encrypted-src","data-src","data-lazy-src","data-cfsrc","data-original","data-manga-src","data-url","src")
    .firstNotNullOfOrNull { attr(it).takeIf { v -> v.isNotBlank() && !v.startsWith("data:") }?.let { v -> absolute(baseUri(),v) } } ?: ""
internal fun Document.meta(name: String) = selectFirst("meta[property='$name'], meta[name='$name']")?.attr("content").orEmpty()

/** Ranking for the aggregated multi-source search. Results with a known, de-duplicated
 * chapter count come first, sorted by count then by latest available chapter, both descending.
 * Results whose count is still loading keep their arrival order at the end and are never
 * treated as zero. Pure and side-effect free so the ordering can be verified in tests. */
internal object AggregateRanking {
    fun <T> order(items: List<T>, count: (T) -> Int?, latest: (T) -> String?): List<T> {
        val known = items.filter { count(it) != null }.sortedWith(
            compareByDescending<T> { count(it)!! }.thenByDescending { latest(it)?.toBigDecimalOrNull() ?: java.math.BigDecimal.valueOf(-1) }
        )
        return known + items.filter { count(it) == null }
    }
}

internal object SourceHttp {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val last = mutableMapOf<String,Long>()
    fun validate(url: String) {
        val u = URI(url)
        require(u.scheme in listOf("https","http") && !u.host.isNullOrBlank() && u.userInfo == null) { "Invalid source URL" }
        val host = u.host.lowercase()
        require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local") && !host.contains(':') && !host.matches(Regex("[0-9.]+"))) { "Private source URL refused" }
    }
    fun bytes(url: String, source: Source, body: String? = null, json: Boolean = false, referer: String = source.base + "/", headers: Map<String,String> = emptyMap(), limit: Int = 16*1024*1024): ByteArray {
        var current = url.substringBefore('#')
        repeat(6) {
            validate(current)
            val host = URI(current).host
            // A single request/second per host respects the strictest bundled source limit.
            synchronized(last) { val pause = 1000 - (System.currentTimeMillis() - (last[host] ?: 0)); if(pause > 0) Thread.sleep(pause); last[host] = System.currentTimeMillis() }
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 18000; c.readTimeout = 25000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent",UA); c.setRequestProperty("Accept","*/*")
                c.setRequestProperty("Accept-Language","ar,en;q=0.8")
                c.setRequestProperty("Referer",referer.substringBefore('#')); c.setRequestProperty("Origin",source.base)
                CookieManager.getInstance().getCookie(current)?.let { c.setRequestProperty("Cookie",it) }
                if(source.id == "kawiimanga") c.setRequestProperty("x-app-key","km_2026_live")
                if(source.id == "dilar") DilarCrypto.headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
                if(URI(current).host == URI(url).host) headers.forEach { (k,v) -> c.setRequestProperty(k,v) }
                if(body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type",if(json) "application/json" else "application/x-www-form-urlencoded"); c.outputStream.use { it.write(body.toByteArray()) } }
                val code = c.responseCode
                c.headerFields.filterKeys { it?.equals("Set-Cookie",true) == true }.values.flatten().forEach { CookieManager.getInstance().setCookie(current,it) }
                if(code in listOf(301,302,303,307,308)) { current = absolute(current,c.getHeaderField("Location") ?: error("Redirect without destination")); return@repeat }
                check(code in 200..299) { if(code in listOf(401,403,429,503)) "المصدر يحتاج تحقق أو تسجيل دخول، أو طلبات أقل (HTTP $code). افتح الموقع ثم أعد المحاولة." else "المصدر رد بخطأ HTTP $code" }
                return c.inputStream.use { input ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(16384)
                    while(true) { val n = input.read(buffer); if(n < 0) break; require(out.size()+n <= limit) { "Source response too large" }; out.write(buffer,0,n) }
                    out.toByteArray()
                }
            } finally { c.disconnect() }
        }
        error("Too many redirects")
    }
}

object SourceBridge {
    @JvmStatic fun open() { Bridge.app.startActivity(android.content.Intent(Bridge.app,SourcesActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    @JvmStatic fun openFor(id: String) { Bridge.app.startActivity(android.content.Intent(Bridge.app,SourcesActivity::class.java).putExtra("target_manga_id",id).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    @JvmStatic fun label(): String = if(Bridge.app.resources.configuration.locales[0].language=="ar") "مصادر عربية" else "Arabic sources"
    @JvmStatic fun pickLabel(): String = if(Bridge.app.resources.configuration.locales[0].language=="ar") "مصادر أخرى" else "Other sources"
    internal fun find(url: String) = arabicSources.firstOrNull { it.owns(url) }
    @JvmStatic fun chapters(url: String): List<Any>? {
        val source = find(url) ?: return null
        val items = source.details(SourceManga(url, "")).chapters
        val constructor = Class.forName("com.mangaku.app.data.WebChapter").getConstructor(String::class.java,String::class.java,String::class.java,String::class.java)
        return items.map { val c = it.json(source); constructor.newInstance(c.getString("name"),c.getString("url"),c.getString("id"),c.getString("number")) }
    }
    @JvmStatic fun pages(chapter: Any): List<String>? {
        val url = chapter.javaClass.getField("b").get(chapter) as String
        val source = find(url) ?: return null
        return SourceImages.register(source, source.pages(url))
    }
    internal fun add(source: Source, details: SourceDetails, target: String? = null): String = Bridge.use { store ->
        val remote = relative(source, details.manga.url)
        val existing = store.db.rawQuery("SELECT canonical_manga_id FROM manga_provider_mapping WHERE provider=? AND provider_id=?",arrayOf(source.provider,remote)).use { if(it.moveToFirst()) it.getString(0) else null }
        require(target == null || existing == null || target == existing) { "المصدر مرتبط بعمل آخر في مكتبتك" }
        val id = target ?: existing ?: UUID.randomUUID().toString()
        val old = store.record(id)
        require(target == null || old != null) { "المانجا لم تعد موجودة في المكتبة" }
        val item = old ?: JSONObject().put("id",id).put("title",details.manga.title).put("pageCount",0).put("addedAtMillis",System.currentTimeMillis())
        val links = item.optJSONObject("builtinSources") ?: JSONObject()
        links.put(source.provider,remote)
        item.put("builtinSources",links).put("source","SEARCH").put("sourceUrl",details.manga.url).put("sourceId",remote)
        if(old == null) item.put("coverUrl",details.manga.cover).put("synopsis",details.manga.description).put("author",details.manga.author).put("artist",details.manga.artist)
        item.put("cachedChapters",JSONArray(details.chapters.map { it.json(source) }))
        item.put("sourceChapterCount",details.chapters.size)
        details.chapters.mapNotNull { it.number.toBigDecimalOrNull() }.maxOrNull()?.let { item.put("latestChapter",it.stripTrailingZeros().toPlainString()) }
        store.put(item); Bridge.publishAll(); store.mirrors(); id
    }
}
