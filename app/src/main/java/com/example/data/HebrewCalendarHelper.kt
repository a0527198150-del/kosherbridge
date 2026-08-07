package com.example.data

import android.icu.util.HebrewCalendar
import android.icu.util.Calendar

data class HebrewDateInfo(
    val day: Int,
    val monthIndex: Int, // 0-based index from ICU
    val monthName: String,
    val year: Int,
    val yearHebrewString: String,
    val formattedDay: String,
    val fullFormattedDate: String
)

object HebrewCalendarHelper {
    
    fun getHebrewDateInfo(timestamp: Long): HebrewDateInfo {
        val cal = HebrewCalendar()
        cal.timeInMillis = timestamp
        
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val monthIndex = cal.get(Calendar.MONTH) // 0 to 12
        val year = cal.get(Calendar.YEAR)
        
        val isLeap = (7 * year + 1) % 19 < 7
        
        val monthName = when (monthIndex) {
            0 -> "תשרי"
            1 -> "חשון"
            2 -> "כסלו"
            3 -> "טבת"
            4 -> "שבט"
            5 -> if (isLeap) "אדר א'" else "אדר"
            6 -> if (isLeap) "אדר ב'" else "אדר"
            7 -> "ניסן"
            8 -> "אייר"
            9 -> "סיון"
            10 -> "תמוז"
            11 -> "אב"
            12 -> "אלול"
            else -> "תשרי"
        }
        
        val yearHebrewString = when (year) {
            5784 -> "תשפ\"ד"
            5785 -> "תשפ\"ה"
            5786 -> "תשפ\"ו"
            5787 -> "תשפ\"ז"
            5788 -> "תשפ\"ח"
            5789 -> "תשפ\"ט"
            5790 -> "תש\"צ"
            5791 -> "תשצ\"א"
            5792 -> "תשצ\"ב"
            5793 -> "תשצ\"ג"
            5794 -> "תשצ\"ד"
            5795 -> "תשצ\"ה"
            else -> "ה'$year"
        }
        
        val formattedDay = getHebrewDayGematria(day)
        val fullFormattedDate = "$formattedDay ב$monthName $yearHebrewString"
        
        return HebrewDateInfo(
            day = day,
            monthIndex = monthIndex,
            monthName = monthName,
            year = year,
            yearHebrewString = yearHebrewString,
            formattedDay = formattedDay,
            fullFormattedDate = fullFormattedDate
        )
    }
    
    // Total number of days in a given Hebrew month/year (e.g. 29 or 30) - needed for spending-pace forecasts
    fun getDaysInHebrewMonth(year: Int, monthIndex: Int): Int {
        val cal = HebrewCalendar()
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, monthIndex)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    // Timestamp (millis) of the 1st day of a given Hebrew month/year - needed for spending-pace forecasts
    fun getHebrewMonthStartTimestamp(year: Int, monthIndex: Int): Long {
        val cal = HebrewCalendar()
        cal.clear()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, monthIndex)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun getHebrewDayGematria(day: Int): String {
        return when (day) {
            1 -> "א'"
            2 -> "ב'"
            3 -> "ג'"
            4 -> "ד'"
            5 -> "ה'"
            6 -> "ו'"
            7 -> "ז'"
            8 -> "ח'"
            9 -> "ט'"
            10 -> "י'"
            11 -> "יא'"
            12 -> "יב'"
            13 -> "יג'"
            14 -> "יד'"
            15 -> "טו'"
            16 -> "טז'"
            17 -> "יז'"
            18 -> "יח'"
            19 -> "יט'"
            20 -> "כ'"
            21 -> "כא'"
            22 -> "כב'"
            23 -> "כג'"
            24 -> "כד'"
            25 -> "כה'"
            26 -> "כו'"
            27 -> "כז'"
            28 -> "כח'"
            29 -> "כט'"
            30 -> "ל'"
            else -> day.toString()
        }
    }
}
