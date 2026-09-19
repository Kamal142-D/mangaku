package com.mangaku.local

import org.jsoup.nodes.Document
import java.net.URI

/**
 * Resolves an arbitrary pasted manga URL (the old "paste the page link" flow) by
 * detecting the site's engine family and reusing the built-in adapters. Sites whose
 * layout matches no known family fall back to a broad union of chapter-link and
 * page-image selectors, so any listing-style page can still yield chapters.
 *
 * Only the pasted site's own HTML is read; no source code or executable is fetched.
 * Known catalog hosts are handled by their dedicated adapters first (see
 * [SourceBridge.find]); this path runs only for hosts outside the built-in catalog.
 */
internal object GenericSource {
    // Chapter links across the supported engines, plus generic chapter-href heuristics.
    private const val CHAPTER_SELECTOR =
        "li.wp-manga-chapter > a, .wp-manga-chapter a, .listing-chapters_wrap a, " +
        "#chapterlist li a, div.bxcl li a, div.cl li a, .eph-num a, li.a-h a, " +
        "ul.chapters > li a, .chapter-list a, .chapters-list a, .su-list li a, " +
        "a[href*='/chapter'], a[href*='chapter-'], a[href*='/ch-'], a[href*='-chapter-']"
    // Reader images across the supported engines, plus common content containers.
    private const val PAGE_SELECTOR =
        "div.reading-content img, .page-break img, div.wp-manga-chapter-img img, .text-left img, " +
        "div#readerarea img, #readerarea img, .reader-area img, .reader-page img, " +
        "#all img.img-responsive, #all > img, .separator img, .chapter_image img, " +
        "#chapter-container img, .chapter-content img, .entry-content img"

    /** Build a synthetic Source whose only mirror is the URL's own origin. No request is made. */
    internal fun sourceFor(url: String, family: String = ""): Source? {
        val u = runCatching { URI(url) }.getOrNull() ?: return null
        val host = u.host?.takeIf { it.isNotBlank() } ?: return null
        val origin = "${u.scheme}://$host" + (if(u.port > 0) ":${u.port}" else "")
        return Source("web:$host", host, family, listOf(origin), false)
    }

    /** Detect the engine family from a fetched page. Returns "" when nothing matches. */
    internal fun detect(doc: Document): String = when {
        doc.selectFirst("#readerarea, #chapterlist, .epcheck, .listupd .bsx, .bixbox .eph-num") != null
            || doc.html().contains("ts_reader.run") -> "mangathemesia"
        doc.selectFirst("#manga-chapters-holder, .wp-manga-chapter, .reading-content, .listing-chapters_wrap") != null
            || doc.selectFirst("body[class*=wp-manga]") != null -> "madara"
        doc.selectFirst("#clwd, #latest, .check-box .separator") != null
            || doc.selectFirst("meta[name=generator][content*=logger]") != null -> "zeistmanga"
        else -> ""
    }

    /** Parse the pasted manga page into details and chapters. */
    fun chapters(url: String): SourceDetails {
        SourceHttp.validate(url)
        val probe = sourceFor(url) ?: error("رابط غير صالح")
        val doc = probe.doc(url)
        HtmlSources.checkDocument(doc)
        val family = detect(doc)
        val source = probe.copy(family = family)
        val details = if(family == "") {
            val manga = HtmlSources.parseDetails(source, SourceManga(url, ""), doc)
            SourceDetails(manga, genericChapters(source, doc))
        } else {
            HtmlSources.details(source, SourceManga(url, ""))
        }
        check(details.chapters.isNotEmpty()) {
            "لم يُعثر على فصول في هذا الرابط. تأكد أنه صفحة العمل التي تعرض قائمة الفصول."
        }
        return details.copy(chapters = details.chapters.distinctBy { it.url })
    }

    /** Parse a chapter page into ordered page images. */
    fun pages(url: String): List<SourcePage> {
        SourceHttp.validate(url)
        val probe = sourceFor(url) ?: error("رابط غير صالح")
        val doc = probe.doc(url)
        HtmlSources.checkDocument(doc)
        val family = detect(doc)
        val source = probe.copy(family = family)
        val pages = if(family == "") genericPages(source, doc, url) else HtmlSources.pages(source, url)
        check(pages.isNotEmpty()) { "لم يُعثر على صفحات في هذا الفصل. افتح الموقع للتحقق." }
        return pages
    }

    internal fun genericChapters(source: Source, doc: Document): List<SourceChapter> {
        val seen = LinkedHashSet<String>()
        return doc.select(CHAPTER_SELECTOR).mapNotNull { a ->
            val url = a.absUrl("href").substringBefore('#')
            if(url.isBlank() || !source.owns(url) || !seen.add(url)) return@mapNotNull null
            val name = a.selectFirst(".chapternum, .chapter-title, .chap-num, .chapter-number")?.text().orEmpty()
                .ifBlank { a.ownText() }.ifBlank { a.text() }.trim()
            if(name.isBlank() || name.length > 120) return@mapNotNull null
            val number = chapterNumber(name)
            val chapterLike = number.isNotEmpty() ||
                Regex("chapter|oneshot|فصل|ون\\s*شوت", RegexOption.IGNORE_CASE).containsMatchIn(name) ||
                Regex("/chapter|chapter-|/ch-|-chapter-").containsMatchIn(url.lowercase())
            if(!chapterLike) return@mapNotNull null
            val date = a.parent()?.selectFirst("time, .chapterdate, .chapter-release-date, .chapter-date, .date")
                ?.let { it.attr("datetime").ifBlank { it.text() } }.orEmpty()
            SourceChapter(url, name, number, date)
        }
    }

    internal fun genericPages(source: Source, doc: Document, url: String): List<SourcePage> {
        var page = doc
        // Madara-style single-page readers expose every image only in list mode.
        if(page.selectFirst("#single-pager") != null) page = source.doc(url.substringBefore('?') + "?style=list")
        return page.select(PAGE_SELECTOR).map { it.image() }.filter { it.isNotBlank() }.distinct().map { SourcePage(it, url) }
    }
}
