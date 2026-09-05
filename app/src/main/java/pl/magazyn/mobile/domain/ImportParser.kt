package pl.magazyn.mobile.domain

import java.math.BigDecimal
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import kotlin.math.roundToLong
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.XlsxReader

object ImportParser {
    fun parse(fileName: String, bytes: ByteArray, products: List<ProductEntity>, alreadyImported: Boolean): ImportPreview {
        val table = XlsxReader.readFirstSheet(bytes)
        require(table.isNotEmpty()) { "Arkusz jest pusty" }
        val headers = table.first().map(::key)
        val kind = when {
            headers.take(3) == listOf("artykul", "stan", "jednostki") -> ImportKind.STOCK
            headers.take(4) == listOf("imie", "nazwisko", "data", "przedmiot") -> ImportKind.PEOPLE
            headers.take(4) == listOf("stocznia", "przedmiot", "ilosc", "data") -> ImportKind.SHIPYARDS
            else -> error("Nie rozpoznano układu kolumn: ${table.first().joinToString(", ")}")
        }
        val errors = mutableListOf<String>()
        val occurrences = mutableMapOf<String, Int>()
        val rows = table.drop(1).mapIndexedNotNull { index, columns ->
            val rowNumber = index + 2
            val cleanColumns = columns.dropLastWhile(String::isBlank)
            if (cleanColumns.isEmpty()) return@mapIndexedNotNull null
            runCatching {
                val signature = cleanColumns.joinToString("|") { key(it) }
                val occurrence = (occurrences[signature] ?: 0) + 1
                occurrences[signature] = occurrence
                val sourceKey = sha256("${kind.name}|$signature|$occurrence")
                when (kind) {
                    ImportKind.STOCK -> parseStock(rowNumber, sourceKey, cleanColumns)
                    ImportKind.PEOPLE -> parsePerson(rowNumber, sourceKey, cleanColumns)
                    ImportKind.SHIPYARDS -> parseShipyard(rowNumber, sourceKey, cleanColumns)
                }
            }.getOrElse {
                errors += "Wiersz $rowNumber: ${it.message ?: "nieprawidłowe dane"}"
                null
            }
        }
        val productKeys = productLookup(products).keys
        val unresolved = when (kind) {
            ImportKind.STOCK -> emptyList()
            ImportKind.PEOPLE -> rows.filterIsInstance<PersonIssueImportRow>().map { it.productName }
            ImportKind.SHIPYARDS -> rows.filterIsInstance<ShipyardIssueImportRow>().map { it.productName }
        }.distinct().filter { key(it) !in productKeys }.sorted()
        return ImportPreview(
            kind = kind,
            fileName = fileName,
            fileHash = sha256(bytes),
            rows = rows,
            errors = errors,
            duplicateRows = occurrences.values.sumOf { (it - 1).coerceAtLeast(0) },
            unresolvedProductNames = unresolved,
            alreadyImported = alreadyImported,
        )
    }

    fun productLookup(products: List<ProductEntity>): Map<String, ProductEntity> = buildMap {
        products.forEach { product ->
            putIfAbsent(key(product.name), product)
            product.aliases.split(",").map(String::trim).filter(String::isNotBlank).forEach { alias ->
                putIfAbsent(key(alias), product)
            }
        }
    }

    fun key(value: String): String = Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('ł', 'l')
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun sha256(value: String): String = sha256(value.toByteArray())

    fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun parseStock(row: Int, sourceKey: String, values: List<String>): StockImportRow {
        val name = values.getOrElse(0) { "" }.trim().ifBlank { error("brak nazwy artykułu") }
        val rawQuantity = values.getOrElse(1) { "" }.trim()
        val known = rawQuantity.isNotBlank()
        val quantity = when {
            rawQuantity.isBlank() -> 0L
            rawQuantity.equals("brak", ignoreCase = true) -> 0L
            else -> wholeNumber(rawQuantity, "stan")
        }
        val unit = values.getOrElse(2) { "" }.trim().ifBlank { error("brak jednostki") }
        return StockImportRow(row, sourceKey, name, quantity, known, unit)
    }

    private fun parsePerson(row: Int, sourceKey: String, values: List<String>): PersonIssueImportRow =
        PersonIssueImportRow(
            rowNumber = row,
            sourceKey = sourceKey,
            firstName = normalizeFirstName(values.getOrElse(0) { "" }).ifBlank { error("brak imienia") },
            lastName = normalizePersonName(values.getOrElse(1) { "" }).ifBlank { error("brak nazwiska") },
            effectiveDate = excelDate(values.getOrElse(2) { "" }),
            productName = values.getOrElse(3) { "" }.trim().ifBlank { error("brak przedmiotu") },
        )

    private fun parseShipyard(row: Int, sourceKey: String, values: List<String>): ShipyardIssueImportRow =
        ShipyardIssueImportRow(
            rowNumber = row,
            sourceKey = sourceKey,
            shipyard = values.getOrElse(0) { "" }.trim().ifBlank { error("brak stoczni") },
            productName = values.getOrElse(1) { "" }.trim().ifBlank { error("brak przedmiotu") },
            quantity = wholeNumber(values.getOrElse(2) { "" }, "ilość"),
            effectiveDate = excelDate(values.getOrElse(3) { "" }),
        )

    private fun wholeNumber(value: String, label: String): Long = runCatching {
        BigDecimal(value.trim().replace(',', '.')).longValueExact()
    }.getOrElse { error("$label nie jest liczbą całkowitą") }

    private fun excelDate(value: String): String {
        val raw = value.trim().ifBlank { error("brak daty") }
        raw.toDoubleOrNull()?.let { return LocalDate.of(1899, 12, 30).plusDays(it.roundToLong()).toString() }
        return listOf("yyyy-MM-dd", "dd.MM.yyyy", "dd-MM-yyyy").firstNotNullOfOrNull { pattern ->
            runCatching { LocalDate.parse(raw, java.time.format.DateTimeFormatter.ofPattern(pattern)).toString() }.getOrNull()
        } ?: error("nieprawidłowa data")
    }
}
