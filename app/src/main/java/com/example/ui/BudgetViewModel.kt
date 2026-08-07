package com.example.ui

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.auth.GoogleAuthManager
import com.example.data.*
import com.example.reminders.ReminderReceiver
import com.example.reminders.ReminderScheduler
import com.example.ui.theme.ThemeMode
import com.example.widget.BalanceWidget
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar

class BudgetViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = BudgetRepository(database)

    // Google account sign-in + cloud backup/sync
    private val authManager = GoogleAuthManager(application)
    private val cloudSync = CloudSyncManager(application)

    // Preferences store for app-wide settings that aren't per-category (e.g. overall monthly budget cap)
    private val appPrefs = application.getSharedPreferences("hebrew_budget_prefs", Context.MODE_PRIVATE)

    // Overall monthly budget cap (0.0 means no cap is defined), independent of per-category limits
    private val _monthlyBudgetLimit = MutableStateFlow(appPrefs.getFloat(KEY_MONTHLY_BUDGET_LIMIT, 0f).toDouble())
    val monthlyBudgetLimit = _monthlyBudgetLimit.asStateFlow()

    // Calendar mode: whether periods/months are grouped by Hebrew month or Gregorian billing cycle
    private val _calendarMode = MutableStateFlow(
        if (appPrefs.getString(KEY_CALENDAR_MODE, "HEBREW") == "GREGORIAN") CalendarMode.GREGORIAN else CalendarMode.HEBREW
    )
    val calendarMode = _calendarMode.asStateFlow()

    // App display theme: light / dark / follow system
    private val _themeMode = MutableStateFlow(
        when (appPrefs.getString(KEY_THEME_MODE, "SYSTEM")) {
            "LIGHT" -> ThemeMode.LIGHT
            "DARK" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    )
    val themeMode = _themeMode.asStateFlow()

    // Day of month (1-28) on which the Gregorian billing cycle starts (relevant only in GREGORIAN mode)
    private val _gregorianCycleStartDay = MutableStateFlow(appPrefs.getInt(KEY_GREGORIAN_START_DAY, 1))
    val gregorianCycleStartDay = _gregorianCycleStartDay.asStateFlow()

    // Time of day the recurring-rule "2 days before" reminder notification fires
    private val _reminderHour = MutableStateFlow(appPrefs.getInt(ReminderReceiver.KEY_REMINDER_HOUR, ReminderReceiver.DEFAULT_HOUR))
    val reminderHour = _reminderHour.asStateFlow()

    private val _reminderMinute = MutableStateFlow(appPrefs.getInt(ReminderReceiver.KEY_REMINDER_MINUTE, ReminderReceiver.DEFAULT_MINUTE))
    val reminderMinute = _reminderMinute.asStateFlow()

    // Recurring monthly income/expense rules
    val recurringRules: StateFlow<List<RecurringRuleEntity>> = repository.allRecurringRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Recurring rules whose "2 days before" reminder is due today, for the in-app banner
    val upcomingReminders: StateFlow<List<RecurringRuleEntity>> = recurringRules
        .map { rules -> ReminderHelper.rulesDueToday(rules) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Exposed lists
    val categories: StateFlow<List<CategoryEntity>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTransactions: StateFlow<List<TransactionEntity>> = repository.allTransactions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently selected Hebrew month and year for filtering
    private val _selectedHebrewMonthIndex = MutableStateFlow(0)
    val selectedHebrewMonthIndex = _selectedHebrewMonthIndex.asStateFlow()

    private val _selectedHebrewMonthName = MutableStateFlow("")
    val selectedHebrewMonthName = _selectedHebrewMonthName.asStateFlow()

    private val _selectedHebrewYear = MutableStateFlow(0)
    val selectedHebrewYear = _selectedHebrewYear.asStateFlow()

    private val _selectedHebrewYearString = MutableStateFlow("")
    val selectedHebrewYearString = _selectedHebrewYearString.asStateFlow()

    // Gemini Parsing State
    private val _isParsing = MutableStateFlow(false)
    val isParsing = _isParsing.asStateFlow()

    private val _parseError = MutableStateFlow<String?>(null)
    val parseError = _parseError.asStateFlow()

    private val _parsedDraft = MutableStateFlow<ParsedTransaction?>(null)
    val parsedDraft = _parsedDraft.asStateFlow()

    init {
        // Run pre-population of default categories
        viewModelScope.launch {
            repository.checkAndPrepopulateCategories()
            
            // Set default selected period to the current period in whichever calendar mode is active
            resetSelectionToCurrentPeriod()

            // Auto-generate any due recurring income/expense transactions
            checkAndGenerateRecurringTransactions()

            // Make sure the daily reminder alarm is (re)scheduled every time the app launches
            ReminderScheduler.scheduleDailyCheck(getApplication(), _reminderHour.value, _reminderMinute.value)
        }

        // Automatic cloud backup: whenever the local data changes, wait a short quiet period
        // and then push a fresh snapshot to Firestore if the user is signed in. This way the
        // backup stays up to date even if the phone is lost or reset without pressing the
        // "גיבוי לענן" button.
        viewModelScope.launch {
            combine(
                repository.allCategories,
                repository.allTransactions,
                repository.allRecurringRules
            ) { cats, txs, rules -> Triple(cats, txs, rules) }
                .debounce(30_000)
                .collect {
                    autoBackupIfSignedIn()
                }
        }
    }

    // Silent cloud backup used by the automatic watcher. Never touches _syncState so it
    // doesn't disturb the UI - errors are simply retried on the next data change.
    private suspend fun autoBackupIfSignedIn() {
        val user = _authUser.value ?: return
        try {
            cloudSync.backup(user.uid)
        } catch (e: Exception) {
            // Offline / transient errors: silently skip, the next change retries.
        }
    }

    private fun resetSelectionToCurrentPeriod() {
        if (_calendarMode.value == CalendarMode.HEBREW) {
            val current = HebrewCalendarHelper.getHebrewDateInfo(System.currentTimeMillis())
            _selectedHebrewMonthIndex.value = current.monthIndex
            _selectedHebrewMonthName.value = current.monthName
            _selectedHebrewYear.value = current.year
            _selectedHebrewYearString.value = current.yearHebrewString
        } else {
            val cycle = GregorianCycleHelper.getCycleInfo(System.currentTimeMillis(), _gregorianCycleStartDay.value)
            _selectedHebrewMonthIndex.value = cycle.month
            _selectedHebrewMonthName.value = cycle.monthName
            _selectedHebrewYear.value = cycle.year
            _selectedHebrewYearString.value = cycle.year.toString()
        }
    }

    // Returns (year, month-bucket) for a given timestamp, in terms of whichever calendar mode is currently active
    private fun periodBucketFor(timestamp: Long): Pair<Int, Int> {
        return if (_calendarMode.value == CalendarMode.HEBREW) {
            val h = HebrewCalendarHelper.getHebrewDateInfo(timestamp)
            h.year to h.monthIndex
        } else {
            val c = GregorianCycleHelper.getCycleInfo(timestamp, _gregorianCycleStartDay.value)
            c.year to c.month
        }
    }

    private suspend fun checkAndGenerateRecurringTransactions() {
        val rules = repository.allRecurringRules.first()
        if (rules.isEmpty()) return

        val nowCal = Calendar.getInstance()
        val currentPeriodKey = "${nowCal.get(Calendar.YEAR)}-${nowCal.get(Calendar.MONTH) + 1}"
        val todayDay = nowCal.get(Calendar.DAY_OF_MONTH)

        for (rule in rules) {
            if (!rule.isActive) continue
            if (rule.lastGeneratedPeriodKey == currentPeriodKey) continue
            if (todayDay < rule.dayOfMonth) continue

            val txCal = Calendar.getInstance()
            val maxDay = txCal.getActualMaximum(Calendar.DAY_OF_MONTH)
            txCal.set(Calendar.DAY_OF_MONTH, rule.dayOfMonth.coerceAtMost(maxDay))
            val timestamp = txCal.timeInMillis
            val hebrewInfo = HebrewCalendarHelper.getHebrewDateInfo(timestamp)

            val entity = TransactionEntity(
                title = rule.title,
                amount = rule.amount,
                isExpense = rule.isExpense,
                categoryName = rule.categoryName,
                paymentType = rule.paymentType,
                timestamp = timestamp,
                hebrewDay = hebrewInfo.day,
                hebrewMonthIndex = hebrewInfo.monthIndex,
                hebrewMonthName = hebrewInfo.monthName,
                hebrewYear = hebrewInfo.year,
                hebrewYearString = hebrewInfo.yearHebrewString,
                rawText = "רשומה קבועה אוטומטית"
            )
            repository.insertTransaction(entity)
            repository.updateRecurringRule(rule.copy(lastGeneratedPeriodKey = currentPeriodKey))
        }
        BalanceWidget.refreshAll(getApplication())
    }

    // Filtered transactions for the selected period (Hebrew month, or Gregorian billing cycle)
    val filteredTransactions: StateFlow<List<TransactionEntity>> = combine(
        allTransactions,
        _selectedHebrewMonthIndex,
        _selectedHebrewYear,
        _calendarMode,
        _gregorianCycleStartDay
    ) { transactions, month, year, mode, startDay ->
        transactions.filter { tx ->
            if (mode == CalendarMode.HEBREW) {
                tx.hebrewMonthIndex == month && tx.hebrewYear == year
            } else {
                val cycle = GregorianCycleHelper.getCycleInfo(tx.timestamp, startDay)
                cycle.month == month && cycle.year == year
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // List of available periods (Hebrew month+year, or Gregorian billing cycle month+year) across all transactions, plus current period
    val availableMonths: StateFlow<List<HebrewMonthYearOption>> = combine(
        allTransactions,
        _calendarMode,
        _gregorianCycleStartDay
    ) { list, mode, startDay ->
        if (mode == CalendarMode.HEBREW) {
            val current = HebrewCalendarHelper.getHebrewDateInfo(System.currentTimeMillis())
            val currentOption = HebrewMonthYearOption(
                monthIndex = current.monthIndex,
                monthName = current.monthName,
                year = current.year,
                yearString = current.yearHebrewString
            )

            val optionsFromTx = list.map {
                HebrewMonthYearOption(
                    monthIndex = it.hebrewMonthIndex,
                    monthName = it.hebrewMonthName,
                    year = it.hebrewYear,
                    yearString = it.hebrewYearString
                )
            }.distinct()

            (optionsFromTx + currentOption).distinctBy { "${it.year}_${it.monthIndex}" }
                .sortedWith(compareBy<HebrewMonthYearOption> { it.year }.thenBy { it.monthIndex })
        } else {
            val currentCycle = GregorianCycleHelper.getCycleInfo(System.currentTimeMillis(), startDay)
            val currentOption = HebrewMonthYearOption(
                monthIndex = currentCycle.month,
                monthName = currentCycle.monthName,
                year = currentCycle.year,
                yearString = currentCycle.year.toString()
            )

            val optionsFromTx = list.map {
                val cycle = GregorianCycleHelper.getCycleInfo(it.timestamp, startDay)
                HebrewMonthYearOption(
                    monthIndex = cycle.month,
                    monthName = cycle.monthName,
                    year = cycle.year,
                    yearString = cycle.year.toString()
                )
            }.distinct()

            (optionsFromTx + currentOption).distinctBy { "${it.year}_${it.monthIndex}" }
                .sortedWith(compareBy<HebrewMonthYearOption> { it.year }.thenBy { it.monthIndex })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Calculations for the current selected month (anomalous/unusual transactions are excluded from all totals)
    val monthlyStats: StateFlow<MonthlyStats> = filteredTransactions.map { list ->
        var totalIncome = 0.0
        var totalExpense = 0.0
        var cashExpense = 0.0
        var creditExpense = 0.0
        var cashIncome = 0.0
        var creditIncome = 0.0
        var anomalousExpenseTotal = 0.0
        var anomalousIncomeTotal = 0.0

        for (tx in list) {
            val amount = tx.amount
            if (tx.isAnomalous) {
                if (tx.isExpense) anomalousExpenseTotal += amount else anomalousIncomeTotal += amount
                continue
            }
            if (tx.isExpense) {
                totalExpense += amount
                if (tx.paymentType == "CASH") cashExpense += amount else creditExpense += amount
            } else {
                totalIncome += amount
                if (tx.paymentType == "CASH") cashIncome += amount else creditIncome += amount
            }
        }

        MonthlyStats(
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            cashExpense = cashExpense,
            creditExpense = creditExpense,
            cashIncome = cashIncome,
            creditIncome = creditIncome,
            netBalance = totalIncome - totalExpense,
            anomalousExpenseTotal = anomalousExpenseTotal,
            anomalousIncomeTotal = anomalousIncomeTotal
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthlyStats())

    // Groups the 4 period-defining flows into one, since combine() only has typed overloads up to 5 flows
    private data class PeriodInfo(val month: Int, val year: Int, val mode: CalendarMode, val startDay: Int)

    private val periodInfo: StateFlow<PeriodInfo> = combine(
        _selectedHebrewMonthIndex,
        _selectedHebrewYear,
        _calendarMode,
        _gregorianCycleStartDay
    ) { month, year, mode, startDay -> PeriodInfo(month, year, mode, startDay) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PeriodInfo(0, 0, CalendarMode.HEBREW, 1))

    // Spending-pace forecast for the currently selected period (only meaningful while that period is ongoing)
    val spendingForecast: StateFlow<SpendingForecast> = combine(
        filteredTransactions,
        recurringRules,
        periodInfo
    ) { list, rules, info ->
        computeForecast(list, info.month, info.year, info.mode, info.startDay, rules)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SpendingForecast())

    private fun computeForecast(
        transactions: List<TransactionEntity>,
        month: Int,
        year: Int,
        mode: CalendarMode,
        startDay: Int,
        rules: List<RecurringRuleEntity>
    ): SpendingForecast {
        val now = System.currentTimeMillis()
        val (currentYear, currentMonth) = periodBucketFor(now)

        // The forecast only makes sense for the period that is actually running right now
        if (year != currentYear || month != currentMonth) {
            return SpendingForecast(hasData = false)
        }

        val periodStart: Long
        val totalDays: Int
        if (mode == CalendarMode.HEBREW) {
            periodStart = HebrewCalendarHelper.getHebrewMonthStartTimestamp(year, month)
            totalDays = HebrewCalendarHelper.getDaysInHebrewMonth(year, month)
        } else {
            periodStart = GregorianCycleHelper.getCycleStartTimestamp(year, month, startDay)
            totalDays = GregorianCycleHelper.getDaysInCycle(year, month, startDay)
        }

        val msPerDay = 24L * 60 * 60 * 1000
        val daysElapsed = (((now - periodStart) / msPerDay).toInt() + 1).coerceIn(1, totalDays)

        // Anomalous transactions are excluded from the rate calculation, consistent with the rest of the app
        val actualExpenseSoFar = transactions.filter { !it.isAnomalous && it.isExpense }.sumOf { it.amount }
        val actualIncomeSoFar = transactions.filter { !it.isAnomalous && !it.isExpense }.sumOf { it.amount }

        val dailyRate = actualExpenseSoFar / daysElapsed
        val projectedExpense = dailyRate * totalDays

        val activeIncomeRules = rules.filter { it.isActive && !it.isExpense }
        val hasRecurringIncome = activeIncomeRules.isNotEmpty()

        // For each active recurring income rule, only count it toward the projection if it hasn't
        // already generated a transaction this period (otherwise it would be double-counted)
        var projectedRemainingIncome = 0.0
        for (rule in activeIncomeRules) {
            val alreadyGenerated = transactions.any {
                !it.isExpense && it.title == rule.title && it.amount == rule.amount
            }
            if (!alreadyGenerated) projectedRemainingIncome += rule.amount
        }
        val projectedIncome = actualIncomeSoFar + projectedRemainingIncome
        val projectedBalance = projectedIncome - projectedExpense

        return SpendingForecast(
            hasData = true,
            daysElapsed = daysElapsed,
            totalDaysInPeriod = totalDays,
            dailyRate = dailyRate,
            actualExpenseSoFar = actualExpenseSoFar,
            projectedExpense = projectedExpense,
            hasRecurringIncome = hasRecurringIncome,
            projectedIncome = projectedIncome,
            projectedBalance = projectedBalance
        )
    }

    // Select different month
    fun selectMonth(option: HebrewMonthYearOption) {
        _selectedHebrewMonthIndex.value = option.monthIndex
        _selectedHebrewMonthName.value = option.monthName
        _selectedHebrewYear.value = option.year
        _selectedHebrewYearString.value = option.yearString
    }

    // Add category
    fun addCategory(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.insertCategory(CategoryEntity(name = name.trim()))
        }
    }

    // Remove category
    fun removeCategory(category: CategoryEntity) {
        if (category.isSystem) return // system categories cannot be deleted
        viewModelScope.launch {
            repository.deleteCategory(category)
        }
    }

    // Update category budget
    fun updateCategoryBudget(category: CategoryEntity, limit: Double) {
        viewModelScope.launch {
            repository.updateCategoryBudget(category.id, limit)
        }
    }

    // Update the overall monthly budget cap (applies across all categories combined)
    fun setMonthlyBudgetLimit(limit: Double) {
        val safeLimit = if (limit < 0.0) 0.0 else limit
        _monthlyBudgetLimit.value = safeLimit
        appPrefs.edit().putFloat(KEY_MONTHLY_BUDGET_LIMIT, safeLimit.toFloat()).apply()
    }

    // Switch between viewing periods by Hebrew month or by Gregorian billing cycle
    fun setCalendarMode(mode: CalendarMode) {
        _calendarMode.value = mode
        appPrefs.edit().putString(KEY_CALENDAR_MODE, mode.name).apply()
        resetSelectionToCurrentPeriod()
    }

    // Change the app's display theme (light / dark / follow system)
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        appPrefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    // Set which day of the Gregorian month the billing cycle starts on (1-28)
    fun setGregorianCycleStartDay(day: Int) {
        val safeDay = day.coerceIn(1, 28)
        _gregorianCycleStartDay.value = safeDay
        appPrefs.edit().putInt(KEY_GREGORIAN_START_DAY, safeDay).apply()
        if (_calendarMode.value == CalendarMode.GREGORIAN) resetSelectionToCurrentPeriod()
    }

    // Add a new recurring monthly income/expense rule
    fun addRecurringRule(
        title: String,
        amount: Double,
        isExpense: Boolean,
        categoryName: String,
        paymentType: String,
        dayOfMonth: Int
    ) {
        if (title.isBlank() || amount <= 0.0) return
        val safeDay = dayOfMonth.coerceIn(1, 28)
        viewModelScope.launch {
            repository.insertRecurringRule(
                RecurringRuleEntity(
                    title = title.trim(),
                    amount = amount,
                    isExpense = isExpense,
                    categoryName = categoryName,
                    paymentType = paymentType,
                    dayOfMonth = safeDay,
                    isActive = true,
                    lastGeneratedPeriodKey = null
                )
            )
        }
    }

    fun setRecurringRuleActive(rule: RecurringRuleEntity, isActive: Boolean) {
        viewModelScope.launch {
            repository.updateRecurringRule(rule.copy(isActive = isActive))
        }
    }

    // Turn the "2 days before" reminder on/off for one specific recurring rule
    fun setRecurringRuleReminderEnabled(rule: RecurringRuleEntity, reminderEnabled: Boolean) {
        viewModelScope.launch {
            repository.updateRecurringRule(rule.copy(reminderEnabled = reminderEnabled))
        }
    }

    fun deleteRecurringRule(rule: RecurringRuleEntity) {
        viewModelScope.launch {
            repository.deleteRecurringRule(rule)
        }
    }

    // Change what time of day the recurring-rule reminder notification fires
    fun setReminderTime(hour: Int, minute: Int) {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        _reminderHour.value = safeHour
        _reminderMinute.value = safeMinute
        appPrefs.edit()
            .putInt(ReminderReceiver.KEY_REMINDER_HOUR, safeHour)
            .putInt(ReminderReceiver.KEY_REMINDER_MINUTE, safeMinute)
            .apply()
        ReminderScheduler.scheduleDailyCheck(getApplication(), safeHour, safeMinute)
    }

    // Insert transaction
    fun addManualTransaction(
        title: String,
        amount: Double,
        isExpense: Boolean,
        categoryName: String,
        paymentType: String, // "CASH" or "CREDIT"
        timestamp: Long = System.currentTimeMillis(),
        isAnomalous: Boolean = false
    ) {
        if (title.isBlank() || amount <= 0.0) return
        viewModelScope.launch {
            val hebrewInfo = HebrewCalendarHelper.getHebrewDateInfo(timestamp)
            val (periodYear, periodMonth) = periodBucketFor(timestamp)

            // Anomalous (one-off/unusual) transactions are excluded from budget-limit checks entirely
            if (isExpense && !isAnomalous) {
                val category = categories.value.find { it.name == categoryName }
                val limit = category?.budgetLimit ?: 0.0
                if (limit > 0.0) {
                    val currentCategoryTotal = allTransactions.value
                        .filter { !it.isAnomalous && it.isExpense && it.categoryName == categoryName && periodBucketFor(it.timestamp) == (periodYear to periodMonth) }
                        .sumOf { it.amount }
                    val newTotal = currentCategoryTotal + amount
                    val pBefore = currentCategoryTotal / limit
                    val pAfter = newTotal / limit

                    if (pBefore < 0.8 && pAfter >= 0.8 && pAfter < 1.0) {
                        sendLocalNotification(
                            "התראת תקציב - 80%",
                            "ההוצאות בקטגוריה '$categoryName' הגיעו ל-80% מהתקציב (₪${String.format("%.2f", newTotal)} מתוך ₪${String.format("%.2f", limit)})"
                        )
                    } else if (pBefore < 1.0 && pAfter >= 1.0) {
                        sendLocalNotification(
                            "חריגה מהתקציב!",
                            "ההוצאות בקטגוריה '$categoryName' עברו את התקציב שהוגדר! (₪${String.format("%.2f", newTotal)} מתוך ₪${String.format("%.2f", limit)})"
                        )
                    }
                }

                // Check overall monthly budget cap (across all categories combined)
                val overallLimit = _monthlyBudgetLimit.value
                if (overallLimit > 0.0) {
                    val currentMonthTotal = allTransactions.value
                        .filter { !it.isAnomalous && it.isExpense && periodBucketFor(it.timestamp) == (periodYear to periodMonth) }
                        .sumOf { it.amount }
                    val newMonthTotal = currentMonthTotal + amount
                    val pBeforeOverall = currentMonthTotal / overallLimit
                    val pAfterOverall = newMonthTotal / overallLimit

                    if (pBeforeOverall < 0.8 && pAfterOverall >= 0.8 && pAfterOverall < 1.0) {
                        sendLocalNotification(
                            "התראת תקציב חודשי כולל - 80%",
                            "סך כל ההוצאות החודש הגיעו ל-80% מהתקציב הכולל (₪${String.format("%.2f", newMonthTotal)} מתוך ₪${String.format("%.2f", overallLimit)})"
                        )
                    } else if (pBeforeOverall < 1.0 && pAfterOverall >= 1.0) {
                        sendLocalNotification(
                            "חריגה מהתקציב החודשי הכולל!",
                            "סך כל ההוצאות החודש עברו את התקציב הכולל שהוגדר! (₪${String.format("%.2f", newMonthTotal)} מתוך ₪${String.format("%.2f", overallLimit)})"
                        )
                    }
                }
            }

            val entity = TransactionEntity(
                title = title.trim(),
                amount = amount,
                isExpense = isExpense,
                categoryName = categoryName,
                paymentType = paymentType,
                timestamp = timestamp,
                hebrewDay = hebrewInfo.day,
                hebrewMonthIndex = hebrewInfo.monthIndex,
                hebrewMonthName = hebrewInfo.monthName,
                hebrewYear = hebrewInfo.year,
                hebrewYearString = hebrewInfo.yearHebrewString,
                isAnomalous = isAnomalous
            )
            repository.insertTransaction(entity)
            BalanceWidget.refreshAll(getApplication())
        }
    }

    private fun sendLocalNotification(title: String, message: String) {
        try {
            val context = getApplication<Application>().applicationContext
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val channelId = "budget_alerts"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "התראות תקציב",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "התראות כאשר ההוצאות מגיעות ל-80% או עוברות את התקציב החודשי"
                }
                notificationManager.createNotificationChannel(channel)
            }
            
            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                
            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Delete transaction
    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.deleteTransaction(transaction)
            BalanceWidget.refreshAll(getApplication())
        }
    }

    // Ask Gemini to parse expense description
    fun parseDescriptionWithGemini(description: String) {
        if (description.isBlank()) return
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            _parseError.value = "מפתח ה-API של Gemini חסר. אנא הגדר אותו בפאנל ה-Secrets ב-AI Studio."
            return
        }

        viewModelScope.launch {
            _isParsing.value = true
            _parseError.value = null
            _parsedDraft.value = null

            val currentCategoryNames = categories.value.map { it.name }
            val parsedResult = GeminiExpenseParser.parseExpense(
                prompt = description,
                categories = currentCategoryNames,
                apiKey = apiKey
            )

            _isParsing.value = false
            if (parsedResult != null) {
                _parsedDraft.value = parsedResult
            } else {
                _parseError.value = "לא הצלחנו לפענח את המשפט. נסה לנסח אחרת או להוסיף ידנית."
            }
        }
    }

    // Confirm and save Gemini drafted transaction
    fun confirmDraftTransaction(categoryName: String? = null) {
        val draft = _parsedDraft.value ?: return
        val finalCategoryName = (categoryName ?: draft.categoryName).trim()

        viewModelScope.launch {
            // If this category doesn't exist yet (e.g. a brand-new one Gemini proposed), create it now
            val exists = categories.value.any { it.name == finalCategoryName }
            if (!exists && finalCategoryName.isNotBlank()) {
                repository.insertCategory(CategoryEntity(name = finalCategoryName))
            }

            addManualTransaction(
                title = draft.title,
                amount = draft.amount,
                isExpense = draft.isExpense,
                categoryName = finalCategoryName,
                paymentType = draft.paymentType,
                timestamp = System.currentTimeMillis()
            )
        }
        _parsedDraft.value = null
    }

    // Clear Gemini states
    fun clearDraft() {
        _parsedDraft.value = null
        _parseError.value = null
    }

    // --- Google Sign-In & Cloud Sync ---

    private val _authUser = MutableStateFlow<FirebaseUser?>(authManager.currentUser)
    val authUser = _authUser.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn = _isSigningIn.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)
    val syncState = _syncState.asStateFlow()

    fun signInWithGoogle(activity: Activity) {
        viewModelScope.launch {
            _isSigningIn.value = true
            _syncState.value = SyncUiState.Idle
            authManager.signIn(activity)
                .onSuccess { user ->
                    _authUser.value = user
                    _syncState.value = SyncUiState.Success("מחובר: ${user.displayName ?: user.email}")
                    // Push a first backup right after signing in
                    viewModelScope.launch { autoBackupIfSignedIn() }
                }
                .onFailure { e ->
                    if (e is GetCredentialCancellationException) {
                        _syncState.value = SyncUiState.Idle
                    } else {
                        _syncState.value = SyncUiState.Error("ההתחברות נכשלה: ${e.message ?: "שגיאה לא ידועה"}")
                    }
                }
            _isSigningIn.value = false
        }
    }

    fun signOut() {
        authManager.signOut()
        _authUser.value = null
        _syncState.value = SyncUiState.Idle
    }

    fun backupToCloud() {
        val user = _authUser.value
        if (user == null) {
            _syncState.value = SyncUiState.Error("אנא התחבר קודם כדי לגבות")
            return
        }
        viewModelScope.launch {
            _syncState.value = SyncUiState.InProgress
            try {
                val summary = cloudSync.backup(user.uid)
                _syncState.value = SyncUiState.Success(
                    "הגיבוי הושלם: ${summary.transactions} עסקאות, ${summary.categories} קטגוריות"
                )
            } catch (e: Exception) {
                _syncState.value = SyncUiState.Error("הגיבוי נכשל: ${e.message ?: "שגיאה לא ידועה"}")
            }
        }
    }

    fun restoreFromCloud() {
        val user = _authUser.value
        if (user == null) {
            _syncState.value = SyncUiState.Error("אנא התחבר קודם כדי לשחזר")
            return
        }
        viewModelScope.launch {
            _syncState.value = SyncUiState.InProgress
            try {
                val summary = cloudSync.restore(user.uid)
                if (summary == null) {
                    _syncState.value = SyncUiState.Error("לא נמצא גיבוי בענן")
                } else {
                    _syncState.value = SyncUiState.Success(
                        "השחזור הושלם: ${summary.transactions} עסקאות, ${summary.categories} קטגוריות"
                    )
                    BalanceWidget.refreshAll(getApplication())
                }
            } catch (e: Exception) {
                _syncState.value = SyncUiState.Error("השחזור נכשל: ${e.message ?: "שגיאה לא ידועה"}")
            }
        }
    }

    companion object {
        private const val KEY_MONTHLY_BUDGET_LIMIT = "monthly_budget_limit"
        private const val KEY_CALENDAR_MODE = "calendar_mode"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_GREGORIAN_START_DAY = "gregorian_cycle_start_day"
    }
}

enum class CalendarMode { HEBREW, GREGORIAN }

// Helper structures
data class HebrewMonthYearOption(
    val monthIndex: Int,
    val monthName: String,
    val year: Int,
    val yearString: String
) {
    fun getDisplayName(): String = "$monthName $yearString"
}

data class MonthlyStats(
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val cashExpense: Double = 0.0,
    val creditExpense: Double = 0.0,
    val cashIncome: Double = 0.0,
    val creditIncome: Double = 0.0,
    val netBalance: Double = 0.0,
    val anomalousExpenseTotal: Double = 0.0,
    val anomalousIncomeTotal: Double = 0.0
)

// Spending-pace forecast for the currently ongoing period
data class SpendingForecast(
    val hasData: Boolean = false,
    val daysElapsed: Int = 0,
    val totalDaysInPeriod: Int = 0,
    val dailyRate: Double = 0.0,
    val actualExpenseSoFar: Double = 0.0,
    val projectedExpense: Double = 0.0,
    val hasRecurringIncome: Boolean = false,
    val projectedIncome: Double = 0.0,
    val projectedBalance: Double = 0.0
)

// Cloud-sync UI state
sealed class SyncUiState {
    object Idle : SyncUiState()
    object InProgress : SyncUiState()
    data class Success(val message: String) : SyncUiState()
    data class Error(val message: String) : SyncUiState()
}
