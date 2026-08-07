package com.example.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.text.FontWeight
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.example.MainActivity
import com.example.data.AppDatabase
import com.example.data.GregorianCycleHelper
import com.example.data.HebrewCalendarHelper
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Balance-only home screen widget.
 * Shows the net balance (income - expense, anomalous transactions excluded) for whichever
 * period is "current" right now, using the same Hebrew/Gregorian calendar-mode logic and
 * settings (hebrew_budget_prefs) as the main app - so it always matches what the app itself
 * would show as the big balance number if opened right now.
 */
class BalanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (balance, periodLabel) = computeCurrentBalance(context)
        val isDark = resolveIsDark(context)

        provideContent {
            WidgetContent(balance = balance, periodLabel = periodLabel, isDark = isDark, context = context)
        }
    }

    /**
     * Mirrors resolveDarkTheme(mode: ThemeMode) in ui/theme/Theme.kt exactly:
     * reads the same "theme_mode" key ("LIGHT"/"DARK"/"SYSTEM") from the same
     * hebrew_budget_prefs file the app itself writes to via setThemeMode().
     */
    private fun resolveIsDark(context: Context): Boolean {
        val prefs = context.getSharedPreferences("hebrew_budget_prefs", Context.MODE_PRIVATE)
        return when (prefs.getString("theme_mode", "SYSTEM")) {
            "LIGHT" -> false
            "DARK" -> true
            else -> {
                val nightMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    @Composable
    private fun WidgetContent(balance: Double, periodLabel: String, isDark: Boolean, context: Context) {
        // Same surface/text colors as MinimalistDarkSurface/MinimalistDarkTextPrimary/
        // MinimalistDarkTextSecondary vs. the light surface/text colors in ui/theme/Color.kt -
        // reusing the exact hex values already verified working in the app, not new ones.
        val bgColor = if (isDark) Color(0xFF1A1F24) else Color(0xFFFFFFFF)
        val titleColor = if (isDark) Color(0xFFB8BDC7) else Color(0xFF44474E)
        val periodColor = if (isDark) Color(0xFF8A8F98) else Color(0xFF9E9E9E)

        // Balance sign color is intentionally the same fixed green/red in both themes,
        // matching how the app itself treats this as a semantic (not theme) color.
        val isPositive = balance >= 0.0
        val balanceColor = if (isPositive) Color(0xFF1B5E20) else Color(0xFFB71C1C)
        val sign = if (isPositive) "" else "-"
        val amountText = "$sign₪${String.format(Locale("he"), "%,.2f", kotlin.math.abs(balance))}"

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .padding(12.dp)
                .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "תקציב עברי",
                style = TextStyle(fontSize = 12.sp, color = ColorProvider(titleColor))
            )
            Text(
                text = amountText,
                style = TextStyle(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(balanceColor)
                )
            )
            Text(
                text = periodLabel,
                style = TextStyle(fontSize = 11.sp, color = ColorProvider(periodColor))
            )
        }
    }

    private suspend fun computeCurrentBalance(context: Context): Pair<Double, String> {
        val prefs = context.getSharedPreferences("hebrew_budget_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("calendar_mode", "HEBREW") ?: "HEBREW"
        val startDay = prefs.getInt("gregorian_cycle_start_day", 1)
        val now = System.currentTimeMillis()

        val (periodYear, periodMonth, periodName) = if (mode == "GREGORIAN") {
            val cycle = GregorianCycleHelper.getCycleInfo(now, startDay)
            Triple(cycle.year, cycle.month, cycle.monthName)
        } else {
            val h = HebrewCalendarHelper.getHebrewDateInfo(now)
            Triple(h.year, h.monthIndex, h.monthName)
        }

        val db = AppDatabase.getDatabase(context)
        val transactions = db.transactionDao().getAllTransactions().first()

        val filtered = transactions.filter { tx ->
            if (mode == "GREGORIAN") {
                val cycle = GregorianCycleHelper.getCycleInfo(tx.timestamp, startDay)
                cycle.year == periodYear && cycle.month == periodMonth
            } else {
                tx.hebrewYear == periodYear && tx.hebrewMonthIndex == periodMonth
            }
        }

        val netBalance = filtered
            .filter { !it.isAnomalous }
            .sumOf { if (it.isExpense) -it.amount else it.amount }

        return netBalance to periodName
    }

    companion object {
        /** Call after any change that could affect the current period's balance (add/delete/auto-generate). */
        suspend fun refreshAll(context: Context) {
            BalanceWidget().updateAll(context)
        }
    }
}
