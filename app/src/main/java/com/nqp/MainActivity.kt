package com.nqp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.work.*
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Store.init(this)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("tick", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Tick>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(34), px(10), px(6)) }
        val bar = LinearLayout(this)
        fun btn(t: String, f: () -> Unit): Button = Button(this).apply {
            text = t
            setOnClickListener { f() }
        }.also { bar.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        btn("Add") { addDialog() }
        btn("Pull") {
            toast("Pulling…")
            thread {
                val n: Int = Engine.pull()
                toast("Pulled $n")
                refresh()
            }
        }
        btn("Approve all") {
            val q = Store.queue()
            for (k in 0 until q.length()) {
                val item = q.getJSONObject(k)
                if (item.optString("status") == "draft") item.put("status", "ready")
            }
            Store.saveQueue(q)
            thread {
                Engine.schedule()
                refresh()
            }
        }
        btn("Settings") { settings() }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(bar); root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        handle(intent)
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }
    override fun onResume() { super.onResume(); refresh() }

    // Share a link from the browser to this app to queue it
    private fun handle(i: Intent) {
        if (i.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val urls = Regex("https?://\\S+").findAll(t).map { it.value }.toList()
            toast("Adding…"); thread { val n = urls.sumOf { Engine.ingest(it) }; toast("Added $n"); refresh() }
        }
    }

    private fun addDialog() {
        val e = EditText(this).apply { hint = "One article URL per line"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 4 }
        AlertDialog.Builder(this).setTitle("Add articles").setView(e).setPositiveButton("Add") { _, _ ->
            val urls = e.text.toString().lines().map { it.trim() }.filter { it.startsWith("http") }
            toast("Adding…"); thread { val n = urls.sumOf { Engine.ingest(it) }; toast("Added $n"); refresh() }
        }.show()
    }

    private fun refresh() = runOnUiThread {
        list.removeAllViews(); val q = Store.queue(); val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        if (q.length() == 0) list.addView(TextView(this).apply { text = "Queue is empty. Tap Add, set Sources in Settings and tap Pull, or share a link to this app." ; setPadding(px(8), px(24), px(8), 0) })
        for (k in 0 until q.length()) {
            val x = q.getJSONObject(k); val id = x.optString("id"); val st = x.optString("status")
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(8), px(10), px(8), px(10)); setBackgroundColor(0x11000000) }
            val time = if (x.optLong("time") > 0) df.format(Date(x.optLong("time"))) else "unscheduled"
            card.addView(TextView(this).apply { text = "${st.uppercase()} · $time · ${Lib.wlen(x.optString("text"))}/${Lib.limit(x.optBoolean("long"))}"; textSize = 12f })
            if (x.optString("err").isNotEmpty() && st == "failed") card.addView(TextView(this).apply { text = x.optString("err"); setTextColor(0xFFB00020.toInt()); textSize = 12f })
            card.addView(TextView(this).apply { text = x.optString("text"); textSize = 15f; setPadding(0, px(4), 0, px(4)) })
            val row = LinearLayout(this)
            fun b(t: String, f: () -> Unit): Button = Button(this).apply { text = t; textSize = 12f; setOnClickListener { f() } }.also { row.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
            if (st == "draft") b("Approve") { Store.update(id) { it.put("status", "ready") }; thread { Engine.schedule(); refresh() } }
            if (st == "failed") b("Retry") { Store.update(id) { it.put("status", "ready").put("time", 0L).remove("err") }; thread { Engine.schedule(); refresh() } }
            b("Edit") { editDialog(id, x.optString("text")) }
            if (st != "draft") b("Post") { postNow(x) }
            b("Del") { Engine.markPosted(id); refresh() }
            card.addView(row); list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(8) })
        }
    }

    private fun postNow(x: org.json.JSONObject) {
        if (Store.s("postMode", "share") == "api") thread {
            val r = Engine.postApi(x)
            if (r == "ok") { Engine.markPosted(x.optString("id")); toast("Posted") } else { Store.update(x.optString("id")) { it.put("status", "failed").put("err", r) }; toast(r) }
            refresh()
        } else {
            Engine.share(this, x)
            AlertDialog.Builder(this).setMessage("After posting in X, mark it as done.").setPositiveButton("Done, posted") { _, _ -> Engine.markPosted(x.optString("id")); refresh() }.setNegativeButton("Not yet", null).show()
        }
    }

    private fun editDialog(id: String, text: String) {
        val e = EditText(this).apply { setText(text); minLines = 4 }
        AlertDialog.Builder(this).setTitle("Edit post").setView(e).setPositiveButton("Save") { _, _ ->
            if (Lib.wlen(e.text.toString()) > Lib.limit(false)) toast("Over X character limit") else { Store.update(id) { it.put("text", e.text.toString()) }; refresh() }
        }.show()
    }

    private val fields = listOf(
        "sources" to "News source pages (one URL per line)", "template" to "Post template ({title} {lead} {shortUrl} {url} {site})",
        "maxChars" to "Max characters (100-280)", "cap" to "Max posts per hour (1-10)", "minGap" to "Min minutes between posts",
        "startHour" to "Start hour (0-23)", "endHour" to "End hour (0-23)", "pullEvery" to "Pull every N minutes (min 15)",
        "approval" to "[bool] Require approval before scheduling", "auto" to "[bool] Auto-post / notify when due",
        "attachPhoto" to "[bool] Attach article photo (share mode)", "stripDateline" to "[bool] Strip datelines like 'LONDON (Reuters) -'",
        "postMode" to "Post mode: share (X app) or api", "postUrl" to "Post API URL (default https://api.x.com/2/tweets)",
        "postKey" to "Post API token", "postAuth" to "Auth: bearer or x-api-key", "postBody" to "Body template, e.g. {\"text\": {{text}}}",
        "aiOn" to "[bool] Rewrite posts with AI", "aiPreset" to "AI preset: groq, gemini, openrouter, anthropic (or blank + URL)",
        "aiUrl" to "AI base URL (OpenAI-compatible)", "aiModel" to "AI model", "aiKey" to "AI API key", "aiPrompt" to "AI prompt ({title} {lead})")
    private val defaults = mapOf("template" to "{title}\n\n{lead}\n{shortUrl}", "maxChars" to "280", "cap" to "10", "minGap" to "3", "startHour" to "8",
        "endHour" to "23", "pullEvery" to "30", "approval" to "true", "attachPhoto" to "true", "stripDateline" to "true", "postMode" to "share", "postAuth" to "bearer")

    private fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), px(8), px(16), px(8)) }
        val views = mutableMapOf<String, View>()
        for ((k, label) in fields) {
            if (label.startsWith("[bool]")) views[k] = CheckBox(this).apply { text = label.removePrefix("[bool] "); isChecked = Store.b(k, defaults[k] == "true") }
            else { box.addView(TextView(this).apply { text = label; textSize = 12f; setPadding(0, px(8), 0, 0) })
                views[k] = EditText(this).apply { setText(Store.s(k, defaults[k] ?: "")); if (k in listOf("postKey", "aiKey")) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD } }
            box.addView(views[k])
        }
        AlertDialog.Builder(this).setTitle("Settings").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Save") { _, _ ->
            Store.setCfg(views.mapValues { (_, v) -> if (v is CheckBox) v.isChecked.toString() else (v as EditText).text.toString() })
            thread { Engine.schedule(); refresh() }
        }.setNegativeButton("Cancel", null).show()
    }
}
