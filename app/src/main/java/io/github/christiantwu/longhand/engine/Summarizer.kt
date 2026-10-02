package io.github.christiantwu.longhand.engine

import java.io.Closeable

data class CallSummary(val topic: String, val summary: String, val followUps: List<String>)

/**
 * On-device call summaries with a small language model (Qwen 3.5 4B) run by llama.cpp.
 * One instance holds the model in memory (~2.6 GB, memory-mapped); create it for a batch
 * of calls and close it afterwards.
 */
class Summarizer(modelPath: String, threads: Int = 4) : Closeable {

    private val handle: Long = nativeLoad(modelPath, CONTEXT_TOKENS, threads)
        .also { require(it != 0L) { "Could not load the summary model" } }

    @Volatile private var cancelled = false

    /**
     * @return the summary, or null when the model couldn't produce one (prompt too long even
     * after trimming, or [cancel] was called).
     */
    fun summarize(header: String, lines: List<String>): CallSummary? {
        cancelled = false
        nativeReset(handle)
        // Characters per token is roughly 3.5 for English; retry with less if it still doesn't fit.
        var budget = TRANSCRIPT_CHAR_BUDGET
        repeat(3) {
            if (cancelled) return null
            val prompt = SummaryPrompt.build(header, lines, budget)
            val bytes = nativeGenerate(handle, prompt, SummaryPrompt.GRAMMAR, MAX_REPLY_TOKENS)
            if (bytes != null) return SummaryParser.parse(String(bytes, Charsets.UTF_8))
            budget /= 2
        }
        return null
    }

    /** Stops a running [summarize] from another thread; it then returns null without retrying. */
    fun cancel() {
        cancelled = true
        nativeCancel(handle)
    }

    override fun close() = nativeFree(handle)

    companion object {
        const val CONTEXT_TOKENS = 8192
        const val MAX_REPLY_TOKENS = 320
        private const val TRANSCRIPT_CHAR_BUDGET = 22_000

        init {
            System.loadLibrary("llm")
        }

        @JvmStatic private external fun nativeLoad(path: String, contextTokens: Int, threads: Int): Long
        @JvmStatic private external fun nativeGenerate(handle: Long, prompt: String, grammar: String, maxTokens: Int): ByteArray?
        @JvmStatic private external fun nativeCancel(handle: Long)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}

object SummaryPrompt {

    /** The instructions evaluated against sample calls on the desktop before choosing the model. */
    const val SYSTEM = """You summarize phone calls for the owner of this phone. In the transcript the owner is "You"; the other person is named in the first line. Reply with JSON only.
topic: the subject of the call in 2 to 5 words, like an email subject line. Name the specific project, place or item discussed. If the call covered several subjects, name the main ones briefly. Leave out people's names and the word "call". Use lower case except for names of places, projects, companies and products. Example: "Harbor Road building materials".
summary: one or two sentences, at most 35 words, with the key facts (amounts, dates, decisions). Use "you" for the owner.
follow_ups: up to 3 things still to happen after the call, each with any day or time mentioned. Make clear who does it, for example "Email the photos to Maria today" for something you do, or "Jordan sends the revised quote this afternoon" for something they do. Use an empty list if nothing is left to do."""

    /** Constrains the reply to exactly {"topic": ..., "summary": ..., "follow_ups": [...]}. */
    const val GRAMMAR = """root ::= "{" ws "\"topic\":" ws topic "," ws "\"summary\":" ws summary "," ws "\"follow_ups\":" ws followups ws "}"
topic ::= "\"" char{1,60} "\""
summary ::= "\"" char{1,320} "\""
followups ::= "[" ws ( item ( "," ws item ){0,2} )? ws "]"
item ::= "\"" char{1,90} "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4})
ws ::= | " " | "\n" [ \t]{0,20}
"""

    /**
     * Builds the chat-formatted prompt (Qwen's format with thinking turned off).
     * When the transcript is longer than [charBudget], the middle of the call is left out:
     * the opening usually says what the call is about and the end holds the decisions.
     */
    fun build(header: String, lines: List<String>, charBudget: Int): String {
        val transcript = fit(lines, charBudget).joinToString("\n")
        val user = "$header\n\nTranscript:\n$transcript"
        return "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n" +
            "<|im_start|>assistant\n<think>\n\n</think>\n\n"
    }

    fun fit(lines: List<String>, charBudget: Int): List<String> {
        if (lines.sumOf { it.length + 1 } <= charBudget) return lines
        val headBudget = charBudget * 7 / 10
        val tailBudget = charBudget - headBudget
        val head = ArrayList<String>()
        var used = 0
        for (l in lines) {
            if (used + l.length + 1 > headBudget) break
            head += l
            used += l.length + 1
        }
        val tail = ArrayList<String>()
        used = 0
        for (l in lines.asReversed()) {
            if (used + l.length + 1 > tailBudget || head.size + tail.size >= lines.size) break
            tail += l
            used += l.length + 1
        }
        return head + "[... part of the call left out ...]" + tail.asReversed()
    }
}

/**
 * Reads the grammar-constrained reply. A tiny dedicated parser keeps this testable on
 * the JVM (org.json is only a stub there) and tolerant of a reply cut off mid-string.
 */
object SummaryParser {

    fun parse(json: String): CallSummary? {
        val fields = HashMap<String, Any>()
        var i = 0
        fun skipWs() {
            while (i < json.length && json[i].isWhitespace()) i++
        }
        fun readString(): String? {
            skipWs()
            if (i >= json.length || json[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < json.length) {
                val c = json[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' && i < json.length -> {
                        when (val e = json[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> if (i + 4 <= json.length) {
                                sb.append(json.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }
        skipWs()
        if (i >= json.length || json[i] != '{') return null
        i++
        while (i < json.length) {
            val key = readString() ?: break
            skipWs()
            if (i >= json.length || json[i] != ':') break
            i++
            skipWs()
            if (i < json.length && json[i] == '[') {
                i++
                val items = ArrayList<String>()
                while (true) {
                    skipWs()
                    if (i < json.length && json[i] == ']') {
                        i++
                        break
                    }
                    items += readString() ?: break
                    skipWs()
                    if (i < json.length && json[i] == ',') i++
                }
                fields[key] = items
            } else {
                fields[key] = readString() ?: break
            }
            skipWs()
            if (i < json.length && json[i] == ',') i++ else break
        }
        val topic = (fields["topic"] as? String)?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
        val summary = (fields["summary"] as? String)?.trim().orEmpty()
        @Suppress("UNCHECKED_CAST")
        val followUps = (fields["follow_ups"] as? List<String>).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        return CallSummary(topic, summary, followUps)
    }
}
