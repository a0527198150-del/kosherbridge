package com.example.ui

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.CategoryEntity
import com.example.data.TransactionEntity
import com.example.data.RecurringRuleEntity
import com.example.data.ParsedTransaction
import com.example.ui.theme.ThemeMode
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import com.google.firebase.auth.FirebaseUser
import java.text.DecimalFormat

// Semantic money colors that stay readable in both light and dark themes
@Composable
private fun moneyIncomeColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF81C784) else Color(0xFF2E7D32)

@Composable
private fun moneyExpenseColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFEF9A9A) else Color(0xFFBA1A1A)

@Composable
private fun moneyWarningColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFE2B93B) else Color(0xFF6B4E00)

// Chart colors that stay vivid and readable in both light and dark themes
@Composable
private fun chartTeal(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4DB6AC) else Color(0xFF00A699)

@Composable
private fun chartAmber(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFD54F) else Color(0xFFF2A900)

@Composable
private fun chartRed(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFEF9A9A) else Color(0xFFD32F2F)

@Composable
private fun chartPurple(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFCE93D8) else Color(0xFF8E24AA)

@Composable
private fun chartOrange(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFB74D) else Color(0xFFE65100)

@Composable
private fun chartSky(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF4FC3F7) else Color(0xFF0288D1)

@Composable
private fun chartIndigo(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF7986CB) else Color(0xFF3949AB)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel,
    modifier: Modifier = Modifier
) {
    // Force RTL direction for Hebrew app
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        val categories by viewModel.categories.collectAsStateWithLifecycle()
        val filteredTransactions by viewModel.filteredTransactions.collectAsStateWithLifecycle()
        val availableMonths by viewModel.availableMonths.collectAsStateWithLifecycle()
        val monthlyStats by viewModel.monthlyStats.collectAsStateWithLifecycle()
        val monthlyBudgetLimit by viewModel.monthlyBudgetLimit.collectAsStateWithLifecycle()
        val allTransactions by viewModel.allTransactions.collectAsStateWithLifecycle()
        val calendarMode by viewModel.calendarMode.collectAsStateWithLifecycle()
        val gregorianCycleStartDay by viewModel.gregorianCycleStartDay.collectAsStateWithLifecycle()
        val recurringRules by viewModel.recurringRules.collectAsStateWithLifecycle()
        val upcomingReminders by viewModel.upcomingReminders.collectAsStateWithLifecycle()
        val reminderHour by viewModel.reminderHour.collectAsStateWithLifecycle()
        val reminderMinute by viewModel.reminderMinute.collectAsStateWithLifecycle()
        val spendingForecast by viewModel.spendingForecast.collectAsStateWithLifecycle()
        val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
        val authUser by viewModel.authUser.collectAsStateWithLifecycle()
        val isSigningIn by viewModel.isSigningIn.collectAsStateWithLifecycle()
        val syncState by viewModel.syncState.collectAsStateWithLifecycle()

        val selectedMonthIndex by viewModel.selectedHebrewMonthIndex.collectAsStateWithLifecycle()
        val selectedMonthName by viewModel.selectedHebrewMonthName.collectAsStateWithLifecycle()
        val selectedYear by viewModel.selectedHebrewYear.collectAsStateWithLifecycle()
        val selectedYearString by viewModel.selectedHebrewYearString.collectAsStateWithLifecycle()

        val isParsing by viewModel.isParsing.collectAsStateWithLifecycle()
        val parseError by viewModel.parseError.collectAsStateWithLifecycle()
        val parsedDraft by viewModel.parsedDraft.collectAsStateWithLifecycle()

        // UI states
        var currentTab by remember { mutableStateOf(0) } // 0 = Detailed List, 1 = By Categories
        var showManualAddDialog by remember { mutableStateOf(false) }
        var showCategoryManagerDialog by remember { mutableStateOf(false) }
        var showMonthSelector by remember { mutableStateOf(false) }
        var geminiInputText by remember { mutableStateOf("") }
        var budgetToEdit by remember { mutableStateOf<CategoryEntity?>(null) }
        var showMonthlyBudgetDialog by remember { mutableStateOf(false) }
        var showSettingsMenu by remember { mutableStateOf(false) }
        var showCalendarModeDialog by remember { mutableStateOf(false) }
        var showThemeModeDialog by remember { mutableStateOf(false) }
        var showRecurringManagerDialog by remember { mutableStateOf(false) }
        var showReminderTimeDialog by remember { mutableStateOf(false) }
        var showSyncDialog by remember { mutableStateOf(false) }
        var showRestoreConfirm by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "תקציב עברי חכם",
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "$selectedMonthName $selectedYearString",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    actions = {
                        Row(
                            modifier = Modifier.padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box {
                                IconButton(
                                    onClick = { showSettingsMenu = true },
                                    modifier = Modifier.testTag("more_settings_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = "הגדרות נוספות",
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSettingsMenu,
                                    onDismissRequest = { showSettingsMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("מצב תצוגה: עברי / לועזי") },
                                        leadingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                                        onClick = {
                                            showSettingsMenu = false
                                            showCalendarModeDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("מצב כהה / בהיר") },
                                        leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null) },
                                        onClick = {
                                            showSettingsMenu = false
                                            showThemeModeDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("הכנסות/הוצאות קבועות") },
                                        leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null) },
                                        onClick = {
                                            showSettingsMenu = false
                                            showRecurringManagerDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("שעת תזכורות") },
                                        leadingIcon = { Icon(Icons.Default.Notifications, contentDescription = null) },
                                        onClick = {
                                            showSettingsMenu = false
                                            showReminderTimeDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("גיבוי וסנכרון עם Google") },
                                        leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                                        onClick = {
                                            showSettingsMenu = false
                                            showSyncDialog = true
                                        }
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showMonthlyBudgetDialog = true },
                                modifier = Modifier.testTag("monthly_budget_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Savings,
                                    contentDescription = "הגדרת תקציב חודשי כולל",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            IconButton(
                                onClick = { showCategoryManagerDialog = true },
                                modifier = Modifier.testTag("manage_categories_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Category,
                                    contentDescription = "ניהול קטגוריות",
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(50)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "יה",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showManualAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .testTag("add_transaction_fab")
                        .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "הוספת תנועה")
                        Text("הוספה ידנית", fontWeight = FontWeight.Bold)
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            modifier = modifier
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Hebrew Month Selector Banner
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "חודש פעיל (לפי הלוח העברי)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { showMonthSelector = true }
                                        .testTag("month_select_trigger")
                                        .padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$selectedMonthName $selectedYearString",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "בחר חודש",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }

                            // Quick current date info
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Text(
                                    text = "מתחיל בא'",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // 1.5 Recurring-rule reminder banner (only shown when something is due today)
                if (upcomingReminders.isNotEmpty()) {
                    item {
                        ReminderBannerCard(dueRules = upcomingReminders)
                    }
                }

                // 2. Budget Statistics Dashboard
                item {
                    BudgetDashboardCard(stats = monthlyStats, monthlyBudgetLimit = monthlyBudgetLimit, calendarMode = calendarMode)
                }

                // 3. Gemini Input Section
                item {
                    GeminiInputCard(
                        inputText = geminiInputText,
                        onInputTextChange = { geminiInputText = it },
                        isParsing = isParsing,
                        parseError = parseError,
                        onParseClick = {
                            viewModel.parseDescriptionWithGemini(geminiInputText)
                            geminiInputText = "" // clear input on send
                        }
                    )
                }

                // 4. Tab Navigation (Detailed vs Category Split vs Charts)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Tab 0 pill ("פירוט הכל")
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .background(
                                    color = if (currentTab == 0) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                                    shape = RoundedCornerShape(50)
                                )
                                .border(
                                    width = if (currentTab == 0) 0.dp else 1.dp,
                                    color = if (currentTab == 0) Color.Transparent else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable { currentTab = 0 }
                                .testTag("tab_all_detailed"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "תנועות",
                                fontWeight = if (currentTab == 0) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (currentTab == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }

                        // Tab 1 pill ("תקציב וקטגוריות")
                        Box(
                            modifier = Modifier
                                .weight(1.3f)
                                .height(42.dp)
                                .background(
                                    color = if (currentTab == 1) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                                    shape = RoundedCornerShape(50)
                                )
                                .border(
                                    width = if (currentTab == 1) 0.dp else 1.dp,
                                    color = if (currentTab == 1) Color.Transparent else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable { currentTab = 1 }
                                .testTag("tab_by_categories"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "תקציב וקטגוריות",
                                fontWeight = if (currentTab == 1) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (currentTab == 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }

                        // Tab 2 pill ("תרשימים")
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .background(
                                    color = if (currentTab == 2) MaterialTheme.colorScheme.outlineVariant else Color.Transparent,
                                    shape = RoundedCornerShape(50)
                                )
                                .border(
                                    width = if (currentTab == 2) 0.dp else 1.dp,
                                    color = if (currentTab == 2) Color.Transparent else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(50)
                                )
                                .clickable { currentTab = 2 }
                                .testTag("tab_charts"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "תרשימים",
                                fontWeight = if (currentTab == 2) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (currentTab == 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                // 5. Dynamic Tab Content
                if (currentTab == 0) {
                    // TAB 0: Detailed List
                    if (filteredTransactions.isEmpty()) {
                        item {
                            EmptyStatePlaceholder(text = "אין תנועות בחודש זה עדיין. תאר לג'מיני מה קנית או הוסף ידנית!")
                        }
                    } else {
                        items(filteredTransactions, key = { it.id }) { tx ->
                            TransactionItemRow(
                                transaction = tx,
                                onDeleteClick = { viewModel.deleteTransaction(tx) }
                            )
                        }
                    }
                } else if (currentTab == 1) {
                    // TAB 1: Grouped By Categories & Budgets
                    val grouped = filteredTransactions.filter { !it.isAnomalous }.groupBy { it.categoryName }
                    
                    if (categories.isEmpty()) {
                        item {
                            EmptyStatePlaceholder(text = "אין קטגוריות מוגדרות.")
                        }
                    } else {
                        items(categories, key = { it.id }) { category ->
                            val categoryTransactions = grouped[category.name] ?: emptyList()
                            val totalCategoryExpense = categoryTransactions.filter { it.isExpense }.sumOf { it.amount }
                            val totalCategoryIncome = categoryTransactions.filter { !it.isExpense }.sumOf { it.amount }

                            CategoryGroupCard(
                                category = category,
                                transactions = categoryTransactions,
                                totalExpense = totalCategoryExpense,
                                totalIncome = totalCategoryIncome,
                                onDeleteTransaction = { viewModel.deleteTransaction(it) },
                                onEditBudget = { budgetToEdit = it }
                            )
                        }
                    }
                } else {
                    // TAB 2: Graphical Analysis & Charts
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ForecastCard(
                                forecast = spendingForecast
                            )

                            DonutChartCard(
                                categories = categories,
                                transactions = filteredTransactions.filter { !it.isAnomalous }
                            )

                            CashCreditBreakdownCard(
                                stats = monthlyStats
                            )
                            
                            BarChartCard(
                                allTransactions = allTransactions.filter { !it.isAnomalous }
                            )
                        }
                    }
                }
                
                // Add a spacer at the end for floating button overlap
                item {
                    Spacer(modifier = Modifier.height(70.dp))
                }
            }
        }

        // --- Dialogs ---

        // A. Month Selector Dialog
        if (showMonthSelector) {
            MonthSelectorDialog(
                options = availableMonths,
                selectedMonthIndex = selectedMonthIndex,
                selectedYear = selectedYear,
                onSelect = { option ->
                    viewModel.selectMonth(option)
                    showMonthSelector = false
                },
                onDismiss = { showMonthSelector = false }
            )
        }

        // B. Gemini Draft Confirmation Dialog
        parsedDraft?.let { draft ->
            GeminiDraftConfirmDialog(
                draft = draft,
                availableCategories = categories,
                onConfirm = { finalCategory ->
                    viewModel.confirmDraftTransaction(finalCategory)
                },
                onDismiss = {
                    viewModel.clearDraft()
                }
            )
        }

        // C. Manual Add Dialog
        if (showManualAddDialog) {
            ManualAddTransactionDialog(
                categories = categories,
                onAdd = { title, amount, isExpense, category, paymentType, timestamp, isAnomalous ->
                    viewModel.addManualTransaction(
                        title = title,
                        amount = amount,
                        isExpense = isExpense,
                        categoryName = category,
                        paymentType = paymentType,
                        timestamp = timestamp,
                        isAnomalous = isAnomalous
                    )
                    showManualAddDialog = false
                },
                onDismiss = { showManualAddDialog = false }
            )
        }

        // D. Category Manager Dialog
        if (showCategoryManagerDialog) {
            CategoryManagerDialog(
                categories = categories,
                onAdd = { viewModel.addCategory(it) },
                onDelete = { viewModel.removeCategory(it) },
                onDismiss = { showCategoryManagerDialog = false }
            )
        }

        // E. Edit Category Budget Dialog
        budgetToEdit?.let { category ->
            EditCategoryBudgetDialog(
                category = category,
                onDismiss = { budgetToEdit = null },
                onSave = { limit ->
                    viewModel.updateCategoryBudget(category, limit)
                    budgetToEdit = null
                }
            )
        }

        // F. Edit Overall Monthly Budget Dialog (cap across all categories combined)
        if (showMonthlyBudgetDialog) {
            EditMonthlyBudgetDialog(
                currentLimit = monthlyBudgetLimit,
                onDismiss = { showMonthlyBudgetDialog = false },
                onSave = { limit ->
                    viewModel.setMonthlyBudgetLimit(limit)
                    showMonthlyBudgetDialog = false
                }
            )
        }

        // G. Calendar Mode Dialog (Hebrew month vs Gregorian billing cycle)
        if (showCalendarModeDialog) {
            CalendarModeDialog(
                currentMode = calendarMode,
                currentStartDay = gregorianCycleStartDay,
                onDismiss = { showCalendarModeDialog = false },
                onSave = { mode, startDay ->
                    viewModel.setCalendarMode(mode)
                    viewModel.setGregorianCycleStartDay(startDay)
                    showCalendarModeDialog = false
                }
            )
        }

        // I. Theme Mode Dialog (light / dark / follow system)
        if (showThemeModeDialog) {
            ThemeModeDialog(
                currentMode = themeMode,
                onDismiss = { showThemeModeDialog = false },
                onSave = { mode ->
                    viewModel.setThemeMode(mode)
                    showThemeModeDialog = false
                }
            )
        }

        // H. Recurring Income/Expense Manager Dialog
        if (showRecurringManagerDialog) {
            RecurringRuleManagerDialog(
                rules = recurringRules,
                categories = categories,
                onDismiss = { showRecurringManagerDialog = false },
                onAdd = { rTitle, rAmount, rIsExpense, rCategory, rPayment, rDay ->
                    viewModel.addRecurringRule(rTitle, rAmount, rIsExpense, rCategory, rPayment, rDay)
                },
                onToggleActive = { rule, active -> viewModel.setRecurringRuleActive(rule, active) },
                onToggleReminder = { rule, enabled -> viewModel.setRecurringRuleReminderEnabled(rule, enabled) },
                onDelete = { rule -> viewModel.deleteRecurringRule(rule) }
            )
        }

        // J. Reminder Time Dialog (what time of day the "2 days before" notification fires)
        if (showReminderTimeDialog) {
            ReminderTimeDialog(
                currentHour = reminderHour,
                currentMinute = reminderMinute,
                onDismiss = { showReminderTimeDialog = false },
                onSave = { hour, minute ->
                    viewModel.setReminderTime(hour, minute)
                    showReminderTimeDialog = false
                }
            )
        }

        // K. Google Backup & Sync Dialog (sign-in + cloud backup/restore)
        if (showSyncDialog) {
            val activity = LocalContext.current.findActivity()
            BackupSyncDialog(
                authUser = authUser,
                isSigningIn = isSigningIn,
                syncState = syncState,
                onDismiss = { showSyncDialog = false },
                onSignIn = {
                    if (activity != null) viewModel.signInWithGoogle(activity)
                },
                onSignOut = { viewModel.signOut() },
                onBackup = { viewModel.backupToCloud() },
                // Restoring replaces all local data, so ask for confirmation first
                onRestore = { showRestoreConfirm = true }
            )
        }

        // K2. Restore confirmation dialog - restore wipes local data, so the user
        // must explicitly confirm before the ViewModel is invoked
        if (showRestoreConfirm) {
            AlertDialog(
                onDismissRequest = { showRestoreConfirm = false },
                title = { Text("שחזור מהענן?") },
                text = {
                    Text(
                        "כל הנתונים המקומיים הנוכחיים (קטגוריות, עסקאות ורשומות קבועות) יוחלפו " +
                            "בנתוני הגיבוי שבענן. פעולה זו אינה ניתנת לביטול."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRestoreConfirm = false
                            viewModel.restoreFromCloud()
                        }
                    ) {
                        Text("כן, שחזר", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRestoreConfirm = false }) {
                        Text("ביטול")
                    }
                }
            )
        }
    }
}

// --- Sub-Composables ---

@Composable
fun ReminderBannerCard(
    dueRules: List<RecurringRuleEntity>,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (dueRules.size == 1) "רשומה קבועה בעוד יומיים" else "${dueRules.size} רשומות קבועות בעוד יומיים",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    text = dueRules.joinToString(" • ") { "${it.title} (₪${String.format("%.2f", it.amount)})" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
fun BudgetDashboardCard(
    stats: MonthlyStats,
    monthlyBudgetLimit: Double = 0.0,
    calendarMode: CalendarMode = CalendarMode.HEBREW,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("#,##0.00") }

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(28.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Balance row header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (calendarMode == CalendarMode.HEBREW) "יתרה חודשית עברית" else "יתרה חודשית לועזית",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = if (calendarMode == CalendarMode.HEBREW) "חודש עברי" else "חודש לועזי",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Big balance display, with anomalous totals shown large alongside it on the other side
            val balanceSign = if (stats.netBalance >= 0) "" else "-"
            val absoluteBalance = kotlin.math.abs(stats.netBalance)
            val balanceColor = if (stats.netBalance >= 0) moneyIncomeColor() else moneyExpenseColor()
            val hasAnomalous = stats.anomalousExpenseTotal > 0.0 || stats.anomalousIncomeTotal > 0.0

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (hasAnomalous) Arrangement.SpaceBetween else Arrangement.Start,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "${balanceSign}₪${decFormat.format(absoluteBalance)}",
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    color = balanceColor,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                if (hasAnomalous) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        if (stats.anomalousExpenseTotal > 0.0) {
                            Text(
                                text = "הוצאה חריגה",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = moneyWarningColor()
                            )
                            Text(
                                text = "₪${decFormat.format(stats.anomalousExpenseTotal)}",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = moneyWarningColor()
                            )
                        }
                        if (stats.anomalousIncomeTotal > 0.0) {
                            Text(
                                text = "הכנסה חריגה",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = moneyWarningColor()
                            )
                            Text(
                                text = "₪${decFormat.format(stats.anomalousIncomeTotal)}",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = moneyWarningColor()
                            )
                        }
                    }
                }
            }

            // Total Income / Expense side-by-side
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Total Income Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFFE8F5E9).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "סה\"כ הכנסות 📈",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "₪${decFormat.format(stats.totalIncome)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1B5E20)
                    )
                }

                // Total Expense Column
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFFFFEBEE).copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "סה\"כ הוצאות 📉",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFC62828)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "₪${decFormat.format(stats.totalExpense)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB71C1C)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))

            // Income / Expense summary split
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Cash details
                val netCash = stats.cashIncome - stats.cashExpense
                val cashSign = if (netCash >= 0) "" else "-"
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "מזומן",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${cashSign}₪${decFormat.format(kotlin.math.abs(netCash))}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                // Vertical border divider
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))
                        .align(Alignment.CenterVertically)
                )

                // Credit details
                val netCredit = stats.creditIncome - stats.creditExpense
                val creditSign = if (netCredit >= 0) "" else "-"
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                ) {
                    Text(
                        text = "אשראי",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${creditSign}₪${decFormat.format(kotlin.math.abs(netCredit))}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Overall monthly budget cap progress (across all categories combined)
            if (monthlyBudgetLimit > 0.0) {
                HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f))

                val usageFraction = (stats.totalExpense / monthlyBudgetLimit).toFloat().coerceIn(0f, 1f)
                val isOverBudget = stats.totalExpense >= monthlyBudgetLimit
                val isNearBudget = stats.totalExpense >= monthlyBudgetLimit * 0.8

                val progressColor = when {
                    isOverBudget -> Color(0xFFBA1A1A)
                    isNearBudget -> Color(0xFFE65100)
                    else -> MaterialTheme.colorScheme.primary
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "תקציב חודשי כולל",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = "₪${decFormat.format(stats.totalExpense)} מתוך ₪${decFormat.format(monthlyBudgetLimit)}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = progressColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { usageFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50)),
                        color = progressColor,
                        trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    )
                    if (isOverBudget) {
                        Text(
                            text = "חריגה מהתקציב החודשי הכולל",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFBA1A1A)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GeminiInputCard(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isParsing: Boolean,
    parseError: String?,
    onParseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "ג'מיני פיענוח חכם",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "דבר אל ג'מיני בחופשיות ✦",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Text(
                text = "ספר לג'מיני מה קנית או קיבלת, והוא ימיין זאת מיד לקטגוריה, לאמצעי תשלום, ולסכום הנכון.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                placeholder = {
                    Text(
                        "לדוגמה: 'קניתי אוכל בחומוס אליהו ב-45 שקלים באשראי' או 'קיבלתי משכורת 5500 שקלים במזומן'",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .testTag("gemini_input_field"),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Button(
                    onClick = onParseClick,
                    enabled = inputText.isNotBlank() && !isParsing,
                    modifier = Modifier.testTag("parse_gemini_button"),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)
                    )
                ) {
                    if (isParsing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("מפענח...")
                    } else {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("שלח לג'מיני", fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (isParsing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            parseError?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun TransactionItemRow(
    transaction: TransactionEntity,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("#,##0.00") }

    // Map categories to emojis and background colors
    val (emoji, bg) = when (transaction.categoryName) {
        "אוכל מוכן" -> Pair("🥘", Color(0xFFFFDBCB))
        "אוכל קנוי בברכל" -> Pair("🛒", Color(0xFFE0E2EC))
        "סלולר" -> Pair("📱", Color(0xFFD8E2FF))
        "ביגוד" -> Pair("👔", Color(0xFFF6EDFF))
        "מגורים" -> Pair("🏠", Color(0xFFE0F2F1))
        "בריאות" -> Pair("💊", Color(0xFFFFCDD2))
        "תחבורה" -> Pair("🚗", Color(0xFFFFF9C4))
        "הכנסות" -> Pair("📈", Color(0xFFC8E6C9))
        else -> Pair("💰", Color(0xFFE8EAF6))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category Emoji badge
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(bg, shape = RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 20.sp)
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = transaction.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (transaction.isAnomalous) {
                            Surface(
                                color = Color(0xFFFFF3CD),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "חריג",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6B4E00)
                                )
                            }
                        }
                    }
                    
                    Text(
                        text = "${transaction.categoryName} • ${if (transaction.paymentType == "CASH") "מזומן" else "אשראי"} • יום ${transaction.hebrewDay} ב${transaction.hebrewMonthName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val amountColor = if (transaction.isExpense) moneyExpenseColor() else moneyIncomeColor()
                val amountPrefix = if (transaction.isExpense) "-" else "+"
                
                Text(
                    text = "$amountPrefix₪${decFormat.format(transaction.amount)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = amountColor
                )

                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.testTag("delete_transaction_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "מחק תנועה",
                        tint = Color(0xFFBA1A1A).copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CategoryGroupCard(
    category: CategoryEntity,
    transactions: List<TransactionEntity>,
    totalExpense: Double,
    totalIncome: Double,
    onDeleteTransaction: (TransactionEntity) -> Unit,
    onEditBudget: (CategoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val decFormat = remember { DecimalFormat("#,##0.00") }

    val emoji = when (category.name) {
        "אוכל מוכן" -> "🥘"
        "אוכל קנוי בברכל" -> "🛒"
        "סלולר" -> "📱"
        "ביגוד" -> "👔"
        "מגורים" -> "🏠"
        "בריאות" -> "💊"
        "תחבורה" -> "🚗"
        "הכנסות" -> "📈"
        else -> "💰"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 18.sp)
                    }

                    Column {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "תנועות: ${transactions.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (totalExpense > 0.0) {
                            Text(
                                text = "הוצאות: ₪${decFormat.format(totalExpense)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = moneyExpenseColor(),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (totalIncome > 0.0) {
                            Text(
                                text = "הכנסות: ₪${decFormat.format(totalIncome)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = moneyIncomeColor(),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (totalExpense == 0.0 && totalIncome == 0.0) {
                            Text(
                                text = "₪0.00",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                    
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Budget limit row
                    val budget = category.budgetLimit
                    if (budget > 0.0) {
                        val progress = (totalExpense / budget).coerceIn(0.0, 1.0)
                        val isOver80 = totalExpense >= budget * 0.8
                        val isOver100 = totalExpense >= budget
                        
                        val progressColor = when {
                            isOver100 -> Color(0xFFBA1A1A) // Red
                            isOver80 -> Color(0xFFD68A00) // Amber/Orange
                            else -> MaterialTheme.colorScheme.primary // Blue
                        }
                        
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        imageVector = if (isOver100) Icons.Default.Warning else Icons.Default.Info,
                                        contentDescription = null,
                                        tint = progressColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "תקציב חודשי: ₪${decFormat.format(budget)}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                
                                Text(
                                    text = "${(progress * 100).toInt()}% נוצל",
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = progressColor
                                )
                            }
                            
                            LinearProgressIndicator(
                                progress = progress.toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(50)),
                                color = progressColor,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when {
                                        isOver100 -> "חרגת מהתקציב ב-₪${decFormat.format(totalExpense - budget)}!"
                                        isOver80 -> "שים לב! הגעת ל-80% מהתקציב בקטגוריה זו."
                                        else -> "נשאר תקציב פנוי של ₪${decFormat.format(budget - totalExpense)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = progressColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                
                                Row(
                                    modifier = Modifier.clickable { onEditBudget(category) },
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "ערוך תקציב",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = "ערוך",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else {
                        // Category has no budget set
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
                                .clickable { onEditBudget(category) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "לא הוגדר תקציב חודשי לקטגוריה זו",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "הגדר תקציב",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    if (transactions.isEmpty()) {
                        Text(
                            text = "אין הוצאות או הכנסות בקטגוריה זו בחודש הנבחר.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        transactions.forEach { tx ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                    .border(
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Payment badge
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = if (tx.paymentType == "CASH") Color(0xFFE0F2F1) else Color(0xFFE8EAF6),
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (tx.paymentType == "CASH") "מזומן" else "אשראי",
                                            fontSize = 10.sp,
                                            color = if (tx.paymentType == "CASH") Color(0xFF00796B) else Color(0xFF3F51B5),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Column {
                                        Text(
                                            text = tx.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "יום ${tx.hebrewDay} ב${tx.hebrewMonthName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val amountColor = if (tx.isExpense) moneyExpenseColor() else moneyIncomeColor()
                                    Text(
                                        text = "${if (tx.isExpense) "-" else "+"}₪${decFormat.format(tx.amount)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = amountColor
                                    )

                                    IconButton(
                                        onClick = { onDeleteTransaction(tx) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "מחק תנועה",
                                            tint = Color(0xFFBA1A1A).copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStatePlaceholder(
    text: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

// --- Dialog Sub-Composables ---

@Composable
fun MonthSelectorDialog(
    options: List<HebrewMonthYearOption>,
    selectedMonthIndex: Int,
    selectedYear: Int,
    onSelect: (HebrewMonthYearOption) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "בחר חודש עברי",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 280.dp)
                ) {
                    items(options) { option ->
                        val isSelected = option.monthIndex == selectedMonthIndex && option.year == selectedYear
                        val cardColors = if (isSelected) {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        } else {
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        }

                        Card(
                            colors = cardColors,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = option.getDisplayName(),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "נבחר",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("ביטול", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiDraftConfirmDialog(
    draft: ParsedTransaction,
    availableCategories: List<CategoryEntity>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(draft.categoryName) }
    val decFormat = remember { DecimalFormat("#,##0.00") }

    // Is the category Gemini proposed a brand-new one (not yet in the app)?
    val isNewCategory = availableCategories.none { it.name == selectedCategory }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "פיענוח ג'מיני מוכן! ✦",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Information Grid
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Title
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("תיאור שנמצא:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(draft.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }

                    // Amount
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("סכום:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("₪${decFormat.format(draft.amount)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = if (draft.isExpense) moneyExpenseColor() else moneyIncomeColor())
                    }

                    // Transaction Type
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("סוג פעולה:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (draft.isExpense) "הוצאה" else "הכנסה", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }

                    // Payment Type
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("אמצעי תשלום:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (draft.paymentType == "CASH") "מזומן" else "אשראי", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }

                    // Category Selection Grid
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("קטגוריה:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(6.dp))

                        if (isNewCategory) {
                            Surface(
                                color = Color(0xFFE8F0FE),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "ג'מיני הציע קטגוריה חדשה: \"$selectedCategory\" - אם תשמור, היא תיווצר אוטומטית. אפשר גם לבחור קטגוריה קיימת במקום.",
                                    modifier = Modifier.padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF1A3E7C)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp, max = 260.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(8.dp)
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(2),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // If Gemini proposed a brand-new category, show it as a selectable option first
                                if (isNewCategory) {
                                    item {
                                        val isSelected = selectedCategory == draft.categoryName
                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.surface),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 56.dp)
                                                .clickable { selectedCategory = draft.categoryName },
                                            border = if (isSelected) null else BorderStroke(1.dp, Color(0xFF8AB4F8))
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                                                    .fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(text = "✨", fontSize = 18.sp)
                                                Column {
                                                    Text(
                                                        text = draft.categoryName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = "חדשה",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else Color(0xFF1A3E7C)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                items(availableCategories) { category ->
                                    val isSelected = category.name == selectedCategory
                                    val emoji = when (category.name) {
                                        "אוכל מוכן" -> "🥘"
                                        "אוכל קנוי בברכל" -> "🛒"
                                        "סלולר" -> "📱"
                                        "ביגוד" -> "👔"
                                        "מגורים" -> "🏠"
                                        "בריאות" -> "💊"
                                        "תחבורה" -> "🚗"
                                        "הכנסות" -> "📈"
                                        else -> "💰"
                                    }
                                    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.surface
                                    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                                    
                                    Card(
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(containerColor = backgroundColor),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 56.dp)
                                            .clickable { selectedCategory = category.name },
                                        border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .padding(horizontal = 10.dp, vertical = 10.dp)
                                                .fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(text = emoji, fontSize = 18.sp)
                                            Text(
                                                text = category.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = contentColor,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.testTag("dismiss_draft_btn")
                    ) {
                        Text("ביטול", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(selectedCategory)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimaryContainer, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("confirm_draft_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("אשר ושמור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualAddTransactionDialog(
    categories: List<CategoryEntity>,
    onAdd: (title: String, amount: Double, isExpense: Boolean, category: String, paymentType: String, timestamp: Long, isAnomalous: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var paymentType by remember { mutableStateOf("CREDIT") } // "CREDIT" or "CASH"
    var selectedCategoryName by remember { mutableStateOf(categories.firstOrNull()?.name ?: "אחר") }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }
    var isAnomalous by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "הוספת תנועה ידנית",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Custom Segmented Toggle for Expense/Income
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                color = if (isExpense) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable {
                                isExpense = true
                                if (selectedCategoryName == "הכנסות") {
                                    selectedCategoryName = categories.firstOrNull { it.name != "הכנסות" }?.name ?: "אחר"
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "הוצאה",
                            fontWeight = FontWeight.Bold,
                            color = if (isExpense) moneyExpenseColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .background(
                                color = if (!isExpense) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable {
                                isExpense = false
                                if (categories.any { it.name == "הכנסות" }) {
                                    selectedCategoryName = "הכנסות"
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "הכנסה",
                            fontWeight = FontWeight.Bold,
                            color = if (!isExpense) moneyIncomeColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                }

                // Title Input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("תיאור (לדוגמה: קניות בברכל)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_title_input"),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                // Amount Input
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("סכום בשקלים") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_amount_input"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )

                // Anomalous (unusual/one-off) transaction toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFFF8E1), RoundedCornerShape(12.dp))
                        .clickable { isAnomalous = !isAnomalous }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isExpense) "הוצאה חריגה" else "הכנסה חריגה",
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF6B4E00)
                        )
                        Text(
                            text = "לא ייכלל ביתרה ובסטטיסטיקות - יוצג בנפרד",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF6B4E00).copy(alpha = 0.8f)
                        )
                    }
                    Checkbox(
                        checked = isAnomalous,
                        onCheckedChange = { isAnomalous = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6B4E00))
                    )
                }

                // Cash / Credit toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("אמצעי תשלום:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = paymentType == "CREDIT",
                            onClick = { paymentType = "CREDIT" },
                            label = { Text("אשראי") },
                            leadingIcon = { if (paymentType == "CREDIT") Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = paymentType == "CASH",
                            onClick = { paymentType = "CASH" },
                            label = { Text("מזומן") },
                            leadingIcon = { if (paymentType == "CASH") Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }

                // Category Selection Grid
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("בחר קטגוריה:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 260.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categories) { category ->
                                val isSelected = category.name == selectedCategoryName
                                val emoji = when (category.name) {
                                    "אוכל מוכן" -> "🥘"
                                    "אוכל קנוי בברכל" -> "🛒"
                                    "סלולר" -> "📱"
                                    "ביגוד" -> "👔"
                                    "מגורים" -> "🏠"
                                    "בריאות" -> "💊"
                                    "תחבורה" -> "🚗"
                                    "הכנסות" -> "📈"
                                    else -> "💰"
                                }
                                val backgroundColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.surface
                                val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
                                
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = backgroundColor),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 56.dp)
                                        .clickable { selectedCategoryName = category.name },
                                    border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(horizontal = 10.dp, vertical = 10.dp)
                                            .fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(text = emoji, fontSize = 18.sp)
                                        Text(
                                            text = category.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = contentColor,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.testTag("manual_dismiss_btn")
                    ) {
                        Text("ביטול", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val amt = amountStr.toDoubleOrNull() ?: 0.0
                            if (title.isNotBlank() && amt > 0.0) {
                                onAdd(title, amt, isExpense, selectedCategoryName, paymentType, System.currentTimeMillis(), isAnomalous)
                            }
                        },
                        enabled = title.isNotBlank() && (amountStr.toDoubleOrNull() ?: 0.0) > 0.0,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimaryContainer, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("manual_save_btn")
                    ) {
                        Text("שמור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private val UnspecifiedTypeColor = Color(0xFF555555)

@Composable
fun CategoryManagerDialog(
    categories: List<CategoryEntity>,
    onAdd: (String) -> Unit,
    onDelete: (CategoryEntity) -> Unit,
    onDismiss: () -> Unit
) {
    var newCategoryName by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "ניהול קטגוריות",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Add Category Input Box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        placeholder = { Text("קטגוריה חדשה...") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("new_category_input"),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )
                    Button(
                        onClick = {
                            if (newCategoryName.isNotBlank()) {
                                onAdd(newCategoryName)
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimaryContainer, contentColor = MaterialTheme.colorScheme.onPrimary),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.testTag("add_category_button")
                    ) {
                        Text("הוסף", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "קטגוריות קיימות:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 240.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { category ->
                        val emoji = when (category.name) {
                            "אוכל מוכן" -> "🥘"
                            "אוכל קנוי בברכל" -> "🛒"
                            "סלולר" -> "📱"
                            "ביגוד" -> "👔"
                            "מגורים" -> "🏠"
                            "בריאות" -> "💊"
                            "תחבורה" -> "🚗"
                            "הכנסות" -> "📈"
                            else -> "💰"
                        }

                        Card(
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(text = emoji, fontSize = 16.sp)
                                    Text(
                                        text = category.name,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (category.isSystem) {
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "מובנה",
                                                fontSize = 9.sp,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                if (!category.isSystem) {
                                    IconButton(
                                        onClick = { onDelete(category) },
                                        modifier = Modifier.testTag("delete_category_button_${category.name}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "מחק קטגוריה",
                                            tint = Color(0xFFBA1A1A).copy(alpha = 0.8f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("סגור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun EditCategoryBudgetDialog(
    category: CategoryEntity,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var budgetValue by remember { mutableStateOf(if (category.budgetLimit > 0.0) category.budgetLimit.toString() else "") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "הגדרת תקציב ל-${category.name}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                OutlinedTextField(
                    value = budgetValue,
                    onValueChange = {
                        budgetValue = it
                        isError = false
                    },
                    label = { Text("סכום תקציב חודשי (₪)") },
                    placeholder = { Text("לדוגמה: 1500") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )

                if (isError) {
                    Text(
                        text = "אנא הזן סכום תקין וגדול מ-0",
                        color = Color(0xFFBA1A1A),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("ביטול", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val limit = if (budgetValue.isBlank()) 0.0 else budgetValue.toDoubleOrNull()
                            if (limit != null && limit >= 0.0) {
                                onSave(limit)
                            } else {
                                isError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("שמור תקציב", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun EditMonthlyBudgetDialog(
    currentLimit: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var budgetValue by remember { mutableStateOf(if (currentLimit > 0.0) currentLimit.toString() else "") }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "תקציב חודשי כולל",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    text = "גג הוצאה כולל לחודש, על כל הקטגוריות יחד. השאר ריק כדי לבטל את התקרה.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = budgetValue,
                    onValueChange = {
                        budgetValue = it
                        isError = false
                    },
                    label = { Text("סכום תקציב חודשי כולל (₪)") },
                    placeholder = { Text("לדוגמה: 5000") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary
                    )
                )

                if (isError) {
                    Text(
                        text = "אנא הזן סכום תקין וגדול או שווה ל-0",
                        color = Color(0xFFBA1A1A),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("ביטול", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            val limit = if (budgetValue.isBlank()) 0.0 else budgetValue.toDoubleOrNull()
                            if (limit != null && limit >= 0.0) {
                                onSave(limit)
                            } else {
                                isError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("שמור תקציב", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarModeDialog(
    currentMode: CalendarMode,
    currentStartDay: Int,
    onDismiss: () -> Unit,
    onSave: (CalendarMode, Int) -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var startDayText by remember { mutableStateOf(currentStartDay.toString()) }
    var isError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "מצב תצוגת חודש",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "בחר האם התצוגה והחישובים (יתרה, תקציב, קטגוריות) יתבססו על החודש העברי או על מחזור חודש לועזי (שימושי בשביל התאמה למחזור חיוב האשראי).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(if (selectedMode == CalendarMode.HEBREW) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                            .clickable { selectedMode = CalendarMode.HEBREW },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("חודש עברי", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(if (selectedMode == CalendarMode.GREGORIAN) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                            .clickable { selectedMode = CalendarMode.GREGORIAN },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("חודש לועזי", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                }

                if (selectedMode == CalendarMode.GREGORIAN) {
                    OutlinedTextField(
                        value = startDayText,
                        onValueChange = { startDayText = it; isError = false },
                        label = { Text("יום תחילת המחזור בחודש (1-28)") },
                        placeholder = { Text("לדוגמה: 10") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        singleLine = true,
                        isError = isError,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    Text(
                        text = "ימים לפני התאריך הזה בכל חודש ייחשבו כשייכים לחודש הקודם - למשל אם החיוב מתחיל ב-10, אז ה-1 עד ה-9 בחודש שייכים למחזור של החודש הקודם.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isError) {
                        Text(
                            text = "אנא הזן מספר בין 1 ל-28",
                            color = Color(0xFFBA1A1A),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("ביטול", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            if (selectedMode == CalendarMode.HEBREW) {
                                onSave(CalendarMode.HEBREW, currentStartDay)
                            } else {
                                val day = startDayText.toIntOrNull()
                                if (day != null && day in 1..28) {
                                    onSave(CalendarMode.GREGORIAN, day)
                                } else {
                                    isError = true
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("שמור", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ThemeModeDialog(
    currentMode: ThemeMode,
    onDismiss: () -> Unit,
    onSave: (ThemeMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "מצב כהה / בהיר",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "בחר כיצד האפליקציה תוצג: תמיד בהיר, תמיד כהה, או בהתאמה להגדרות המערכת של הטלפון.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(if (selectedMode == ThemeMode.LIGHT) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                            .clickable { selectedMode = ThemeMode.LIGHT },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("בהיר", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(if (selectedMode == ThemeMode.DARK) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                            .clickable { selectedMode = ThemeMode.DARK },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("כהה", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .background(if (selectedMode == ThemeMode.SYSTEM) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                            .clickable { selectedMode = ThemeMode.SYSTEM },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("מערכת", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 13.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("ביטול", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onSave(selectedMode) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("שמור", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberStepper(
    value: Int,
    range: IntRange,
    step: Int = 1,
    onValueChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = {
            val next = value + step
            onValueChange(if (next > range.last) range.first else next)
        }) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = "הגדל",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Text(
            text = String.format("%02d", value),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(vertical = 2.dp)
        )
        IconButton(onClick = {
            val prev = value - step
            onValueChange(if (prev < range.first) range.last else prev)
        }) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "הקטן",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun ReminderTimeDialog(
    currentHour: Int,
    currentMinute: Int,
    onDismiss: () -> Unit,
    onSave: (hour: Int, minute: Int) -> Unit
) {
    var hour by remember { mutableStateOf(currentHour) }
    var minute by remember { mutableStateOf(currentMinute) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "שעת תזכורות",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "באיזו שעה תרצה לקבל התראה על רשומה קבועה שעומדת להירשם בעוד יומיים.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberStepper(value = hour, range = 0..23, onValueChange = { hour = it })
                    Text(
                        text = ":",
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    NumberStepper(value = minute, range = 0..59, step = 5, onValueChange = { minute = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("ביטול", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onSave(hour, minute) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("שמור", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun RecurringRuleManagerDialog(
    rules: List<RecurringRuleEntity>,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onAdd: (title: String, amount: Double, isExpense: Boolean, categoryName: String, paymentType: String, dayOfMonth: Int) -> Unit,
    onToggleActive: (RecurringRuleEntity, Boolean) -> Unit,
    onToggleReminder: (RecurringRuleEntity, Boolean) -> Unit,
    onDelete: (RecurringRuleEntity) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var dayStr by remember { mutableStateOf("1") }
    var isExpense by remember { mutableStateOf(true) }
    var paymentType by remember { mutableStateOf("CREDIT") }
    var selectedCategoryName by remember { mutableStateOf(categories.firstOrNull()?.name ?: "אחר") }
    var showAddForm by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 640.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "הכנסות/הוצאות קבועות",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "רשומות שנוצרות אוטומטית כל חודש קלנדרי, בתאריך שתבחר.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (rules.isEmpty()) {
                    Text(
                        text = "אין עדיין רשומות קבועות",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rules.forEach { rule ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (rule.isExpense) Color(0xFFFFEBEE) else Color(0xFFE8F5E9),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(
                                        text = "${rule.categoryName} • ₪${rule.amount} • כל ${rule.dayOfMonth} בחודש",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { onToggleReminder(rule, !rule.reminderEnabled) }) {
                                    Icon(
                                        imageVector = if (rule.reminderEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                        contentDescription = if (rule.reminderEnabled) "תזכורת פעילה - הקש לכיבוי" else "תזכורת כבויה - הקש להפעלה",
                                        tint = if (rule.reminderEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = rule.isActive,
                                    onCheckedChange = { onToggleActive(rule, it) }
                                )
                                IconButton(onClick = { onDelete(rule) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "מחק", tint = Color(0xFFB71C1C))
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                if (!showAddForm) {
                    Button(
                        onClick = { showAddForm = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onPrimaryContainer)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("הוסף רשומה קבועה", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("שם (למשל: משכורת / שכירות)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text("סכום (₪)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                    OutlinedTextField(
                        value = dayStr,
                        onValueChange = { dayStr = it },
                        label = { Text("יום בחודש (1-28)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            focusedLabelColor = MaterialTheme.colorScheme.primary
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(if (isExpense) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                                .clickable { isExpense = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "הוצאה קבועה",
                                fontWeight = FontWeight.Bold,
                                color = if (isExpense) moneyExpenseColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .background(if (!isExpense) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(50))
                                .clickable { isExpense = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "הכנסה קבועה",
                                fontWeight = FontWeight.Bold,
                                color = if (!isExpense) moneyIncomeColor() else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = paymentType == "CREDIT",
                            onClick = { paymentType = "CREDIT" },
                            label = { Text("אשראי") }
                        )
                        FilterChip(
                            selected = paymentType == "CASH",
                            onClick = { paymentType = "CASH" },
                            label = { Text("מזומן") }
                        )
                    }

                    Text("קטגוריה:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 180.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categories) { category ->
                                val isSelected = category.name == selectedCategoryName
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.surface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable { selectedCategoryName = category.name },
                                    border = if (isSelected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = category.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                    ) {
                        TextButton(
                            onClick = { showAddForm = false },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                        ) {
                            Text("ביטול", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val amt = amountStr.toDoubleOrNull()
                                val day = dayStr.toIntOrNull()
                                if (title.isNotBlank() && amt != null && amt > 0.0 && day != null && day in 1..28) {
                                    onAdd(title, amt, isExpense, selectedCategoryName, paymentType, day)
                                    title = ""
                                    amountStr = ""
                                    dayStr = "1"
                                    showAddForm = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("שמור", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("סגור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DonutChartCard(
    categories: List<CategoryEntity>,
    transactions: List<TransactionEntity>,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("#,##0.00") }
    val expenseTransactions = transactions.filter { it.isExpense }
    val totalExpense = expenseTransactions.sumOf { it.amount }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "פילוח הוצאות לפי קטגוריות",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            if (totalExpense == 0.0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Default.PieChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "אין הוצאות מתועדות לחודש זה.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val categoryTotals = expenseTransactions
                    .groupBy { it.categoryName }
                    .mapValues { it.value.sumOf { tx -> tx.amount } }
                    .toList()
                    .sortedByDescending { it.second }
                
                val chartColors = listOf(
                    MaterialTheme.colorScheme.primary, // Deep Blue
                    chartTeal(), // Teal
                    moneyIncomeColor(), // Forest Green
                    chartAmber(), // Amber
                    chartRed(), // Red
                    chartPurple(), // Purple
                    chartOrange(), // Dark Orange
                    chartSky(), // Sky Blue
                    chartIndigo()  // Indigo
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Donut Chart Canvas
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(140.dp)) {
                            var startAngle = -90f
                            categoryTotals.forEachIndexed { index, (_, amt) ->
                                val sweepAngle = (amt / totalExpense * 360.0).toFloat()
                                drawArc(
                                    color = chartColors[index % chartColors.size],
                                    startAngle = startAngle,
                                    sweepAngle = sweepAngle,
                                    useCenter = false,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                                        width = 24.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                )
                                startAngle += sweepAngle
                            }
                        }
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "סה\"כ הוצאות",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "₪${decFormat.format(totalExpense)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    
                    // Legend Column
                    Column(
                        modifier = Modifier.weight(1.2f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        categoryTotals.take(5).forEachIndexed { index, (name, amt) ->
                            val pct = (amt / totalExpense * 100).toInt()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(chartColors[index % chartColors.size], RoundedCornerShape(2.dp))
                                )
                                Column {
                                    Text(
                                        text = "$name ($pct%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "₪${decFormat.format(amt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (categoryTotals.size > 5) {
                            val otherSum = categoryTotals.drop(5).sumOf { it.second }
                            val otherPct = (otherSum / totalExpense * 100).toInt()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(Color.Gray, RoundedCornerShape(2.dp))
                                )
                                Column {
                                    Text(
                                        text = "אחר ($otherPct%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "₪${decFormat.format(otherSum)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ForecastCard(
    forecast: SpendingForecast,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("#,##0") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "תחזית לסוף התקופה",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            if (!forecast.hasData) {
                Text(
                    text = "התחזית זמינה רק עבור התקופה הנוכחית שרצה כרגע. עברו לתקופה הנוכחית כדי לראותה.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "יום ${forecast.daysElapsed} מתוך ${forecast.totalDaysInPeriod} בתקופה",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                LinearProgressIndicator(
                    progress = {
                        (forecast.daysElapsed.toFloat() / forecast.totalDaysInPeriod.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Color(0xFF6750A4),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("הוצא עד כה", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "₪${decFormat.format(forecast.actualExpenseSoFar)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("קצב יומי ממוצע", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = "₪${decFormat.format(forecast.dailyRate)} ליום",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (forecast.hasRecurringIncome) "יתרה חזויה בסוף התקופה" else "תחזית הוצאה לסוף התקופה",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    val displayValue = if (forecast.hasRecurringIncome) {
                        forecast.projectedBalance
                    } else {
                        -forecast.projectedExpense
                    }
                    Text(
                        text = "₪${decFormat.format(displayValue)}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (displayValue >= 0) moneyIncomeColor() else moneyExpenseColor()
                    )
                }

                if (forecast.hasRecurringIncome) {
                    Text(
                        text = "כולל הכנסה חזויה של ₪${decFormat.format(forecast.projectedIncome)} מול הוצאה חזויה של ₪${decFormat.format(forecast.projectedExpense)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun CashCreditBreakdownCard(
    stats: MonthlyStats,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("#,##0") }
    val maxAmount = maxOf(stats.cashIncome, stats.cashExpense, stats.creditIncome, stats.creditExpense, 1.0)

    data class BarRow(val label: String, val amount: Double, val color: Color)

    val rows = listOf(
        BarRow("הכנסות מזומן", stats.cashIncome, moneyIncomeColor()),
        BarRow("הוצאות מזומן", stats.cashExpense, moneyExpenseColor()),
        BarRow("הכנסות אשראי", stats.creditIncome, chartSky()),
        BarRow("הוצאות אשראי", stats.creditExpense, chartOrange())
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "פירוט מזומן מול אשראי",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            rows.forEach { row ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "₪${decFormat.format(row.amount)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = row.color
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (row.amount / maxAmount).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(50)),
                        color = row.color,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("סה\"כ מזומן", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "₪${decFormat.format(stats.cashIncome - stats.cashExpense)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (stats.cashIncome - stats.cashExpense >= 0) moneyIncomeColor() else moneyExpenseColor()
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("סה\"כ אשראי", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "₪${decFormat.format(stats.creditIncome - stats.creditExpense)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (stats.creditIncome - stats.creditExpense >= 0) moneyIncomeColor() else moneyExpenseColor()
                    )
                }
            }
        }
    }
}

@Composable
fun BarChartCard(
    allTransactions: List<TransactionEntity>,
    modifier: Modifier = Modifier
) {
    val decFormat = remember { DecimalFormat("#,##0") }
    
    val monthlyData = remember(allTransactions) {
        // Group transactions by year and month
        allTransactions.groupBy { "${it.hebrewYear}_${it.hebrewMonthIndex}" }
            .map { (key, txs) ->
                val firstTx = txs.first()
                val income = txs.filter { !it.isExpense }.sumOf { it.amount }
                val expense = txs.filter { it.isExpense }.sumOf { it.amount }
                
                MonthSummary(
                    year = firstTx.hebrewYear,
                    yearString = firstTx.hebrewYearString,
                    monthIndex = firstTx.hebrewMonthIndex,
                    monthName = firstTx.hebrewMonthName,
                    income = income,
                    expense = expense
                )
            }
            .sortedWith(compareBy<MonthSummary> { it.year }.thenBy { it.monthIndex })
            .takeLast(5) // Show last 5 months
    }
    
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "השוואת הכנסות מול הוצאות לאורך זמן",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            
            if (monthlyData.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "אין מספיק נתונים להשוואה חודשית.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val maxAmount = monthlyData.maxOfOrNull { maxOf(it.income, it.expense) } ?: 1.0
                
                // Legend
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(moneyIncomeColor(), RoundedCornerShape(3.dp))
                        )
                        Text("הכנסות", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(moneyExpenseColor(), RoundedCornerShape(3.dp))
                        )
                        Text("הוצאות", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Chart layout
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    monthlyData.forEach { month ->
                        val incomeHeightFraction = (month.income / maxAmount).toFloat().coerceIn(0.01f, 1.0f)
                        val expenseHeightFraction = (month.expense / maxAmount).toFloat().coerceIn(0.01f, 1.0f)
                        
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom,
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Income Bar
                                Box(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .fillMaxHeight(incomeHeightFraction)
                                        .background(moneyIncomeColor(), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                )
                                // Expense Bar
                                Box(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .fillMaxHeight(expenseHeightFraction)
                                        .background(moneyExpenseColor(), RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Text(
                                text = month.monthName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                            Text(
                                text = month.yearString,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

data class MonthSummary(
    val year: Int,
    val yearString: String,
    val monthIndex: Int,
    val monthName: String,
    val income: Double,
    val expense: Double
)


// Google Sign-In & Cloud Backup/Restore Dialog
@Composable
fun BackupSyncDialog(
    authUser: FirebaseUser?,
    isSigningIn: Boolean,
    syncState: SyncUiState,
    onDismiss: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "גיבוי וסנכרון עם Google",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "התחבר עם חשבון Google כדי לגבות את הנתונים לענן. אם הטלפון יתאפס, תוכל לשחזר את כל הנתונים במכשיר החדש.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (authUser == null) {
                    Button(
                        onClick = onSignIn,
                        enabled = !isSigningIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (isSigningIn) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("מתחבר...", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("התחבר עם Google", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Text(
                        text = "מחובר: ${authUser.displayName ?: authUser.email ?: ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onBackup,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("גיבוי לענן", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onRestore,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("שחזור מהענן", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold)
                        }
                    }
                    TextButton(
                        onClick = onSignOut,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("התנתק מהחשבון", fontWeight = FontWeight.Bold)
                    }
                }

                when (val state = syncState) {
                    is SyncUiState.InProgress -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("מתבצע...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is SyncUiState.Success -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    is SyncUiState.Error -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    SyncUiState.Idle -> {}
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Text("סגור", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Finds the host Activity from a Compose Context (handles ContextWrapper chains)
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

