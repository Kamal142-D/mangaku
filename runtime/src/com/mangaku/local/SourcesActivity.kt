package com.mangaku.local

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import java.util.concurrent.Executors

/** Native, virtualized source browser; no extension installation flow. */
class SourcesActivity: Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var list: ListView
    private var selected: Source?=null
    private var detail: SourceDetails?=null
    private var query=""
    private var page=1
    private var results=emptyList<SourceManga>()
    private var next=false
    private val targetId get()=intent.getStringExtra("target_manga_id")
    private var targetTitle=""
    private var generation=0
    private var work: java.util.concurrent.Future<*>?=null
    private val executor=Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("builtin_sources",MODE_PRIVATE) }
    private val ar get()=resources.configuration.locales[0].language=="ar"
    private fun t(a:String,e:String)=if(ar) a else e
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun color(attr:Int)=obtainStyledAttributes(intArrayOf(attr)).let { a -> try { a.getColor(0,ColorFallback) } finally { a.recycle() } }
    private val ColorFallback=android.graphics.Color.DKGRAY

    // Aggregated multi-source search state.
    private class AggResult(val source: Source, var manga: SourceManga) {
        var count: Int?=null; var latest: String?=null; var details: SourceDetails?=null
        var loading=false; var detailFailed=false
    }
    private val perSource=5; private val detailBatch=12; private val browseParallel=6; private val detailParallel=4
    private var aggregating=false
    private var aggRun=0
    private var aggQuery=""
    private var aggSearched=0
    private var aggTotal=0
    private val aggResults=mutableListOf<AggResult>()
    private val aggFailed=linkedSetOf<String>()
    private var aggPool: java.util.concurrent.ExecutorService?=null
    private var detailPool: java.util.concurrent.ExecutorService?=null
    private val thumbPool=Executors.newFixedThreadPool(2)
    private val thumbs=object: LinkedHashMap<String,android.graphics.Bitmap>(0,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,android.graphics.Bitmap>)=size>60
    }

    override fun onCreate(state: Bundle?) {
        val dark=resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK==Configuration.UI_MODE_NIGHT_YES
        setTheme(if(dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)
        super.onCreate(state); actionBar?.setDisplayHomeAsUpEnabled(true)
        targetId?.let { id ->
            val record=Bridge.use { it.record(id) }
            if(record==null) { Toast.makeText(this,t("المانجا لم تعد موجودة في المكتبة","This manga is no longer in your library"),Toast.LENGTH_LONG).show(); finish(); return }
            targetTitle=record.getString("title")
        }
        selected=state?.getString("source")?.let { id -> arabicSources.firstOrNull { it.id==id } }; query=state?.getString("query").orEmpty()
        when { selected!=null -> browse(); targetId!=null -> aggregate(); else -> catalog() }
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("source",selected?.id); out.putString("query",query) }
    override fun onDestroy() { generation++; aggRun++; work?.cancel(true); executor.shutdownNow(); aggPool?.shutdownNow(); detailPool?.shutdownNow(); thumbPool.shutdownNow(); super.onDestroy() }
    override fun onBackPressed() { when {
        aggregating && detail!=null -> { detail=null; selected=null; aggregate() }
        aggregating -> super.onBackPressed()
        detail!=null -> { detail=null; browse(false) }
        selected!=null -> { selected=null; query=""; catalog() }
        else -> super.onBackPressed()
    } }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            android.R.id.home -> onBackPressed()
            1 -> selected?.let { startActivity(Intent(this,SourceWebsiteActivity::class.java).putExtra("source",it.id)) }
            2 -> { val s=selected ?: return true; prefs.edit().putBoolean("favorite:${s.id}",!prefs.getBoolean("favorite:${s.id}",false)).apply(); invalidateOptionsMenu() }
            3 -> { AlertDialog.Builder(this).setTitle("Keiyoushi • Apache 2.0").setMessage("Built-in adapters adapted from Keiyoushi extensions-source.\nCommit: eb9062cd3d81645a87a8bc80364db75931ffffca\n\n"+assets.open("source-licenses.txt").bufferedReader().use { it.readText() }).setPositiveButton(android.R.string.ok,null).show() }
            else -> return super.onOptionsItemSelected(item)
        }; return true
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if(selected!=null) { menu.add(0,1,0,t("فتح الموقع","Open website")); menu.add(0,2,1,if(prefs.getBoolean("favorite:${selected!!.id}",false)) t("إزالة من المفضلة","Unfavorite") else t("إضافة للمفضلة","Favorite")) }
        menu.add(0,3,2,t("المصادر والتراخيص","Sources & licenses")); return true
    }
    private fun frame(name:String) {
        generation++; work?.cancel(true); title=name; invalidateOptionsMenu()
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0); layoutDirection=if(ar) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR }
        status=TextView(this).apply { textSize=14f; setPadding(0,dp(12),0,dp(12)); accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        list=ListView(this).apply { dividerHeight=dp(1); clipToPadding=false; setPadding(0,0,0,dp(16)) }
        root.addView(status); setContentView(root)
    }
    private fun button(label:String, action:()->Unit)=Button(this).apply { text=label; minHeight=dp(48); isAllCaps=false; setOnClickListener { action() } }
    private fun input(hint:String,value:String="")=EditText(this).apply { this.hint=hint; setSingleLine(true); setText(value); minHeight=dp(48); inputType=android.text.InputType.TYPE_CLASS_TEXT; imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
    private fun rows(labels:List<Pair<String,String>>, click:(Int)->Unit) {
        list.adapter=object: BaseAdapter() {
            override fun getCount()=labels.size
            override fun getItem(p:Int)=labels[p]
            override fun getItemId(p:Int)=p.toLong()
            override fun getView(p:Int,recycled:View?,parent:ViewGroup):View {
                val row=(recycled as? LinearLayout) ?: LinearLayout(this@SourcesActivity).apply { orientation=LinearLayout.VERTICAL; minimumHeight=dp(72); setPadding(dp(8),dp(12),dp(8),dp(12)); addView(TextView(context).apply { textSize=17f; setTextColor(color(android.R.attr.textColorPrimary)) }); addView(TextView(context).apply { textSize=13f; setTextColor(color(android.R.attr.textColorSecondary)); setPadding(0,dp(4),0,0) }) }
                (row.getChildAt(0) as TextView).text=labels[p].first; (row.getChildAt(1) as TextView).text=labels[p].second; return row
            }
        }; list.setOnItemClickListener { _,_,p,_ -> click(p) }
    }
    private fun catalog() {
        aggregating=false; aggRun++
        frame(t("المصادر العربية","Arabic sources"))
        status.text=t("55 مصدرًا مدمجًا • ابحث واختر مصدر القراءة","55 built-in sources • Choose where to read")
        val search=input(t("ابحث عن مصدر","Find a source")); root.addView(search)
        val adult=Switch(this).apply { text=t("إظهار مصادر البالغين","Show adult sources"); minHeight=dp(48); isChecked=prefs.getBoolean("adult",false) }; root.addView(adult)
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        fun filter() {
            val visible=arabicSources.filter { (adult.isChecked || !it.adult) && (it.name.contains(search.text,true) || it.id.contains(search.text,true)) }.sortedByDescending { prefs.getBoolean("favorite:${it.id}",false) }
            rows(visible.map { s -> (if(prefs.getBoolean("favorite:${s.id}",false)) "★ " else "")+s.name to java.net.URI(s.base).host + if(s.adult) " • 18+" else "" }) { p -> selected=visible[p]; query=targetTitle.take(500); page=1; results=emptyList(); browse() }
            if(visible.isEmpty()) status.text=t("لا توجد مصادر تطابق البحث","No matching sources") else status.text=if(targetId!=null) t("اختر مصدرًا لـ $targetTitle","Choose a source for $targetTitle") else t("${visible.size} مصدرًا • جاهزة داخل التطبيق","${visible.size} sources • Included in the app")
        }
        search.addTextChangedListener(object: TextWatcher { override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}; override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int)=filter(); override fun afterTextChanged(e:Editable?){} })
        adult.setOnCheckedChangeListener { _,v -> prefs.edit().putBoolean("adult",v).apply(); filter() }; filter()
    }

    /** Entry screen for the profile "Other sources" button: one query across every allowed source. */
    private fun aggregate() {
        aggregating=true; selected=null; detail=null
        frame(t("مصادر عربية","Arabic sources"))
        status.text=if(aggResults.isEmpty()) t("اكتب اسم المانجا وابحث في كل المصادر مرة واحدة","Type a title and search every source at once") else aggStatusText()
        val search=input(t("اسم المانجا","Manga title"),aggQuery.ifBlank { targetTitle }); root.addView(search)
        val adult=Switch(this).apply { text=t("تضمين مصادر البالغين","Include adult sources"); minHeight=dp(48); isChecked=prefs.getBoolean("adult",false) }; root.addView(adult)
        val controls=LinearLayout(this)
        fun start() {
            prefs.edit().putBoolean("adult",adult.isChecked).apply()
            val q=search.text.toString().trim()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken,0)
            if(q.isNotBlank()) runAggregate(q,adult.isChecked)
        }
        controls.addView(button(t("بحث في المصادر العربية","Search Arabic sources"),::start),LinearLayout.LayoutParams(0,-2,2f))
        controls.addView(button(t("مصدر واحد","One source")) { catalog() },LinearLayout.LayoutParams(0,-2,1f))
        root.addView(controls)
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        search.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { start(); true } else false }
        if(aggResults.isNotEmpty()) showAggResults()
    }
    private fun runAggregate(q:String, adult:Boolean) {
        val run= ++aggRun
        aggPool?.shutdownNow(); detailPool?.shutdownNow(); aggPool=null; detailPool=null
        aggQuery=q; aggResults.clear(); aggFailed.clear(); aggSearched=0
        val visible=arabicSources.filter { adult || !it.adult }
        aggTotal=visible.size; showAggResults()
        val pool=Executors.newFixedThreadPool(browseParallel); aggPool=pool
        for(s in visible) pool.execute {
            val found=try { s.browse(q.take(500),1).manga.take(perSource) } catch(e:Throwable) { null }
            runOnUiThread {
                if(run!=aggRun) return@runOnUiThread
                if(found==null) aggFailed.add(s.id) else for(m in found) if(aggResults.none { it.source.id==s.id && it.manga.url==m.url }) aggResults.add(AggResult(s,m))
                aggSearched++
                if(aggregating && detail==null && !isFinishing) { showAggResults(); maybeAutoLoad(run) }
            }
        }
    }
    private fun retryFailed() {
        val run=aggRun; val retry=aggFailed.toList(); if(retry.isEmpty()) return
        aggFailed.clear(); showAggResults()
        val pool=aggPool ?: Executors.newFixedThreadPool(browseParallel).also { aggPool=it }
        for(id in retry) { val s=arabicSources.first { it.id==id }; pool.execute {
            val found=try { s.browse(aggQuery.take(500),1).manga.take(perSource) } catch(e:Throwable) { null }
            runOnUiThread {
                if(run!=aggRun) return@runOnUiThread
                if(found==null) aggFailed.add(s.id) else for(m in found) if(aggResults.none { it.source.id==s.id && it.manga.url==m.url }) aggResults.add(AggResult(s,m))
                if(aggregating && detail==null && !isFinishing) { showAggResults(); maybeAutoLoad(run) }
            }
        } }
    }
    private fun maybeAutoLoad(run:Int) {
        val active=aggResults.count { it.loading || it.count!=null || it.detailFailed }
        if(active<detailBatch) loadDetails(detailBatch-active,run)
    }
    private fun loadDetails(n:Int, run:Int) {
        if(run!=aggRun) return
        val pending=aggResults.filter { it.count==null && !it.loading && !it.detailFailed }.take(n)
        if(pending.isEmpty()) return
        pending.forEach { it.loading=true }
        if(aggregating && detail==null && !isFinishing) showAggResults()
        val pool=detailPool ?: Executors.newFixedThreadPool(detailParallel).also { detailPool=it }
        for(r in pending) pool.execute {
            val loaded=try { val d=r.source.details(r.manga); val unique=d.chapters.distinctBy { it.url }; Triple(d,unique.size,unique.mapNotNull { it.number.toBigDecimalOrNull() }.maxOrNull()?.stripTrailingZeros()?.toPlainString()) } catch(e:Throwable) { null }
            runOnUiThread {
                if(run!=aggRun) return@runOnUiThread
                if(loaded==null) { r.detailFailed=true; r.loading=false } else { r.details=loaded.first; r.manga=loaded.first.manga; r.count=loaded.second; r.latest=loaded.third; r.loading=false }
                if(aggregating && detail==null && !isFinishing) showAggResults()
            }
        }
    }
    private fun aggSorted(): List<AggResult> = AggregateRanking.order(aggResults.toList(),{ it.count },{ it.latest })
    private fun aggStatusText(): String {
        val shown=aggResults.size; val done=aggSearched>=aggTotal
        var text=if(done) t("اكتمل البحث في $aggTotal مصدرًا • $shown نتيجة","Searched all $aggTotal sources • $shown results")
                 else t("اكتمل البحث في $aggSearched من $aggTotal مصدرًا • $shown نتيجة","Searched $aggSearched of $aggTotal sources • $shown results")
        if(done && shown==0) text+="\n"+t("لا نتائج. جرّب اسمًا مختلفًا (بالعربية أو الإنجليزية).","No results. Try a different title (Arabic or English).")
        if(done && aggFailed.isNotEmpty()) text+="\n"+t("تعذّر البحث في ${aggFailed.size} مصدرًا","${aggFailed.size} sources failed")
        return text
    }
    private fun showAggResults() {
        status.text=aggStatusText()
        val sorted=aggSorted(); val done=aggSearched>=aggTotal
        val footers=mutableListOf<String>()
        if(aggResults.any { it.count==null && !it.loading && !it.detailFailed }) footers.add("more")
        if(done && aggFailed.isNotEmpty()) footers.add("retry")
        list.adapter=object: BaseAdapter() {
            override fun getCount()=sorted.size+footers.size
            override fun getItem(p:Int)=p
            override fun getItemId(p:Int)=p.toLong()
            override fun getViewTypeCount()=2
            override fun getItemViewType(p:Int)=if(p<sorted.size) 0 else 1
            override fun getView(p:Int,recycled:View?,parent:ViewGroup):View {
                if(p>=sorted.size) {
                    val b=(recycled as? Button) ?: Button(this@SourcesActivity).apply { isAllCaps=false; minHeight=dp(48) }
                    val kind=footers[p-sorted.size]
                    b.text=if(kind=="more") t("تحميل تفاصيل المزيد","Load more details") else t("إعادة محاولة المتعثرة (${aggFailed.size})","Retry failed (${aggFailed.size})")
                    b.setOnClickListener { if(kind=="more") loadDetails(detailBatch,aggRun) else retryFailed() }
                    return b
                }
                val r=sorted[p]
                val row=(recycled as? LinearLayout)?.takeIf { it.tag=="res" } ?: LinearLayout(this@SourcesActivity).apply {
                    orientation=LinearLayout.HORIZONTAL; tag="res"; minimumHeight=dp(100); setPadding(dp(4),dp(8),dp(4),dp(8)); gravity=android.view.Gravity.CENTER_VERTICAL
                    addView(ImageView(context).apply { layoutParams=LinearLayout.LayoutParams(dp(60),dp(84)); scaleType=ImageView.ScaleType.CENTER_CROP })
                    addView(LinearLayout(context).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,dp(10),0)
                        addView(TextView(context).apply { textSize=16f; setTextColor(color(android.R.attr.textColorPrimary)); maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
                        addView(TextView(context).apply { textSize=13f; setTextColor(color(android.R.attr.textColorSecondary)); setPadding(0,dp(3),0,0) })
                        addView(TextView(context).apply { textSize=13f; setPadding(0,dp(2),0,0) })
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
                val img=row.getChildAt(0) as ImageView
                val texts=row.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text=r.manga.title
                (texts.getChildAt(1) as TextView).text=r.source.name + if(r.source.adult) " • 18+" else ""
                val line3=texts.getChildAt(2) as TextView
                when {
                    r.loading -> { line3.setTextColor(color(android.R.attr.textColorSecondary)); line3.text=t("جارٍ تحميل الفصول…","Loading chapters…") }
                    r.detailFailed -> { line3.setTextColor(android.graphics.Color.rgb(0xC6,0x28,0x28)); line3.text=t("تعذّر تحميل الفصول","Couldn't load chapters") }
                    r.count!=null -> { line3.setTextColor(color(android.R.attr.textColorPrimary)); line3.text=t("${r.count} فصل","${r.count} chapters")+(r.latest?.let { t(" • آخر فصل $it"," • latest $it") } ?: "") }
                    else -> { line3.setTextColor(color(android.R.attr.textColorSecondary)); line3.text=t("عدد الفصول قيد التحميل","Chapter count pending") }
                }
                thumb(img,r); return row
            }
        }
        list.setOnItemClickListener { _,_,p,_ -> if(p<sorted.size) openAggResult(sorted[p]) }
    }
    private fun openAggResult(r: AggResult) {
        selected=r.source
        val cached=r.details
        if(cached!=null) { detail=cached; renderDetail() }
        else async(t("جاري تحميل الفصول…","Loading chapters…"),{ r.source.details(r.manga) }) { d ->
            val unique=d.chapters.distinctBy { it.url }
            r.details=d; r.manga=d.manga; r.count=unique.size
            r.latest=unique.mapNotNull { it.number.toBigDecimalOrNull() }.maxOrNull()?.stripTrailingZeros()?.toPlainString()
            detail=d; renderDetail()
        }
    }
    private fun thumb(img: ImageView, r: AggResult) {
        val url=r.manga.cover; img.setImageDrawable(null); img.tag=url
        if(url.isBlank()) return
        synchronized(thumbs) { thumbs[url] }?.let { img.setImageBitmap(it); return }
        thumbPool.execute {
            try {
                val bytes=SourceHttp.bytes(url,r.source,referer=r.source.base+"/",limit=8*1024*1024)
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                var sample=1; while(bounds.outWidth/(sample*2)>=120) sample*=2
                val bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample }) ?: return@execute
                synchronized(thumbs) { thumbs[url]=bmp }
                runOnUiThread { if(!isFinishing && img.tag==url) img.setImageBitmap(bmp) }
            } catch(_:Throwable) {}
        }
    }

    private fun browse(load:Boolean=true) {
        val source=selected ?: return; frame(source.name)
        val search=input(t("ابحث عن مانجا في هذا المصدر","Search this source"),query); root.addView(search)
        val controls=LinearLayout(this)
        fun searchNow() { query=search.text.toString().trim(); page=1; results=emptyList(); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken,0); fetch() }
        controls.addView(button(t("بحث","Search"),::searchNow),LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(button(t("تحديث","Refresh")) { page=1; results=emptyList(); fetch() },LinearLayout.LayoutParams(0,-2,1f)); root.addView(controls)
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f)); showResults(); if(load) fetch()
        search.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchNow(); true } else false }
    }
    private fun showResults() {
        status.text=if(results.isEmpty()) t("لا توجد نتائج حتى الآن","No results yet") else t("${results.size} عملًا • اضغط لعرض الفصول","${results.size} titles • Tap for chapters")
        val labels=results.map { it.title to t("عرض التفاصيل والفصول","Details and chapters") }.toMutableList()
        if(next) labels+=t("تحميل المزيد","Load more") to ""
        rows(labels) { i -> if(i==results.size) { page++; fetch() } else showDetail(results[i]) }
    }
    private fun fetch() {
        val source=selected ?: return; val requestedPage=page; val requestedQuery=query
        async(t("جاري تحميل الأعمال…","Loading titles…"),{ source.browse(requestedQuery,requestedPage) }) { data ->
            results=(if(requestedPage==1) data.manga else results+data.manga).distinctBy { it.url }; next=data.next; showResults()
            if(results.isEmpty()) status.text=t("لا توجد نتائج. جرّب اسمًا آخر أو افتح الموقع للتحقق.","No results. Try another title or check the website.")
        }
    }
    private fun showDetail(manga:SourceManga) {
        val source=selected ?: return
        async(t("جاري تحميل الفصول…","Loading chapters…"),{ source.details(manga) }) { data -> detail=data; renderDetail() }
    }
    private fun renderDetail() {
        val data=detail ?: return; val source=selected ?: return; frame(data.manga.title)
        status.text="${source.name} • ${data.chapters.size} " + t("فصل","chapters") + if(data.manga.description.isBlank()) "" else "\n"+data.manga.description.take(350)
        root.addView(button(if(targetId!=null) t("استخدام هذا المصدر","Use this source") else t("إضافة إلى المكتبة والقراءة","Add to library & read")) { save(targetId) })
        if(targetId==null) root.addView(button(t("ربط بعمل موجود في مكتبتي","Link to an existing library title")) {
            val records=Bridge.use { it.library().objects() }
            if(records.isEmpty()) Toast.makeText(this,t("مكتبتك فارغة","Your library is empty"),Toast.LENGTH_SHORT).show()
            else AlertDialog.Builder(this).setTitle(t("اختر العمل نفسه للاحتفاظ بتقدمك","Choose the same title to keep your progress")).setItems(records.map { it.getString("title") }.toTypedArray()) { _,i -> save(records[i].getString("id")) }.setNegativeButton(android.R.string.cancel,null).show()
        })
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        rows(data.chapters.map { it.name to listOf(it.group,it.date).filter(String::isNotBlank).joinToString(" • ") }) { Toast.makeText(this,t("أضف العمل للمكتبة لقراءته وتحميل فصوله","Add this title to your library to read and download"),Toast.LENGTH_SHORT).show() }
    }
    private fun save(target:String?) {
        val source=selected ?: return; val data=detail ?: return
        async(t("جاري الحفظ…","Saving…"),{ SourceBridge.add(source,data,target) }) {
            if(targetId!=null) { Toast.makeText(this,t("تم ربط المصدر بـ $targetTitle","Source linked to $targetTitle"),Toast.LENGTH_SHORT).show(); finish(); return@async }
            Toast.makeText(this,t("تم الحفظ. افتح العمل من المكتبة للقراءة أو التحميل.","Saved. Open the title in your library to read or download."),Toast.LENGTH_LONG).show()
            startActivity(Intent().setClassName(this,"com.mangaku.app.MainActivity").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish()
        }
    }
    private fun <T> async(message:String,task:()->T,done:(T)->Unit) {
        val run=++generation; work?.cancel(true); status.text=message
        work=executor.submit {
            try { val result=task(); runOnUiThread { if(run==generation && !isFinishing) done(result) } }
            catch(e:Exception) { runOnUiThread { if(run==generation && !isFinishing) { if(page>1) page--; status.text=t("تعذر التحميل. أعد المحاولة أو افتح الموقع من القائمة.","Could not load. Retry or open the website from the menu."); AlertDialog.Builder(this).setTitle(t("المصدر غير متاح الآن","Source unavailable right now")).setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton(android.R.string.ok,null).show() } } }
        }
    }
}

/** User-controlled sign-in/website verification; only the chosen source can retain navigation. */
class SourceWebsiteActivity: Activity() {
    private lateinit var web:WebView
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        val source=arabicSources.firstOrNull { it.id==intent.getStringExtra("source") } ?: return finish()
        title=source.name; actionBar?.setDisplayHomeAsUpEnabled(true)
        web=WebView(this); web.settings.javaScriptEnabled=true; web.settings.domStorageEnabled=true; web.settings.userAgentString=SourceHttp.UA
        web.settings.allowFileAccess=false; web.settings.allowContentAccess=false; web.settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:android.webkit.WebResourceRequest):Boolean = !source.owns(request.url.toString()) || request.url.scheme!="https"
        }
        setContentView(web); web.loadUrl(source.base)
    }
    override fun onOptionsItemSelected(item:MenuItem):Boolean { if(item.itemId==android.R.id.home) { finish(); return true }; return super.onOptionsItemSelected(item) }
    override fun onBackPressed() { if(web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onDestroy() { if(::web.isInitialized) { android.webkit.CookieManager.getInstance().flush(); web.destroy() }; super.onDestroy() }
}
