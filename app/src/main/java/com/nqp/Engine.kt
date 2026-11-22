package com.nqp

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.*
import java.io.File
import java.net.*
import java.util.UUID

object Engine {
    fun http(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 20000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) NewsQueuePoster")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        return code to ((if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "")
    }

    private val presets = mapOf(
        "groq" to Triple("https://api.groq.com/openai/v1", "llama-3.1-8b-instant", "openai"),
        "gemini" to Triple("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "openai"),
        "openrouter" to Triple("https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", "openai"),
        "anthropic" to Triple("https://api.anthropic.com/v1", "claude-sonnet-5-5", "anthropic"))

    fun aiText(title: String, lead: String, short: String): String {
        val p = presets[Store.s("aiPreset")]
        val url = Store.s("aiUrl").ifEmpty { p?.first ?: "" }.trimEnd('/'); val model = Store.s("aiModel").ifEmpty { p?.second ?: "" }
        val fmt = if (Store.s("aiPreset") == "anthropic" || Store.s("aiFmt") == "anthropic") "anthropic" else "openai"
        val prompt = Store.s("aiPrompt", "Write one engaging X post (max 200 characters, no hashtags, no quotes) for this news article. Return only the post text.\n\nTitle: {title}\nLead: {lead}")
            .replace("{title}", title).replace("{lead}", lead).replace("{shortUrl}", short)
        val key = Store.s("aiKey")
        val msgs = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
        val (code, txt) = if (fmt == "anthropic")
            http("POST", "$url/messages", mapOf("content-type" to "application/json", "x-api-key" to key, "anthropic-version" to "2023-06-01"),
                JSONObject().put("model", model).put("max_tokens", 400).put("messages", msgs).toString())
        else http("POST", "$url/chat/completions", mapOf("content-type" to "application/json", "authorization" to "Bearer $key"),
            JSONObject().put("model", model).put("max_tokens", 800).put("messages", msgs).toString())
        if (code >= 400) throw Exception("AI $code ${txt.take(120)}")
        val j = JSONObject(txt)
        val out = if (fmt == "anthropic") j.getJSONArray("content").getJSONObject(0).optString("text") else j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        return out.replace(Regex("<think>[\\s\\S]*?</think>"), "").trim().trim('"', '“', '”')
    }

    /** Fetch article, build post, add to queue. Returns 1 if added. */
    fun ingest(url: String): Int {
        val k = Lib.norm(url)
        fun dup(): Boolean {
            val q = Store.queue(); val h = Store.hist()
            return q.length() >= 60 || (0 until q.length()).any { Lib.norm(q.getJSONObject(it).optString("url")) == k } ||
                (0 until h.length()).any { Lib.norm(h.getJSONObject(it).optString("url")) == k }
        }
        if (dup()) return 0
        val html = try { http("GET", url).second } catch (e: Exception) { return 0 }
        val a = Lib.extract(url, html)
        if (a.title.isEmpty() && a.lead.isEmpty()) return 0
        var text = Lib.buildText(a, url)
        if (Store.b("aiOn", false)) try {
            val body = aiText(a.title, a.lead, a.shortUrl)
            text = (Lib.trim(body, Lib.limit(false) - Lib.wlen("\n" + a.shortUrl)) + "\n" + a.shortUrl).trim()
        } catch (e: Exception) { /* keep constructed post */ }
        val item = JSONObject().put("id", UUID.randomUUID().toString()).put("url", url).put("shortUrl", a.shortUrl).put("imageUrl", a.imageUrl)
            .put("title", a.title).put("lead", a.lead).put("text", text).put("time", 0L)
            .put("status", if (Store.b("approval", true)) "draft" else "ready")
        synchronized(this) { val q = Store.queue(); q.put(item); Store.saveQueue(q); schedule() }
        return 1
    }

    @Synchronized fun schedule() {
        val q = Store.queue(); val h = Store.hist()
        val need = (0 until q.length()).map { q.getJSONObject(it) }.filter { it.optString("status") == "ready" && it.optLong("time") == 0L }
        val taken = (0 until q.length()).map { q.getJSONObject(it) }.filter { it.optString("status") == "ready" && it.optLong("time") > 0 }.map { it.optLong("time") } +
            (0 until h.length()).map { h.getJSONObject(it).optLong("at") }.filter { it > 0 }
        Lib.pickTimes(need.size, taken, System.currentTimeMillis()).forEachIndexed { i, t -> need[i].put("time", t) }
        Store.saveQueue(q)
    }

    fun pull(): Int {
        var n = 0
        for (s in Store.s("sources").lines().map { it.trim() }.filter { it.startsWith("http") }) try {
            val base = URI(s); val html = http("GET", s).second; val links = LinkedHashSet<String>()
            for (m in Regex("<a[^>]+href=[\"']([^\"'#]+)[\"']", RegexOption.IGNORE_CASE).findAll(html)) try {
                val u = base.resolve(Lib.decode(m.groupValues[1]))
                if (u.host == base.host && u.path.split("/").count { it.isNotEmpty() } >= 2 && u.toString() != s && !Regex("\\.(jpe?g|png|gif|svg|css|js|pdf)$", RegexOption.IGNORE_CASE).containsMatchIn(u.path)) links.add(u.toString())
            } catch (e: Exception) {}
            var c = 0; for (u in links.take(40)) { if (c >= 5) break; c += ingest(u) }; n += c
        } catch (e: Exception) {}
        Store.lastPull = System.currentTimeMillis(); return n
    }

    /** Returns "ok" or an error message. Sends the post to the configured API (X API v2 by default, or any webhook). */
    fun postApi(it: JSONObject): String = try {
        val url = Store.s("postUrl", "https://api.x.com/2/tweets").ifEmpty { "https://api.x.com/2/tweets" }
        var body = Store.s("postBody", "{\"text\": {{text}}}").ifEmpty { "{\"text\": {{text}}}" }
        for (k in listOf("text", "title", "lead", "url", "shortUrl", "imageUrl")) body = body.replace("{{$k}}", JSONObject.quote(it.optString(k).ifEmpty { if (k == "shortUrl") it.optString("url") else "" }))
        JSONObject(body)
        val h = mutableMapOf("content-type" to "application/json"); val key = Store.s("postKey")
        if (key.isNotEmpty()) if (Store.s("postAuth", "bearer") == "x-api-key") h["x-api-key"] = key else h["authorization"] = "Bearer $key"
        val (c, t) = http("POST", url, h, body)
        if (c in 200..299) "ok" else "HTTP $c ${t.take(140)}"
    } catch (e: Exception) { e.message ?: "error" }

    fun markPosted(id: String) {
        val q = Store.queue(); for (k in 0 until q.length()) { val x = q.getJSONObject(k); if (x.optString("id") == id) Store.addHist(x.optString("url")) }
        Store.remove(id)
    }

    /** Opens the X app's share sheet with the post text and the article photo. */
    fun share(ctx: Context, it: JSONObject) {
        Thread {
            var uri: Uri? = null
            if (Store.b("attachPhoto", true) && it.optString("imageUrl").isNotEmpty()) try {
                val c = URL(it.optString("imageUrl")).openConnection() as HttpURLConnection
                val f = File(ctx.cacheDir, "shared").apply { mkdirs() }.let { d -> File(d, "photo.jpg") }
                c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                uri = FileProvider.getUriForFile(ctx, "com.nqp.newsqueueposter.files", f)
            } catch (e: Exception) {}
            val i = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, it.optString("text"))
            if (uri != null) i.setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) else i.setType("text/plain")
            val x = Intent(i).setPackage("com.twitter.android")
            val launch = if (x.resolveActivity(ctx.packageManager) != null) x else Intent.createChooser(i, "Post to X")
            ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.start()
    }

    /** Called every ~15 min by WorkManager. */
    fun tick(ctx: Context) {
        val pe = Store.s("pullEvery", "30").toIntOrNull()?.coerceAtLeast(15) ?: 30
        if (System.currentTimeMillis() - Store.lastPull > pe * Lib.M) pull()
        if (!Store.b("auto", false)) return
        val q = Store.queue(); val now = System.currentTimeMillis()
        for (k in 0 until q.length()) {
            val x = q.getJSONObject(k)
            if (x.optString("status") != "ready" || x.optLong("time") == 0L || x.optLong("time") > now) continue
            if (now - x.optLong("time") > 30 * Lib.M || !Lib.slotOk(now)) { Store.update(x.optString("id")) { it.put("time", 0L) }; schedule(); continue }
            if (Store.s("postMode", "share") == "api") {
                val r = postApi(x)
                if (r == "ok") markPosted(x.optString("id")) else Store.update(x.optString("id")) { it.put("status", "failed").put("err", r) }
            } else if (!x.optBoolean("notified")) {
                Store.update(x.optString("id")) { it.put("notified", true) }
                notifyDue(ctx, x.optString("title").ifEmpty { x.optString("text").take(60) })
            }
        }
    }

    private fun notifyDue(ctx: Context, msg: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("due", "Posts due", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(System.currentTimeMillis().toInt(), Notification.Builder(ctx, "due").setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle("Post ready for X").setContentText(msg).setContentIntent(pi).setAutoCancel(true).build())
    }
}
