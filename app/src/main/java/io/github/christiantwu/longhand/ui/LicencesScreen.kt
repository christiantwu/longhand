package io.github.christiantwu.longhand.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Longhand itself, every third-party component in the app, and the models it downloads, from assets/licenses/index.json. */
object Licences {
    /**
     * [group] is "app", "built-in" or "models". [file] names the licence text in assets/licenses; it's blank
     * for the downloaded models, whose licences live with the models.
     */
    data class Entry(
        val name: String, val version: String, val license: String, val copyright: String,
        val source: String, val file: String, val group: String,
    ) {
        val key get() = "$group/$name"
    }

    fun load(context: Context): List<Entry> {
        val json = context.assets.open("licenses/index.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Entry(o.getString("name"), o.optString("version"), o.getString("license"), o.optString("copyright"),
                o.optString("source"), o.optString("file"), o.optString("group", "built-in"))
        }
    }

    /** The licence text split into screen-sized pieces, so very long notices scroll smoothly. */
    fun chunks(context: Context, file: String): List<String> {
        val text = context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (paragraph in text.split("\n\n").map(::reflow)) {
            if (sb.length + paragraph.length > 3000 && sb.isNotEmpty()) {
                out += sb.toString()
                sb.clear()
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(paragraph)
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    /**
     * Licence texts are hard-wrapped at about 80 columns, which a phone wraps again into ragged
     * pairs of lines. This joins the lines of a prose paragraph so it flows at any width. Headers,
     * lists, tables and other short-line blocks are left exactly as written. Display only: the
     * files themselves stay verbatim.
     */
    fun reflow(paragraph: String): String {
        val lines = paragraph.trimEnd('\n').split('\n')
        if (lines.size < 2) return paragraph
        val prose = lines.dropLast(1).all { it.trimEnd().length in 55..100 } &&
            lines.drop(1).none { listItem.containsMatchIn(it) || label.containsMatchIn(it) } &&
            lines.none { copyright.containsMatchIn(it) || rule.matches(it.trim()) || columns.containsMatchIn(it.trim()) }
        if (!prose) return paragraph
        return lines.first().takeWhile { it == ' ' } + lines.joinToString(" ") { it.trim() }
    }

    private val listItem = Regex("""^\s*([-*•+]\s|\(?[0-9a-zA-Z]{1,3}[.)]\s)""")
    /** A copyright line ("Copyright (c) 2024 …", "© 2011 …"), not a sentence that starts with the word. */
    private val copyright = Regex("""^\s*(Copyright\s*(\([cC]\)|©)|Copyright\b.*\d{4}|©|\([cC]\)\s*\d{4})""")
    /** A "Source: …" style line or a bare address, which starts a line of its own. */
    private val label = Regex("""^\s*(\S+:\s|https?://)""")
    private val rule = Regex("""^[=\-_*~#]{4,}$""")
    private val columns = Regex("""\S {3,}\S""")
}

private val groupTitles = mapOf(
    "built-in" to ("Built into the app" to null),
    "models" to ("Downloaded models" to "Not part of the app. Longhand downloads the ones you choose."),
)

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val entries = remember { Licences.load(context) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // Some notices run to hundreds of kilobytes, so they're read off the main thread, once per opened entry.
    val openChunks by produceState(emptyList<String>(), open) {
        value = emptyList()
        val file = entries.firstOrNull { it.key == open }?.file.orEmpty()
        if (file.isNotBlank()) value = withContext(Dispatchers.IO) { Licences.chunks(context, file) }
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { InkTopBar(left = { InkIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) }) }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Open-source licences", style = MaterialTheme.typography.headlineMedium, color = ink.ink)
                Spacer(Modifier.height(10.dp))
                GradientRule()
                Spacer(Modifier.height(12.dp))
                Text(
                    "Longhand is free software, copyright © 2026 Christian Wu. You can redistribute and modify it under " +
                        "the GNU General Public License, version 3 or later. It comes with ABSOLUTELY NO WARRANTY. " +
                        "Tap an entry to read its licence.",
                    style = MaterialTheme.typography.bodySmall, color = ink.muted,
                )
            }
        }
        entries.forEachIndexed { i, e ->
            if (i == 0 || entries[i - 1].group != e.group) groupTitles[e.group]?.let { (title, note) ->
                item(key = "group-" + e.group) {
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MonoLabel(title)
                        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = ink.faint)
                    }
                }
            }
            item(key = e.key) {
                val expandable = e.file.isNotBlank()
                Column(
                    Modifier.fillMaxWidth()
                        .then(if (expandable) Modifier.clickable { open = if (open == e.key) null else e.key } else Modifier)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(listOf(e.name, e.version).filter { it.isNotBlank() }.joinToString(" "), style = MaterialTheme.typography.titleSmall, color = ink.ink)
                    MonoLabel(e.license, color = ink.violet)
                    if (e.copyright.isNotBlank()) Text(e.copyright, style = MaterialTheme.typography.bodySmall, color = ink.muted)
                    if (e.source.isNotBlank()) Text(e.source, style = InkType.clock, color = ink.faint)
                }
            }
            if (open == e.key) {
                items(openChunks) { chunk ->
                    Text(chunk, style = InkType.clock.copy(fontSize = 11.sp, lineHeight = 15.sp), color = ink.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }
            }
            item(key = "div-" + e.key) { HorizontalDivider(Modifier.padding(start = 20.dp), color = ink.line) }
        }
    }
}
