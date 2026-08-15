package com.example

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.example.ui.BudgetDashboardCard
import com.example.ui.CalendarMode
import com.example.ui.MonthlyStats
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GreetingScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun budget_dashboard_screenshot() {
    // The original template rendered a "Greeting" composable that no longer
    // exists. Capture a real, self-contained screen component instead so the
    // screenshot test keeps exercising the app's theme.
    composeTestRule.setContent {
      MyApplicationTheme {
        BudgetDashboardCard(
          stats = MonthlyStats(totalIncome = 5000.0, totalExpense = 3200.0, netBalance = 1800.0),
          monthlyBudgetLimit = 4000.0,
          calendarMode = CalendarMode.HEBREW,
        )
      }
    }

    composeTestRule.onRoot().captureRoboImage(filePath = "src/test/screenshots/dashboard.png")
  }
}
