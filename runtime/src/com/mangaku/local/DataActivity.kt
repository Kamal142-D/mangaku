package com.mangaku.local

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONObject

/**
 * Native "Data & sync" screen. Uses platform widgets only, so it stays independent
 * from the recovered Compose ABI. This revision reorganises the same actions into
 * clear sections, hardens the progress editor, and adds Arabic/English + RTL. It
 * changes presentation only; all storage, backup and sync behaviour is unchanged.
 */
class DataActivity : Activity() {
    private lateinit var content: LinearLayout
    private var arabic = false
    private var dir = View.LAYOUT_DIRECTION_LTR
    private val accent by lazy { themeColor(android.R.attr.colorAccent) }
    private val secondaryColor by lazy { themeColor(android.R.attr.textColorSecondary) }

    // --- Localised strings (central, new-text only) ---------------------------
    private object Loc {
        val en = mapOf(
            "screen_title" to "Data & sync",
            "storage_error" to "Storage could not open. Your original files are preserved.\n%s",
            "ok" to "OK", "cancel" to "Cancel", "failed" to "Failed",
            "op_failed" to "That didn't work; your saved data is preserved.",
            "working" to "Working…", "save" to "Save", "saved_ok" to "Saved",
            "sec_library" to "Library & progress",
            "w_manga" to "manga", "w_records" to "reading records",
            "saved_here" to "Stored on this device.",
            "manage_progress" to "Manage progress & links",
            "manage_progress_desc" to "Update status, chapter, notes and source links without opening the reader.",
            "sec_backup" to "Backup & restore",
            "backup_desc" to "Backups include your tracking, chapters, notes and reading history. They do not include downloaded images or your AniList token.",
            "export" to "Export a backup", "restore" to "Restore a backup",
            "exported_ok" to "Backup exported", "restored_ok" to "Backup restored",
            "restore_confirm_title" to "Restore %d manga?",
            "restore_confirm_msg" to "This replaces your current library and history. A copy of your current data is saved on this device first, and downloaded images are kept.",
            "invalid_backup" to "This file isn't a valid Mangaku backup.",
            "sec_anilist" to "AniList account & sync",
            "anilist_benefit" to "Link your AniList account to import your list and keep chapter progress in sync.",
            "connect" to "Link account", "connect_title" to "Link AniList",
            "connect_msg" to "Paste an access token from an AniList app you trust. Linking leaves sync off until you choose a mode. Your token is encrypted on this device and never included in backups.",
            "token_hint" to "Access token", "connected_ok" to "Account linked",
            "account_label" to "Account", "sync_mode_label" to "Sync mode",
            "last_sync_label" to "Last successful sync", "last_sync_never" to "Never",
            "sync_now" to "Sync now", "sync_running" to "Syncing…",
            "sync_off_hint" to "Syncing is off. Choose a sync mode to start.",
            "change_mode" to "Change sync mode", "choose_mode_title" to "AniList sync",
            "mode_none" to "Off", "mode_import" to "Import only", "mode_two" to "Two-way",
            "mode_none_item" to "Off — don't sync",
            "mode_import_item" to "Import only — never send your changes",
            "mode_two_item" to "Two-way — send and receive changes",
            "sync_error_prefix" to "Last sync error: %s",
            "account_settings" to "Account settings", "disconnect" to "Unlink account",
            "disconnect_msg" to "This removes the AniList link and its saved token from this device. Your local progress is kept.",
            "conflicts_header" to "Needs review",
            "conflicts_count" to "%d title needs a sync decision.",
            "review" to "Review: %s", "conflict_title" to "Choose progress — %s",
            "col_device" to "This device", "col_anilist" to "AniList",
            "conflict_missing_explain" to "This title isn't on your AniList list.\n\n• Unlink keeps all your local progress and removes the link.\n• Keep local keeps your progress and, in two-way sync, adds it to AniList on the next sync.",
            "f_chapter" to "Chapter", "f_volume" to "Volume", "f_status" to "Status",
            "f_rating" to "Rating", "f_notes" to "Notes", "none_value" to "None",
            "outcome_use" to "• Use AniList: replaces this title's progress on this device.",
            "outcome_keep_two" to "• Keep local: keeps your device values and sends them to AniList on the next sync.",
            "outcome_keep_import" to "• Keep local: keeps your device values and ignores this AniList state until it changes.",
            "outcome_unlink" to "• Unlink: removes the AniList link but keeps all local progress.",
            "outcome_keep_missing_two" to "• Keep local: keeps your progress and re-creates this entry on AniList on the next sync.",
            "use_anilist" to "Use AniList", "unlink_anilist" to "Unlink",
            "keep_local" to "Keep local", "later" to "Later",
            "edit_pick_title" to "Choose a manga", "edit_empty" to "Add a manga to your library first.",
            "sec_reading" to "Reading status", "sec_details" to "Details",
            "sec_links" to "Source & provider links",
            "status_label" to "Status", "cur_chapter" to "Current chapter",
            "cur_volume" to "Volume", "cur_page" to "Page",
            "rating_label" to "Rating (0–100, optional)", "notes_label" to "Notes",
            "anilist_id_label" to "AniList manga ID (0 = not linked)",
            "source_label" to "Reading source URL",
            "err_page" to "Enter a whole number, 0 or more.",
            "err_rating" to "Enter a number from 0 to 100, or leave blank.",
            "err_anilist_id" to "Enter a whole number, 0 or more.",
            "err_chapter" to "Enter a chapter — a number, decimal or label.",
            "reopen_before_save" to "This manga changed since you opened it. Close and reopen before saving.",
            "backward_title" to "Move progress backwards?",
            "backward_msg" to "This sets the chapter lower than before. It's kept as an intentional correction and may need review during sync.",
            "apply_correction" to "Apply",
            "st_READING" to "Reading", "st_PLAN_TO_READ" to "Plan to read",
            "st_FINISHED" to "Finished", "st_ON_HOLD" to "On hold",
            "st_DROPPED" to "Dropped", "st_REREADING" to "Rereading"
        )
        val ar = mapOf(
            "screen_title" to "البيانات والمزامنة",
            "storage_error" to "تعذّر فتح التخزين. ملفاتك الأصلية محفوظة.\n%s",
            "ok" to "حسنًا", "cancel" to "إلغاء", "failed" to "فشل",
            "op_failed" to "لم تنجح العملية؛ بياناتك المحفوظة سليمة.",
            "working" to "جارٍ التنفيذ…", "save" to "حفظ", "saved_ok" to "تم الحفظ",
            "sec_library" to "المكتبة والتقدّم",
            "w_manga" to "مانجا", "w_records" to "سجل قراءة",
            "saved_here" to "محفوظة على هذا الجهاز.",
            "manage_progress" to "إدارة التقدّم والروابط",
            "manage_progress_desc" to "حدّث الحالة والفصل والملاحظات وروابط المصدر دون فتح القارئ.",
            "sec_backup" to "النسخ الاحتياطي والاسترجاع",
            "backup_desc" to "تتضمن النسخة تتبّعك والفصول والملاحظات وسجل القراءة. لا تتضمن الصور المُنزّلة ولا رمز AniList.",
            "export" to "تصدير نسخة احتياطية", "restore" to "استرجاع نسخة احتياطية",
            "exported_ok" to "تم تصدير النسخة", "restored_ok" to "تم الاسترجاع",
            "restore_confirm_title" to "استرجاع %d مانجا؟",
            "restore_confirm_msg" to "سيستبدل هذا مكتبتك وسجلك الحاليين. تُحفظ نسخة من بياناتك الحالية على الجهاز أولًا، وتبقى الصور المُنزّلة.",
            "invalid_backup" to "هذا الملف ليس نسخة Mangaku صالحة.",
            "sec_anilist" to "حساب AniList والمزامنة",
            "anilist_benefit" to "اربط حساب AniList لاستيراد قائمتك ومزامنة تقدّم الفصول.",
            "connect" to "ربط الحساب", "connect_title" to "ربط AniList",
            "connect_msg" to "الصق رمز وصول من تطبيق AniList تثق به. تبقى المزامنة متوقفة بعد الربط حتى تختار وضعًا. يُشفَّر الرمز على هذا الجهاز ولا يُدرَج في النسخ الاحتياطي.",
            "token_hint" to "رمز الوصول", "connected_ok" to "تم ربط الحساب",
            "account_label" to "الحساب", "sync_mode_label" to "وضع المزامنة",
            "last_sync_label" to "آخر مزامنة ناجحة", "last_sync_never" to "لم تحدث بعد",
            "sync_now" to "مزامنة الآن", "sync_running" to "جارٍ المزامنة…",
            "sync_off_hint" to "المزامنة متوقفة. اختر وضع مزامنة للبدء.",
            "change_mode" to "تغيير وضع المزامنة", "choose_mode_title" to "مزامنة AniList",
            "mode_none" to "متوقفة", "mode_import" to "استيراد فقط", "mode_two" to "ثنائية الاتجاه",
            "mode_none_item" to "متوقفة — بدون مزامنة",
            "mode_import_item" to "استيراد فقط — لا تُرسل تغييراتك",
            "mode_two_item" to "ثنائية — إرسال واستقبال التغييرات",
            "sync_error_prefix" to "خطأ آخر مزامنة: %s",
            "account_settings" to "إعدادات الحساب", "disconnect" to "فصل الحساب",
            "disconnect_msg" to "يزيل هذا رابط AniList والرمز المحفوظ من هذا الجهاز. يبقى تقدّمك المحلي.",
            "conflicts_header" to "بحاجة إلى مراجعة",
            "conflicts_count" to "%d عنوان بحاجة إلى قرار مزامنة.",
            "review" to "مراجعة: %s", "conflict_title" to "اختر التقدّم — %s",
            "col_device" to "هذا الجهاز", "col_anilist" to "AniList",
            "conflict_missing_explain" to "هذا العنوان غير موجود في قائمة AniList.\n\n• إلغاء الربط يُبقي كل تقدّمك المحلي ويزيل الرابط.\n• إبقاء المحلي يُبقي تقدّمك، وفي المزامنة الثنائية يُضيفه إلى AniList في المزامنة التالية.",
            "f_chapter" to "الفصل", "f_volume" to "المجلد", "f_status" to "الحالة",
            "f_rating" to "التقييم", "f_notes" to "الملاحظات", "none_value" to "لا شيء",
            "outcome_use" to "• استخدام AniList: يستبدل تقدّم هذا العنوان على الجهاز.",
            "outcome_keep_two" to "• إبقاء المحلي: يُبقي قيم جهازك ويرسلها إلى AniList في المزامنة التالية.",
            "outcome_keep_import" to "• إبقاء المحلي: يُبقي قيم جهازك ويتجاهل حالة AniList هذه حتى تتغيّر.",
            "outcome_unlink" to "• إلغاء الربط: يزيل رابط AniList مع إبقاء كل التقدّم المحلي.",
            "outcome_keep_missing_two" to "• إبقاء المحلي: يُبقي تقدّمك ويعيد إنشاء الإدخال على AniList في المزامنة التالية.",
            "use_anilist" to "استخدام AniList", "unlink_anilist" to "إلغاء الربط",
            "keep_local" to "إبقاء المحلي", "later" to "لاحقًا",
            "edit_pick_title" to "اختر مانجا", "edit_empty" to "أضف مانجا إلى مكتبتك أولًا.",
            "sec_reading" to "حالة القراءة", "sec_details" to "التفاصيل",
            "sec_links" to "روابط المصدر والمزامنة",
            "status_label" to "الحالة", "cur_chapter" to "الفصل الحالي",
            "cur_volume" to "المجلد", "cur_page" to "الصفحة",
            "rating_label" to "التقييم (0–100، اختياري)", "notes_label" to "ملاحظات",
            "anilist_id_label" to "معرّف AniList (0 = غير مربوط)",
            "source_label" to "رابط مصدر القراءة",
            "err_page" to "أدخل رقمًا صحيحًا، 0 أو أكثر.",
            "err_rating" to "أدخل رقمًا من 0 إلى 100، أو اتركه فارغًا.",
            "err_anilist_id" to "أدخل رقمًا صحيحًا، 0 أو أكثر.",
            "err_chapter" to "أدخل فصلًا — رقمًا أو كسرًا أو تسمية.",
            "reopen_before_save" to "تغيّرت هذه المانجا منذ فتحها. أغلقها وافتحها من جديد قبل الحفظ.",
            "backward_title" to "إرجاع التقدّم للخلف؟",
            "backward_msg" to "سيضبط الفصل أقل من السابق. يُحفظ كتصحيح متعمّد وقد يحتاج مراجعة أثناء المزامنة.",
            "apply_correction" to "تطبيق",
            "st_READING" to "قيد القراءة", "st_PLAN_TO_READ" to "أنوي قراءتها",
            "st_FINISHED" to "مكتملة", "st_ON_HOLD" to "متوقفة مؤقتًا",
            "st_DROPPED" to "متروكة", "st_REREADING" to "إعادة قراءة"
        )
    }
    private lateinit var strings: Map<String, String>
    private fun tr(key: String): String = strings[key] ?: Loc.en[key] ?: key

    private data class Conf(val id: String, val title: String, val localJson: String, val remoteJson: String)
    private class Snap(
        val manga: Int, val history: Int, val accountName: String?, val mode: String,
        val error: String?, val lastSync: Long?, val conflicts: List<Conf>
    )

    override fun onCreate(state: Bundle?) {
        arabic = resources.configuration.locales[0].language == "ar"
        strings = if (arabic) Loc.ar else Loc.en
        dir = if (arabic) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setTheme(if (night) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)
        super.onCreate(state)
        title = tr("screen_title")
        actionBar?.setDisplayHomeAsUpEnabled(true)
        render()
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    // --- Small view helpers ---------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun themeColor(attr: Int): Int {
        val a = obtainStyledAttributes(intArrayOf(attr)); return try { a.getColor(0, 0) } finally { a.recycle() }
    }
    private fun message(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton(tr("ok"), null).show() }
    private fun toast(text: String) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    private fun safe(run: () -> Unit) { try { run() } catch (e: Exception) { message(e.message ?: tr("failed")) } }

    private fun header(text: String) {
        content.addView(TextView(this).apply {
            this.text = text; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent)
            setPadding(0, dp(18), 0, dp(6)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
    }
    private fun body(text: String, secondary: Boolean = false, ltr: Boolean = false) {
        content.addView(TextView(this).apply {
            this.text = text; textSize = if (secondary) 13f else 15f
            if (secondary) setTextColor(secondaryColor)
            setPadding(0, dp(2), 0, dp(6)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setTextIsSelectable(true)
            if (ltr) textDirection = View.TEXT_DIRECTION_LTR
        })
    }
    private fun divider() {
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
                .also { it.topMargin = dp(14); it.bottomMargin = dp(2) }
            setBackgroundColor(0x33888888)
        })
    }
    private fun primary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        content.addView(Button(this).apply {
            this.text = text; isEnabled = enabled; setAllCaps(false); minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(6) }
            setOnClickListener { safe(onClick) }
        })
    }
    private fun secondary(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        content.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            this.text = text; isEnabled = enabled; setAllCaps(false); minimumHeight = dp(48)
            setTextColor(accent); gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(2) }
            setOnClickListener { safe(onClick) }
        })
    }

    private fun statusLabel(code: String) = tr("st_$code")
    private fun anilistStatusLabel(code: String) = when (code) {
        "CURRENT" -> tr("st_READING"); "PLANNING" -> tr("st_PLAN_TO_READ"); "COMPLETED" -> tr("st_FINISHED")
        "PAUSED" -> tr("st_ON_HOLD"); "DROPPED" -> tr("st_DROPPED"); "REPEATING" -> tr("st_REREADING"); else -> code
    }
    private fun modeLabel(mode: String) = when (mode) { "import_only" -> tr("mode_import"); "two_way" -> tr("mode_two"); else -> tr("mode_none") }

    /** Runs a mutation off the UI thread, keeping the last saved state on failure. */
    private fun work(ok: String? = null, task: () -> Unit) {
        for (i in 0 until content.childCount) content.getChildAt(i).isEnabled = false
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = dir
            setPadding(0, dp(14), 0, dp(6))
            addView(ProgressBar(this@DataActivity).apply { layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)) })
            addView(TextView(this@DataActivity).apply { text = tr("working"); setPadding(dp(14), 0, dp(14), 0) })
        })
        Bridge.worker.execute {
            try { task(); runOnUiThread { if (!isFinishing) { render(); ok?.let { toast(it) } } } }
            catch (e: Exception) { runOnUiThread { if (!isFinishing) { render(); message(e.message ?: tr("op_failed")) } } }
        }
    }

    // --- Screen ---------------------------------------------------------------
    private fun render() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutDirection = dir
            val p = dp(20); setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(content); layoutDirection = dir })
        if (Bridge.store == null) { header(tr("sec_library")); body(String.format(tr("storage_error"), Bridge.error ?: "")); return }
        val snap = try { Bridge.use { gatherSnapshot(it) } } catch (e: Exception) { body(e.message ?: tr("op_failed")); return }

        // 1) Library & progress
        primary(if(arabic) "المصادر العربية" else "Arabic sources") { startActivity(Intent(this,SourcesActivity::class.java)) }
        header(tr("sec_library"))
        body("${snap.manga} ${tr("w_manga")} · ${snap.history} ${tr("w_records")}")
        body(tr("saved_here"), secondary = true)
        primary(tr("manage_progress")) { chooseManga() }
        body(tr("manage_progress_desc"), secondary = true)

        // 2) Backup & restore
        divider(); header(tr("sec_backup"))
        body(tr("backup_desc"), secondary = true)
        primary(tr("export")) {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "mangaku-backup.json").addCategory(Intent.CATEGORY_OPENABLE), 1)
        }
        secondary(tr("restore")) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 2)
        }

        // 3) AniList account & sync
        divider(); header(tr("sec_anilist"))
        if (snap.accountName == null) {
            body(tr("anilist_benefit"), secondary = true)
            primary(tr("connect")) { connectDialog() }
        } else {
            body("${tr("account_label")}: ${snap.accountName}")
            body("${tr("sync_mode_label")}: ${modeLabel(snap.mode)}", secondary = true)
            val last = snap.lastSync?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: tr("last_sync_never")
            body("${tr("last_sync_label")}: $last", secondary = true)
            if (snap.error != null) body(String.format(tr("sync_error_prefix"), snap.error), secondary = true)
            if (snap.mode == "none") body(tr("sync_off_hint"), secondary = true)
            else {
                val busy = Bridge.busy.get()
                primary(if (busy) tr("sync_running") else tr("sync_now"), enabled = !busy) {
                    Bridge.sync { if (!isFinishing) render() }; render()
                }
            }
            secondary(tr("change_mode")) { modeDialog() }

            if (snap.conflicts.isNotEmpty()) {
                header(tr("conflicts_header"))
                body(String.format(tr("conflicts_count"), snap.conflicts.size), secondary = true)
                for (c in snap.conflicts) secondary(String.format(tr("review"), c.title)) { reviewConflict(c, snap.mode) }
            }

            divider(); header(tr("account_settings"))
            secondary(tr("disconnect")) { disconnectDialog() }
        }
    }

    private fun gatherSnapshot(s: LocalStore): Snap {
        val conflicts = s.db.rawQuery("SELECT canonical_manga_id,local_json,remote_json FROM conflicts", null).use { c ->
            buildList { while (c.moveToNext()) { val id = c.getString(0); add(Conf(id, s.record(id)?.optString("title")?.ifBlank { id } ?: id, c.getString(1), c.getString(2))) } }
        }
        return Snap(
            s.library().length(), s.history().length(), s.meta("account_name")?.ifBlank { null },
            s.meta("sync_mode") ?: "none", s.meta("sync_error")?.ifBlank { null }, s.meta("last_sync")?.toLongOrNull(), conflicts
        )
    }

    // --- AniList account dialogs ---------------------------------------------
    private fun connectDialog() {
        val input = EditText(this).apply {
            hint = tr("token_hint"); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            minimumHeight = dp(48); textDirection = View.TEXT_DIRECTION_LTR
        }
        val box = FrameLayout(this).apply { val p = dp(20); setPadding(p, dp(8), p, 0); addView(input) }
        AlertDialog.Builder(this).setTitle(tr("connect_title")).setMessage(tr("connect_msg")).setView(box)
            .setPositiveButton(tr("connect")) { _, _ -> val token = input.text.toString().trim(); input.text.clear(); work(tr("connected_ok")) { Bridge.connect(token) } }
            .setNegativeButton(tr("cancel"), null).show()
    }
    private fun modeDialog() {
        val items = arrayOf(tr("mode_none_item"), tr("mode_import_item"), tr("mode_two_item"))
        val values = listOf("none", "import_only", "two_way")
        val current = Bridge.use { it.meta("sync_mode") ?: "none" }
        AlertDialog.Builder(this).setTitle(tr("choose_mode_title"))
            .setSingleChoiceItems(items, values.indexOf(current)) { d, i -> try { Bridge.mode(values[i]); d.dismiss(); render() } catch (e: Exception) { d.dismiss(); message(e.message ?: tr("failed")) } }
            .setNegativeButton(tr("cancel"), null).show()
    }
    private fun disconnectDialog() {
        AlertDialog.Builder(this).setTitle(tr("disconnect")).setMessage(tr("disconnect_msg"))
            .setPositiveButton(tr("disconnect")) { _, _ ->
                try { Bridge.mode("none"); Credentials.clear(this); Bridge.use { it.meta("account_name", "") }; render() } catch (e: Exception) { message(e.message ?: tr("failed")) }
            }.setNegativeButton(tr("cancel"), null).show()
    }

    // --- Conflict review ------------------------------------------------------
    private fun reviewConflict(c: Conf, mode: String) {
        val local = JSONObject(c.localJson); val remote = JSONObject(c.remoteJson)
        val missing = remote.optBoolean("_missing")
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = dir; val p = dp(20); setPadding(p, dp(8), p, 0) }
        fun line(text: String, bold: Boolean = false, secondary: Boolean = false) {
            root.addView(TextView(this).apply {
                this.text = text; textSize = if (secondary) 13f else 15f
                if (bold) setTypeface(typeface, Typeface.BOLD)
                if (secondary) setTextColor(secondaryColor)
                setPadding(0, dp(3), 0, dp(3)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setTextIsSelectable(true)
            })
        }
        if (missing) line(tr("conflict_missing_explain"))
        else {
            line("${tr("col_device")}   |   ${tr("col_anilist")}", secondary = true)
            data class F(val key: String, val label: String, val lv: String, val rv: String)
            val fields = listOf(
                F("progress", tr("f_chapter"), local.optInt("progress").toString(), remote.optInt("progress").toString()),
                F("progressVolumes", tr("f_volume"), local.optInt("progressVolumes").toString(), remote.optInt("progressVolumes").toString()),
                F("status", tr("f_status"), anilistStatusLabel(local.optString("status")), anilistStatusLabel(remote.optString("status"))),
                F("score", tr("f_rating"), fmtScore(local.optDouble("score")), fmtScore(remote.optDouble("score"))),
                F("notes", tr("f_notes"), local.optString("notes").ifBlank { tr("none_value") }, remote.optString("notes").ifBlank { tr("none_value") })
            )
            for (f in fields) line("${f.label}: ${f.lv}   |   ${f.rv}", bold = !same(local.opt(f.key), remote.opt(f.key)))
        }
        line(" ", secondary = true)
        if (missing) {
            line(tr("outcome_unlink"), secondary = true)
            line(if (mode == "two_way") tr("outcome_keep_missing_two") else tr("outcome_keep_import"), secondary = true)
        } else {
            line(tr("outcome_use"), secondary = true)
            line(if (mode == "two_way") tr("outcome_keep_two") else tr("outcome_keep_import"), secondary = true)
        }
        AlertDialog.Builder(this).setTitle(String.format(tr("conflict_title"), c.title)).setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton(if (missing) tr("unlink_anilist") else tr("use_anilist")) { _, _ -> resolve(c.id, true) }
            .setNegativeButton(tr("keep_local")) { _, _ -> resolve(c.id, false) }
            .setNeutralButton(tr("later"), null).show()
    }
    private fun fmtScore(d: Double): String = if (d <= 0.0) tr("none_value") else if (d == Math.floor(d)) d.toInt().toString() else d.toString()
    private fun resolve(id: String, remote: Boolean) {
        work(tr("saved_ok")) { SyncEngine(Bridge.store!!, AniList(Credentials.read(this) ?: "")).resolve(id, remote); Bridge.publishAll(); Bridge.use { it.mirrors() } }
    }

    // --- Progress editor ------------------------------------------------------
    private fun chooseManga() {
        val items = Bridge.use { it.library().objects() }
        if (items.isEmpty()) { message(tr("edit_empty")); return }
        val labels = items.map { o -> "${o.getString("title")}\n${statusLabel(o.optString("status", "READING"))} · ${tr("f_chapter")} ${o.optString("currentChapter").ifBlank { "—" }}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(tr("edit_pick_title")).setItems(labels) { _, i -> edit(items[i]) }.show()
    }
    private fun edit(original: JSONObject) {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = dir; val p = dp(20); setPadding(p, dp(8), p, 0) }
        fun sectionLabel(text: String) {
            root.addView(TextView(this).apply { this.text = text; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(accent); setPadding(0, dp(14), 0, dp(4)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        }
        fun field(labelText: String, value: String, type: Int, ltr: Boolean = false): EditText {
            root.addView(TextView(this).apply { text = labelText; textSize = 14f; setPadding(0, dp(8), 0, dp(2)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
            val e = EditText(this).apply {
                setText(value); inputType = type; minimumHeight = dp(48); textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                if (ltr) textDirection = View.TEXT_DIRECTION_LTR
            }
            root.addView(e); return e
        }
        // Reading status
        sectionLabel(tr("sec_reading"))
        val statuses = arrayOf("READING", "PLAN_TO_READ", "FINISHED", "ON_HOLD", "DROPPED", "REREADING")
        root.addView(TextView(this).apply { text = tr("status_label"); textSize = 14f; setPadding(0, dp(8), 0, dp(2)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START })
        val statusSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@DataActivity, android.R.layout.simple_spinner_dropdown_item, statuses.map { statusLabel(it) })
            setSelection(statuses.indexOf(original.optString("status", "READING")).coerceAtLeast(0)); minimumHeight = dp(48)
        }
        root.addView(statusSpinner)
        val chapter = field(tr("cur_chapter"), original.getString("currentChapter"), InputType.TYPE_CLASS_TEXT)
        // Details
        sectionLabel(tr("sec_details"))
        val volume = field(tr("cur_volume"), original.getString("currentVolume"), InputType.TYPE_CLASS_TEXT)
        val page = field(tr("cur_page"), original.getInt("currentPage").toString(), InputType.TYPE_CLASS_NUMBER)
        val rating = field(tr("rating_label"), original.getString("rating"), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val notes = field(tr("notes_label"), original.getString("notes"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        // Source & provider links
        sectionLabel(tr("sec_links"))
        val anilist = field(tr("anilist_id_label"), original.getInt("anilistId").toString(), InputType.TYPE_CLASS_NUMBER, ltr = true)
        val source = field(tr("source_label"), original.getString("sourceUrl"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, ltr = true)

        val dialog = AlertDialog.Builder(this).setTitle(original.getString("title"))
            .setView(ScrollView(this).apply { addView(root) })
            .setPositiveButton(tr("save"), null).setNegativeButton(tr("cancel"), null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                chapter.error = null; page.error = null; rating.error = null; anilist.error = null
                val chapterText = chapter.text.toString().trim()
                if (chapterText.isEmpty()) { chapter.error = tr("err_chapter"); chapter.requestFocus(); return@setOnClickListener }
                val pageVal = page.text.toString().trim().toIntOrNull()
                if (pageVal == null || pageVal < 0) { page.error = tr("err_page"); page.requestFocus(); return@setOnClickListener }
                val ratingText = rating.text.toString().trim()
                if (ratingText.isNotEmpty()) { val r = ratingText.toDoubleOrNull(); if (r == null || r < 0 || r > 100) { rating.error = tr("err_rating"); rating.requestFocus(); return@setOnClickListener } }
                val anilistVal = anilist.text.toString().trim().toIntOrNull()
                if (anilistVal == null || anilistVal < 0) { anilist.error = tr("err_anilist_id"); anilist.requestFocus(); return@setOnClickListener }
                val updated = original.copy()
                    .put("status", statuses[statusSpinner.selectedItemPosition])
                    .put("currentChapter", chapterText).put("currentVolume", volume.text.toString().trim())
                    .put("currentPage", pageVal).put("rating", ratingText).put("notes", notes.text.toString())
                    .put("anilistId", anilistVal).put("sourceUrl", source.text.toString().trim())
                val before = original.getString("currentChapter").toBigDecimalOrNull()
                val after = updated.getString("currentChapter").toBigDecimalOrNull()
                val proceed = {
                    dialog.dismiss()
                    work(tr("saved_ok")) {
                        Bridge.use { s -> s.transaction { require(same(s.record(original.getString("id")), original)) { tr("reopen_before_save") }; s.put(updated) }; s.mirrors() }
                        Bridge.publishAll()
                    }
                }
                if (before != null && (after == null || after < before))
                    AlertDialog.Builder(this).setTitle(tr("backward_title")).setMessage(tr("backward_msg")).setPositiveButton(tr("apply_correction")) { _, _ -> proceed() }.setNegativeButton(tr("cancel"), null).show()
                else proceed()
            }
        }
        dialog.show()
    }

    @Deprecated("Platform callback") override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (result != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        if (request == 1) work(tr("exported_ok")) { val json = Bridge.use { it.export() }; contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(json) } }
        if (request == 2) {
            Bridge.worker.execute {
                try {
                    val bytes = contentResolver.openInputStream(uri)!!.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var n = input.read(buffer); while (n != -1) { require(out.size() + n <= 32 * 1024 * 1024) { "Backup exceeds 32 MB" }; out.write(buffer, 0, n); n = input.read(buffer) }; out.toByteArray() }
                    val text = String(bytes, Charsets.UTF_8); val backup = JSONObject(text); require(backup.getInt("schemaVersion") == 2)
                    val count = LocalStore.parse(backup.getJSONArray("library").toString()).size
                    runOnUiThread { if (!isFinishing) AlertDialog.Builder(this).setTitle(String.format(tr("restore_confirm_title"), count)).setMessage(tr("restore_confirm_msg")).setPositiveButton(tr("restore")) { _, _ -> work(tr("restored_ok")) { Bridge.use { it.restore(text) }; Bridge.publishAll() } }.setNegativeButton(tr("cancel"), null).show() }
                } catch (e: Exception) { runOnUiThread { if (!isFinishing) message(tr("invalid_backup")) } }
            }
        }
    }
}
