package pl.magazyn.mobile.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "warehouses")
data class WarehouseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isMain: Boolean,
    val isArchived: Boolean = false,
)

@Entity(tableName = "employees", indices = [Index(value = ["fullName"])])
data class EmployeeEntity(
    @PrimaryKey val id: String,
    val fullName: String,
    @ColumnInfo(defaultValue = "''") val firstName: String = "",
    @ColumnInfo(defaultValue = "''") val lastName: String = "",
    @ColumnInfo(defaultValue = "''") val phoneNumbers: String = "",
    val aliases: String = "",
    val tags: String = "",
    val isArchived: Boolean = false,
)

@Entity(tableName = "job_positions", indices = [Index(value = ["name"], unique = true)])
data class JobPositionEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(
    tableName = "employee_job_positions",
    primaryKeys = ["employeeId", "positionId"],
    foreignKeys = [
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["employeeId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = JobPositionEntity::class,
            parentColumns = ["id"],
            childColumns = ["positionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("employeeId"), Index("positionId")],
)
data class EmployeeJobPositionEntity(
    val employeeId: String,
    val positionId: String,
)

@Entity(tableName = "products", indices = [Index(value = ["name"])])
data class ProductEntity(
    @PrimaryKey val id: String,
    val name: String,
    val variant: String? = null,
    val unit: String,
    val category: String = "",
    @ColumnInfo(defaultValue = "''") val groupName: String = "",
    @ColumnInfo(defaultValue = "''") val subgroupName: String = "",
    val aliases: String = "",
    val tags: String = "",
    @ColumnInfo(defaultValue = "''") val photoUri: String = "",
    val isReturnable: Boolean = false,
    val lowStockThreshold: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val repeatIssueWeeks: Int = 0,
    val isArchived: Boolean = false,
)

@Entity(tableName = "product_groups", indices = [Index(value = ["name"], unique = true)])
data class ProductGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "product_subgroups", indices = [Index(value = ["name", "groupName"], unique = true)])
data class ProductSubgroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val groupName: String = "",
)

@Entity(tableName = "product_categories", indices = [Index(value = ["name"], unique = true)])
data class ProductCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Entity(tableName = "shipyards", indices = [Index(value = ["name"], unique = true)])
data class ShipyardEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isArchived: Boolean = false,
)

@Entity(
    tableName = "shipyard_leaders",
    primaryKeys = ["shipyardId", "employeeId"],
    foreignKeys = [
        ForeignKey(entity = ShipyardEntity::class, parentColumns = ["id"], childColumns = ["shipyardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = EmployeeEntity::class, parentColumns = ["id"], childColumns = ["employeeId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("shipyardId"), Index("employeeId")],
)
data class ShipyardLeaderEntity(
    val shipyardId: String,
    val employeeId: String,
)

@Entity(
    tableName = "shipyard_stock_balances",
    primaryKeys = ["shipyardId", "productId"],
    foreignKeys = [
        ForeignKey(entity = ShipyardEntity::class, parentColumns = ["id"], childColumns = ["shipyardId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["productId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("shipyardId"), Index("productId")],
)
data class ShipyardStockBalanceEntity(
    val shipyardId: String,
    val productId: String,
    val quantity: Double,
)

@Entity(
    tableName = "stock_movements",
    foreignKeys = [
        ForeignKey(
            entity = WarehouseEntity::class,
            parentColumns = ["id"],
            childColumns = ["warehouseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["employeeId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("warehouseId"), Index("employeeId"), Index("effectiveDate")],
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val type: String,
    val warehouseId: String,
    val employeeId: String?,
    @ColumnInfo(defaultValue = "''") val recipientLabel: String = "",
    val effectiveDate: String,
    val createdAtEpochMillis: Long,
    val note: String = "",
)

@Entity(
    tableName = "stock_movement_lines",
    foreignKeys = [
        ForeignKey(
            entity = StockMovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["movementId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("movementId"), Index("productId")],
)
data class StockMovementLineEntity(
    @PrimaryKey val id: String,
    val movementId: String,
    val productId: String,
    val quantityDelta: Double,
    val unit: String,
)

@Entity(
    tableName = "issue_amendments",
    foreignKeys = [
        ForeignKey(entity = StockMovementLineEntity::class, parentColumns = ["id"], childColumns = ["originalLineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ProductEntity::class, parentColumns = ["id"], childColumns = ["replacementProductId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("originalLineId"), Index("replacementProductId"), Index("createdAtEpochMillis")],
)
data class IssueAmendmentEntity(
    @PrimaryKey val id: String,
    val originalLineId: String,
    val replacementProductId: String,
    val replacementQuantity: Double,
    val replacementDate: String,
    val isDeleted: Boolean,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "issue_returns",
    foreignKeys = [
        ForeignKey(entity = StockMovementLineEntity::class, parentColumns = ["id"], childColumns = ["originalLineId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("originalLineId")],
)
data class IssueReturnEntity(
    @PrimaryKey val id: String,
    val originalLineId: String,
    val quantity: Double,
    val returnedDate: String,
    val createdAtEpochMillis: Long,
)

@Entity(tableName = "parser_learning_rules", indices = [Index(value = ["ruleType", "triggerKey"], unique = true)])
data class ParserLearningRuleEntity(
    @PrimaryKey val id: String,
    val triggerKey: String,
    val sourceLabel: String,
    val learnedName: String,
    val learnedVariant: String?,
    val learnedUnit: String,
    val confirmations: Int = 1,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "'PRODUCT'") val ruleType: String = "PRODUCT",
    @ColumnInfo(defaultValue = "''") val resultExtra: String = "",
    @ColumnInfo(defaultValue = "1") val isEnabled: Boolean = true,
)

@Entity(
    tableName = "custodies",
    foreignKeys = [
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["employeeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = StockMovementEntity::class,
            parentColumns = ["id"],
            childColumns = ["issuedMovementId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("employeeId"), Index("productId"), Index("issuedMovementId")],
)
data class CustodyEntity(
    @PrimaryKey val id: String,
    val employeeId: String,
    val productId: String,
    val quantity: Double,
    val issuedMovementId: String,
    val issuedDate: String,
    val returnedDate: String? = null,
)

@Entity(
    tableName = "stock_balances",
    primaryKeys = ["warehouseId", "productId"],
    foreignKeys = [
        ForeignKey(
            entity = WarehouseEntity::class,
            parentColumns = ["id"],
            childColumns = ["warehouseId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("warehouseId"), Index("productId")],
)
data class StockBalanceEntity(
    val warehouseId: String,
    val productId: String,
    val quantity: Double,
    @ColumnInfo(defaultValue = "1") val isKnown: Boolean = true,
)

@Entity(tableName = "import_batches", indices = [Index(value = ["fileHash"], unique = true)])
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val sourceFileName: String,
    val fileHash: String,
    val importedAtEpochMillis: Long,
    val totalRows: Int,
    val importedRows: Int,
    val pendingRows: Int,
    val skippedRows: Int,
)

@Entity(
    tableName = "import_source_rows",
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("batchId")],
)
data class ImportSourceRowEntity(
    @PrimaryKey val sourceKey: String,
    val batchId: String,
    val kind: String,
    val sourceRowNumber: Int,
)

@Entity(
    tableName = "import_pending_rows",
    foreignKeys = [
        ForeignKey(
            entity = ImportBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("batchId"), Index("kind")],
)
data class ImportPendingRowEntity(
    @PrimaryKey val sourceKey: String,
    val batchId: String,
    val kind: String,
    val sourceRowNumber: Int,
    val recipientFirstName: String = "",
    val recipientLastName: String = "",
    val recipientLabel: String = "",
    val effectiveDate: String,
    val rawProductName: String,
    val quantity: Long,
)

@Entity(tableName = "order_notebooks")
data class OrderNotebookEntity(
    @PrimaryKey val id: String,
    val rawText: String,
    val status: String,
    @ColumnInfo(defaultValue = "'NOTE'") val detectedType: String = "NOTE",
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "notebook_tasks",
    foreignKeys = [
        ForeignKey(entity = OrderNotebookEntity::class, parentColumns = ["id"], childColumns = ["notebookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("notebookId")],
)
data class NotebookTaskEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    val text: String,
    val isCompleted: Boolean = false,
    val position: Int,
    val dueDate: String? = null,
    @ColumnInfo(defaultValue = "'NORMAL'") val priority: String = "NORMAL",
    val employeeId: String? = null,
    val shipyardId: String? = null,
    val productId: String? = null,
    val orderId: String? = null,
)

@Entity(
    tableName = "orders",
    foreignKeys = [
        ForeignKey(
            entity = OrderNotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id"],
            childColumns = ["employeeId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("notebookId"), Index("employeeId")],
)
data class OrderEntity(
    @PrimaryKey val id: String,
    val notebookId: String?,
    val employeeId: String?,
    val recipientLabel: String,
    val siteLabel: String?,
    val status: String,
    val plannedIssueDate: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "order_lines",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["id"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["id"],
            childColumns = ["productId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("orderId"), Index("productId")],
)
data class OrderLineEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val productId: String?,
    val rawText: String,
    val quantity: Double,
    val unit: String,
    val verificationStatus: String,
    val isPrepared: Boolean = false,
)

@Entity(
    tableName = "order_changes",
    foreignKeys = [
        ForeignKey(entity = OrderEntity::class, parentColumns = ["id"], childColumns = ["orderId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("orderId"), Index("createdAtEpochMillis")],
)
data class OrderChangeEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val action: String,
    val description: String,
    val createdAtEpochMillis: Long,
    val actorLabel: String = "Użytkownik lokalny",
)
