package com.justdataplease.boringtravel.ai

import android.content.Context
import java.time.LocalDate

/**
 * Who may ask the planner now. Pro plans without a count; without Pro there are [FREE_PLANS] plans to try (one
 * "Make a plan" in Notes, or one chat with its follow-ups). Everyone has a daily cap of [DAILY_ANSWERS] answers,
 * which keeps one phone from running up the Gemini bill.
 */
object PlannerAllowance {
    const val FREE_PLANS = 2
    const val DAILY_ANSWERS = 40

    sealed interface Check {
        /** [freeLeft] is null with Pro. */
        data class Allowed(val freeLeft: Int?) : Check
        data object NeedsPro : Check
        data object DailyLimit : Check
    }

    /** [startsPlan]: this request begins a new plan (every Notes run, a chat's first answer). */
    fun check(isPro: Boolean, freeUsed: Int, answersToday: Int, startsPlan: Boolean): Check = when {
        answersToday >= DAILY_ANSWERS -> Check.DailyLimit
        isPro -> Check.Allowed(null)
        startsPlan && freeUsed >= FREE_PLANS -> Check.NeedsPro
        else -> Check.Allowed((FREE_PLANS - freeUsed).coerceAtLeast(0))
    }

    fun freeLeft(freeUsed: Int): Int = (FREE_PLANS - freeUsed).coerceIn(0, FREE_PLANS)
}

/** The counts behind [PlannerAllowance], in their own small preferences file. */
class PlannerUsage(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ai_planner", Context.MODE_PRIVATE)

    val freeUsed: Int get() = prefs.getInt(KEY_FREE_USED, 0)

    fun answersToday(today: LocalDate = LocalDate.now()): Int =
        if (prefs.getString(KEY_DAY, null) == today.toString()) prefs.getInt(KEY_ANSWERS, 0) else 0

    fun check(isPro: Boolean, startsPlan: Boolean, today: LocalDate = LocalDate.now()) =
        PlannerAllowance.check(isPro, freeUsed, answersToday(today), startsPlan)

    /** Counts one answer that arrived; a new plan without Pro uses one free plan. */
    fun record(isPro: Boolean, startsPlan: Boolean, today: LocalDate = LocalDate.now()) {
        val editor = prefs.edit()
            .putString(KEY_DAY, today.toString())
            .putInt(KEY_ANSWERS, answersToday(today) + 1)
        if (!isPro && startsPlan) editor.putInt(KEY_FREE_USED, freeUsed + 1)
        editor.apply()
    }

    private companion object {
        const val KEY_FREE_USED = "free_plans_used"
        const val KEY_DAY = "answers_day"
        const val KEY_ANSWERS = "answers_today"
    }
}
