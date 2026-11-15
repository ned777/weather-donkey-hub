package com.weatherwidget.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Moon phase worked out on the phone (no network): Meeus, Astronomical Algorithms
 * ch. 48–49, good to a minute or two. Same math as the web page's MOON helper.
 */
object MoonPhase {
    data class Event(val quarter: Int, val time: ZonedDateTime, val nickname: String?) {
        val name get() = NAMES[quarter]
    }

    data class Info(
        val name: String, val lit: Double, val waxing: Boolean,
        val fullName: String?, val upcoming: List<Event>
    )

    private val NAMES = listOf("New Moon", "First Quarter", "Full Moon", "Last Quarter")
    private val BETWEEN = listOf("Waxing Crescent", "Waxing Gibbous", "Waning Gibbous", "Waning Crescent")
    private val FULL_NAMES = listOf("Wolf", "Snow", "Worm", "Pink", "Flower", "Strawberry", "Buck",
        "Sturgeon", "Harvest", "Hunter's", "Beaver", "Cold")

    private fun s(d: Double) = sin(Math.toRadians(d))
    private fun c(d: Double) = cos(Math.toRadians(d))
    private fun jdOf(ms: Long) = ms / 86400000.0 + 2440587.5

    /** q: 0 new, 1 first quarter, 2 full, 3 last quarter, of lunation k (k = 0 → Jan 6, 2000). */
    private fun phaseTime(k0: Int, q: Int): Instant {
        val k = k0 + q / 4.0
        val t = k / 1236.85
        val e = 1 - 0.002516 * t - 0.0000074 * t * t
        val m = 2.5534 + 29.1053567 * k - 0.0000014 * t * t
        val mp = 201.5643 + 385.81693528 * k + 0.0107582 * t * t + 0.00001238 * t * t * t
        val f = 160.7108 + 390.67050284 * k - 0.0016118 * t * t - 0.00000227 * t * t * t
        val o = 124.7746 - 1.56375588 * k + 0.0020672 * t * t
        var jd = 2451550.09766 + 29.530588861 * k + 0.00015437 * t * t - 0.00000015 * t * t * t
        if (q == 0 || q == 2) {
            val n = q == 0
            jd += (if (n) -0.4072 else -0.40614) * s(mp) + (if (n) 0.17241 else 0.17302) * e * s(m) +
                (if (n) 0.01608 else 0.01614) * s(2 * mp) + (if (n) 0.01039 else 0.01043) * s(2 * f) +
                (if (n) 0.00739 else 0.00734) * e * s(mp - m) - (if (n) 0.00514 else 0.00515) * e * s(mp + m) +
                (if (n) 0.00208 else 0.00209) * e * e * s(2 * m) - 0.00111 * s(mp - 2 * f) - 0.00057 * s(mp + 2 * f) +
                0.00056 * e * s(2 * mp + m) - 0.00042 * s(3 * mp) + 0.00042 * e * s(m + 2 * f) +
                0.00038 * e * s(m - 2 * f) - 0.00024 * e * s(2 * mp - m) - 0.00017 * s(o)
        } else {
            jd += -0.62801 * s(mp) + 0.17172 * e * s(m) - 0.01183 * e * s(mp + m) + 0.00862 * s(2 * mp) +
                0.00804 * s(2 * f) + 0.00454 * e * s(mp - m) + 0.00204 * e * e * s(2 * m) - 0.0018 * s(mp - 2 * f) -
                0.0007 * s(mp + 2 * f) - 0.0004 * s(3 * mp) - 0.00034 * e * s(2 * mp - m) + 0.00032 * e * s(m + 2 * f) +
                0.00032 * e * s(m - 2 * f) - 0.00028 * e * e * s(mp + 2 * m) + 0.00027 * e * s(2 * mp + m) - 0.00017 * s(o)
            val w = 0.00306 - 0.00038 * e * c(m) + 0.00026 * c(mp) - 0.00002 * c(mp - m) +
                0.00002 * c(mp + m) + 0.00002 * c(2 * f)
            jd += if (q == 1) w else -w
        }
        jd -= 69.0 / 86400 // TT → UTC
        return Instant.ofEpochMilli(((jd - 2440587.5) * 86400000).toLong())
    }

    /** Fraction lit (0–1) and whether it's growing. */
    private fun illumination(ms: Long): Pair<Double, Boolean> {
        val t = (jdOf(ms) - 2451545) / 36525
        val d = 297.8501921 + 445267.1114034 * t
        val m = 357.5291092 + 35999.0502909 * t
        val mp = 134.9633964 + 477198.8675055 * t
        val i = 180 - d - 6.289 * s(mp) + 2.1 * s(m) - 1.274 * s(2 * d - mp) - 0.658 * s(2 * d) -
            0.214 * s(2 * mp) - 0.11 * s(d)
        return (1 + c(i)) / 2 to (((d % 360) + 360) % 360 < 180)
    }

    fun info(now: ZonedDateTime = ZonedDateTime.now(ZoneId.systemDefault())): Info {
        val zone = now.zone
        val nowMs = now.toInstant().toEpochMilli()
        val k0 = floor((jdOf(nowMs) - 2451550.09766) / 29.530588861).toInt() - 1
        val raw = (k0 until k0 + 4).flatMap { k -> (0..3).map { q -> q to phaseTime(k, q).atZone(zone) } }
            .sortedBy { it.second }
        fun nick(q: Int, t: ZonedDateTime): String? {
            if (q != 2) return null
            // second full moon in one calendar month is a "Blue Moon"
            val blue = raw.any { (oq, ot) -> oq == 2 && ot < t && ot.year == t.year && ot.month == t.month }
            return if (blue) "Blue Moon" else "${FULL_NAMES[t.monthValue - 1]} Moon"
        }
        val events = raw.map { (q, t) -> Event(q, t, nick(q, t)) }
        val today = now.toLocalDate()
        val todayEv = events.firstOrNull { it.time.toLocalDate() == today }
        val prev = events.last { it.time <= now }
        val (lit, waxing) = illumination(nowMs)
        return Info(
            name = todayEv?.name ?: BETWEEN[prev.quarter],
            lit = lit, waxing = waxing,
            fullName = todayEv?.takeIf { it.quarter == 2 }?.nickname,
            upcoming = events.filter { it.time > now && it != todayEv }.take(4)
        )
    }

    /** Lit fraction and side for an icon of one of the four main phases. */
    fun iconFor(quarter: Int) = listOf(0.0, 0.5, 1.0, 0.5)[quarter] to (quarter < 2)
}

/** A moon drawn with its real shadow: lit side on the right while waxing (northern sky). */
class MoonView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var lit = 0.5
        set(v) { field = v; invalidate() }
    var waxing = true
        set(v) { field = v; invalidate() }

    private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#24243A") }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4A4A66"); style = Paint.Style.STROKE }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5FF") }
    private val path = Path()
    private val oval = RectF()
    private val term = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) / 2f * 0.92f
        val cx = width / 2f
        val cy = height / 2f
        rim.strokeWidth = r / 23f
        canvas.drawCircle(cx, cy, r, dark)
        canvas.drawCircle(cx, cy, r, rim)
        when {
            lit < 0.005 -> return
            lit > 0.995 -> { canvas.drawCircle(cx, cy, r, light); return }
        }
        val rx = (abs(1 - 2 * lit) * r).toFloat()
        oval.set(cx - r, cy - r, cx + r, cy + r)
        term.set(cx - rx, cy - r, cx + rx, cy + r)
        path.reset()
        path.moveTo(cx, cy - r)
        // outer edge: top → bottom along the lit side (right while waxing)
        path.arcTo(oval, -90f, if (waxing) 180f else -180f)
        // terminator: back to the top, bulging toward the lit side for a crescent
        val crescent = lit < 0.5
        path.arcTo(term, 90f, if (waxing == crescent) -180f else 180f)
        path.close()
        canvas.drawPath(path, light)
    }
}
