package pl.magazyn.mobile.domain

enum class ImportKind(val title: String) {
    STOCK("Stan magazynowy"),
    PEOPLE("Wydania pracownikom"),
    SHIPYARDS("Wydania na stocznie"),
}

sealed interface ImportRow {
    val rowNumber: Int
    val sourceKey: String
}

data class StockImportRow(
    override val rowNumber: Int,
    override val sourceKey: String,
    val productName: String,
    val quantity: Long,
    val quantityKnown: Boolean,
    val unit: String,
) : ImportRow

data class PersonIssueImportRow(
    override val rowNumber: Int,
    override val sourceKey: String,
    val firstName: String,
    val lastName: String,
    val effectiveDate: String,
    val productName: String,
) : ImportRow

data class ShipyardIssueImportRow(
    override val rowNumber: Int,
    override val sourceKey: String,
    val shipyard: String,
    val productName: String,
    val quantity: Long,
    val effectiveDate: String,
) : ImportRow

data class ImportPreview(
    val kind: ImportKind,
    val fileName: String,
    val fileHash: String,
    val rows: List<ImportRow>,
    val errors: List<String>,
    val duplicateRows: Int,
    val unresolvedProductNames: List<String>,
    val alreadyImported: Boolean,
) {
    val totalRows: Int get() = rows.size + errors.size
}
