package pl.magazyn.mobile.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface EmployeeDao {
    @Query("SELECT COUNT(*) FROM employees WHERE isArchived = 0")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM employees WHERE isArchived = 0 ORDER BY lastName COLLATE NOCASE, firstName COLLATE NOCASE")
    fun observeAll(): Flow<List<EmployeeEntity>>

    @Query("SELECT * FROM employees WHERE isArchived = 0")
    suspend fun getAllNow(): List<EmployeeEntity>

    @Query("""
        SELECT e.id, e.fullName, e.firstName, e.lastName, e.phoneNumbers, e.aliases, e.tags,
               COALESCE(GROUP_CONCAT(j.name, ', '), '') AS positions
        FROM employees e
        LEFT JOIN employee_job_positions ej ON ej.employeeId = e.id
        LEFT JOIN job_positions j ON j.id = ej.positionId
        WHERE e.isArchived = 0
        GROUP BY e.id
        ORDER BY e.lastName COLLATE NOCASE, e.firstName COLLATE NOCASE
    """)
    fun observeSummaries(): Flow<List<EmployeeSummary>>

    @Query("SELECT * FROM employees WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<EmployeeEntity?>

    @Query("SELECT * FROM employees WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): EmployeeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<EmployeeEntity>)

    @Insert
    suspend fun insert(item: EmployeeEntity)

    @Update
    suspend fun update(item: EmployeeEntity)

    @Query("UPDATE employees SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)
}

data class EmployeeSummary(
    val id: String,
    val fullName: String,
    val firstName: String,
    val lastName: String,
    val phoneNumbers: String,
    val aliases: String,
    val tags: String,
    val positions: String,
)

@Dao
interface JobPositionDao {
    @Query("SELECT * FROM job_positions ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<JobPositionEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(items: List<JobPositionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun link(items: List<EmployeeJobPositionEntity>)

    @Query("DELETE FROM employee_job_positions WHERE employeeId = :employeeId")
    suspend fun deleteLinks(employeeId: String)
}

@Dao
interface ProductDao {
    @Query("SELECT COUNT(*) FROM products WHERE isArchived = 0")
    fun observeActiveCount(): Flow<Int>

    @Query("SELECT * FROM products WHERE isArchived = 0 ORDER BY name, variant")
    fun observeAll(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products WHERE isArchived = 0")
    suspend fun getAllNow(): List<ProductEntity>

    @Query("""
        SELECT p.*, COALESCE(s.quantity, 0.0) AS stockQuantity,
               COALESCE(s.isKnown, 0) AS stockKnown
        FROM products p
        LEFT JOIN stock_balances s
          ON s.productId = p.id AND s.warehouseId = :warehouseId
        WHERE p.isArchived = 0
        ORDER BY p.name, p.variant
    """)
    fun observeWithStock(warehouseId: String): Flow<List<ProductWithStock>>

    @Query("SELECT * FROM products WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<ProductEntity>)

    @Insert
    suspend fun insert(item: ProductEntity)

    @Update
    suspend fun update(item: ProductEntity)

    @Query("UPDATE products SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)
}

data class ProductWithStock(
    val id: String,
    val name: String,
    val variant: String?,
    val unit: String,
    val category: String,
    val groupName: String,
    val subgroupName: String,
    val aliases: String,
    val tags: String,
    val photoUri: String,
    val isReturnable: Boolean,
    val lowStockThreshold: Double,
    val repeatIssueWeeks: Int,
    val isArchived: Boolean,
    val stockQuantity: Double,
    val stockKnown: Boolean,
)

@Dao
interface WarehouseDao {
    @Query("SELECT COUNT(*) FROM warehouses")
    suspend fun count(): Int

    @Query("SELECT * FROM warehouses WHERE isArchived = 0 ORDER BY isMain DESC, name")
    fun observeAll(): Flow<List<WarehouseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<WarehouseEntity>)
}

@Dao
interface ShipyardDao {
    @Query("SELECT * FROM shipyards WHERE isArchived = 0 ORDER BY name")
    fun observeAll(): Flow<List<ShipyardEntity>>

    @Query("SELECT * FROM shipyards")
    suspend fun getAllNow(): List<ShipyardEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ShipyardEntity)

    @Query("UPDATE shipyards SET isArchived = 0 WHERE name = :name")
    suspend fun restore(name: String): Int

    @Query("UPDATE shipyards SET isArchived = 0 WHERE id = :id")
    suspend fun restoreById(id: String)

    @Query("UPDATE shipyards SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: String)

    @Query("UPDATE shipyards SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("UPDATE orders SET siteLabel = :newName WHERE siteLabel = :oldName")
    suspend fun renameOrderSiteLabels(oldName: String, newName: String)

    @Query("UPDATE orders SET recipientLabel = :newName WHERE employeeId IS NULL AND recipientLabel = :oldName")
    suspend fun renameOrderRecipients(oldName: String, newName: String)

    @Query("UPDATE stock_movements SET recipientLabel = :newName WHERE employeeId IS NULL AND recipientLabel = :oldName")
    suspend fun renameMovementRecipients(oldName: String, newName: String)

    @Query("DELETE FROM shipyard_leaders WHERE shipyardId = :shipyardId")
    suspend fun clearLeaders(shipyardId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLeaders(items: List<ShipyardLeaderEntity>)

    @Query("SELECT employeeId FROM shipyard_leaders WHERE shipyardId = :shipyardId")
    fun observeLeaderIds(shipyardId: String): Flow<List<String>>

    @Query("SELECT shipyardId, employeeId FROM shipyard_leaders")
    fun observeAllLeaderLinks(): Flow<List<ShipyardLeaderLink>>

    @Query("SELECT * FROM shipyard_stock_balances WHERE shipyardId = :shipyardId AND productId = :productId LIMIT 1")
    suspend fun findStock(shipyardId: String, productId: String): ShipyardStockBalanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStock(item: ShipyardStockBalanceEntity)

    @Query("""
        SELECT p.id AS productId, p.name, p.variant, p.unit, b.quantity
        FROM shipyard_stock_balances b
        JOIN products p ON p.id = b.productId
        WHERE b.shipyardId = :shipyardId AND b.quantity != 0
        ORDER BY p.name, p.variant
    """)
    fun observeStock(shipyardId: String): Flow<List<ShipyardStockItem>>
}

data class ShipyardStockItem(
    val productId: String,
    val name: String,
    val variant: String?,
    val unit: String,
    val quantity: Double,
)

data class ShipyardLeaderLink(
    val shipyardId: String,
    val employeeId: String,
)

@Dao
interface StockDao {
    @Query("SELECT COUNT(*) FROM stock_balances s JOIN warehouses w ON w.id = s.warehouseId JOIN products p ON p.id = s.productId WHERE s.quantity < 0 AND w.isArchived = 0 AND p.isArchived = 0")
    fun observeNegativeCount(): Flow<Int>

    @Query("""
        SELECT p.id AS productId, s.warehouseId, w.name AS warehouseName, p.name, p.variant, s.quantity, p.unit
        FROM stock_balances s
        JOIN products p ON p.id = s.productId
        JOIN warehouses w ON w.id = s.warehouseId
        WHERE s.quantity < 0 AND w.isArchived = 0 AND p.isArchived = 0
        ORDER BY p.name, p.variant
    """)
    fun observeNegativeItems(): Flow<List<NegativeStockItem>>

    @Query("SELECT * FROM stock_balances WHERE warehouseId = :warehouseId AND productId = :productId LIMIT 1")
    suspend fun find(warehouseId: String, productId: String): StockBalanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<StockBalanceEntity>)
}

data class NegativeStockItem(
    val productId: String,
    val warehouseId: String,
    val warehouseName: String,
    val name: String,
    val variant: String?,
    val quantity: Double,
    val unit: String,
)

@Dao
interface MovementDao {
    @Insert
    suspend fun insertMovement(item: StockMovementEntity)

    @Insert
    suspend fun insertLine(item: StockMovementLineEntity)

    @Insert
    suspend fun insertCustody(item: CustodyEntity)

    @Insert
    suspend fun insertAmendment(item: IssueAmendmentEntity)

    @Insert
    suspend fun insertIssueReturn(item: IssueReturnEntity)

    @Update
    suspend fun updateCustody(item: CustodyEntity)

    @Query("DELETE FROM custodies WHERE id = :id")
    suspend fun deleteCustody(id: String)

    @Query("SELECT * FROM custodies WHERE issuedMovementId = :movementId AND productId = :productId AND returnedDate IS NULL LIMIT 1")
    suspend fun findActiveCustody(movementId: String, productId: String): CustodyEntity?

    @Query("SELECT * FROM custodies WHERE employeeId = :employeeId AND productId = :productId AND returnedDate IS NULL ORDER BY issuedDate LIMIT 1")
    suspend fun findActiveCustodyForEmployee(employeeId: String, productId: String): CustodyEntity?

    @Query("SELECT * FROM custodies WHERE employeeId = :employeeId AND productId = :productId AND returnedDate IS NULL ORDER BY issuedDate")
    suspend fun findActiveCustodiesForEmployee(employeeId: String, productId: String): List<CustodyEntity>

    @Query("""
        SELECT m.id, m.type, m.effectiveDate, m.createdAtEpochMillis, m.note,
               COALESCE(w.name, '') AS warehouseName,
               COALESCE(GROUP_CONCAT(DISTINCT p.category), '') AS categories,
               COALESCE(GROUP_CONCAT(DISTINCT p.tags), '') AS tags,
               CASE
                   WHEN e.id IS NOT NULL THEN TRIM(e.lastName || ' ' || e.firstName)
                   WHEN TRIM(m.recipientLabel) != '' THEN m.recipientLabel
                   ELSE ''
               END AS recipient,
               COUNT(l.id) AS lineCount,
               COALESCE(GROUP_CONCAT(p.name || CASE WHEN p.variant IS NULL OR p.variant = '' THEN '' ELSE ' · ' || p.variant END, ' • '), '') AS itemSummary
        FROM stock_movements m
        LEFT JOIN warehouses w ON w.id = m.warehouseId
        LEFT JOIN employees e ON e.id = m.employeeId
        LEFT JOIN stock_movement_lines l ON l.movementId = m.id
        LEFT JOIN products p ON p.id = l.productId
        GROUP BY m.id
        ORDER BY m.effectiveDate DESC, m.createdAtEpochMillis DESC
    """)
    fun observeHistory(): Flow<List<HistoryEntry>>

    @Query("""
        SELECT l.id, l.productId, p.name AS productName, p.variant, l.quantityDelta, l.unit
        FROM stock_movement_lines l
        JOIN products p ON p.id = l.productId
        WHERE l.movementId = :movementId
        ORDER BY p.name, p.variant
    """)
    fun observeHistoryLines(movementId: String): Flow<List<HistoryLine>>

    @Query("""
        SELECT c.id, p.name AS productName, p.variant, c.quantity, p.unit, c.issuedDate
        FROM custodies c
        JOIN products p ON p.id = c.productId
        WHERE c.employeeId = :employeeId AND c.returnedDate IS NULL
        ORDER BY c.issuedDate DESC, p.name
    """)
    fun observeActiveCustody(employeeId: String): Flow<List<EmployeePossession>>

    @Query("""
        SELECT l.id AS lineId, m.id AS movementId, m.type AS movementType,
               COALESCE(a.replacementProductId, l.productId) AS productId,
               p.name AS productName, p.variant,
               CASE WHEN a.id IS NULL THEN -l.quantityDelta ELSE a.replacementQuantity END AS quantity,
               p.unit,
               COALESCE(a.replacementDate, m.effectiveDate) AS effectiveDate,
               p.repeatIssueWeeks,
               COALESCE(a.isDeleted, 0) AS isDeleted,
               CASE WHEN a.id IS NULL THEN 0 ELSE 1 END AS isAmended,
               COALESCE((SELECT SUM(r.quantity) FROM issue_returns r WHERE r.originalLineId = l.id), 0) AS returnedQuantity,
               (SELECT r.returnedDate FROM issue_returns r WHERE r.originalLineId = l.id ORDER BY r.returnedDate DESC, r.createdAtEpochMillis DESC LIMIT 1) AS lastReturnedDate
        FROM stock_movements m
        JOIN stock_movement_lines l ON l.movementId = m.id
        LEFT JOIN issue_amendments a ON a.id = (
            SELECT ia.id FROM issue_amendments ia
            WHERE ia.originalLineId = l.id
            ORDER BY ia.createdAtEpochMillis DESC, ia.id DESC LIMIT 1
        )
        JOIN products p ON p.id = COALESCE(a.replacementProductId, l.productId)
        WHERE m.employeeId = :employeeId AND m.type IN ('ISSUE', 'HISTORICAL_ISSUE_IMPORT')
        ORDER BY COALESCE(a.replacementDate, m.effectiveDate) DESC, m.createdAtEpochMillis DESC
    """)
    fun observeEmployeeIssues(employeeId: String): Flow<List<EmployeeIssue>>
}

data class EmployeePossession(
    val id: String,
    val productName: String,
    val variant: String?,
    val quantity: Double,
    val unit: String,
    val issuedDate: String,
)

data class HistoryEntry(
    val id: String,
    val type: String,
    val effectiveDate: String,
    val createdAtEpochMillis: Long,
    val note: String,
    val recipient: String,
    val lineCount: Int,
    val itemSummary: String,
    val warehouseName: String,
    val categories: String,
    val tags: String,
)

data class HistoryLine(
    val id: String,
    val productId: String,
    val productName: String,
    val variant: String?,
    val quantityDelta: Double,
    val unit: String,
)

data class EmployeeIssue(
    val lineId: String,
    val movementId: String,
    val movementType: String,
    val productId: String,
    val productName: String,
    val variant: String?,
    val quantity: Double,
    val unit: String,
    val effectiveDate: String,
    val repeatIssueWeeks: Int,
    val isDeleted: Boolean,
    val isAmended: Boolean,
    val returnedQuantity: Double,
    val lastReturnedDate: String?,
)

@Dao
interface LearningRuleDao {
    @Query("SELECT * FROM parser_learning_rules ORDER BY confirmations DESC, updatedAtEpochMillis DESC")
    fun observeAll(): Flow<List<ParserLearningRuleEntity>>

    @Query("SELECT * FROM parser_learning_rules WHERE triggerKey = :triggerKey AND ruleType = :ruleType LIMIT 1")
    suspend fun findByTrigger(triggerKey: String, ruleType: String): ParserLearningRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ParserLearningRuleEntity)

    @Query("DELETE FROM parser_learning_rules WHERE id = :id")
    suspend fun delete(id: String)
}

data class ProductSubgroupChoice(
    val name: String,
    val groupName: String,
)

@Dao
interface ProductDictionaryDao {
    @Query("SELECT name FROM product_groups UNION SELECT groupName AS name FROM products WHERE groupName != '' ORDER BY name")
    fun observeGroups(): Flow<List<String>>

    @Query("SELECT name, groupName FROM product_subgroups UNION SELECT subgroupName AS name, groupName FROM products WHERE subgroupName != '' ORDER BY name")
    fun observeSubgroups(): Flow<List<ProductSubgroupChoice>>

    @Query("SELECT name FROM product_categories UNION SELECT category AS name FROM products WHERE category != '' ORDER BY name")
    fun observeCategories(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(item: ProductGroupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubgroup(item: ProductSubgroupEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(item: ProductCategoryEntity)
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE status NOT IN ('ISSUED', 'CANCELLED') ORDER BY plannedIssueDate, createdAtEpochMillis")
    fun observeOpenOrders(): Flow<List<OrderEntity>>

    @Query("""
        SELECT o.id, o.employeeId, COALESCE(NULLIF(TRIM(e.lastName || ' ' || e.firstName), ''), o.recipientLabel) AS recipient, o.siteLabel,
               o.status, o.plannedIssueDate, o.createdAtEpochMillis,
               COUNT(l.id) AS lineCount,
               COALESCE(SUM(CASE WHEN l.isPrepared = 1 THEN 1 ELSE 0 END), 0) AS preparedCount,
               COALESCE(SUM(CASE WHEN l.productId IS NULL THEN 1 ELSE 0 END), 0) AS unmappedCount
        FROM orders o
        LEFT JOIN employees e ON e.id = o.employeeId
        LEFT JOIN order_lines l ON l.orderId = o.id
        WHERE o.status NOT IN ('ISSUED', 'CANCELLED')
        GROUP BY o.id
        ORDER BY o.plannedIssueDate, o.createdAtEpochMillis
    """)
    fun observeActiveSummaries(): Flow<List<OrderSummary>>

    @Query("SELECT * FROM orders WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): OrderEntity?

    @Query("""
        SELECT l.id, l.orderId, l.productId, l.rawText, l.quantity, l.unit, l.verificationStatus, l.isPrepared,
               p.name AS productName, p.variant AS productVariant,
               COALESCE(s.quantity, 0.0) AS stockQuantity
        FROM order_lines l
        LEFT JOIN products p ON p.id = l.productId
        LEFT JOIN stock_balances s ON s.productId = l.productId AND s.warehouseId = 'warehouse-main'
        WHERE l.orderId = :orderId
        ORDER BY l.rowid
    """)
    fun observeLines(orderId: String): Flow<List<OrderDetailLine>>

    @Query("SELECT * FROM order_changes WHERE orderId = :orderId ORDER BY createdAtEpochMillis DESC")
    fun observeChanges(orderId: String): Flow<List<OrderChangeEntity>>

    @Query("""
        SELECT l.id, l.orderId, l.productId, l.rawText, l.quantity, l.unit, l.verificationStatus, l.isPrepared,
               p.name AS productName, p.variant AS productVariant,
               COALESCE(s.quantity, 0.0) AS stockQuantity
        FROM order_lines l
        LEFT JOIN products p ON p.id = l.productId
        LEFT JOIN stock_balances s ON s.productId = l.productId AND s.warehouseId = 'warehouse-main'
        WHERE l.orderId = :orderId
        ORDER BY l.rowid
    """)
    suspend fun getLinesNow(orderId: String): List<OrderDetailLine>

    @Query("SELECT * FROM order_lines WHERE id = :lineId LIMIT 1")
    suspend fun findLineById(lineId: String): OrderLineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrders(items: List<OrderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLines(items: List<OrderLineEntity>)

    @Insert
    suspend fun insertChange(item: OrderChangeEntity)

    @Query("UPDATE orders SET employeeId = :employeeId, recipientLabel = :recipientLabel, plannedIssueDate = :date WHERE id = :orderId")
    suspend fun updateOrder(orderId: String, employeeId: String?, recipientLabel: String, date: String)

    @Query("UPDATE orders SET status = :status WHERE id = :orderId")
    suspend fun setStatus(orderId: String, status: String)

    @Query("UPDATE order_lines SET isPrepared = :prepared WHERE id = :lineId")
    suspend fun setPrepared(lineId: String, prepared: Boolean)

    @Query("UPDATE order_lines SET productId = :productId, rawText = :rawText, quantity = :quantity, unit = :unit, verificationStatus = :verificationStatus WHERE id = :lineId")
    suspend fun updateLine(lineId: String, productId: String?, rawText: String, quantity: Double, unit: String, verificationStatus: String)

    @Query("DELETE FROM order_lines WHERE id = :lineId")
    suspend fun deleteLine(lineId: String)

    @Query("SELECT COUNT(*) FROM order_lines WHERE orderId = :orderId AND isPrepared = 1")
    fun observePreparedCount(orderId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM order_lines WHERE orderId = :orderId")
    fun observeLineCount(orderId: String): Flow<Int>
}

data class OrderSummary(
    val id: String,
    val employeeId: String?,
    val recipient: String,
    val siteLabel: String?,
    val status: String,
    val plannedIssueDate: String,
    val createdAtEpochMillis: Long,
    val lineCount: Int,
    val preparedCount: Int,
    val unmappedCount: Int,
)

data class OrderDetailLine(
    val id: String,
    val orderId: String,
    val productId: String?,
    val rawText: String,
    val quantity: Double,
    val unit: String,
    val verificationStatus: String,
    val isPrepared: Boolean,
    val productName: String?,
    val productVariant: String?,
    val stockQuantity: Double,
)

@Dao
interface NotebookDao {
    @Query("""
        SELECT t.id, t.notebookId, t.text, t.isCompleted, t.dueDate, t.priority, t.place,
               t.employeeId, t.shipyardId, t.productId, t.orderId,
               n.createdAtEpochMillis,
               COALESCE(
                   GROUP_CONCAT(TRIM(COALESCE(te.lastName, '') || ' ' || COALESCE(te.firstName, ''))),
                   NULLIF(TRIM(COALESCE(e.lastName, '') || ' ' || COALESCE(e.firstName, '')), '')
               ) AS employeeName,
               COALESCE(GROUP_CONCAT(nte.employeeId), t.employeeId) AS employeeIds,
               s.name AS shipyardName,
               TRIM(COALESCE(p.name, '') || ' ' || COALESCE(p.variant, '')) AS productName,
               COALESCE(NULLIF(TRIM(oe.lastName || ' ' || oe.firstName), ''), o.recipientLabel) AS orderName
        FROM notebook_tasks t
        JOIN order_notebooks n ON n.id = t.notebookId
        LEFT JOIN employees e ON e.id = t.employeeId
        LEFT JOIN notebook_task_employees nte ON nte.taskId = t.id
        LEFT JOIN employees te ON te.id = nte.employeeId
        LEFT JOIN shipyards s ON s.id = t.shipyardId
        LEFT JOIN products p ON p.id = t.productId
        LEFT JOIN orders o ON o.id = t.orderId
        LEFT JOIN employees oe ON oe.id = o.employeeId
        WHERE n.status != 'ARCHIVED'
        GROUP BY t.id
        ORDER BY t.isCompleted, CASE WHEN t.dueDate IS NULL THEN 1 ELSE 0 END, t.dueDate, n.createdAtEpochMillis DESC, t.position
    """)
    fun observeTasks(): Flow<List<NotebookTaskView>>

    @Insert
    suspend fun insertNotebook(item: OrderNotebookEntity)

    @Insert
    suspend fun insertTasks(items: List<NotebookTaskEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTaskEmployees(items: List<NotebookTaskEmployeeEntity>)

    @Query("DELETE FROM notebook_task_employees WHERE taskId = :taskId")
    suspend fun deleteTaskEmployees(taskId: String)

    @Query("UPDATE notebook_tasks SET isCompleted = :completed WHERE id = :id")
    suspend fun setTaskCompleted(id: String, completed: Boolean)

    @Query("""
        UPDATE notebook_tasks SET text = :text, dueDate = :dueDate, priority = :priority, place = :place,
            employeeId = :employeeId, shipyardId = :shipyardId, productId = :productId, orderId = :orderId
        WHERE id = :id
    """)
    suspend fun updateTask(id: String, text: String, dueDate: String?, priority: String, place: String, employeeId: String?, shipyardId: String?, productId: String?, orderId: String?)

    @Query("DELETE FROM notebook_tasks WHERE id = :id")
    suspend fun deleteTask(id: String)
}

data class NotebookTaskView(
    val id: String,
    val notebookId: String,
    val text: String,
    val isCompleted: Boolean,
    val dueDate: String?,
    val priority: String,
    val place: String,
    val employeeId: String?,
    val employeeIds: String?,
    val shipyardId: String?,
    val productId: String?,
    val orderId: String?,
    val createdAtEpochMillis: Long,
    val employeeName: String?,
    val shipyardName: String?,
    val productName: String?,
    val orderName: String?,
)

@Dao
interface ImportDao {
    @Query("SELECT EXISTS(SELECT 1 FROM import_batches WHERE fileHash = :fileHash)")
    suspend fun wasFileImported(fileHash: String): Boolean

    @Query("SELECT * FROM import_batches WHERE fileHash = :fileHash LIMIT 1")
    suspend fun findBatchByHash(fileHash: String): ImportBatchEntity?

    @Insert
    suspend fun insertBatch(item: ImportBatchEntity)

    @Update
    suspend fun updateBatch(item: ImportBatchEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceRow(item: ImportSourceRowEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingRow(item: ImportPendingRowEntity)

    @Query("SELECT COUNT(*) FROM import_pending_rows")
    fun observePendingCount(): Flow<Int>

    @Query("""
        SELECT p.sourceKey, p.kind, p.sourceRowNumber, p.recipientFirstName,
               p.recipientLastName, p.recipientLabel, p.effectiveDate,
               p.rawProductName, p.quantity, b.sourceFileName
        FROM import_pending_rows p
        JOIN import_batches b ON b.id = p.batchId
        ORDER BY b.importedAtEpochMillis DESC, p.sourceRowNumber
    """)
    fun observePendingDetails(): Flow<List<PendingImportDetail>>

    @Query("SELECT EXISTS(SELECT 1 FROM import_source_rows WHERE sourceKey = :sourceKey)")
    suspend fun wasSourceRowImported(sourceKey: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM import_pending_rows WHERE sourceKey = :sourceKey)")
    suspend fun isSourceRowPending(sourceKey: String): Boolean

    @Query("DELETE FROM import_pending_rows WHERE sourceKey = :sourceKey")
    suspend fun deletePendingRow(sourceKey: String)
}

data class PendingImportDetail(
    val sourceKey: String,
    val kind: String,
    val sourceRowNumber: Int,
    val recipientFirstName: String,
    val recipientLastName: String,
    val recipientLabel: String,
    val effectiveDate: String,
    val rawProductName: String,
    val quantity: Long,
    val sourceFileName: String,
)
