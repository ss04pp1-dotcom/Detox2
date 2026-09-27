package com.maxleveldetox.growth

import android.content.Context
import com.maxleveldetox.MldApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * OpportunityCostEngine (v2.5.9, r11.1) — the "what this time was worth"
 * layer (user-requested roadmap addition).
 *
 * Idea, in the user's own words: "Take bolbe eivabe shomoy noshto korle eto
 * din pisaya jabe ... ei shomoy kaje lagale eita hoto" — when the user is
 * caught wasting time, show them, with THEIR OWN numbers, what the habit
 * costs per year and what the same hours could have produced.
 *
 * DATA (local, Room daily_stats — display-only, never an enforcement input):
 *   - distractingMinutes/day  (v2.5.8 high-water mark, fed by TrendSampler)
 *   - shortsWarnings/day      (every intercepted reel counts — the same
 *                              series the trend widget charts as "reels
 *                              skipped")
 *
 * PROJECTIONS (deterministic, documented constants — honest arithmetic, no
 * fake precision: a quiet week yields NO nudge rather than a zero-nudge):
 *   - yearlyDaysLost  = weeklyDistractingHours × 52 / 24   (days/year)
 *   - booksPerYear    = weeklyDistractingHours × 52 / AVG_BOOK_HOURS
 *   - takaPerYear     = weeklyDistractingHours × 52 × HOURLY_RATE_BDT
 *     (illustrative informal-sector rate; the point is scale, not salary
 *     advice — the copy always says "hoto" (would have been), never promises)
 *
 * SURFACES:
 *   1. ReelsOverlayActivity soft/hard card — one rotating Banglish line.
 *   2. InsightNotifier daily summary — appended when today ≥ 30 minutes.
 *   3. Flutter Insights screen via NativeBridge.getOpportunityCost (plus
 *      getDistractionTrend for the 7-day chart card).
 */
object OpportunityCostEngine {

    /** Avg non-fiction book ≈ 6 reading hours (industry rule of thumb). */
    const val AVG_BOOK_HOURS = 6.0

    /**
     * Illustrative value of one focused hour in BDT (BD informal-sector
     * average, deliberately conservative). Used ONLY for the "same hours,
     * different outcome" comparison — not financial advice.
     */
    const val HOURLY_RATE_BDT = 100

    /** Minimum weekly distraction before a nudge is worth showing. */
    const val MIN_WEEKLY_MINUTES = 30

    data class Snapshot(
        val weeklyMinutes: Int,
        val yearlyHours: Int,
        val yearlyDaysLost: Int,
        val booksPerYear: Int,
        val takaPerYear: Int,
        val reelsBlockedWeek: Int,
        val focusHoursWeek: Int,
        val line: String,
    )

    // -----------------------------------------------------------------
    // Computation
    // -----------------------------------------------------------------

    /** Compute the 7-day snapshot; null when there is nothing to say. */
    suspend fun snapshot(context: Context): Snapshot? = withContext(Dispatchers.Default) {
        val app = context.applicationContext as? MldApp ?: return@withContext null
        val dao = app.database.dailyStatDao()

        // Last 7 local days (inclusive of today) — same key format as the
        // daily_stats writers (yyyy-MM-dd, local timezone).
        val keys = lastSevenDayKeys()
        val rows = try {
            keys.mapNotNull { key -> dao.byKey(key) }
        } catch (_: Exception) {
            return@withContext null
        }

        val weeklyMinutes = rows.sumOf { it.distractingMinutes }
        val focusSeconds = rows.sumOf { it.focusSeconds + it.detoxSeconds }
        val reelsBlocked = rows.sumOf { it.shortsWarnings }

        if (weeklyMinutes < MIN_WEEKLY_MINUTES) return@withContext null

        val weeklyHours = weeklyMinutes / 60.0
        val yearlyHours = (weeklyHours * 52).toInt()
        val yearlyDaysLost = ((weeklyHours * 52) / 24.0).toInt().coerceAtLeast(1)
        val booksPerYear = ((weeklyHours * 52) / AVG_BOOK_HOURS).toInt().coerceAtLeast(1)
        val takaPerYear = (weeklyHours * 52 * HOURLY_RATE_BDT).toInt()

        return@withContext Snapshot(
            weeklyMinutes = weeklyMinutes,
            yearlyHours = yearlyHours,
            yearlyDaysLost = yearlyDaysLost,
            booksPerYear = booksPerYear,
            takaPerYear = takaPerYear,
            reelsBlockedWeek = reelsBlocked,
            focusHoursWeek = focusSeconds / 3600,
            line = pickLine(weeklyMinutes, yearlyDaysLost, booksPerYear, takaPerYear),
        )
    }

    /**
     * One Banglish nudge, rotating by day-of-year so it does not wear out.
     * Tone: Sinthia's honest older-sibling energy — direct about the loss,
     * concrete about the alternative, never insulting.
     */
    private fun pickLine(
        weeklyMinutes: Int,
        yearlyDays: Int,
        books: Int,
        taka: Int,
    ): String {
        val hours = weeklyMinutes / 60
        val minutesPart = weeklyMinutes % 60
        val timeStr = if (hours > 0) {
            if (minutesPart > 0) "$hours ghonta $minutesPart minute" else "$hours ghonta"
        } else {
            "$weeklyMinutes minute"
        }
        return when (dayOfYear() % 3) {
            0 -> "Ei hizbe cholle bosore puro $yearlyDays din shudhu scroll e chole jabe. " +
                "Ei shomoy ta kaje lagle $books ta boi shesh hoto."
            1 -> "Gata shoptaye $timeStr reels e gechhe. Ei shomoy ta kaje lagle " +
                "bosore ৳$taka kama hoto — shob shikhar shohoj keu e na."
            else -> "Bosore $yearlyDays din haranor hisab peyechi — ei shomoy ta kaje " +
                "lagle $books ta boi hoto. Prottek bar back chharle sei din gulo phire ashe."
        }
    }

    private fun dayOfYear(): Int = try {
        Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    } catch (_: Exception) {
        0
    }

    private fun lastSevenDayKeys(): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return (0..6).map { back ->
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -back)
            fmt.format(cal.time)
        }
    }

    // -----------------------------------------------------------------
    // Projections (bridge + notifier)
    // -----------------------------------------------------------------

    fun toJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("weeklyMinutes", s.weeklyMinutes)
        put("yearlyHours", s.yearlyHours)
        put("yearlyDaysLost", s.yearlyDaysLost)
        put("booksPerYear", s.booksPerYear)
        put("takaPerYear", s.takaPerYear)
        put("reelsBlockedWeek", s.reelsBlockedWeek)
        put("focusHoursWeek", s.focusHoursWeek)
        put("line", s.line)
        put("hourlyRateBdt", HOURLY_RATE_BDT)
    }

    /** Bridge helper: snapshot JSON or an enabled=false marker. */
    suspend fun snapshotJson(context: Context): JSONObject {
        val snap = snapshot(context)
        return if (snap == null) {
            JSONObject().put("enabled", false)
        } else {
            toJson(snap).put("enabled", true)
        }
    }

    /**
     * 7-day distraction trend series for the Insights screen chart (oldest
     * first). Zero-filled so the chart always shows seven days.
     */
    suspend fun trendJson(context: Context): JSONObject = withContext(Dispatchers.Default) {
        val app = context.applicationContext as? MldApp ?: return@withContext JSONObject()
        val dao = app.database.dailyStatDao()
        val keys = lastSevenDayKeys()
        val rows = try {
            keys.mapNotNull { key -> dao.byKey(key) }.associateBy { it.dateKey }
        } catch (_: Exception) {
            emptyMap()
        }
        JSONObject().apply {
            put("days", JSONArray().apply {
                keys.forEach { key ->
                    val r = rows[key]
                    put(JSONObject().apply {
                        put("date", key)
                        put("distractingMinutes", r?.distractingMinutes ?: 0)
                        put("reelsBlocked", r?.shortsWarnings ?: 0)
                        put("focusMinutes", ((r?.focusSeconds ?: 0) + (r?.detoxSeconds ?: 0)) / 60)
                    })
                }
            })
        }
    }
}
