package com.nqp

import java.net.*
import java.util.Calendar
import kotlin.math.abs
import kotlin.random.Random

// Ported from the extension's lib.js / background.js
object Lib {
    const val H = 3_600_000L
    const val M = 60_000L

    private fun wc(c: Int) = if (c <= 4351 || c in 8192..8205 || c in 8208..8223 || c in 8242..8247) 1 else 2
    fun wlen(t: String): Int {
        var n = 0
        val s = t.replace(Regex("https?://\\S+"), "x".repeat(23))
        var i = 0
        while (i < s.length) { val c = s.codePointAt(i); n += wc(c); i += Character.charCount(c) }
        return n
    }
    fun trim(text: String, max: Int): String {
        val s = text.trim().replace(Regex("\\s+"), " ")
        if (wlen(s) <= max) return s
        val sb = StringBuilder(); var w = 0; var i = 0
        while (i < s.length) { val c = s.codePointAt(i); val cw = wc(c); if (w + cw > max - 1) break; sb.appendCodePoint(c); w += cw; i += Character.charCount(c) }
        var out = sb.toString()
        val se = listOf(". ", "! ", "? ", "؟ ").maxOf { out.lastIndexOf(it) }
        if (se >= out.length * 0.45) return out.substring(0, se + 1).trim()
        val sp = out.lastIndexOf(' ')
        if (sp >= out.length * 0.5) out = out.substring(0, sp)
        return out.trimEnd(',', ';', ':', '-', '–', '—', ' ') + "…"
    }
    fun decode(s: String?) = (s ?: "").replace("&amp;", "&").replace("&quot;", "\"").replace(Regex("&#0?39;|&apos;"), "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("&nbsp;|&#160;"), " ").replace(Regex("&#8211;|&ndash;"), "–")
        .replace(Regex("&#8212;|&mdash;"), "—").replace(Regex("&#8216;|&#8217;|&lsquo;|&rsquo;"), "'")
        .replace(Regex("&#8220;|&#8221;|&ldquo;|&rdquo;"), "\"").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()

    fun cleanDateline(lead: String): String {
        var s = lead.trim()
        listOf(Regex("^[A-Z][A-Z\\s.,'-]{1,35}\\((?:Reuters|AP|AFP|Bloomberg|dpa|EFE|TASS|Xinhua|IRNA|Tasnim|Mehr|Staff)[^)]*\\)\\s*[-–—:]\\s*", RegexOption.IGNORE_CASE),
            Regex("^[A-Z]{2,}(?:\\s+[A-Z]{2,})*(?:,\\s*[A-Za-z.]+\\s*\\d{1,2}(?:,\\s*\\d{4})?)?\\s*[-–—:]\\s+")).forEach { s = s.replace(it, "").trim() }
        return s
    }

    private fun attrs(tag: String) = Regex("([a-zA-Z0-9_:-]+)\\s*=\\s*([\"'])([\\s\\S]*?)\\2").findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[3] }
    fun meta(h: String, n: String): String {
        for (m in Regex("<meta[^>]+>", RegexOption.IGNORE_CASE).findAll(h)) { val a = attrs(m.value)
            if ((a["property"] ?: a["name"] ?: "").lowercase() == n && !a["content"].isNullOrEmpty()) return a["content"]!! }
        return ""
    }
    fun linkRel(h: String, rel: String): String {
        for (m in Regex("<link[^>]+>", RegexOption.IGNORE_CASE).findAll(h)) { val a = attrs(m.value)
            if ((a["rel"] ?: "").lowercase().split(Regex("\\s+")).contains(rel) && !a["href"].isNullOrEmpty()) return a["href"]!! }
        return ""
    }
    private fun firstParagraph(h: String): String {
        val scope = Regex("<article[^>]*>([\\s\\S]*?)</article>", RegexOption.IGNORE_CASE).find(h)?.groupValues?.get(1) ?: h
        for (m in Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).findAll(scope)) {
            val t = decode(m.groupValues[1]); if (t.length >= 45 && !Regex("^(cookie|subscribe|sign up|advertisement|read more|copyright)", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
        }
        return ""
    }
    fun norm(u: String): String = try {
        val x = URI(u); val q = (x.rawQuery ?: "").split("&").filter { it.isNotEmpty() && !it.startsWith("utm_") && !it.startsWith("fbclid") && !it.startsWith("gclid") }.joinToString("&")
        URI(x.scheme, x.rawAuthority, x.path, null, null).toString().let { it + (if (q.isNotEmpty()) "?$q" else "") }.trimEnd('/')
    } catch (e: Exception) { u }

    data class Article(val title: String, val lead: String, val shortUrl: String, val imageUrl: String, val site: String)
    fun extract(url: String, html: String): Article {
        val title = decode(meta(html, "og:title").ifEmpty { meta(html, "twitter:title") }.ifEmpty { Regex("<title[^>]*>([^<]*)", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: "" })
        var lead = decode(meta(html, "og:description").ifEmpty { meta(html, "twitter:description") }.ifEmpty { meta(html, "description") }.ifEmpty { firstParagraph(html) })
        if (Store.b("stripDateline", true)) lead = cleanDateline(lead)
        val orig = norm(url)
        val cands = listOf(linkRel(html, "shortlink"), meta(html, "og:url"), linkRel(html, "canonical")).filter { it.isNotEmpty() }
            .mapNotNull { try { norm(URI(url).resolve(decode(it)).toString()) } catch (e: Exception) { null } }.filter { it.startsWith("http") }
        val short = cands.minByOrNull { it.length }?.takeIf { it.length <= orig.length && Store.s("shortMode", "auto") != "original" } ?: orig
        val img = listOf("og:image:secure_url", "og:image", "twitter:image:src", "twitter:image").map { meta(html, it) }.firstOrNull { it.isNotEmpty() }
            ?.let { try { URI(url).resolve(decode(it)).toString().replace(Regex("-(\\d{2,4})x(\\d{2,4})(?=\\.(?:jpe?g|png|webp|gif)$)", RegexOption.IGNORE_CASE), "") } catch (e: Exception) { "" } } ?: ""
        return Article(title, lead, short, img, try { URI(url).host.removePrefix("www.") } catch (e: Exception) { "" })
    }

    fun limit(long: Boolean) = if (long || Store.b("longPost", false)) 25000 else Store.i("maxChars", 280).coerceIn(100, 280)
    private fun render(t: String, title: String, lead: String, short: String, url: String, site: String) =
        t.replace("{title}", title).replace("{lead}", lead).replace("{desc}", lead).replace("{shortUrl}", short).replace("{url}", url).replace("{site}", site)
            .replace(Regex("\n{3,}"), "\n\n").trim()
    fun buildText(a: Article, url: String): String {
        val max = limit(false); val tpl = Store.s("template", "{title}\n\n{lead}\n{shortUrl}").ifEmpty { "{title}\n\n{lead}\n{shortUrl}" }
        val full = render(tpl, a.title, a.lead, a.shortUrl, url, a.site)
        if (wlen(full) <= max) return full
        if (Store.s("overflow", "lead_only") == "title_only" || a.lead.isEmpty()) {
            val b = max - wlen("\n" + a.shortUrl); return trim(a.title.ifEmpty { a.lead }, maxOf(20, b)) + "\n" + a.shortUrl
        }
        val lo = a.lead + "\n" + a.shortUrl
        if (wlen(lo) <= max) return lo
        return trim(a.lead, maxOf(20, max - wlen("\n" + a.shortUrl))) + "\n" + a.shortUrl
    }

    private fun hourOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.HOUR_OF_DAY)
    fun slotOk(t: Long): Boolean { val h = hourOf(t); val s = Store.i("startHour", 8); val e = Store.i("endHour", 23); return if (s < e) h in s until e else (h >= s || h < e) }
    fun pickTimes(n: Int, taken: List<Long>, now: Long): List<Long> {
        val cap = Store.i("cap", 10).coerceIn(1, 10); val gap = Store.i("minGap", 3).coerceAtLeast(1) * M
        val out = mutableListOf<Long>(); val all = taken.toMutableList(); var slot = now / H * H; var g = 0
        while (g < 336 && out.size < n) {
            if (slotOk(slot)) {
                val inS = all.filter { it >= slot && it < slot + H }.toMutableList()
                while (inS.size < cap && out.size < n) {
                    val lo = maxOf(slot, now + M); val hi = slot + H - M; if (lo >= hi) break
                    var t = -1L
                    repeat(40) { if (t < 0) { val x = lo + Random.nextLong(hi - lo); if (inS.all { abs(it - x) >= gap }) t = x } }
                    if (t < 0) break
                    inS.add(t); all.add(t); out.add(t)
                }
            }
            g++; slot += H
        }
        return out
    }
}
