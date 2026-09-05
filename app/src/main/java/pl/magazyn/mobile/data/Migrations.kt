package pl.magazyn.mobile.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        canonicalizeShipyard(db, "Ulstein - Elektro")
        canonicalizeShipyard(db, "Ulstein - Rura")
    }
}

private fun canonicalizeShipyard(db: SupportSQLiteDatabase, canonicalName: String) {
    val ids = mutableListOf<String>()
    db.query(
        "SELECT id FROM shipyards WHERE lower(trim(name)) = lower(trim(?)) ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, isArchived, rowid",
        arrayOf(canonicalName, canonicalName),
    ).use { cursor ->
        while (cursor.moveToNext()) ids += cursor.getString(0)
    }
    if (ids.isEmpty()) return
    val targetId = ids.first()
    ids.drop(1).forEach { sourceId ->
        db.execSQL(
            "UPDATE shipyard_stock_balances SET quantity = quantity + COALESCE((SELECT source.quantity FROM shipyard_stock_balances source WHERE source.shipyardId = ? AND source.productId = shipyard_stock_balances.productId), 0) WHERE shipyardId = ? AND EXISTS (SELECT 1 FROM shipyard_stock_balances source WHERE source.shipyardId = ? AND source.productId = shipyard_stock_balances.productId)",
            arrayOf(sourceId, targetId, sourceId),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO shipyard_stock_balances(shipyardId, productId, quantity) SELECT ?, productId, quantity FROM shipyard_stock_balances WHERE shipyardId = ?",
            arrayOf(targetId, sourceId),
        )
        db.execSQL(
            "INSERT OR IGNORE INTO shipyard_leaders(shipyardId, employeeId) SELECT ?, employeeId FROM shipyard_leaders WHERE shipyardId = ?",
            arrayOf(targetId, sourceId),
        )
        db.execSQL("DELETE FROM shipyards WHERE id = ?", arrayOf(sourceId))
    }
    db.execSQL("UPDATE shipyards SET name = ?, isArchived = 0 WHERE id = ?", arrayOf(canonicalName, targetId))
    db.execSQL("UPDATE orders SET siteLabel = ? WHERE siteLabel IS NOT NULL AND lower(trim(siteLabel)) = lower(trim(?))", arrayOf(canonicalName, canonicalName))
    db.execSQL("UPDATE orders SET recipientLabel = ? WHERE employeeId IS NULL AND lower(trim(recipientLabel)) = lower(trim(?))", arrayOf(canonicalName, canonicalName))
    db.execSQL("UPDATE stock_movements SET recipientLabel = ? WHERE employeeId IS NULL AND lower(trim(recipientLabel)) = lower(trim(?))", arrayOf(canonicalName, canonicalName))
}

val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS shipyard_leaders (shipyardId TEXT NOT NULL, employeeId TEXT NOT NULL, PRIMARY KEY(shipyardId, employeeId), FOREIGN KEY(shipyardId) REFERENCES shipyards(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(employeeId) REFERENCES employees(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shipyard_leaders_shipyardId ON shipyard_leaders(shipyardId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shipyard_leaders_employeeId ON shipyard_leaders(employeeId)")
    }
}

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE parser_learning_rules ADD COLUMN ruleType TEXT NOT NULL DEFAULT 'PRODUCT'")
        db.execSQL("ALTER TABLE parser_learning_rules ADD COLUMN resultExtra TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE parser_learning_rules ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("DROP INDEX IF EXISTS index_parser_learning_rules_triggerKey")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_parser_learning_rules_ruleType_triggerKey ON parser_learning_rules(ruleType, triggerKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS order_changes (id TEXT NOT NULL PRIMARY KEY, orderId TEXT NOT NULL, action TEXT NOT NULL, description TEXT NOT NULL, createdAtEpochMillis INTEGER NOT NULL, actorLabel TEXT NOT NULL, FOREIGN KEY(orderId) REFERENCES orders(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_order_changes_orderId ON order_changes(orderId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_order_changes_createdAtEpochMillis ON order_changes(createdAtEpochMillis)")
    }
}

val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Dawne mini-magazyny są stoczniami. Przenosimy ich stany bez zmiany magazynu głównego.
        db.execSQL(
            """
            INSERT OR IGNORE INTO shipyards(id, name, isArchived)
            SELECT 'shipyard-from-' || id, name, 0
            FROM warehouses w
            WHERE w.isMain = 0
              AND NOT EXISTS (SELECT 1 FROM shipyards s WHERE lower(trim(s.name)) = lower(trim(w.name)))
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE shipyard_stock_balances
            SET quantity = quantity + COALESCE((
                SELECT SUM(b.quantity)
                FROM stock_balances b
                JOIN warehouses w ON w.id = b.warehouseId
                JOIN shipyards s ON lower(trim(s.name)) = lower(trim(w.name))
                WHERE w.isMain = 0
                  AND s.id = shipyard_stock_balances.shipyardId
                  AND b.productId = shipyard_stock_balances.productId
            ), 0)
            WHERE EXISTS (
                SELECT 1 FROM stock_balances b
                JOIN warehouses w ON w.id = b.warehouseId
                JOIN shipyards s ON lower(trim(s.name)) = lower(trim(w.name))
                WHERE w.isMain = 0
                  AND s.id = shipyard_stock_balances.shipyardId
                  AND b.productId = shipyard_stock_balances.productId
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO shipyard_stock_balances(shipyardId, productId, quantity)
            SELECT s.id, b.productId, SUM(b.quantity)
            FROM stock_balances b
            JOIN warehouses w ON w.id = b.warehouseId AND w.isMain = 0
            JOIN shipyards s ON lower(trim(s.name)) = lower(trim(w.name))
            GROUP BY s.id, b.productId
            """.trimIndent(),
        )
        db.execSQL("DELETE FROM stock_balances WHERE warehouseId IN (SELECT id FROM warehouses WHERE isMain = 0)")
        db.execSQL("UPDATE warehouses SET isArchived = 1 WHERE isMain = 0")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS issue_amendments (id TEXT NOT NULL PRIMARY KEY, originalLineId TEXT NOT NULL, replacementProductId TEXT NOT NULL, replacementQuantity REAL NOT NULL, replacementDate TEXT NOT NULL, isDeleted INTEGER NOT NULL, createdAtEpochMillis INTEGER NOT NULL, FOREIGN KEY(originalLineId) REFERENCES stock_movement_lines(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(replacementProductId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_issue_amendments_originalLineId ON issue_amendments(originalLineId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_issue_amendments_replacementProductId ON issue_amendments(replacementProductId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_issue_amendments_createdAtEpochMillis ON issue_amendments(createdAtEpochMillis)")
        db.execSQL("CREATE TABLE IF NOT EXISTS parser_learning_rules (id TEXT NOT NULL PRIMARY KEY, triggerKey TEXT NOT NULL, sourceLabel TEXT NOT NULL, learnedName TEXT NOT NULL, learnedVariant TEXT, learnedUnit TEXT NOT NULL, confirmations INTEGER NOT NULL, updatedAtEpochMillis INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_parser_learning_rules_triggerKey ON parser_learning_rules(triggerKey)")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN photoUri TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_movements (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                warehouseId TEXT NOT NULL,
                employeeId TEXT,
                effectiveDate TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                note TEXT NOT NULL,
                FOREIGN KEY(warehouseId) REFERENCES warehouses(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(employeeId) REFERENCES employees(id) ON UPDATE NO ACTION ON DELETE SET NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_warehouseId ON stock_movements(warehouseId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_employeeId ON stock_movements(employeeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movements_effectiveDate ON stock_movements(effectiveDate)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS stock_movement_lines (
                id TEXT NOT NULL PRIMARY KEY,
                movementId TEXT NOT NULL,
                productId TEXT NOT NULL,
                quantityDelta REAL NOT NULL,
                unit TEXT NOT NULL,
                FOREIGN KEY(movementId) REFERENCES stock_movements(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(productId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movement_lines_movementId ON stock_movement_lines(movementId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_stock_movement_lines_productId ON stock_movement_lines(productId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS custodies (
                id TEXT NOT NULL PRIMARY KEY,
                employeeId TEXT NOT NULL,
                productId TEXT NOT NULL,
                quantity REAL NOT NULL,
                issuedMovementId TEXT NOT NULL,
                issuedDate TEXT NOT NULL,
                returnedDate TEXT,
                FOREIGN KEY(employeeId) REFERENCES employees(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(productId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(issuedMovementId) REFERENCES stock_movements(id) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_custodies_employeeId ON custodies(employeeId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_custodies_productId ON custodies(productId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_custodies_issuedMovementId ON custodies(issuedMovementId)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE employees ADD COLUMN firstName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE employees ADD COLUMN lastName TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE employees
            SET firstName = CASE
                    WHEN instr(trim(fullName), ' ') > 0
                    THEN substr(trim(fullName), 1, instr(trim(fullName), ' ') - 1)
                    ELSE trim(fullName)
                END,
                lastName = CASE
                    WHEN instr(trim(fullName), ' ') > 0
                    THEN substr(trim(fullName), instr(trim(fullName), ' ') + 1)
                    ELSE ''
                END
            """.trimIndent(),
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("UPDATE stock_balances SET quantity = ROUND(quantity)")
        db.execSQL("UPDATE stock_movement_lines SET quantityDelta = ROUND(quantityDelta)")
        db.execSQL("UPDATE custodies SET quantity = ROUND(quantity)")
        db.execSQL("UPDATE order_lines SET quantity = ROUND(quantity)")
        db.execSQL("UPDATE products SET lowStockThreshold = ROUND(lowStockThreshold)")
        db.execSQL("UPDATE employees SET aliases = REPLACE(REPLACE(aliases, '|', ', '), ';', ', ')")
        db.execSQL("UPDATE employees SET tags = REPLACE(REPLACE(tags, '|', ', '), ';', ', ')")
        db.execSQL("UPDATE products SET aliases = REPLACE(REPLACE(aliases, '|', ', '), ';', ', ')")
        db.execSQL("UPDATE products SET tags = REPLACE(REPLACE(tags, '|', ', '), ';', ', ')")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE stock_movements ADD COLUMN recipientLabel TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE stock_balances ADD COLUMN isKnown INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_batches (
                id TEXT NOT NULL PRIMARY KEY,
                kind TEXT NOT NULL,
                sourceFileName TEXT NOT NULL,
                fileHash TEXT NOT NULL,
                importedAtEpochMillis INTEGER NOT NULL,
                totalRows INTEGER NOT NULL,
                importedRows INTEGER NOT NULL,
                pendingRows INTEGER NOT NULL,
                skippedRows INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_import_batches_fileHash ON import_batches(fileHash)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_source_rows (
                sourceKey TEXT NOT NULL PRIMARY KEY,
                batchId TEXT NOT NULL,
                kind TEXT NOT NULL,
                sourceRowNumber INTEGER NOT NULL,
                FOREIGN KEY(batchId) REFERENCES import_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_import_source_rows_batchId ON import_source_rows(batchId)")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_pending_rows (
                sourceKey TEXT NOT NULL PRIMARY KEY,
                batchId TEXT NOT NULL,
                kind TEXT NOT NULL,
                sourceRowNumber INTEGER NOT NULL,
                recipientFirstName TEXT NOT NULL,
                recipientLastName TEXT NOT NULL,
                recipientLabel TEXT NOT NULL,
                effectiveDate TEXT NOT NULL,
                rawProductName TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                FOREIGN KEY(batchId) REFERENCES import_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_import_pending_rows_batchId ON import_pending_rows(batchId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_import_pending_rows_kind ON import_pending_rows(kind)")

        // Usuwa wyłącznie rozpoznawalne rekordy demonstracyjne, o ile użytkownik nie wykonał na nich własnych ruchów.
        db.execSQL("DELETE FROM order_lines WHERE orderId = 'order-104'")
        db.execSQL("DELETE FROM orders WHERE id = 'order-104'")
        db.execSQL(
            """
            DELETE FROM stock_balances
            WHERE productId IN ('product-coverall-welding-48', 'product-shoes-welding-44')
              AND NOT EXISTS (SELECT 1 FROM stock_movement_lines l WHERE l.productId = stock_balances.productId)
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM products
            WHERE id IN ('product-coverall-welding-48', 'product-shoes-welding-44')
              AND NOT EXISTS (SELECT 1 FROM stock_movement_lines l WHERE l.productId = products.id)
              AND NOT EXISTS (SELECT 1 FROM order_lines o WHERE o.productId = products.id)
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM employee_job_positions
            WHERE employeeId IN ('employee-lukasz-wojdylo', 'employee-adam-pawlak')
              AND NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.employeeId = employee_job_positions.employeeId)
            """.trimIndent(),
        )
        db.execSQL(
            """
            DELETE FROM employees
            WHERE id IN ('employee-lukasz-wojdylo', 'employee-adam-pawlak')
              AND NOT EXISTS (SELECT 1 FROM stock_movements m WHERE m.employeeId = employees.id)
              AND NOT EXISTS (SELECT 1 FROM orders o WHERE o.employeeId = employees.id)
            """.trimIndent(),
        )
        db.execSQL("DELETE FROM job_positions WHERE id IN ('position-welder', 'position-pipefitter') AND NOT EXISTS (SELECT 1 FROM employee_job_positions e WHERE e.positionId = job_positions.id)")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE products ADD COLUMN groupName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE products ADD COLUMN subgroupName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE products ADD COLUMN repeatIssueWeeks INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS product_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_groups_name ON product_groups(name)")
        db.execSQL("CREATE TABLE IF NOT EXISTS product_subgroups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, groupName TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_subgroups_name_groupName ON product_subgroups(name, groupName)")
        db.execSQL("CREATE TABLE IF NOT EXISTS product_categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_product_categories_name ON product_categories(name)")
        db.execSQL("INSERT OR IGNORE INTO product_categories(id, name) SELECT 'category-' || lower(replace(trim(category), ' ', '-')), trim(category) FROM products WHERE trim(category) != ''")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS shipyards (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, isArchived INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_shipyards_name ON shipyards(name)")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS shipyard_stock_balances (
                shipyardId TEXT NOT NULL,
                productId TEXT NOT NULL,
                quantity REAL NOT NULL,
                PRIMARY KEY(shipyardId, productId),
                FOREIGN KEY(shipyardId) REFERENCES shipyards(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(productId) REFERENCES products(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shipyard_stock_balances_shipyardId ON shipyard_stock_balances(shipyardId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_shipyard_stock_balances_productId ON shipyard_stock_balances(productId)")
        db.execSQL(
            """
            INSERT OR REPLACE INTO shipyard_stock_balances(shipyardId, productId, quantity)
            SELECT s.id, l.productId, SUM(-l.quantityDelta)
            FROM shipyards s
            JOIN stock_movements m ON m.recipientLabel = s.name AND m.type = 'SHIPYARD_ISSUE'
            JOIN stock_movement_lines l ON l.movementId = m.id
            GROUP BY s.id, l.productId
            """.trimIndent(),
        )
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE employees ADD COLUMN phoneNumbers TEXT NOT NULL DEFAULT ''")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE order_notebooks ADD COLUMN detectedType TEXT NOT NULL DEFAULT 'NOTE'")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notebook_tasks (
                id TEXT NOT NULL PRIMARY KEY,
                notebookId TEXT NOT NULL,
                text TEXT NOT NULL,
                isCompleted INTEGER NOT NULL,
                position INTEGER NOT NULL,
                FOREIGN KEY(notebookId) REFERENCES order_notebooks(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_notebook_tasks_notebookId ON notebook_tasks(notebookId)")

        // Naprawia wcześniejsze importy stoczni: odtwarza odbiorców i ich stan bez zmiany magazynu głównego.
        db.execSQL(
            """
            INSERT OR IGNORE INTO shipyards(id, name, isArchived)
            SELECT 'shipyard-migrated-' || lower(replace(trim(recipientLabel), ' ', '-')), trim(recipientLabel), 0
            FROM stock_movements
            WHERE type = 'HISTORICAL_ISSUE_IMPORT' AND employeeId IS NULL AND trim(recipientLabel) != ''
            GROUP BY trim(recipientLabel)
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TEMP TABLE historical_shipyard_totals AS
            SELECT s.id AS shipyardId, l.productId AS productId, SUM(-l.quantityDelta) AS quantity
            FROM shipyards s
            JOIN stock_movements m ON m.recipientLabel = s.name
            JOIN stock_movement_lines l ON l.movementId = m.id
            WHERE m.type = 'HISTORICAL_ISSUE_IMPORT' AND m.employeeId IS NULL
            GROUP BY s.id, l.productId
            """.trimIndent(),
        )
        db.execSQL(
            """
            UPDATE shipyard_stock_balances
            SET quantity = quantity + (
                SELECT t.quantity FROM historical_shipyard_totals t
                WHERE t.shipyardId = shipyard_stock_balances.shipyardId
                  AND t.productId = shipyard_stock_balances.productId
            )
            WHERE EXISTS (
                SELECT 1 FROM historical_shipyard_totals t
                WHERE t.shipyardId = shipyard_stock_balances.shipyardId
                  AND t.productId = shipyard_stock_balances.productId
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO shipyard_stock_balances(shipyardId, productId, quantity)
            SELECT shipyardId, productId, quantity FROM historical_shipyard_totals
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE historical_shipyard_totals")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notebook_tasks ADD COLUMN dueDate TEXT")
        db.execSQL("ALTER TABLE notebook_tasks ADD COLUMN priority TEXT NOT NULL DEFAULT 'NORMAL'")
        db.execSQL("ALTER TABLE notebook_tasks ADD COLUMN employeeId TEXT")
        db.execSQL("ALTER TABLE notebook_tasks ADD COLUMN shipyardId TEXT")
        db.execSQL("ALTER TABLE notebook_tasks ADD COLUMN productId TEXT")
        db.execSQL("ALTER TABLE notebook_tasks ADD COLUMN orderId TEXT")
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS issue_returns (
                id TEXT NOT NULL PRIMARY KEY,
                originalLineId TEXT NOT NULL,
                quantity REAL NOT NULL,
                returnedDate TEXT NOT NULL,
                createdAtEpochMillis INTEGER NOT NULL,
                FOREIGN KEY(originalLineId) REFERENCES stock_movement_lines(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_issue_returns_originalLineId ON issue_returns(originalLineId)")
        db.execSQL(
            """
            INSERT OR IGNORE INTO issue_returns(id, originalLineId, quantity, returnedDate, createdAtEpochMillis)
            SELECT 'migrated-return-' || c.id, l.id, c.quantity, c.returnedDate, 0
            FROM custodies c
            JOIN stock_movement_lines l ON l.movementId = c.issuedMovementId AND l.productId = c.productId
            WHERE c.returnedDate IS NOT NULL
            """.trimIndent(),
        )
    }
}
