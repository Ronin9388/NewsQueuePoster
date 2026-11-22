package com.nqp

import android.content.Context
import android.content.SharedPreferences
import org.json.*

object Store {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("nqp", 0) }
    private fun cfg() = JSONObject(sp.getString("cfg", "{}")!!)
    fun s(k: String, d: String = "") = cfg().optString(k, d)
    fun i(k: String, d: Int) = s(k, "").toIntOrNull() ?: d
    fun b(k: String, d: Boolean) = if (cfg().has(k)) cfg().optString(k) == "true" else d
    fun setCfg(m: Map<String, String>) { val c = cfg(); m.forEach { (k, v) -> c.put(k, v) }; sp.edit().putString("cfg", c.toString()).apply() }
    @Synchronized fun queue() = JSONArray(sp.getString("q", "[]"))
    @Synchronized fun saveQueue(q: JSONArray) { sp.edit().putString("q", q.toString()).apply() }
    @Synchronized fun hist() = JSONArray(sp.getString("h", "[]"))
    @Synchronized fun addHist(url: String) { val h = hist(); h.put(JSONObject().put("url", url).put("at", System.currentTimeMillis())); sp.edit().putString("h", h.toString()).apply() }
    var lastPull: Long get() = sp.getLong("lp", 0); set(v) { sp.edit().putLong("lp", v).apply() }
    @Synchronized fun update(id: String, f: (JSONObject) -> Unit) {
        val q = queue(); for (k in 0 until q.length()) if (q.getJSONObject(k).optString("id") == id) f(q.getJSONObject(k)); saveQueue(q)
    }
    @Synchronized fun remove(id: String) {
        val q = queue(); val n = JSONArray(); for (k in 0 until q.length()) if (q.getJSONObject(k).optString("id") != id) n.put(q.get(k)); saveQueue(n)
    }
}
