package pl.magazyn.mobile.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WarehouseEntity::class,
        EmployeeEntity::class,
        JobPositionEntity::class,
        EmployeeJobPositionEntity::class,
        ProductEntity::class,
        ProductGroupEntity::class,
        ProductSubgroupEntity::class,
        ProductCategoryEntity::class,
        ShipyardEntity::class,
        ShipyardLeaderEntity::class,
        ShipyardStockBalanceEntity::class,
        StockBalanceEntity::class,
        StockMovementEntity::class,
        StockMovementLineEntity::class,
        CustodyEntity::class,
        ImportBatchEntity::class,
        ImportSourceRowEntity::class,
        ImportPendingRowEntity::class,
        OrderNotebookEntity::class,
        NotebookTaskEntity::class,
        OrderEntity::class,
        OrderLineEntity::class,
        IssueAmendmentEntity::class,
        IssueReturnEntity::class,
        ParserLearningRuleEntity::class,
        OrderChangeEntity::class,
    ],
    version = 17,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun employeeDao(): EmployeeDao
    abstract fun jobPositionDao(): JobPositionDao
    abstract fun productDao(): ProductDao
    abstract fun productDictionaryDao(): ProductDictionaryDao
    abstract fun warehouseDao(): WarehouseDao
    abstract fun shipyardDao(): ShipyardDao
    abstract fun stockDao(): StockDao
    abstract fun movementDao(): MovementDao
    abstract fun orderDao(): OrderDao
    abstract fun notebookDao(): NotebookDao
    abstract fun importDao(): ImportDao
    abstract fun learningRuleDao(): LearningRuleDao
}
