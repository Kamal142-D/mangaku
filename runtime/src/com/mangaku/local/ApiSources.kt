package com.mangaku.local

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object ApiSources {
    val ids = setOf("ariatoon","azora","dilar","eshadow","kawiimanga","mangacloud","mangaswat","mangatales","mangatek","mangatime")
    private const val kawii = "https://manga-api.kawaii-anime.com/api/manga/own"
    private const val cloud = "https://firestore.googleapis.com/v1/projects/ninja-brew-crew-4d79c/databases/(default)/documents/cloudmangas"
    private const val cloudKey = "AIzaSyAvdmgz_r_d89Eo8JBs9vAjUAJR451bMYU"
    private fun path(url: String) = URI(url).path.trim('/').split('/')
    private fun fragment(url: String) = URI(url).fragment.orEmpty()
    private fun trpc(s: Source, method: String, input: JSONObject): JSONObject {
        val envelope = JSONObject().put("0",JSONObject().put("json",input))
        return JSONArray(s.text(s.url("/api/trpc/$method?batch=1&input=${enc(envelope.toString())}"))).getJSONObject(0).getJSONObject("result").getJSONObject("data").getJSONObject("json")
    }
    fun browse(s: Source, query: String, page: Int): SourceResults {
        val q=enc(query)
        val result: JSONObject; val rows: List<JSONObject>; val next: Boolean
        when(s.id) {
            "ariatoon" -> { result=s.json("https://api.ariatoon.com/v1/mangas"+(if(query.isBlank()) "?" else "/search?search=$q&")+"page=$page&limit=20"); rows=result.array("data").objects(); next=rows.size==20 }
            "azora" -> { result=s.json("https://api.azorafly.com/api/query?page=$page&perPage=18&searchTerm=$q&orderBy=totalViews&orderDirection=desc"); rows=result.array("posts").objects().filterNot { it.optBoolean("isNovel") || it.string("seriesType").equals("novel",true) }; next=result.optInt("totalCount")>page*18 }
            "dilar" -> { result=if(query.isBlank()) s.json(s.url("/api/series?page=$page")) else s.json(s.url("/api/search/filter"),JSONObject().put("query",query).put("page",page)); rows=result.array(if(query.isBlank()) "series" else "rows").objects().filterNot { it.optJSONObject("seriesType")?.string("title")=="رواية" }; next=if(query.isBlank()) result.optInt("totalPages")>page else result.optInt("total")>page*result.optInt("perPage",20) }
            "eshadow" -> { result=s.json(s.url("/api/manga?page=$page&query=$q")); rows=result.array("data").objects(); next=result.optInt("total")>page*result.optInt("limit",20) }
            "kawiimanga" -> { result=s.json(kawii+(if(query.isBlank()) "?action=browse&page=$page&sort=views" else "?action=search&q=$q")); rows=result.array("results").objects(); next=result.optBoolean("hasMore") && query.isBlank() }
            "mangaswat" -> { result=s.json(s.url("/v2/api/v2/series/?search=$q&page=$page")); rows=result.array("results").objects(); next=result.string("next").isNotBlank() }
            "mangatime" -> { result=trpc(s,"search.searchSeries",JSONObject().put("page",page).put("limit",24).put("sortBy","popularity").put("sortOrder","desc").put("query",query)); rows=result.array("results").objects(); next=result.optBoolean("hasMore") }
            "mangatales" -> {
                val payload=JSONObject("""{"oneshot":{"value":false},"manga_types":{"include":["1","2"],"exclude":[]},"story_status":{"include":[],"exclude":[]},"translation_status":{"include":[],"exclude":["3"]},"categories":{"include":[null],"exclude":[]},"chapters":{"min":"","max":""},"dates":{"start":"","end":""}}""").put("title",query).put("page",page)
                result=JSONObject(decryptTales(s.json(s.url("/api/mangas/search"),payload).getString("data"))); rows=result.array("mangas").objects().filterNot { it.optBoolean("is_novel") }; next=rows.size==50
            }
            "mangacloud" -> {
                // Firestore exposes no title search. Search the paginated catalog, never silently cap it at the first 300 titles.
                val all=mutableListOf<JSONObject>(); var token=""; val visited=mutableSetOf<String>()
                do { val data=s.json("$cloud?pageSize=300&key=$cloudKey"+if(token.isEmpty()) "" else "&pageToken=${enc(token)}"); all+=data.array("documents").objects(); token=data.string("nextPageToken"); check(visited.add(token)) { "Source repeated a page token" }; check(visited.size<=200) { "Catalog too large" } } while(token.isNotBlank())
                val filtered=all.filter { query.isBlank() || it.getJSONObject("fields").toString().contains(query,true) }
                return SourceResults(filtered.drop((page-1)*20).take(20).map { cloudManga(s,it) }, filtered.size>page*20)
            }
            "mangatek" -> return HtmlSources.browse(s,query,page)
            else -> error("Missing bundled API adapter: ${s.id}")
        }
        return SourceResults(rows.map { manga(s,it) }.distinctBy { it.url },next)
    }
    private fun manga(s: Source, o: JSONObject): SourceManga {
        val id=o.string("id"); val slug=o.string("slug"); val title=o.string("title").ifBlank { o.string("postTitle") }
        val url=when(s.id) {
            "ariatoon" -> s.url("/series/manga/$id")
            "azora" -> s.url("/series/$slug#$id")
            "dilar" -> s.url("/series/$id/${enc(title)}")
            "eshadow" -> s.url("/manga/$slug#$id")
            "kawiimanga" -> s.url("/manga/$slug")
            "mangaswat" -> s.url("/series/${id.ifBlank { o.string("serie_id") }}")
            "mangatales" -> s.url("/mangas/$id")
            "mangatime" -> s.url("/${o.getString("type")}/$slug#$id")
            else -> error("Unknown manga format")
        }
        val cover=when(s.id) {
            "ariatoon" -> "https://api.ariatoon.com/uploads/${o.string("coverPath")}"
            "azora" -> o.string("featuredImage")
            "dilar" -> if(o.string("cover").isBlank()) "" else s.url("/uploads/manga/cover/$id/large_${o.getString("cover").substringBeforeLast('.')}.webp")
            "eshadow" -> o.string("coverImage")
            "mangaswat" -> o.optJSONObject("poster")?.string("medium").orEmpty()
            "mangatales" -> if(o.string("cover").isBlank()) "" else "https://media.mangatales.com/uploads/manga/cover/$id/large_${o.getString("cover")}"
            "mangatime" -> s.url(o.string("coverUrl"))
            else -> o.string("coverUrl")
        }
        return SourceManga(url,title,cover,Jsoup.parse(o.string("description").ifBlank { o.string("summary") }.ifBlank { o.string("story") }.ifBlank { o.string("postContent") }).text(),(o.opt("author") as? String).orEmpty(),o.string("artist"))
    }
    fun details(s: Source, m: SourceManga): SourceDetails {
        val segments=path(m.url); val id=segments.last(); var result: JSONObject; val chapters=mutableListOf<SourceChapter>(); var full=m
        when(s.id) {
            "ariatoon" -> {
                result=s.json("https://api.ariatoon.com/v1/mangas/$id").getJSONObject("data"); full=manga(s,result)
                paginate { page ->
                    val rows=s.json("https://api.ariatoon.com/v1/mangas/$id/episodes?direction=desc&publishStatus=published&limit=100&page=$page").array("data").objects()
                    chapters+=rows.map { c -> val number=c.string("number"); SourceChapter(s.url("/series/manga/$id/episodes/${c.getString("id")}"),"الفصل $number ${c.string("title")}".trim(),number,c.string("createdAt")) }; rows.size==100
                }
            }
            "azora" -> {
                result=s.json("https://api.azorafly.com/api/post?postSlug=${enc(id)}"); val post=result.getJSONObject("post"); full=manga(s,post)
                var rows=post.array("chapters").objects()
                if(result.optInt("totalChapterCount")>rows.size) rows=s.json("https://api.azorafly.com/api/chapters?postId=${post.getString("id")}").getJSONObject("post").array("chapters").objects()
                chapters+=rows.map { c -> val locked=c.optBoolean("isLocked") || c.optBoolean("isTimeLocked") || (!c.optBoolean("chapterPurchased") && c.optInt("price")>0); val number=c.string("number"); SourceChapter(s.url("/series/$id/${c.getString("slug")}#${c.getString("id")}"),(if(locked) "🔒 " else "")+"الفصل $number ${c.string("title")}".trim(),number,c.string("createdAt")) }
            }
            "dilar" -> {
                val mid=segments[1]; result=s.json(s.url("/api/series/$mid")); full=manga(s,result)
                s.json(s.url("/api/series/$mid/chapters")).array("chapters").objects().forEach { c -> chapters+=c.array("releases").objects().map { r -> val n=c.string("chapter"); SourceChapter(s.url("/reader/$mid/${enc(full.title)}/$n#${r.getString("id")}"),"الفصل $n ${c.string("title")}".trim(),n,r.string("created_at")) } }
            }
            "eshadow" -> {
                val mid=fragment(m.url).ifBlank { id }; result=s.json(s.url("/api/manga/${enc(mid)}")); full=manga(s,result)
                chapters+=result.array("chapters").objects().map { c -> val n=c.string("number"); SourceChapter(s.url("/read/${c.getString("id")}#${result.getString("id")}"),"الفصل $n ${c.string("title")}".trim(),n,c.string("publishedAt")) }
            }
            "kawiimanga" -> {
                result=s.json("$kawii?action=series&slug=${enc(id)}"); full=manga(s,result)
                chapters+=result.array("chapters").objects().map { c -> val n=c.string("number"); SourceChapter(s.url("/reader/$id/$n#${c.getString("id")}"),"الفصل $n ${c.string("title")}".trim(),n,c.string("createdAt")) }
            }
            "mangaswat" -> {
                result=s.json(s.url("/v2/api/v2/series/$id/")).put("id",id); full=manga(s,result)
                var next=s.url("/v2/api/v2/chapters/?serie=$id&order_by=-order&page_size=200"); val visited=mutableSetOf<String>()
                while(next.isNotBlank()) { check(s.owns(next) && visited.add(next) && visited.size<=200) { "Invalid chapter pagination" }; val data=s.json(next); chapters+=data.array("results").objects().map { c -> SourceChapter(s.url("/chapter/${c.getString("id")}"),c.getString("chapter"),chapterNumber(c.getString("chapter")),c.string("created_at")) }; next=data.string("next") }
            }
            "mangatales" -> {
                result=react(s.doc(m.url)).getJSONObject("mangaDataAction").getJSONObject("mangaData").put("id",id); full=manga(s,result)
                chapters+=s.json(s.url("/api/mangas/$id")).array("mangaReleases").objects().map { c -> val n=c.string("chapter"); SourceChapter(s.url("/r/${c.getString("id")}"),"الفصل $n ${c.string("title")}".trim(),n,c.string("created_at"),c.string("team_name")) }
            }
            "mangatime" -> {
                result=trpc(s,"content.getSeriesBySlug",JSONObject().put("slug",id)); val mid=result.string("id").ifBlank { fragment(m.url) }; full=manga(s,result.put("id",mid).put("slug",id).put("type",segments.first()))
                check(mid.isNotBlank()) { "Series ID missing" }
                chapters+=trpc(s,"content.getChapters",JSONObject().put("seriesId",mid).put("limit",-1)).array("chapters").objects().map { c -> val n=c.string("number"); SourceChapter(s.url("/${segments.first()}/$id/chapter/$n"),"الفصل $n ${c.string("title")}".trim(),n,c.string("publishedAt")) }
            }
            "mangacloud" -> {
                full=cloudManga(s,s.json("$cloud/${enc(id)}?key=$cloudKey")); var token=""; val visited=mutableSetOf<String>()
                do { result=s.json("$cloud/${enc(id)}/chapters?pageSize=1000&orderBy=index&key=$cloudKey"+if(token.isEmpty()) "" else "&pageToken=${enc(token)}"); chapters+=result.array("documents").objects().map { c -> val n=c.getString("name").substringAfterLast('/'); SourceChapter(s.url("/manga/$id/chapters/$n"),"الفصل $n",n) }; token=result.string("nextPageToken"); check(visited.add(token) && visited.size<=200) { "Invalid chapter pagination" } } while(token.isNotBlank())
            }
            "mangatek" -> {
                val data=astro(s.doc(m.url),"manga").getJSONObject("manga"); full=SourceManga(m.url,data.getString("title"),data.string("cover_image"),data.string("description"),data.string("author"))
                chapters+=data.array("MangaChapters").objects().map { c -> val n=c.string("chapter_number"); SourceChapter(s.url("/reader/$id/$n"),c.string("title").ifBlank { "الفصل $n" },n,c.string("created_at")) }
            }
        }
        return SourceDetails(full,chapters.distinctBy { it.url }.sortedWith(compareByDescending<SourceChapter> { it.number.toBigDecimalOrNull() ?: java.math.BigDecimal(-1) }.thenByDescending { it.date }))
    }
    fun pages(s: Source, url: String): List<SourcePage> {
        val parts=path(url); val id=parts.last(); val images: List<String>
        when(s.id) {
            "ariatoon" -> images=s.json("https://api.ariatoon.com/v1/mangas/${parts[2]}/episodes/$id").getJSONObject("data").array("images").strings().map { "https://api.ariatoon.com/uploads/$it" }
            "azora" -> { val data=s.json("https://api.azorafly.com/api/chapter?chapterId=${enc(fragment(url))}").getJSONObject("chapter"); check(listOf("isShortLinkLocked","isLockedByCoins","isPermanentlyLocked").none { data.optBoolean(it) }) { "الفصل مقفول لدى المصدر" }; images=data.array("images").objects().sortedBy { it.optInt("order",Int.MAX_VALUE) }.map { it.getString("url") } }
            "dilar" -> {
                val api=s.url("/api/chapters/${enc(fragment(url))}")
                val token=s.json("$api/unlock/free",JSONObject()).getString("token")
                val data=JSONObject(DilarCrypto.decrypt(s.json(api,headers=mapOf("X-Unlock-Free-Chapter" to token))))
                images=data.array("pages").objects().sortedBy { it.getInt("order") }.map { s.url("/uploads/releases/${data.getString("storage_key")}/hq/${it.getString("url")}") }
            }
            "eshadow" -> { val rows=s.json(s.url("/api/manga/${enc(fragment(url))}")).array("chapters").objects(); images=rows.first { it.string("id")==id }.array("images").strings() }
            "kawiimanga" -> images=s.json("$kawii?action=pages&chapterId=${enc(fragment(url))}").array("pages").strings()
            "mangaswat" -> images=s.json(s.url("/v2/api/v2/chapters/$id/")).array("images").objects().map { it.getString("image") }
            "mangatales" -> { val data=react(s.doc(url)); val key=data.getJSONObject("globals").getString("mediaKey"); images=data.getJSONObject("readerDataAction").getJSONObject("readerData").getJSONObject("release").getString("hq_pages").lines().filter(String::isNotBlank).map { "https://media.mangatales.com/uploads/releases/$it?ak=${enc(key)}" } }
            "mangatime" -> { val data=trpc(s,"content.getChapterPages",JSONObject().put("seriesSlug",parts[1]).put("chapterNumber",id.toBigDecimal())); check(data.optBoolean("isUnlocked")) { "الفصل مقفول لدى المصدر" }; images=data.array("pages").strings().map(s::url) }
            "mangacloud" -> images=s.json("$cloud/${parts.drop(1).joinToString("/") { enc(it) }}?key=$cloudKey").getJSONObject("fields").getJSONObject("pages").getJSONObject("arrayValue").array("values").objects().map { it.getString("stringValue") }
            "mangatek" -> {
                val data=astro(s.doc(url),"imageUrls"); val overlay=data.string("overlayBlob")
                val overlays=if(overlay.isBlank()) emptyList() else {
                    val bits=overlay.split(':'); require(bits.size==3); val iv=hex(bits[0]); val tag=hex(bits[2]); val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(hex("ff453871399fe268588a0936b45376022d85ed0fd1292001d5102f6a30291dc1"),"AES"),GCMParameterSpec(tag.size*8,iv))
                    JSONObject(String(cipher.doFinal(hex(bits[1])+tag))).array("pages").objects()
                }
                return data.array("imageUrls").strings().mapIndexed { i, image -> SourcePage(image,url,overlays.firstOrNull { it.optInt("page_number",-1)==i }) }
            }
            else -> error("Missing pages adapter")
        }
        return images.map { SourcePage(it.replace(" ","%20"),url) }
    }
    private fun paginate(block: (Int)->Boolean) { for(page in 1..200) if(!block(page)) return; error("Chapter pagination exceeds 200 pages") }
    private fun cloudManga(s: Source, doc: JSONObject): SourceManga {
        val f=doc.getJSONObject("fields"); fun v(k:String)=f.optJSONObject(k)?.string("stringValue").orEmpty()
        return SourceManga(s.url("/manga/${enc(doc.getString("name").substringAfterLast('/'))}"),v("title"),v("coverImage"),v("description"),v("author"),v("artist"))
    }
    private fun react(doc: org.jsoup.nodes.Document): JSONObject = JSONObject(doc.selectFirst(".js-react-on-rails-component")?.html() ?: error("Reader data missing"))
    internal fun unwrapAstro(v: Any?): Any? = when(v) {
        is JSONObject -> JSONObject().also { out -> v.keysList().forEach { out.put(it,unwrapAstro(v.get(it))) } }
        is JSONArray -> if(v.length()==2 && v.opt(0) is Number) unwrapAstro(v.get(1)) else JSONArray().also { out -> for(i in 0 until v.length()) out.put(unwrapAstro(v.get(i))) }
        else -> v
    }
    private fun astro(doc: org.jsoup.nodes.Document, key: String) = unwrapAstro(JSONObject(doc.selectFirst("[props*=$key]")?.attr("props") ?: error("Source data missing"))) as JSONObject
    internal fun decryptTales(value: String): String {
        val p=value.split('|'); require(p.size==4); val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(p[3].toByteArray()),"AES"),IvParameterSpec(Base64.decode(p[2],Base64.DEFAULT)))
        return String(cipher.doFinal(Base64.decode(p[0],Base64.DEFAULT)))
    }
}
