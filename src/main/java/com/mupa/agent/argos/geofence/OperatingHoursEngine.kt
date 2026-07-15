package com.mupa.agent.argos.geofence

import android.content.Context
import android.util.Log
import com.mupa.agent.argos.managers.SettingsManager
import kotlinx.coroutines.runBlocking
import java.util.Calendar

class OperatingHoursEngine(private val context: Context) {

    private val tag = "OperatingHours"

    fun check() {
        val settings = SettingsManager(context)
        if (!settings.isOperatingHoursEnabledCached()) {
            if (settings.isOperatingHoursLockedCached()) {
                runBlocking { settings.setOperatingHoursLocked(false) }
            }
            return
        }

        val start = settings.getOperatingHoursStartCached()
        val end = settings.getOperatingHoursEndCached()
        val days = settings.getOperatingHoursDaysCached()

        if (start.isBlank() || end.isBlank()) return

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = parseTimeToMinutes(start)
        val endMinutes = parseTimeToMinutes(end)
        if (startMinutes < 0 || endMinutes < 0) return

        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        if (days.isNotBlank() && !isDayAllowed(days, dayOfWeek)) {
            if (!settings.isOperatingHoursLockedCached()) {
                Log.i(tag, "Outside operating day (day=$dayOfWeek). Locking.")
                runBlocking { settings.setOperatingHoursLocked(true) }
            }
            return
        }

        val withinHours = if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }

        if (withinHours) {
            if (settings.isOperatingHoursLockedCached()) {
                Log.i(tag, "Within operating hours ($start-$end). Unlocking.")
                runBlocking { settings.setOperatingHoursLocked(false) }
            }
        } else {
            if (!settings.isOperatingHoursLockedCached()) {
                Log.i(tag, "Outside operating hours ($start-$end, current=${now.get(Calendar.HOUR_OF_DAY)}:${now.get(Calendar.MINUTE)}). Locking.")
                runBlocking { settings.setOperatingHoursLocked(true) }
            }
        }
    }

    private fun parseTimeToMinutes(time: String): Int {
        val parts = time.split(":")
        if (parts.size != 2) return -1
        val h = parts[0].trim().toIntOrNull() ?: return -1
        val m = parts[1].trim().toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    private fun isDayAllowed(days: String, calendarDay: Int): Boolean {
        // days is comma-separated: "1,2,3,4,5,6,7" where 1=Sunday...7=Saturday (Calendar convention)
        // OR "seg,ter,qua,qui,sex,sab,dom" style
        // OR "mon,tue,wed,thu,fri,sat,sun"
        val dayList = days.lowercase().split(",").map { it.trim() }
        val numericDay = calendarDay.toString()
        if (dayList.contains(numericDay)) return true
        val nameMap = mapOf(
            Calendar.SUNDAY to listOf("dom", "sun", "0"),
            Calendar.MONDAY to listOf("seg", "mon", "1"),
            Calendar.TUESDAY to listOf("ter", "tue", "2"),
            Calendar.WEDNESDAY to listOf("qua", "wed", "3"),
            Calendar.THURSDAY to listOf("qui", "thu", "4"),
            Calendar.FRIDAY to listOf("sex", "fri", "5"),
            Calendar.SATURDAY to listOf("sab", "sat", "6"),
        )
        val aliases = nameMap[calendarDay] ?: emptyList()
        return dayList.any { it in aliases }
    }
}
