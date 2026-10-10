package io.github.jasondorjeshort.dystopo.gpx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MiniXmlTest {
    /** Compact trace of all events: <a k=v> text </a>. */
    private fun trace(xml: String): String {
        val r = XmlReader(xml)
        val sb = StringBuilder()
        while (true) {
            when (r.next()) {
                XmlEvent.START -> sb.append("<").append(r.name).append(r.attributes.entries.joinToString("") { " ${it.key}=${it.value}" }).append(">")
                XmlEvent.END -> sb.append("</").append(r.name).append(">")
                XmlEvent.TEXT -> if (r.text.isNotBlank()) sb.append("[").append(r.text).append("]")
                XmlEvent.EOF -> return sb.toString()
            }
        }
    }

    @Test
    fun elementsAttributesAndText() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE gpx>
            <!-- a comment -->
            <a x="1" y='two'><b>hi</b><c/><d   z = "3" /></a>"""
        assertEquals("<a x=1 y=two><b>[hi]</b><c></c><d z=3></d></a>", trace(xml))
    }

    @Test
    fun entitiesCdataAndCharacterReferences() {
        val r = XmlReader("<a t=\"&lt;&amp;&quot;&apos;&gt;\">x &amp; y <![CDATA[<raw & stuff>]]> &#65;&#x42;&#x1F600;<!-- skip -->z</a>")
        r.next()
        assertEquals("<&\"'>", r.attributes["t"])
        assertEquals("x & y <raw & stuff> AB😀z", r.readElementText())
    }

    @Test
    fun namespacesResolveByPrefixAndScope() {
        val r = XmlReader("<g xmlns=\"urn:g\" xmlns:e=\"urn:e\"><e:hr>1</e:hr><p xmlns=\"urn:p\"><q/></p><z:u/></g>")
        r.next()
        assertEquals("urn:g", r.namespaceUri)
        r.next()
        assertEquals("hr", r.localName)
        assertEquals("urn:e", r.namespaceUri)
        r.readElementText()
        r.next()
        assertEquals("urn:p", r.namespaceUri)
        r.next()
        assertEquals("q", r.name)
        assertEquals("urn:p", r.namespaceUri) // default namespace inherited
        r.next() // </q>
        r.next() // </p>
        assertEquals("urn:p", r.namespaceUri)
        r.next() // <z:u/>, undeclared prefix
        assertEquals("u", r.localName)
        assertNull(r.namespaceUri)
    }

    @Test
    fun innerXmlIsVerbatim() {
        val inner = "<ns3:TrackPointExtension><ns3:hr>141</ns3:hr><!-- c --><ns3:cad>80</ns3:cad></ns3:TrackPointExtension>"
        val r = XmlReader("<trkpt><extensions>$inner</extensions><ele>5</ele></trkpt>")
        r.next()
        r.next()
        assertEquals(inner, r.readInnerXml())
        r.next()
        assertEquals("ele", r.name)
        assertEquals("", XmlReader("<a/>").also { it.next() }.readInnerXml())
    }

    @Test
    fun malformedInputThrows() {
        for (bad in listOf("<a><b></a></b>", "<a>", "<a x=1/>", "<a>&bogus;</a>", "<a x=\"1\" x=\"2\"/>", "<a><![CDATA[x</a>", "text<a/>")) {
            assertFailsWith<XmlException>(bad) { trace(bad) }
        }
    }

    @Test
    fun deepNestingIsRefused() {
        val deep = "<a>".repeat(300) + "</a>".repeat(300)
        assertFailsWith<XmlException> { trace(deep) }
    }

    @Test
    fun writerEscapesAndReadsBack() {
        val nasty = "Tom & Jerry's <\"trail\">\tend"
        val w = XmlWriter()
        w.start("r", listOf("k" to nasty)).element("t", nasty).rawElement("x", "<y>1</y>").end()
        val r = XmlReader(w.toString())
        while (r.next() != XmlEvent.START) Unit
        assertEquals(nasty, r.attributes["k"])
        r.nextChild()
        assertEquals(nasty, r.readElementText())
        r.nextChild()
        assertEquals("<y>1</y>", r.readInnerXml())
    }
}
