package com.mangaku.local

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Adapted from the pinned Keiyoushi Arabic sources and multisrc engines (Apache-2.0). */
internal object HtmlSources {
    private val madaraCards = "div.page-item-detail, .manga__item, .c-tabs-item__content"
    private val themesiaCards = ".utao .uta .imgu, .listupd .bs .bsx, .listo .bs .bsx"
    private fun cards(s: Source, search: Boolean): String = when(s.id) {
        "anyonemanga" -> ".am-manga-card"
        "arabmanhwa" -> "article.page-item-detail"
        "arabshentai" -> if(search) ".search-page .result-item article:not(:has(.tvshows))" else "#archive-content .wp-manga"
        "areamanga", "lavascans" -> ".listupd .manga-card-v"
        "goonscans" -> ".cover-wrapper"
        "duskoryvile" -> ".dap-series-grid a[href*='series_id=']"
        "arabhentai", "hentailek" -> "a[href*='/manga/']:not([href*='/chapter-'])"
        "hentaiman" -> "#manga-grid > div"
        "mangadar", "neverscans" -> "a[href*='/manga/']"
        "mangatek" -> ".flex-grow .grid a"
        "rocksmanga" -> ".unit .inner"
        "stellarsaber" -> ".card-grid .card"
        "teamx" -> if(search) "div.tx-grid a.tx-card" else "div.listupd div.bsx"
        "onma" -> "div.chapter-container"
        "hentaislayer" -> "div#card-real"
        else -> if(s.family == "mangathemesia") themesiaCards else madaraCards
    }
    fun browse(s: Source, query: String, page: Int): SourceResults {
        val q = enc(query)
        if(s.family == "zeistmanga" || s.id == "oduto") return bloggerBrowse(s,query,page)
        if(s.id == "onma" && query.isNotBlank()) {
            val all = s.json(s.url("/search?query=$q")).array("suggestions").objects()
            return SourceResults(all.drop((page-1)*24).take(24).map { SourceManga(s.url("/manga/${it.getString("data")}"),it.getString("value")) },all.size > page*24)
        }
        if(s.id == "mangalionz" && query.isNotBlank()) return SourceResults(s.jsonForm("action=wp-manga-search-manga&title=$q").array("data").objects().map { SourceManga(s.url(it.getString("url")),it.getString("title")) })
        if(s.id == "mangadar" && query.isNotBlank()) return SourceResults(s.json(s.url("/wp-admin/admin-ajax.php?action=mangaverse_search&q=$q")).array("data").objects().map { SourceManga(s.url(it.getString("url")),it.getString("title"),it.string("cover")) })
        if(s.id == "stellarsaber" && query.isNotBlank()) {
            val script = s.doc(s.url("/manga/")).selectFirst("#flavor-ajax-js-extra")?.data() ?: error("Source search token missing")
            val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"").find(script)?.groupValues?.get(1) ?: error("Source search token missing")
            val rows = s.jsonForm("action=flavor_ajax_filter_content&nonce=${enc(nonce)}&page=$page&keyword=$q").getJSONObject("data").array("results").objects()
            return SourceResults(rows.filterNot { it.string("type") in listOf("novel","anime") }.map { SourceManga(s.url(it.getString("url")),it.getString("title"),it.string("cover")) },rows.isNotEmpty())
        }
        if((s.family == "madara" && s.id !in listOf("anyonemanga","arabmanhwa","manga3asq")) || (s.id in listOf("mangalek","mangalionz","mangaspark") && query.isBlank())) {
            val fields=linkedMapOf("action" to "madara_load_more","page" to (page-1).toString(),"template" to "madara-core/content/content-archive","vars[paged]" to "1","vars[template]" to "archive","vars[posts_per_page]" to "25","vars[post_type]" to "wp-manga","vars[post_status]" to "publish","vars[manga_archives_item_layout]" to "big_thumbnail","vars[meta_query][0][key]" to "_wp_manga_chapter_type","vars[meta_query][0][value]" to "manga")
            if(query.isBlank()) { fields["vars[orderby]"]="meta_value_num"; fields["vars[meta_key]"]="_wp_manga_views"; fields["vars[order]"]="DESC" } else fields["vars[s]"]=query
            val html=s.text(s.url("/wp-admin/admin-ajax.php"),fields.entries.joinToString("&") { enc(it.key)+"="+enc(it.value) },headers=mapOf("X-Requested-With" to "XMLHttpRequest"))
            val doc=Jsoup.parse(html,s.base); checkDocument(doc); val entries=parseCards(s,doc,query.isNotBlank())
            return SourceResults(entries,entries.size==25)
        }
        val directory = when(s.id) { "empirewebtoon" -> "webtoon"; "hizomanga" -> "serie"; "yonabar" -> "yaoi"; else -> "manga" }
        val path = when(s.id) {
            "arabhentai" -> "/search/manga?keyword=$q&page=$page"
            "arabshentai" -> if(query.isBlank()) "/manga/page/$page/?orderby=new-manga" else "/page/$page/?s=$q"
            "duskoryvile" -> if(query.isBlank()) "/series-list/" else "/search/?q=$q"
            "hentailek" -> if(query.isBlank()) "/library?sort=popular&page=$page" else "/search?q=$q"
            "hentaiman" -> "/manga?search=$q&page=$page"
            "hentaislayer" -> "/manga?title=$q&page=$page"
            "mangadar" -> "/manga/page/$page/"
            "mangatek" -> "/manga-list?search=$q&page=$page"
            "neverscans" -> "/manga?search=$q"
            "onma" -> "/filterList?page=$page&sortBy=views&asc=false"
            "teamx" -> if(query.isBlank()) "/series/?page=$page" else "/search?keyword=$q"
            "stellarsaber" -> "/manga/" + (if(page>1) "page/$page/" else "") + "?sort=rating"
            "rocksmanga" -> "/page/$page/?s=$q"
            "goonscans" -> if(query.isBlank()) "/title/page/$page/?orderby=top_rated" else "/manga?title=$q&page=$page"
            else -> if(s.family == "mangathemesia") "/manga?title=$q&page=$page&order=popular" else if(query.isBlank()) "/$directory/" + (if(page>1) "page/$page/" else "") + "?m_orderby=views" else "/page/$page/?s=$q&post_type=wp-manga"
        }
        val doc = s.doc(s.url(path))
        checkDocument(doc)
        val entries = parseCards(s,doc,query.isNotBlank())
        val next = doc.selectFirst("a[rel=next], a.next, .nav-previous a, a.nextpostslink, .hpage .r, .next-btn, .pagination span.current + a, #nextpagination") != null
        return SourceResults(entries, next && s.id !in listOf("yonabar","neverscans","duskoryvile","teamx"))
    }
    fun parseCards(s: Source, doc: Document, search: Boolean = false): List<SourceManga> = doc.select(cards(s,search)).mapNotNull { el ->
        val link = if(el.tagName() == "a") el else el.selectFirst(".post-title a, .am-manga-card__title a, .info a, .data h3 a, .details .title a, .media-heading a, .manga-heading a, a[href]") ?: return@mapNotNull null
        val url = link.absUrl("href"); if(!s.owns(url)) return@mapNotNull null
        if(s.id == "mangadar") { val parts=URI(url).path.trim('/').split('/'); if(parts.size!=2 || parts[0]!="manga" || parts[1]=="page") return@mapNotNull null }
        val img = el.selectFirst("img")
        val title = el.selectFirst(".card__title, h3, h2, .tt, .post-title, .font-haffer, .font-semibold, div[style*=font-weight]")?.let { it.attr("title").ifBlank { it.text() } }.orEmpty()
            .ifBlank { link.attr("title") }.ifBlank { img?.attr("alt").orEmpty() }.ifBlank { link.text() }
        if(title.isBlank()) null else SourceManga(url,title,img?.image().orEmpty())
    }.distinctBy { it.url }
    fun details(s: Source, m: SourceManga): SourceDetails {
        val doc = s.doc(m.url); checkDocument(doc)
        val manga = parseDetails(s,m,doc)
        val chapters = when {
            s.family == "zeistmanga" || s.id == "oduto" -> bloggerChapters(s,manga,doc)
            s.id == "mangadar" -> {
                val data = doc.select("[x-data]").map { it.attr("x-data") }.firstOrNull { it.contains("rows:") }
                val rows = if(data!=null) JSONArray(balanced(data,data.indexOf('[',data.indexOf("rows:"))))
                    else if(doc.text().contains("لا توجد فصول بعد")) JSONArray() else error("Chapter list missing")
                (0 until rows.length()).map { rows.getJSONArray(it) }.map { SourceChapter(s.url(it.getString(2)),"الفصل ${it.getString(1)}",it.getString(1),it.optString(3)) }
            }
            s.family.startsWith("madara") -> {
                var result = parseChapters(s,doc)
                val ajax = s.id in listOf("anyonemanga","arabmanhwa","arbxcomix","detectiveconanar","manga3asq","paradisebl","mangatuk")
                if(ajax || result.isEmpty()) {
                    val id = doc.selectFirst("[id^=manga-chapters-holder]")?.attr("data-id") ?: doc.selectFirst("input.rating-post-id")?.attr("value")
                    result = if(s.id == "mangaspark" && !id.isNullOrBlank()) parseChapters(s,s.doc(s.url("/wp-admin/admin-ajax.php"),"action=manga_get_chapters&manga=${enc(id)}"))
                    else parseChapters(s,s.doc(m.url.substringBefore('#').trimEnd('/') + "/ajax/chapters/",""))
                }
                result
            }
            else -> {
                val result = parseChapters(s,doc).toMutableList()
                if(s.id in listOf("teamx","hentaislayer")) {
                    val last = doc.select("ul.pagination a").mapNotNull { it.text().toIntOrNull() }.maxOrNull() ?: 1
                    require(last <= 200) { "Chapter pagination exceeds 200 pages" }
                    for(page in 2..last) result += parseChapters(s,s.doc(m.url.substringBefore('?') + "?page=$page"))
                }
                result
            }
        }.distinctBy { it.url }
        return SourceDetails(manga,chapters)
    }
    fun parseDetails(s: Source, m: SourceManga, doc: Document): SourceManga {
        val titleSelector = when(s.id) {
            "anyonemanga" -> ".am-manga-hero__title"; "areamanga" -> ".manga-title-large"; "lavascans" -> ".lh-title"; "goonscans" -> ".webtoon-title"
            "duskoryvile" -> ".dk-series-hero h2"; "paradisebl" -> ".new-post-title h3 a"; "stellarsaber" -> ".detail-info__title"; "onma" -> ".panel-heading"; else -> "h1, .entry-title"
        }
        val title = doc.selectFirst(titleSelector)?.text().orEmpty().ifBlank { doc.meta("og:title") }.ifBlank { m.title }
        check(title.isNotBlank()) { "عنوان العمل غير متاح؛ قد يكون الموقع بحاجة لتسجيل دخول" }
        val cover = doc.selectFirst(".am-manga-hero__cover img, .manga-poster img, .lh-poster img, .webtoon-cover, .summary_image img, .thumb img, .dk-series-hero-img img, .sheader .poster img, .detail-poster img, div.text-right img, aside img")?.image().orEmpty().ifBlank { doc.meta("og:image") }.ifBlank { m.cover }
        val description = doc.selectFirst(".am-manga-summary__text, .summary__content, .summary-text, .manga-excerpt, .story-text, #manga-story, .description-content, #synopsis, .description, .synopsis, #detail-desc, .review-content, #manga-info .wp-content")?.text().orEmpty().ifBlank { doc.meta("description") }
        return SourceManga(m.url,title,cover,description,doc.select(".author-content a, #author, dt:contains(المؤلف) + dd").text(),doc.select(".artist-content a, #artist, dt:contains(الرسام) + dd").text())
    }
    fun parseChapters(s: Source, doc: Document): List<SourceChapter> {
        val selector = when(s.id) {
            "arabhentai" -> "a[href*='/chapter-']:not([class*='rounded-2xl'])"
            "hentailek" -> "a[href*='/chapter-'][class*='group']"
            "hentaiman" -> "li.chapter-item"
            "hentaislayer" -> "div#chapters-list > a[href]"
            "arabshentai" -> "#chapter-list a[href*='/manga/'], .oneshot-reader .images .image-item a[href$='manga-paged=1']"
            "duskoryvile" -> ".dk-ch-grid a.dk-ch-card"
            "areamanga", "lavascans" -> "#chapters-list-container .ch-item:not(.locked)"
            "goonscans" -> "ul.chapter-list li"
            "neverscans" -> "ol li a[href*='/manga/']"
            "rocksmanga" -> "div.list-body-hh ul li"
            "stellarsaber" -> ".volume-group .chapter-item"
            "teamx" -> "div.chapter-card:not(:has(span.locked))"
            "onma" -> "ul.chapters > li:not(.btn)"
            else -> if(s.family == "mangathemesia") "div.bxcl li, div.cl li, #chapterlist li, ul li:has(div.chbox):has(div.eph-num)" else "li.wp-manga-chapter"
        }
        return doc.select(selector).mapNotNull { el ->
            val a = if(el.tagName()=="a") el else el.selectFirst("a[href]") ?: return@mapNotNull null
            var url = a.absUrl("href"); if(!s.owns(url)) return@mapNotNull null
            if(s.id == "arabshentai" && url.contains("style=paged")) url = url.substringBefore('?')
            val name = el.selectFirst(".chapter-item__title, .chapter-title, .chapternum, .chap-num, .ch-num, .chapter-number, .dk-ch-title, zebi, #item-title, div[dir=rtl] span, .font-semibold")?.text().orEmpty().ifBlank { a.ownText() }.ifBlank { a.text() }
            val number = el.attr("data-number").ifBlank { el.selectFirst(".chapter-item__number")?.text().orEmpty() }.ifBlank { chapterNumber(name) }
            SourceChapter(url,name.ifBlank { "الفصل $number" },number,el.selectFirst("time, .chapterdate, .chapter-date, .chap-date, .ch-date, .dk-ch-date, .chapter-item__date, .time, .date-chapter-title-rtl")?.let { it.attr("datetime").ifBlank { it.text() } }.orEmpty(),el.selectFirst(".chapter-item__team, .username span")?.text().orEmpty(),el.closest(".volume-group")?.selectFirst(".volume-group__label")?.text().orEmpty())
        }
    }
    fun pages(s: Source, url: String): List<SourcePage> {
        var doc = s.doc(url); checkDocument(doc)
        if(s.family.startsWith("madara") && doc.selectFirst("#single-pager") != null) doc = s.doc(url.substringBefore('?') + "?style=list")
        if(s.id == "areamanga") {
            val id = doc.selectFirst("#comment_post_ID")?.attr("value") ?: error("Chapter ID missing")
            val data = s.jsonForm("action=get_secure_chapter_images&chapter_id=${enc(id)}").getJSONObject("data")
            check(data.string("status") == "unlocked") { "الفصل مقفول. افتح الموقع للحصول على صلاحية القراءة." }
            return Jsoup.parse(data.getString("content"),url).select("img").map { SourcePage(it.image(),url) }
        }
        if(s.id == "stellarsaber") {
            val script = doc.selectFirst("script:containsData(flavorReaderData)")?.data() ?: error("Reader data missing")
            val nonce = Regex("cdnNonce:\\s*'([^']+)'").find(script)?.groupValues?.get(1) ?: error("Reader token missing")
            val id = Regex("chapterId:\\s*(\\d+)").find(script)?.groupValues?.get(1) ?: error("Chapter ID missing")
            val key = s.jsonForm("action=flavor_cdn_get_key&nonce=${enc(nonce)}&chapter_id=$id").getJSONObject("data").getString("key")
            return doc.select("img.reader__page[data-cdn-url]").map { SourcePage(it.absUrl("data-cdn-url"),url,JSONObject().put("key",key)) }
        }
        val protected = doc.selectFirst("#chapter-protector-data")
        if(protected != null) return protectedPages(protected).map { SourcePage(s.url(it),url) }
        val selector = when(s.id) {
            "arabhentai" -> "img[alt^='Page'], img[src*='mangaonl.com']"
            "arabshentai" -> ".chapter_image img.wp-manga-chapter-img"
            "hentailek" -> ".reader-page img, .on-canvas img"
            "hentaiman" -> "#reader img.reader-page"
            "hentaislayer" -> "div#chapter-container > img"
            "duskoryvile" -> ".dap-pages img.dap-page"
            "mangadar" -> ".reader-page img"
            "neverscans" -> "img[src*='/api/public/page/']"
            "rocksmanga" -> "#ch-images .img"
            "teamx" -> "div.image_list canvas[data-src], div.image_list img[src]"
            "onma" -> "#all > img.img-responsive"
            "oduto" -> "div#post-body img"
            "mangahub" -> "article#reader .separator img, div.image-container img"
            "xsanomanga" -> "#reader div.separator img"
            else -> when { s.family == "zeistmanga" -> "div.check-box div.separator img"; s.family == "mangathemesia" -> "div#readerarea img"; else -> "div.page-break img, li.blocks-gallery-item img, .reading-content img, div.wp-manga-chapter-img img" }
        }
        var images = doc.select(selector).map { el -> if(el.tagName() == "canvas") el.absUrl("data-src") else if(el.tagName()=="img") el.image() else el.selectFirst("img")?.image().orEmpty() }.filter { it.isNotBlank() }
        if(images.isEmpty() && s.family == "mangathemesia") {
            val script = doc.selectFirst("script[src^='data:text/javascript;base64,dHNfcmVhZGVyLnJ1bih7']")?.attr("src")?.substringAfter(',')?.let { String(Base64.decode(it,Base64.DEFAULT)) } ?: doc.html()
            Regex("[\"']?(?:images|imageUrls)[\"']?\\s*[:=]\\s*(\\[.*?])",RegexOption.DOT_MATCHES_ALL).find(script)?.groupValues?.get(1)?.let { images = JSONArray(it).strings().map(s::url) }
        }
        if(s.id == "hijala" && doc.selectFirst("#chapter-pages-js-before") != null) {
            require(images.size % 2 == 0) { "Incomplete split image pair" }
            return images.chunked(2).map { SourcePage(it[0],url,JSONObject().put("right",it[1])) }
        }
        if(s.id == "yonabar") images = images.map { it.replaceFirst("medium1","medium1x") }
        return images.map { SourcePage(it,url) }
    }
    private fun protectedPages(el: Element): List<String> {
        val script = if(el.attr("src").startsWith("data:text/javascript;base64,")) String(Base64.decode(el.attr("src").substringAfter(','),Base64.DEFAULT)) else el.html()
        val password = script.substringAfter("wpmangaprotectornonce='").substringBefore("';")
        val data = JSONObject(script.substringAfter("chapter_data='").substringBefore("';").replace("\\/","/"))
        val salt = hex(data.getString("s")); val digest = MessageDigest.getInstance("MD5"); var prev = ByteArray(0); var material = ByteArray(0)
        while(material.size < 48) { prev = digest.digest(prev + password.toByteArray() + salt); material += prev }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(material.copyOfRange(0,32),"AES"),IvParameterSpec(material.copyOfRange(32,48)))
        val result = JSONTokener(String(cipher.doFinal(Base64.decode(data.getString("ct"),Base64.DEFAULT)))).nextValue()
        return (if(result is String) JSONArray(result) else result as JSONArray).strings()
    }
    private fun bloggerBrowse(s: Source, query: String, page: Int): SourceResults {
        if(s.id == "oduto") return SourceResults(listOf(SourceManga(s.url("/search/label/${enc("مانجا بوروتو")}"),"BORUTO: Two Blue Vortex")))
        val feed = s.json(s.url("/feeds/posts/default/-/Series?alt=json&max-results=21&start-index=${(page-1)*20+1}&q=${enc("label:Series $query")}")).getJSONObject("feed")
        val entries = feed.array("entry").objects()
        return SourceResults(entries.take(20).filterNot { it.array("category").objects().any { c -> c.string("term") in listOf("Anime","Novel","Novela") } }.map { e ->
            val url = e.array("link").objects().first { it.string("rel") == "alternate" }.getString("href")
            val html = Jsoup.parse(e.optJSONObject("content")?.string("\$t").orEmpty(),s.base)
            SourceManga(url,e.getJSONObject("title").getString("\$t"),html.selectFirst("img")?.image().orEmpty().ifBlank { e.optJSONObject("media\$thumbnail")?.string("url").orEmpty() })
        },entries.size > 20)
    }
    private fun bloggerChapters(s: Source, m: SourceManga, doc: Document): List<SourceChapter> {
        val category = if(s.id == "mangaailand") "فصل" else "Chapter"
        var path = if(s.id == "oduto") "/feeds/posts/summary/-/${enc("مانجا بوروتو")}" else {
            val old = doc.selectFirst("#myUL > script[src]")?.attr("src")?.substringBefore('?')
            val script = doc.selectFirst("#clwd > script, #latest > script")?.html().orEmpty()
            val feed = Regex("clwd\\.run\\([\"'](.*?)[\"']\\)|label\\s*=\\s*'([^']+)'").find(script)?.let { it.groupValues.drop(1).firstOrNull(String::isNotEmpty) }
                ?: doc.selectFirst("[data-label]")?.attr("data-label") ?: m.title
            old ?: "/feeds/posts/default/-/" + (if(doc.selectFirst("#latest > script") != null) "" else "$category/") + enc(feed)
        }
        if(s.id == "yurimoonsub") path = URLDecoder.decode(path,"UTF-8").replace(Regex("[\\u0600-\\u06FF]"),"").replace(Regex("\\s{2,}"),"").replace(" ","%20")
        val result = mutableListOf<SourceChapter>(); var start = 1
        repeat(200) {
            val feed = s.json(s.url(path) + "?alt=json&max-results=150&start-index=$start").getJSONObject("feed")
            val entries = feed.array("entry").objects()
            entries.filter { s.id == "oduto" || it.array("category").objects().any { c -> c.string("term") == category } }.forEach { e ->
                val url = e.array("link").objects().first { it.string("rel") == "alternate" }.getString("href")
                result += SourceChapter(url,e.getJSONObject("title").getString("\$t"),date=e.optJSONObject(if(s.id == "yokai") "updated" else "published")?.string("\$t").orEmpty(),group=e.array("author").optJSONObject(0)?.optJSONObject("name")?.string("\$t").orEmpty())
            }
            start += entries.size
            if(entries.isEmpty() || start > (feed.optJSONObject("openSearch\$totalResults")?.string("\$t")?.toIntOrNull() ?: entries.size)) {
                if(s.id == "yokai") result += doc.select("div#download > div.index-list > a").map { SourceChapter(it.absUrl("href"),it.text()) }
                return result.distinctBy { it.url.substringBefore('?') }
            }
        }
        error("Chapter pagination exceeds 200 pages")
    }
    internal fun checkDocument(doc: Document) {
        val loginTitle = Regex("^(login|log in|sign in)(?:\\s|$)",RegexOption.IGNORE_CASE).containsMatchIn(doc.title().trim())
        check(doc.selectFirst("#challenge-form, #cf-challenge-running, form#loginform") == null && !doc.title().contains("Just a moment",true) && !loginTitle) { "الموقع يحتاج تحقق أو تسجيل دخول. افتح الموقع ثم أعد المحاولة." }
    }
    internal fun balanced(text: String, start: Int): String {
        require(start >= 0); var depth = 0; var quote = '\u0000'; var escaped = false
        for(i in start until text.length) { val c = text[i]
            if(quote != '\u0000') { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c==quote) quote='\u0000'; continue }
            if(c=='"' || c=='\'') quote=c else if(c=='[' || c=='{') depth++ else if(c==']' || c=='}') { depth--; if(depth==0) return text.substring(start,i+1) }
        }
        error("Incomplete source data")
    }
}
internal fun Source.jsonForm(body: String) = JSONObject(text(url("/wp-admin/admin-ajax.php"),body))
internal fun hex(value: String): ByteArray { require(value.length % 2 == 0); return ByteArray(value.length/2) { value.substring(it*2,it*2+2).toInt(16).toByte() } }
