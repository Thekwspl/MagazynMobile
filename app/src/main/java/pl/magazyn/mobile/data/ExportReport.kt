package pl.magazyn.mobile.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class ExportReportType(val label: String) {
    STOCK("Aktualny stan magazynowy"), DELIVERIES("Dostawy w wybranym okresie")
}

enum class StockReportSource(val label: String) {
    MAIN("Magazyn główny"), SHIPYARD("Stocznia")
}

data class ExportSelection(
    val report: ExportReportType = ExportReportType.STOCK,
    val source: StockReportSource = StockReportSource.MAIN,
    val shipyardId: String = "",
    val dateFrom: String = LocalDate.now().toString(),
    val dateTo: String = LocalDate.now().toString(),
) {
    fun dateError(): String? {
        val from = runCatching { LocalDate.parse(dateFrom) }.getOrNull()
        val to = runCatching { LocalDate.parse(dateTo) }.getOrNull()
        return when {
            from == null || to == null -> "Wybierz poprawne daty początku i końca okresu."
            from > to -> "Data początkowa jest późniejsza niż końcowa."
            else -> null
        }
    }
}

data class ExportStockTarget(val id: String, val name: String, val source: StockReportSource)

fun resolveExportStockTarget(
    selection: ExportSelection,
    warehouses: List<WarehouseEntity>,
    shipyards: List<ShipyardEntity>,
): ExportStockTarget = when (selection.source) {
    StockReportSource.MAIN -> {
        val main = warehouses.singleOrNull { it.isMain && !it.isArchived }
        requireNotNull(main) { "Nie można jednoznacznie wskazać aktywnego magazynu głównego." }
        ExportStockTarget(main.id, main.name, selection.source)
    }
    StockReportSource.SHIPYARD -> {
        val yard = shipyards.firstOrNull { it.id == selection.shipyardId && !it.isArchived }
        requireNotNull(yard) { "Wybierz aktywną stocznię. Poprzednio wybrana stocznia może być już niedostępna." }
        ExportStockTarget(yard.id, "Stocznia ${yard.name}", selection.source)
    }
}

fun ShipyardStockItem.toExportProduct() = ProductWithStock(
    productId, name, variant, unit, category, groupName, subgroupName,
    "", "", "", false, 0.0, 0, false, quantity, true,
)

data class DeliveryReportLine(
    val movementId: String,
    val lineId: String,
    val effectiveDate: String,
    val productName: String,
    val variant: String?,
    val quantity: Double,
    val unit: String,
    val warehouseName: String,
    val note: String,
)

/** Wspólne dane tabeli dla istniejących formatów, bez dostępu do zapisów Room. */
data class ExportTable(
    val title: String,
    val subtitle: String,
    val generatedAt: String,
    val headers: List<String>,
    val rows: List<List<String>>,
    val numericColumns: Set<Int>,
    val pdfColumns: List<Int>,
    val pdfWeights: List<Float>,
)

private fun exportTime(now: LocalDateTime) = now.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))

fun stockExportTable(name: String, items: List<ProductWithStock>, now: LocalDateTime = LocalDateTime.now()): ExportTable {
    val time = exportTime(now)
    return ExportTable(
        "Stan magazynowy", name, time,
        listOf("Magazyn", "Przedmiot", "Wariant", "Grupa", "Podgrupa", "Kategoria", "Stan", "Jednostka", "Wygenerowano"),
        items.map { item -> listOf(name, item.name, item.variant.orEmpty(), item.groupName, item.subgroupName,
            item.category, if (item.stockKnown) item.stockQuantity.toLong().toString() else "Nieustalony", item.unit, time) },
        setOf(6), (1..7).toList(), listOf(2.5f, 1f, 1.3f, 1.3f, 1.3f, 0.85f, 0.85f),
    )
}

fun deliveryExportTable(selection: ExportSelection, lines: List<DeliveryReportLine>, now: LocalDateTime = LocalDateTime.now()): ExportTable {
    require(selection.dateError() == null) { selection.dateError().orEmpty() }
    val time = exportTime(now)
    return ExportTable(
        "Dostawy", "Okres: ${selection.dateFrom} – ${selection.dateTo}", time,
        listOf("Data dostawy", "Przedmiot", "Wariant", "Ilość przyjęta", "Jednostka", "Magazyn docelowy", "Uwagi", "Id operacji", "Id pozycji", "Okres od", "Okres do", "Wygenerowano"),
        lines.map { line -> listOf(line.effectiveDate, line.productName, line.variant.orEmpty(), line.quantity.toLong().toString(),
            line.unit, line.warehouseName, line.note, line.movementId, line.lineId, selection.dateFrom, selection.dateTo, time) },
        setOf(3), (0..7).toList(), listOf(1.15f, 2f, 0.85f, 0.75f, 0.65f, 1.4f, 2f, 1.5f),
    )
}

fun exportFileName(selection: ExportSelection, targetName: String, format: StockExportFormat, now: LocalDateTime): String {
    val slug = targetName.lowercase().replace(Regex("[^a-ząćęłńóśźż0-9]+"), "-").trim('-').ifBlank { "magazyn" }
    val stem = if (selection.report == ExportReportType.STOCK) "stan-$slug-${now.toLocalDate()}"
        else "dostawy-${selection.dateFrom}-${selection.dateTo}"
    return "$stem-${now.format(DateTimeFormatter.ofPattern("HHmmssSSS"))}.${format.extension}"
}
