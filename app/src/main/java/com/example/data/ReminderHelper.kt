package com.example.data

import java.util.Calendar

/**
 * Shared day-math for the "reminder 2 days before a recurring rule posts" feature.
 * Used both by BudgetViewModel (to show the in-app banner) and by ReminderReceiver
 * (to fire the Android notification), so the two stay perfectly in sync.
 */
object ReminderHelper {

    /**
     * Returns the timestamp (start of day) of the next date this rule will actually
     * generate a transaction on - i.e. this calendar month's [RecurringRuleEntity.dayOfMonth]
     * if it hasn't fired yet this period, otherwise next month's.
     */
    fun nextOccurrenceTimestamp(rule: RecurringRuleEntity, now: Calendar = Calendar.getInstance()): Long {
        val today = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val currentPeriodKey = "${now.get(Calendar.YEAR)}-${now.get(Calendar.MONTH) + 1}"
        val alreadyGeneratedThisPeriod = rule.lastGeneratedPeriodKey == currentPeriodKey

        val target = today.clone() as Calendar
        if (alreadyGeneratedThisPeriod) {
            target.add(Calendar.MONTH, 1)
        }
        var maxDay = target.getActualMaximum(Calendar.DAY_OF_MONTH)
        target.set(Calendar.DAY_OF_MONTH, rule.dayOfMonth.coerceAtMost(maxDay))

        // If the rule hasn't generated yet this period but its day-of-month has already
        // passed (e.g. rule day is 5, today is the 20th), the next real occurrence is next month
        if (!alreadyGeneratedThisPeriod && target.timeInMillis < today.timeInMillis) {
            target.add(Calendar.MONTH, 1)
            maxDay = target.getActualMaximum(Calendar.DAY_OF_MONTH)
            target.set(Calendar.DAY_OF_MONTH, rule.dayOfMonth.coerceAtMost(maxDay))
        }

        return target.timeInMillis
    }

    /**
     * True if today is exactly 2 days before this rule's next occurrence, and the rule
     * is active with reminders turned on.
     */
    fun isDueTodayForReminder(rule: RecurringRuleEntity, now: Calendar = Calendar.getInstance()): Boolean {
        if (!rule.isActive || !rule.reminderEnabled) return false

        val occurrence = nextOccurrenceTimestamp(rule, now)
        val reminderDay = Calendar.getInstance().apply {
            timeInMillis = occurrence
            add(Calendar.DAY_OF_MONTH, -2)
        }

        return reminderDay.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            reminderDay.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    }

    /** All rules (from the given list) that a reminder is due for today. */
    fun rulesDueToday(rules: List<RecurringRuleEntity>, now: Calendar = Calendar.getInstance()): List<RecurringRuleEntity> {
        return rules.filter { isDueTodayForReminder(it, now) }
    }
}
