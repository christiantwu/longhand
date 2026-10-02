package io.github.christiantwu.longhand.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
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
     * for most downloaded models, whose licences live with the models. A model whose licence asks for a copy
     * to go with it (OpenMDW, for the Hindi model) has one.
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
    val c = MaterialTheme.colorScheme
    val context = LocalContext.current
    val entries = remember { Licences.load(context) }
    val groups = remember(entries) { entries.groupBy { it.group } }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    // Some notices run to hundreds of kilobytes, so they're read off the main thread, once per opened entry.
    val openChunks by produceState(emptyList<String>(), open) {
        value = emptyList()
        val file = entries.firstOrNull { it.key == open }?.file.orEmpty()
        if (file.isNotBlank()) value = withContext(Dispatchers.IO) { Licences.chunks(context, file) }
    }

    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { AppBar(navigation = { BackButton(onBack) }) }
        item {
            Column {
                ScreenTitle("Open-source licences")
                Text(
                    "Longhand is free software, copyright © 2026 Christian Wu. You can redistribute and modify it under " +
                        "the GNU General Public License, version 3 or later. It comes with ABSOLUTELY NO WARRANTY. " +
                        "Tap an entry to read its licence.",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
            }
        }
        groups.entries.forEachIndexed { g, (group, list) ->
            val (title, note) = groupTitles[group] ?: (null to null)
            // The space above each group, with its header when it has one.
            item(key = "group-$group") {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = if (g == 0) 20.dp else 26.dp, bottom = if (title != null) 10.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (title != null) SectionHeader(title)
                    if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            list.forEachIndexed { i, e ->
                val expandable = e.file.isNotBlank()
                val top = if (i == 0) OuterCorner else InnerCorner
                val bottom = if (i == list.lastIndex) OuterCorner else InnerCorner
                // An opened entry's text continues its card, so the row hands its bottom corners to the text's end.
                val rowBottom = if (open == e.key && openChunks.isNotEmpty()) 0.dp else bottom
                item(key = e.key) {
                    GroupRow(
                        RoundedCornerShape(topStart = top, topEnd = top, bottomStart = rowBottom, bottomEnd = rowBottom),
                        Modifier.padding(start = 16.dp, end = 16.dp, top = if (i > 0) GroupGap else 0.dp),
                        onClick = if (expandable) ({ open = if (open == e.key) null else e.key }) else null,
                        verticalAlignment = Alignment.Top,
                    ) {
                        RowText(listOf(e.name, e.version).filter { it.isNotBlank() }.joinToString(" ")) {
                            MonoLabel(e.license, Modifier.padding(vertical = 2.dp), color = c.primary)
                            if (e.copyright.isNotBlank()) Text(e.copyright, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                            if (e.source.isNotBlank()) Text(e.source, style = EditorialType.time, color = c.onSurfaceVariant)
                        }
                        if (expandable) Icon(if (open == e.key) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null, tint = c.onSurfaceVariant)
                    }
                }
                if (open == e.key) {
                    itemsIndexed(openChunks) { n, chunk ->
                        val last = n == openChunks.lastIndex
                        Text(
                            chunk, style = EditorialType.clock.copy(lineHeight = 15.sp), color = c.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                                .background(c.surfaceContainer, if (last) RoundedCornerShape(bottomStart = bottom, bottomEnd = bottom) else RectangleShape)
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (last) 16.dp else 4.dp),
                        )
                    }
                }
            }
        }
    }
}

// groupShape's corners, needed apart so an opened entry's card can run on through its text.
private val OuterCorner = 16.dp
private val InnerCorner = 4.dp
