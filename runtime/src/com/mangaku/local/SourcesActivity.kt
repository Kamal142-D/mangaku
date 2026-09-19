package com.mangaku.local

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
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
        if(selected==null) catalog() else browse()
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putString("source",selected?.id); out.putString("query",query) }
    override fun onDestroy() { generation++; work?.cancel(true); executor.shutdownNow(); super.onDestroy() }
    override fun onBackPressed() { when { detail!=null -> { detail=null; browse(false) }; selected!=null -> { selected=null; query=""; catalog() }; else -> super.onBackPressed() } }
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
