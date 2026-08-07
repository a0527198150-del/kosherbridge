package com.example.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// --- Room Entities ---

@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val isSystem: Boolean = false,
    val budgetLimit: Double = 0.0 // 0.0 means no budget is defined
)

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val isExpense: Boolean = true,
    val categoryName: String,
    val paymentType: String, // "CASH" or "CREDIT"
    val timestamp: Long,
    val hebrewDay: Int,
    val hebrewMonthIndex: Int,
    val hebrewMonthName: String,
    val hebrewYear: Int,
    val hebrewYearString: String,
    val rawText: String? = null,
    val isAnomalous: Boolean = false
)

@Entity(tableName = "recurring_rules")
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val amount: Double,
    val isExpense: Boolean,
    val categoryName: String,
    val paymentType: String, // "CASH" or "CREDIT"
    val dayOfMonth: Int, // Gregorian day of month (1-28) on which the transaction is generated
    val isActive: Boolean = true,
    val lastGeneratedPeriodKey: String? = null, // "yyyy-MM" of the last calendar month this rule generated a transaction for
    val reminderEnabled: Boolean = true // whether a reminder (notification + in-app banner) fires 2 days before this rule posts
)

// --- DAOs (Data Access Objects) ---

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY id ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CategoryEntity): Long

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun getCategoryByName(name: String): CategoryEntity?

    @Query("UPDATE categories SET budgetLimit = :limit WHERE id = :id")
    suspend fun updateCategoryBudget(id: Int, limit: Double)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE hebrewYear = :year AND hebrewMonthIndex = :monthIndex ORDER BY timestamp DESC")
    fun getTransactionsForHebrewMonth(year: Int, monthIndex: Int): Flow<List<TransactionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Delete
    suspend fun deleteTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Int)
}

@Dao
interface RecurringRuleDao {
    @Query("SELECT * FROM recurring_rules ORDER BY id ASC")
    fun getAllRecurringRules(): Flow<List<RecurringRuleEntity>>

    @Insert
    suspend fun insertRecurringRule(rule: RecurringRuleEntity): Long

    @Update
    suspend fun updateRecurringRule(rule: RecurringRuleEntity)

    @Delete
    suspend fun deleteRecurringRule(rule: RecurringRuleEntity)
}

// --- Room Database ---

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `recurring_rules` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `title` TEXT NOT NULL,
                `amount` REAL NOT NULL,
                `isExpense` INTEGER NOT NULL,
                `categoryName` TEXT NOT NULL,
                `paymentType` TEXT NOT NULL,
                `dayOfMonth` INTEGER NOT NULL,
                `isActive` INTEGER NOT NULL,
                `lastGeneratedPeriodKey` TEXT
            )
            """.trimIndent()
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN isAnomalous INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE recurring_rules ADD COLUMN reminderEnabled INTEGER NOT NULL DEFAULT 1")
    }
}

@Database(entities = [CategoryEntity::class, TransactionEntity::class, RecurringRuleEntity::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun recurringRuleDao(): RecurringRuleDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hebrew_budget_db"
                )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- Repository ---

class BudgetRepository(private val db: AppDatabase) {
    val allCategories: Flow<List<CategoryEntity>> = db.categoryDao().getAllCategories()
    val allTransactions: Flow<List<TransactionEntity>> = db.transactionDao().getAllTransactions()
    val allRecurringRules: Flow<List<RecurringRuleEntity>> = db.recurringRuleDao().getAllRecurringRules()

    suspend fun insertRecurringRule(rule: RecurringRuleEntity) {
        db.recurringRuleDao().insertRecurringRule(rule)
    }

    suspend fun updateRecurringRule(rule: RecurringRuleEntity) {
        db.recurringRuleDao().updateRecurringRule(rule)
    }

    suspend fun deleteRecurringRule(rule: RecurringRuleEntity) {
        db.recurringRuleDao().deleteRecurringRule(rule)
    }

    fun getTransactionsForHebrewMonth(year: Int, monthIndex: Int): Flow<List<TransactionEntity>> {
        return db.transactionDao().getTransactionsForHebrewMonth(year, monthIndex)
    }

    suspend fun insertTransaction(transaction: TransactionEntity) {
        db.transactionDao().insertTransaction(transaction)
    }

    suspend fun deleteTransaction(transaction: TransactionEntity) {
        db.transactionDao().deleteTransaction(transaction)
    }

    suspend fun deleteTransactionById(id: Int) {
        db.transactionDao().deleteTransactionById(id)
    }

    suspend fun insertCategory(category: CategoryEntity): Boolean {
        val rowId = db.categoryDao().insertCategory(category)
        return rowId != -1L
    }

    suspend fun deleteCategory(category: CategoryEntity) {
        db.categoryDao().deleteCategory(category)
    }

    suspend fun updateCategoryBudget(id: Int, limit: Double) {
        db.categoryDao().updateCategoryBudget(id, limit)
    }

    suspend fun checkAndPrepopulateCategories() {
        val defaultCategories = listOf(
            CategoryEntity(name = "אוכל קנוי בברכל", isSystem = true),
            CategoryEntity(name = "אוכל מוכן", isSystem = true),
            CategoryEntity(name = "סלולר", isSystem = true),
            CategoryEntity(name = "ביגוד", isSystem = true),
            CategoryEntity(name = "מגורים", isSystem = true),
            CategoryEntity(name = "בריאות", isSystem = true),
            CategoryEntity(name = "תחבורה", isSystem = true),
            CategoryEntity(name = "הכנסות", isSystem = true),
            CategoryEntity(name = "אחר", isSystem = true)
        )
        for (category in defaultCategories) {
            db.categoryDao().insertCategory(category)
        }
    }
}
