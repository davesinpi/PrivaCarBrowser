package org.privacy.carbrowser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A Greasemonkey-style userscript. No GM_* APIs: runs with the page's own privileges only. */
data class UserScript(
    val name: String,
    val matches: List<String>,
    val runAtStart: Boolean,
    val code: String,
    val enabled: Boolean = true,
) {
    fun appliesTo(url: String) = matches.any { matchPattern(it, url) }

    companion object {
        fun parse(code: String): UserScript {
            val header = code.substringAfter("==UserScript==", "").substringBefore("==/UserScript==")
            val tags = Regex("""//\s*@(\S+)\s+(.+)""").findAll(header)
                .map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
            val name = tags.firstOrNull { it.first == "name" }?.second ?: "Untitled script"
            val matches = tags.filter { it.first == "match" || it.first == "include" }.map { it.second }
                .ifEmpty { listOf("*") }
            val start = tags.any { it.first == "run-at" && it.second == "document-start" }
            return UserScript(name, matches, start, code)
        }
    }
}

// Chrome-style match pattern: scheme://host/path; a `*` scheme means http(s); `*.host` includes the bare host.
// ponytail: legacy @include globs that aren't valid match patterns (no path part) never match.
fun matchPattern(pattern: String, url: String): Boolean {
    if (pattern == "*" || pattern == "<all_urls>") return url.startsWith("http://") || url.startsWith("https://")
    val (scheme, host, path) = Regex("""^(\*|https?)://([^/]*)(/.*)$""").find(pattern)?.destructured ?: return false
    val schemeRx = if (scheme == "*") "https?" else scheme
    val hostRx = when {
        host == "*" -> "[^/]*"
        host.startsWith("*.") -> "([^/]*\\.)?" + Regex.escape(host.drop(2))
        else -> Regex.escape(host)
    }
    val pathRx = path.split("*").joinToString(".*") { Regex.escape(it) }
    return Regex("$schemeRx://$hostRx(:\\d+)?$pathRx").matches(url)
}

/** Installed scripts, persisted in SharedPreferences. */
class ExtensionStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("extensions", Context.MODE_PRIVATE)
    var scripts: List<UserScript> = load(); private set

    fun add(s: UserScript) = save(scripts.filterNot { it.name == s.name } + s) // same name = update
    fun remove(i: Int) = save(scripts.filterIndexed { j, _ -> j != i })
    fun setEnabled(i: Int, on: Boolean) = save(scripts.mapIndexed { j, s -> if (j == i) s.copy(enabled = on) else s })

    private fun save(list: List<UserScript>) {
        scripts = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("code", it.code).put("enabled", it.enabled)) }
        prefs.edit().putString("scripts", arr.toString()).apply()
    }

    private fun load(): List<UserScript> {
        val arr = JSONArray(prefs.getString("scripts", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { UserScript.parse(it.getString("code")).copy(enabled = it.optBoolean("enabled", true)) }
    }
}
