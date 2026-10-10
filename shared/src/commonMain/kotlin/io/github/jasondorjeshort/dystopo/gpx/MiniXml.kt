package io.github.jasondorjeshort.dystopo.gpx

/**
 * Just enough XML for GPX, so `shared` doesn't need an XML library (none is multiplatform and
 * small). A pull reader over a whole document in memory, and a writer.
 *
 * The reader handles elements, attributes (single or double quotes), text, CDATA, the five
 * predefined entities and numeric character references, comments, processing instructions and a
 * DOCTYPE without an internal subset (both skipped), and namespace prefixes resolved against the
 * `xmlns` declarations in scope. It does not validate, expand custom entities, or normalize
 * attribute whitespace. Malformed input throws [XmlException] with the offset.
 */
class XmlException(message: String) : IllegalArgumentException(message)

enum class XmlEvent { START, END, TEXT, EOF }

class XmlReader(private val s: String) {
    /** Depth of nested elements; [maxDepth] stops hostile documents from exhausting the scope stack. */
    var depth = 0
        private set
    var maxDepth = 256

    var event: XmlEvent = XmlEvent.TEXT
        private set

    /** Qualified name (`gpxtpx:hr`) of the current START or END element. */
    var name: String = ""
        private set

    /** [name] without its prefix. */
    val localName: String get() = name.substringAfter(':')

    /** The namespace URI of [name], or null when its prefix is not declared (or there is no default namespace). */
    var namespaceUri: String? = null
        private set

    private fun resolve(n: String): String? = lookup(if (':' in n) n.substringBefore(':') else "")

    /** Attributes of the current START element, qualified names, in document order. */
    var attributes: Map<String, String> = emptyMap()
        private set

    /** Decoded text of the current TEXT event (text and CDATA runs between tags, concatenated). */
    var text: String = ""
        private set

    /** Source offsets of the current event: [eventStart] inclusive, [eventEnd] exclusive. */
    var eventStart = 0
        private set
    var eventEnd = 0
        private set

    private var pos = 0
    private var pendingEnd: String? = null // a self-closing tag's END, reported on the next call
    private val openNames = ArrayList<String>()
    private val scopes = ArrayList<Map<String, String>>() // xmlns declarations per open element

    private fun fail(msg: String, at: Int = pos): Nothing = throw XmlException("XML: $msg at offset $at")

    private fun lookup(prefix: String): String? {
        if (prefix == "xml") return "http://www.w3.org/XML/1998/namespace"
        for (i in scopes.indices.reversed()) scopes[i][prefix]?.let { return it.ifEmpty { null } }
        return null
    }

    /** Advances to the next event. Whitespace-only text is reported like any other text. */
    fun next(): XmlEvent {
        pendingEnd?.let { n ->
            pendingEnd = null
            closeElement(n)
            eventStart = eventEnd
            return XmlEvent.END
        }
        while (true) {
            if (pos >= s.length) {
                if (openNames.isNotEmpty()) fail("unexpected end of document inside <${openNames.last()}>")
                event = XmlEvent.EOF
                eventStart = pos
                eventEnd = pos
                return event
            }
            if (s[pos] != '<') return readText()
            when {
                s.startsWith("<!--", pos) -> skipPast("-->")
                s.startsWith("<?", pos) -> skipPast("?>")
                s.startsWith("<![CDATA[", pos) -> return readText()
                s.startsWith("<!", pos) -> skipDoctype()
                s.startsWith("</", pos) -> return readEnd()
                else -> return readStart()
            }
        }
    }

    /** Text up to the next tag (CDATA sections included); comments in between are skipped. */
    private fun readText(): XmlEvent {
        val sb = StringBuilder()
        eventStart = pos
        while (pos < s.length) {
            when {
                s.startsWith("<![CDATA[", pos) -> {
                    val end = s.indexOf("]]>", pos + 9).takeIf { it >= 0 } ?: fail("unterminated CDATA")
                    sb.append(s, pos + 9, end)
                    pos = end + 3
                }
                s.startsWith("<!--", pos) -> skipPast("-->")
                s[pos] == '<' -> break
                s[pos] == '&' -> pos = decodeEntity(sb)
                else -> {
                    val next = nextSpecial(pos)
                    sb.append(s, pos, next)
                    pos = next
                }
            }
        }
        if (openNames.isEmpty() && sb.isNotBlank()) fail("text outside the root element", eventStart)
        text = sb.toString()
        eventEnd = pos
        event = XmlEvent.TEXT
        return event
    }

    private fun nextSpecial(from: Int): Int {
        var i = from
        while (i < s.length && s[i] != '<' && s[i] != '&') i++
        return i
    }

    private fun skipPast(terminator: String) {
        val end = s.indexOf(terminator, pos).takeIf { it >= 0 } ?: fail("unterminated '${s.substring(pos, minOf(pos + 4, s.length))}'")
        pos = end + terminator.length
    }

    private fun skipDoctype() {
        // <!DOCTYPE ...>, with an internal subset in brackets if any.
        var i = pos + 2
        var bracket = 0
        while (i < s.length) {
            when (s[i]) {
                '[' -> bracket++
                ']' -> bracket--
                '>' -> if (bracket <= 0) { pos = i + 1; return }
            }
            i++
        }
        fail("unterminated declaration")
    }

    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.' || c.code > 0x7f

    private fun readName(): String {
        val start = pos
        while (pos < s.length && isNameChar(s[pos])) pos++
        if (pos == start) fail("expected a name")
        return s.substring(start, pos)
    }

    private fun skipWs() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun readStart(): XmlEvent {
        eventStart = pos
        pos++ // '<'
        val n = readName()
        val attrs = LinkedHashMap<String, String>()
        val ns = HashMap<String, String>()
        while (true) {
            skipWs()
            if (pos >= s.length) fail("unterminated tag <$n")
            val c = s[pos]
            if (c == '>' || c == '/') break
            val an = readName()
            skipWs()
            if (pos >= s.length || s[pos] != '=') fail("expected '=' after attribute $an")
            pos++
            skipWs()
            val q = s.getOrNull(pos)
            if (q != '"' && q != '\'') fail("expected a quoted value for attribute $an")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) fail("unterminated attribute value")
                val ch = s[pos]
                when {
                    ch == q -> { pos++; break }
                    ch == '&' -> pos = decodeEntity(sb)
                    ch == '<' -> fail("'<' in attribute value")
                    else -> { sb.append(ch); pos++ }
                }
            }
            if (an in attrs) fail("duplicate attribute $an")
            attrs[an] = sb.toString()
            if (an == "xmlns") ns[""] = sb.toString() else if (an.startsWith("xmlns:")) ns[an.substring(6)] = sb.toString()
        }
        val selfClosing = s[pos] == '/'
        if (selfClosing) {
            pos++
            if (pos >= s.length || s[pos] != '>') fail("expected '>'")
        }
        pos++
        if (depth >= maxDepth) fail("nesting deeper than $maxDepth", eventStart)
        name = n
        attributes = attrs
        openNames += n
        scopes += ns
        namespaceUri = resolve(n)
        depth++
        eventEnd = pos
        if (selfClosing) pendingEnd = n
        event = XmlEvent.START
        return event
    }

    private fun readEnd(): XmlEvent {
        eventStart = pos
        pos += 2
        val n = readName()
        skipWs()
        if (pos >= s.length || s[pos] != '>') fail("expected '>' after </$n")
        pos++
        if (openNames.isEmpty() || openNames.last() != n) fail("</$n> does not close <${openNames.lastOrNull() ?: ""}>", eventStart)
        closeElement(n)
        eventEnd = pos
        return XmlEvent.END
    }

    /** Reports END for [n] and leaves its namespace scope. */
    private fun closeElement(n: String) {
        name = n
        attributes = emptyMap()
        event = XmlEvent.END
        namespaceUri = resolve(n)
        openNames.removeAt(openNames.size - 1)
        scopes.removeAt(scopes.size - 1)
        depth--
    }

    /** Decodes the entity or character reference at [pos] into [sb]; returns the position after it. */
    private fun decodeEntity(sb: StringBuilder): Int {
        val semi = s.indexOf(';', pos)
        if (semi < 0 || semi - pos > 12) fail("unterminated entity")
        val ent = s.substring(pos + 1, semi)
        when {
            ent == "lt" -> sb.append('<')
            ent == "gt" -> sb.append('>')
            ent == "amp" -> sb.append('&')
            ent == "quot" -> sb.append('"')
            ent == "apos" -> sb.append('\'')
            ent.startsWith("#x") || ent.startsWith("#X") -> appendCodePoint(sb, ent.substring(2).toIntOrNull(16) ?: fail("bad character reference &$ent;"))
            ent.startsWith("#") -> appendCodePoint(sb, ent.substring(1).toIntOrNull() ?: fail("bad character reference &$ent;"))
            else -> fail("unknown entity &$ent;")
        }
        return semi + 1
    }

    private fun appendCodePoint(sb: StringBuilder, cp: Int) {
        when {
            cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> fail("invalid character reference $cp")
            cp < 0x10000 -> sb.append(cp.toChar())
            else -> {
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar())
                sb.append((0xDC00 + (v and 0x3ff)).toChar())
            }
        }
    }

    // --- conveniences for a recursive-descent consumer ------------------------------------------

    /** Moves to the next START or END (skipping text); returns false at the END of the current element. */
    fun nextChild(): Boolean {
        while (true) {
            when (next()) {
                XmlEvent.START -> return true
                XmlEvent.END -> return false
                XmlEvent.EOF -> fail("unexpected end of document")
                XmlEvent.TEXT -> Unit
            }
        }
    }

    /**
     * After a START: the element's text content, stopping at its END. Child elements' text is
     * included (their tags are not), so `<name>a<b/>c</name>` reads as "ac".
     */
    fun readElementText(): String {
        val sb = StringBuilder()
        var level = 1
        while (level > 0) {
            when (next()) {
                XmlEvent.START -> level++
                XmlEvent.END -> level--
                XmlEvent.TEXT -> sb.append(text)
                XmlEvent.EOF -> fail("unexpected end of document")
            }
        }
        return sb.toString()
    }

    /** After a START: skips to its END and returns the raw source between the two tags (empty for `<a/>`). */
    fun readInnerXml(): String {
        val innerStart = eventEnd
        var innerEnd = innerStart
        var level = 1
        while (level > 0) {
            when (next()) {
                XmlEvent.START -> level++
                XmlEvent.END -> { level--; if (level == 0) innerEnd = eventStart }
                XmlEvent.TEXT -> Unit
                XmlEvent.EOF -> fail("unexpected end of document")
            }
        }
        return s.substring(innerStart, maxOf(innerStart, innerEnd))
    }

    /** After a START: skips the element and everything in it. */
    fun skipElement() {
        readInnerXml()
    }
}

/** Builds an indented XML document. Text and attribute values are escaped; [raw] is written as given. */
class XmlWriter(private val indent: String = "  ") {
    private val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    private val open = ArrayList<String>()

    private fun pad() {
        repeat(open.size) { sb.append(indent) }
    }

    fun start(name: String, attributes: List<Pair<String, String>> = emptyList()): XmlWriter {
        pad()
        sb.append('<').append(name)
        for ((k, v) in attributes) sb.append(' ').append(k).append("=\"").append(escape(v, attribute = true)).append('"')
        sb.append(">\n")
        open += name
        return this
    }

    fun end(): XmlWriter {
        val n = open.removeAt(open.size - 1)
        pad()
        sb.append("</").append(n).append(">\n")
        return this
    }

    /** `<name>text</name>` on one line; nothing when [text] is null. */
    fun element(name: String, text: String?): XmlWriter {
        if (text == null) return this
        pad()
        sb.append('<').append(name).append('>').append(escape(text, attribute = false)).append("</").append(name).append(">\n")
        return this
    }

    /** `<name>raw</name>` with [raw] markup copied verbatim (already-serialized XML such as extensions). */
    fun rawElement(name: String, raw: String?): XmlWriter {
        if (raw == null) return this
        pad()
        sb.append('<').append(name).append('>').append(raw).append("</").append(name).append(">\n")
        return this
    }

    override fun toString(): String {
        check(open.isEmpty()) { "unclosed elements: $open" }
        return sb.toString()
    }

    companion object {
        fun escape(text: String, attribute: Boolean): String {
            val sb = StringBuilder(text.length)
            for (c in text) {
                when {
                    c == '&' -> sb.append("&amp;")
                    c == '<' -> sb.append("&lt;")
                    c == '>' -> sb.append("&gt;")
                    c == '"' && attribute -> sb.append("&quot;")
                    // Keep tabs and newlines in attributes (normalized to spaces by a reader otherwise).
                    attribute && (c == '\n' || c == '\r' || c == '\t') -> sb.append("&#").append(c.code).append(';')
                    c == '\r' -> sb.append("&#13;")
                    c < ' ' && c != '\n' && c != '\t' -> Unit // not allowed in XML 1.0; drop
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }
    }
}
