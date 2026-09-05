package pl.magazyn.mobile.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object XlsxReader {
    fun readFirstSheet(bytes: ByteArray): List<List<String>> {
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && (
                        entry.name == "xl/sharedStrings.xml" ||
                            entry.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml"))
                        )
                ) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val sheetBytes = entries.entries
            .filter { it.key.startsWith("xl/worksheets/sheet") }
            .minByOrNull { it.key }
            ?.value
            ?: error("Plik nie zawiera arkusza")
        val sharedStrings = entries["xl/sharedStrings.xml"]?.let(::parseSharedStrings).orEmpty()
        return parseSheet(sheetBytes, sharedStrings)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val document = document(bytes)
        return document.getElementsByTagNameNS("*", "si").asElements().map { item ->
            item.getElementsByTagNameNS("*", "t").asElements().joinToString("") { it.textContent }
        }
    }

    private fun parseSheet(bytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val document = document(bytes)
        return document.getElementsByTagNameNS("*", "row").asElements().map { row ->
            val values = mutableMapOf<Int, String>()
            row.getElementsByTagNameNS("*", "c").asElements().forEach { cell ->
                val reference = cell.getAttribute("r")
                val column = reference.takeWhile(Char::isLetter).fold(0) { result, character ->
                    result * 26 + (character.uppercaseChar() - 'A' + 1)
                } - 1
                val type = cell.getAttribute("t")
                val raw = if (type == "inlineStr") {
                    cell.getElementsByTagNameNS("*", "t").asElements().joinToString("") { it.textContent }
                } else {
                    cell.getElementsByTagNameNS("*", "v").item(0)?.textContent.orEmpty()
                }
                values[column] = if (type == "s") sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty() else raw
            }
            val lastColumn = values.keys.maxOrNull() ?: -1
            (0..lastColumn).map { values[it].orEmpty().trim() }
        }
    }

    private fun document(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun org.w3c.dom.NodeList.asElements(): List<Element> =
        (0 until length).mapNotNull { item(it) as? Element }
}
