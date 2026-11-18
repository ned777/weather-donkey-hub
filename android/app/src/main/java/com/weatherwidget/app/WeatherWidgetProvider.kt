package com.weatherwidget.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlin.math.roundToInt

/**
 * How much room the widget currently has, driving both font sizes and which
 * rows show at all. Taken from the smaller of the widget's reported min
 * width/height, so a widget that's short-and-wide or tall-and-narrow is
 * judged by its tightest dimension either way. Only genuinely tiny sizes
 * (roughly a 1x1/2x1 grid cell) drop to COMPACT — anything past that gets
 * the full layout, since a half-empty "medium" tier just meant smaller text
 * and a missing forecast row for no real reason.
 */
private enum class WidgetSizeTier { COMPACT, FULL }

/**
 * The widget's brain. Tapping "Refresh" at the top-left reloads this widget's weather
 * from the server — the server's latest update, not a new fetch from the weather
 * sources (onReceive() below); tapping anywhere else opens the app. It also repaints
 * whenever the app refreshes (pull-down); there is no timer. onUpdate() and onAppWidgetOptionsChanged() (called on
 * first placement, reboot, and resize) only ever repaint from whatever is
 * already cached.
 */
// AppWidgetProvider is a special-purpose BroadcastReceiver — a component Android
// wakes up briefly to handle one event, then lets go dormant again, rather than a
// screen the user is looking at (that's what MainActivity is). Home-screen widgets
// don't run continuously; Android calls into this class only at specific moments:
// when a widget is first placed, resized, removed, or (via onReceive below) tapped.
open class WeatherWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.weatherwidget.app.ACTION_REFRESH"

        // Shortest time "Updating from server…" stays up after tapping "Refresh".
        private const val MIN_UPDATING_MS = 1200L

        /**
         * Repaints every placed widget from whatever's cached, with no network call —
         * call this right after MainActivity does its own fetch (or a unit toggle), so
         * any placed widget picks up that same data immediately instead of waiting for
         * its own tap.
         */
        fun repaintAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            allWidgetIds(context, manager).forEach { renderFromCache(context, manager, it) }
        }

        /** Called by WeatherWidgetConfigActivity right after binding a location to a brand-new widget id. */
        fun renderConfiguredWidget(context: Context, appWidgetId: Int) {
            renderFromCache(context, AppWidgetManager.getInstance(context), appWidgetId)
        }

        private fun renderFromCache(context: Context, manager: AppWidgetManager, id: Int) {
            val locationId = WidgetLocationBinding.get(context, id)
            val needsPermission = locationId == WeatherCache.CURRENT_LOCATION_ID && !LocationHelper.hasPermission(context)
            val snapshot = WeatherCache.read(context, locationId)?.forSource(SourcePreference.get(context))
            val statusText = when {
                needsPermission -> context.getString(R.string.widget_need_permission)
                snapshot != null -> statusFor(snapshot)
                else -> context.getString(R.string.widget_tap_to_load)
            }
            manager.updateAppWidget(id, buildViews(context, manager, id, locationId, snapshot, statusText))
        }

        /**
         * The "Refresh" tap: asks the server for its latest weather for this widget's place
         * (fresh = false — the server's last update, not a new pull from the weather
         * sources; the app's pull-down can do that). For the "current" binding a GPS fix
         * comes first; a saved city uses its fixed coordinates. Always called from a
         * background Thread (see onReceive) — never the main thread. Pushes an
         * "Updating from server…" frame immediately so the tap feels responsive.
         */
        private fun performRefresh(context: Context, manager: AppWidgetManager, id: Int) {
            val locationId = WidgetLocationBinding.get(context, id)
            // The server answers in a few ms on home Wi-Fi; hold "Updating from server…"
            // at least this long so the tap visibly did something.
            val showUntil = System.currentTimeMillis() + MIN_UPDATING_MS

            if (locationId == WeatherCache.CURRENT_LOCATION_ID) {
                if (!LocationHelper.hasPermission(context)) {
                    renderFromCache(context, manager, id)
                    return
                }
                val cached = WeatherCache.read(context, locationId)?.forSource(SourcePreference.get(context))
                manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, context.getString(R.string.widget_loading)))

                val location = LocationHelper.getLocationBlocking(context)
                if (location == null) {
                    manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, statusWithFallback(context, cached, context.getString(R.string.widget_error_no_location))))
                    return
                }

                WeatherCache.saveCurrentCoords(context, location.latitude, location.longitude)
                val cityName = GeocodeHelper.cityName(context, location)
                val outcome = WeatherClient.fetchWeather(locationId, cityName, location.latitude, location.longitude, fresh = false)
                finishRefresh(context, manager, id, locationId, cached, outcome, cityName, showUntil)
            } else {
                val saved = LocationStore.list(context).find { it.id == locationId }
                if (saved == null) {
                    // The location this widget was bound to got removed from the app
                    // since — fall back to whatever's cached (nothing) rather than crash.
                    renderFromCache(context, manager, id)
                    return
                }
                val cached = WeatherCache.read(context, locationId)?.forSource(SourcePreference.get(context))
                manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached, context.getString(R.string.widget_loading)))

                val outcome = WeatherClient.fetchWeather(locationId, saved.displayName, saved.lat, saved.lon, fresh = false)
                finishRefresh(context, manager, id, locationId, cached, outcome, saved.displayName, showUntil)
            }
        }

        // e.g. "3:45 PM · Aug 23 · PDT — Couldn't reach weather service — tap to retry", so a
        // failed refresh still shows when the last good data came in, not just "error".
        private fun finishRefresh(
            context: Context, manager: AppWidgetManager, id: Int, locationId: String,
            cached: WeatherSnapshot?, outcome: WeatherClient.Outcome<WeatherSnapshot>, cityName: String?,
            showUntil: Long = 0L
        ) {
            val wait = showUntil - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            when (outcome) {
                is WeatherClient.Outcome.Ok -> {
                    val snapshot = outcome.value.copy(cityName = cityName ?: outcome.value.cityName)
                    WeatherCache.save(context, locationId, snapshot)
                    val shown = snapshot.forSource(SourcePreference.get(context))
                    manager.updateAppWidget(id, buildViews(context, manager, id, locationId, shown, statusFor(snapshot)))
                }
                WeatherClient.Outcome.ServerUnreachable -> manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached,
                    statusWithFallback(context, cached, context.getString(R.string.widget_error_server))))
                is WeatherClient.Outcome.Failed -> manager.updateAppWidget(id, buildViews(context, manager, id, locationId, cached,
                    statusWithFallback(context, cached, outcome.message)))
            }
        }

        /** "Server update 15:45 · Nov 14 · PST" (when the server last got this weather from the
         *  sources), plus "· MET Norway only" when the server had to use its backup source. */
        private fun statusFor(snapshot: WeatherSnapshot): String {
            val stamp = "Server update ${WeatherFormat.lastUpdatedTimestamp(snapshot.fetchedAt)}"
            return snapshot.notice?.let { "$stamp · ${WeatherFormat.shortNotice(it)}" } ?: stamp
        }

        private fun statusWithFallback(context: Context, cached: WeatherSnapshot?, error: String): String =
            if (cached != null) "${WeatherFormat.lastUpdatedTimestamp(cached.fetchedAt)} — $error" else error

        /**
         * Builds one fully-ready RemoteViews frame: content, size-based row
         * visibility, and the click PendingIntent (which itself depends on whether
         * we have location permission yet — see setClickIntent below). An empty
         * statusText means "nothing wrong to report" and hides that line entirely.
         */
        // RemoteViews is a special stand-in for a normal layout: a widget's UI is
        // actually drawn by the HOME SCREEN LAUNCHER app, not by this app's own process,
        // so this code can't just grab views with findViewById and set properties on
        // them directly the way MainActivity does. Instead, RemoteViews records a list
        // of instructions ("set this TextView's text to X", "set this view's size to Y")
        // that gets handed across to the launcher process, which then actually applies
        // them and draws the result.
        /** Every placed widget of both sizes: the 2x2 and the 4x2 wide one (a subclass of this provider). */
        private fun allWidgetIds(context: Context, manager: AppWidgetManager): IntArray =
            manager.getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java)) +
                manager.getAppWidgetIds(ComponentName(context, WeatherWideWidgetProvider::class.java))

        private fun isWide(manager: AppWidgetManager, id: Int): Boolean =
            manager.getAppWidgetInfo(id)?.provider?.className == WeatherWideWidgetProvider::class.java.name

        private fun buildViews(context: Context, manager: AppWidgetManager, id: Int, locationId: String, snapshot: WeatherSnapshot?, statusText: String): RemoteViews {
            if (isWide(manager, id)) return buildWideViews(context, manager, id, locationId, snapshot, statusText)
            val views = RemoteViews(context.packageName, R.layout.widget_weather)
            WidgetCorners.apply(views, R.id.weatherWidgetRoot, manager, id)
            val fahrenheit = UnitPreference.isFahrenheit(context)

            val cityStr: String
            val tempStr: String
            val todayHighLowStr: String
            val conditionStr: String
            val sunriseStr: String
            val sunsetStr: String
            val day1: DailyForecast?
            val day2: DailyForecast?

            if (snapshot != null) {
                cityStr = widgetCityLabel(snapshot.cityName ?: context.getString(R.string.current_location))
                tempStr = WeatherFormat.tempString(snapshot.tempF, fahrenheit)
                todayHighLowStr = WeatherFormat.highLowString(snapshot.todayHighF, snapshot.todayLowF, fahrenheit)
                conditionStr = snapshot.condition.label
                sunriseStr = "↑ ${WeatherFormat.clockTime(snapshot.sunrise)}"
                sunsetStr = "↓ ${WeatherFormat.clockTime(snapshot.sunset)}"
                val upcoming = upcomingForecast(snapshot)
                day1 = upcoming.getOrNull(0)
                day2 = upcoming.getOrNull(1)
            } else {
                // No fetch has happened yet for this widget — still show which location
                // it's bound to (helpful right after configuring it) if it's not "current".
                cityStr = widgetCityLabel(
                    if (locationId == WeatherCache.CURRENT_LOCATION_ID) {
                        context.getString(R.string.current_location)
                    } else {
                        LocationStore.list(context).find { it.id == locationId }?.displayName
                            ?: context.getString(R.string.current_location)
                    }
                )
                tempStr = "--°"
                todayHighLowStr = "H:--°  L:--°"
                conditionStr = "--"
                sunriseStr = "↑ --:--"
                sunsetStr = "↓ --:--"
                day1 = null
                day2 = null
            }
            val todayLabel = context.getString(R.string.widget_today_label)
            val forecast1Day = (day1?.dateLabel ?: "--").uppercase()
            val forecast2Day = (day2?.dateLabel ?: "--").uppercase()
            val forecast1HighLow = forecastHighLowString(day1, fahrenheit)
            val forecast2HighLow = forecastHighLowString(day2, fahrenheit)
            val forecast1Condition = day1?.condition?.label ?: "--"
            val forecast2Condition = day2?.condition?.label ?: "--"

            views.setTextViewText(R.id.cityText, cityStr)
            views.setTextViewText(R.id.todayLabelText, todayLabel)
            views.setTextViewText(R.id.tempText, tempStr)
            views.setTextViewText(R.id.todayHighLowText, todayHighLowStr)
            views.setTextViewText(R.id.conditionText, conditionStr)
            views.setTextViewText(R.id.sunriseText, sunriseStr)
            views.setTextViewText(R.id.sunsetText, sunsetStr)
            views.setTextViewText(R.id.updatedText, statusText)
            showUpdatingState(context, views, statusText)
            views.setTextViewText(R.id.forecast1Day, forecast1Day)
            views.setTextViewText(R.id.forecast1HighLow, forecast1HighLow)
            views.setTextViewText(R.id.forecast1Condition, forecast1Condition)
            views.setTextViewText(R.id.forecast2Day, forecast2Day)
            views.setTextViewText(R.id.forecast2HighLow, forecast2HighLow)
            views.setTextViewText(R.id.forecast2Condition, forecast2Condition)

            applyResponsiveSizing(
                context, views, manager, id,
                tempStr, todayHighLowStr, todayLabel, conditionStr, sunriseStr, sunsetStr,
                forecast1Day, forecast2Day, forecast1HighLow, forecast2HighLow,
                forecast1Condition, forecast2Condition
            )
            setClickIntent(context, views, id, locationId)
            return views
        }

        // The city header is narrow — a long name gets a hard character-count
        // truncation ("San Francisco" -> "San Fran..") on top of (not instead of) the
        // normal width-based fit-sizing/ellipsize. 8 chars + ".." = 10 chars max.
        private val WIDE_STAT_LABELS = intArrayOf(R.id.wideStat0Label, R.id.wideStat1Label, R.id.wideStat2Label, R.id.wideStat3Label)
        private val WIDE_STAT_VALUES = intArrayOf(R.id.wideStat0Value, R.id.wideStat1Value, R.id.wideStat2Value, R.id.wideStat3Value)
        private val WIDE_DAY_LABELS = intArrayOf(R.id.wideDay0Label, R.id.wideDay1Label, R.id.wideDay2Label, R.id.wideDay3Label, R.id.wideDay4Label)
        private val WIDE_DAY_HIGHS = intArrayOf(R.id.wideDay0High, R.id.wideDay1High, R.id.wideDay2High, R.id.wideDay3High, R.id.wideDay4High)
        private val WIDE_DAY_LOWS = intArrayOf(R.id.wideDay0Low, R.id.wideDay1Low, R.id.wideDay2Low, R.id.wideDay3Low, R.id.wideDay4Low)

        /**
         * The 4x2 wide widget (widget_weather_wide.xml): the city name, a hero row (temperature, condition,
         * high/low, then feels-like / rain / wind / humidity), the next 5 days with
         * sunrise/sunset at the end of that row, and a one-line refresh strip. Uses the same
         * root/refresh view ids as the 2x2 so setClickIntent() and the
         * "Updating…" swap work unchanged.
         */
        private fun buildWideViews(context: Context, manager: AppWidgetManager, id: Int, locationId: String, snapshot: WeatherSnapshot?, statusText: String): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_weather_wide)
            WidgetCorners.apply(views, R.id.weatherWidgetRoot, manager, id)
            val fahrenheit = UnitPreference.isFahrenheit(context)

            val city = snapshot?.cityName
                ?: if (locationId == WeatherCache.CURRENT_LOCATION_ID) context.getString(R.string.current_location)
                else LocationStore.list(context).find { it.id == locationId }?.displayName ?: context.getString(R.string.current_location)
            views.setTextViewText(R.id.wideCityText, city)

            val tempStr = snapshot?.let { WeatherFormat.tempString(it.tempF, fahrenheit) } ?: "--°"
            views.setTextViewText(R.id.wideTempText, tempStr)
            // 60sp, shrunk only if a 3-digit / negative reading wouldn't fit its ~112dp slot.
            setSp(context, views, R.id.wideTempText, fitWidthSp(context, tempStr, dpToPx(context, 112).toFloat(), 60f, 34f, bold = true))
            views.setTextViewText(R.id.wideConditionText, snapshot?.condition?.label ?: "--")
            views.setTextViewText(
                R.id.wideHighLowText,
                snapshot?.let { WeatherFormat.highLowString(it.todayHighF, it.todayLowF, fahrenheit) } ?: "H:--°  L:--°"
            )

            val d = snapshot?.details
            val wind = snapshot?.let { s ->
                val dir = d?.windDir?.let { " " + WeatherFormat.compass(it) } ?: ""
                "${Math.round(s.windSpeedMph)} mph$dir"
            } ?: "--"
            val stats = listOf(
                "Feels" to (d?.feelsLikeF?.let { WeatherFormat.tempString(it, fahrenheit) } ?: "--"),
                "Rain" to (snapshot?.let { WeatherFormat.rainPct(it.todayRainChancePercent) } ?: "--"),
                "Wind" to wind,
                "Humidity" to WeatherFormat.pct(d?.humidity)
            )
            stats.forEachIndexed { i, (label, value) ->
                views.setTextViewText(WIDE_STAT_LABELS[i], label)
                views.setTextViewText(WIDE_STAT_VALUES[i], value)
            }

            // Next 5 days still ahead of today: day, then high over low (low dimmed). Icons and rain chance are app-only.
            val upcoming = snapshot?.let { upcomingForecast(it) } ?: emptyList()
            for (i in 0 until 5) {
                val day = upcoming.getOrNull(i)
                // A loaded snapshot that has run out of days (cached a while ago) leaves
                // the slot blank rather than placeholder "--".
                val blank = day == null && snapshot != null
                views.setTextViewText(WIDE_DAY_LABELS[i], day?.dateLabel?.uppercase() ?: if (blank) "" else "--")
                views.setTextViewText(WIDE_DAY_HIGHS[i], day?.let { WeatherFormat.tempString(it.highF, fahrenheit) } ?: if (blank) "" else "--°")
                views.setTextViewText(WIDE_DAY_LOWS[i], day?.let { WeatherFormat.tempString(it.lowF, fahrenheit) } ?: if (blank) "" else "--°")
            }

            // Sunrise / sunset at the end of the forecast row, yellow arrows.
            fun sunLine(arrow: String, iso: String?): CharSequence {
                val text = android.text.SpannableString("$arrow ${iso?.let { WeatherFormat.clockTime(it) } ?: "--:--"}")
                text.setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.retro_yellow)), 0, arrow.length, 0)
                return text
            }
            views.setTextViewText(R.id.wideSunriseText, sunLine("↑", snapshot?.sunrise))
            views.setTextViewText(R.id.wideSunsetText, sunLine("↓", snapshot?.sunset))

            views.setTextViewText(R.id.updatedText, statusText)
            showUpdatingState(context, views, statusText)

            setClickIntent(context, views, id, locationId)
            return views
        }

        /**
         * The forecast days still ahead of today. snapshot.forecast starts at the day
         * after it was fetched, so a snapshot cached a few days ago (phone away from
         * the home network) would otherwise lead with days already gone — drop those.
         */
        private fun upcomingForecast(snapshot: WeatherSnapshot): List<DailyForecast> {
            val fetchedOn = runCatching { java.time.LocalDate.parse(snapshot.localTime.take(10)) }.getOrElse {
                java.time.Instant.ofEpochMilli(snapshot.fetchedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            }
            val daysSince = java.time.temporal.ChronoUnit.DAYS.between(fetchedOn, java.time.LocalDate.now()).toInt()
            return snapshot.forecast.drop(daysSince.coerceAtLeast(0))
        }

        private fun widgetCityLabel(name: String): String =
            if (name.length > 8) name.take(8) + ".." else name

        private fun forecastHighLowString(day: DailyForecast?, fahrenheit: Boolean): String =
            if (day != null) "${WeatherFormat.tempString(day.highF, fahrenheit)}/${WeatherFormat.tempString(day.lowF, fahrenheit)}" else "--°/--°"

        /**
         * COMPACT (roughly a 1x1/2x1 grid cell) drops to just the temperature,
         * location, and condition word — genuinely too little room for more. Every
         * other size gets the FULL layout: today's high/low, sunrise/sunset (each on
         * their own line), and the two-day forecast row filling the bottom half.
         * Padding is always a visible constant margin either way, never near-zero.
         * No icons anywhere in this widget, by request — condition is always words.
         *
         * Every text size below is a *maximum* — fitWidthSp() measures the actual
         * string against the column it has to live in and shrinks from there if
         * needed (the same "measure once, scale proportionally" trick
         * DateWidgetProvider uses for the date widget's day number), so nothing ever
         * wraps onto a second line and eats space the forecast row needed.
         */
        private fun applyResponsiveSizing(
            context: Context, views: RemoteViews, manager: AppWidgetManager, id: Int,
            tempStr: String, todayHighLowStr: String, todayLabel: String, conditionStr: String,
            sunriseStr: String, sunsetStr: String, forecast1Day: String, forecast2Day: String,
            forecast1HighLow: String, forecast2HighLow: String, forecast1Condition: String, forecast2Condition: String
        ) {
            val options = manager.getAppWidgetOptions(id)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
            val tier = if (minOf(minWidthDp, minHeightDp) < 130) WidgetSizeTier.COMPACT else WidgetSizeTier.FULL

            // Sides keep the original margin; top and bottom stay equal to each other,
            // so the city name up top and the forecast's last line at the bottom sit
            // the same distance from their respective edges.
            val paddingDp = if (tier == WidgetSizeTier.COMPACT) 10 else 16
            val verticalPaddingDp = if (tier == WidgetSizeTier.COMPACT) 8 else 13
            // + the border inset, so content keeps its distance from the (inset) border.
            val paddingPx = dpToPx(context, paddingDp + WidgetCorners.BORDER_INSET_DP)
            val verticalPaddingPx = dpToPx(context, verticalPaddingDp + WidgetCorners.BORDER_INSET_DP)
            views.setViewPadding(R.id.weatherWidgetRoot, paddingPx, verticalPaddingPx, paddingPx, verticalPaddingPx)

            // Both the top row's two columns and the forecast row's two cells carry a real
            // 4dp+4dp margin against their neighbor (see widget_weather.xml), so the
            // horizontal gap between columns equals the vertical gap between topRow and
            // forecastRow — a true symmetric grid. The city name spans the FULL width instead.
            val widthPx = dpToPx(context, minWidthDp)
            val fullWidthPx = (widthPx - 2 * paddingPx).toFloat().coerceAtLeast(0f)
            val columnGapPx = dpToPx(context, 8)
            val halfColumnPx = ((fullWidthPx - columnGapPx) / 2f).coerceAtLeast(0f)

            val tempMaxSp = if (tier == WidgetSizeTier.FULL) 50f else 27f
            var tempSp = fitWidthSp(context, tempStr, halfColumnPx, tempMaxSp, tempMaxSp * 0.45f, bold = true)

            // Day label and condition word (forecastLabelSp) is computed up front — at
            // FULL tier, "TODAY" and the city name both match it exactly (by request),
            // rather than "TODAY" being fit independently.
            val forecastLabelSp = if (tier == WidgetSizeTier.FULL) {
                minOf(
                    fitWidthSp(context, forecast1Day, halfColumnPx, 13f, 8f, bold = true),
                    fitWidthSp(context, forecast2Day, halfColumnPx, 13f, 8f, bold = true),
                    fitWidthSp(context, forecast1Condition, halfColumnPx, 13f, 8f, bold = false),
                    fitWidthSp(context, forecast2Condition, halfColumnPx, 13f, 8f, bold = false)
                )
            } else {
                0f
            }

            val todaySp = if (tier == WidgetSizeTier.FULL) {
                forecastLabelSp
            } else {
                fitWidthSp(context, todayLabel, halfColumnPx, 11f, 9f, bold = true)
            }
            setSp(context, views, R.id.todayLabelText, todaySp)
            setSp(context, views, R.id.cityText, todaySp)

            // conditionText stays visible at every size (it's the only thing telling you
            // sunny/cloudy/rainy/etc. now that there's no icon) — just smaller when tight.
            var conditionSp = fitWidthSp(context, conditionStr, halfColumnPx, if (tier == WidgetSizeTier.FULL) 14f else 11f, 8f, bold = true)

            if (tier == WidgetSizeTier.FULL) {
                var todayHighLowSp = fitWidthSp(context, todayHighLowStr, halfColumnPx, 14f, 10f, bold = true)
                var sunriseSp = fitWidthSp(context, sunriseStr, halfColumnPx, 13f, 10f, bold = false)
                var sunsetSp = fitWidthSp(context, sunsetStr, halfColumnPx, 13f, 10f, bold = false)

                // Both forecast cells use the smaller of the two needed sizes, so they stay
                // visually matched instead of one day's number being bigger than the other's.
                var forecastHighLowSp = minOf(
                    fitWidthSp(context, forecast1HighLow, halfColumnPx, 13f, 9f, bold = false),
                    fitWidthSp(context, forecast2HighLow, halfColumnPx, 13f, 9f, bold = false)
                )
                var finalForecastLabelSp = forecastLabelSp

                // topRow and forecastRow both carry weight="1" now (see widget_weather.xml),
                // so Android always gives them EXACTLY equal heights regardless of content —
                // that shared half is the real vertical budget each one has to fit within,
                // not just an estimate.
                val cityHeightPx = textHeightPx(context, todaySp, bold = true)
                // The bottom status line (one line of 10sp) plus its 4dp top margin (widget_weather.xml).
                val updatedHeightPx = textHeightPx(context, 10f, bold = false) + dpToPx(context, 4)
                val totalHeightPx = dpToPx(context, minHeightDp).toFloat()
                val availableContentHeightPx = (
                    totalHeightPx - 2 * verticalPaddingPx - cityHeightPx -
                        dpToPx(context, 2) - dpToPx(context, 8) - updatedHeightPx
                    ).coerceAtLeast(0f)
                val halfContentHeightPx = availableContentHeightPx / 2f

                // Width-fitting alone isn't enough for either row — the top row stacks two
                // lines (temp/high-low) against three (TODAY/condition/sunrise+sunset), and
                // the forecast cells stack three (day/high-low/condition). Shrink each row's
                // text uniformly if its taller side doesn't fit the equal half it now has.
                val topLeftColHeightPx = textHeightPx(context, tempSp, bold = true) +
                    textHeightPx(context, todayHighLowSp, bold = true)
                val topRightColHeightPx = textHeightPx(context, todaySp, bold = true) +
                    textHeightPx(context, conditionSp, bold = true) +
                    textHeightPx(context, sunriseSp, bold = false) +
                    textHeightPx(context, sunsetSp, bold = false) -
                    dpToPx(context, 6) // matches conditionText's and sunsetText's negative marginTop in widget_weather.xml
                val topRowNaturalHeightPx = maxOf(topLeftColHeightPx, topRightColHeightPx)
                if (topRowNaturalHeightPx > halfContentHeightPx && topRowNaturalHeightPx > 0f) {
                    val scale = halfContentHeightPx / topRowNaturalHeightPx
                    tempSp = (tempSp * scale).coerceAtLeast(tempMaxSp * 0.3f)
                    todayHighLowSp = (todayHighLowSp * scale).coerceAtLeast(7f)
                    conditionSp = (conditionSp * scale).coerceAtLeast(7f)
                    sunriseSp = (sunriseSp * scale).coerceAtLeast(7f)
                    sunsetSp = (sunsetSp * scale).coerceAtLeast(7f)
                }

                val neededStackHeightPx = textHeightPx(context, finalForecastLabelSp, bold = true) +
                    textHeightPx(context, forecastHighLowSp, bold = false) +
                    textHeightPx(context, finalForecastLabelSp, bold = false) -
                    dpToPx(context, 3) // matches forecastCondition's negative marginTop in widget_weather.xml
                if (neededStackHeightPx > halfContentHeightPx && neededStackHeightPx > 0f) {
                    val scale = halfContentHeightPx / neededStackHeightPx
                    finalForecastLabelSp = (finalForecastLabelSp * scale).coerceAtLeast(7f)
                    forecastHighLowSp = (forecastHighLowSp * scale).coerceAtLeast(7f)
                }

                setSp(context, views, R.id.tempText, tempSp)
                setSp(context, views, R.id.conditionText, conditionSp)
                setSp(context, views, R.id.todayHighLowText, todayHighLowSp)
                setSp(context, views, R.id.sunriseText, sunriseSp)
                setSp(context, views, R.id.sunsetText, sunsetSp)
                setSp(context, views, R.id.forecast1HighLow, forecastHighLowSp)
                setSp(context, views, R.id.forecast2HighLow, forecastHighLowSp)
                setSp(context, views, R.id.forecast1Day, finalForecastLabelSp)
                setSp(context, views, R.id.forecast2Day, finalForecastLabelSp)
                setSp(context, views, R.id.forecast1Condition, finalForecastLabelSp)
                setSp(context, views, R.id.forecast2Condition, finalForecastLabelSp)
            } else {
                setSp(context, views, R.id.tempText, tempSp)
                setSp(context, views, R.id.conditionText, conditionSp)
            }
            setSp(context, views, R.id.updatedText, 10f)

            views.setViewVisibility(R.id.todayHighLowText, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.sunRow, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.forecastRow, if (tier == WidgetSizeTier.FULL) View.VISIBLE else View.GONE)
        }

        // RemoteViews are actually inflated and drawn by the HOST LAUNCHER process, not
        // this app's — and "sp" is a live unit that gets re-resolved against whatever the
        // system font scale is AT DRAW TIME, even for a frame that was already pushed and
        // never touched again. That means a widget already on the home screen can go from
        // "fits fine" to "text cut off" the moment the user changes Settings > Display >
        // Font size, with no tap or refresh involved at all. Setting the size in raw PIXELS
        // instead (COMPLEX_UNIT_PX) freezes it: every render here already measures against
        // the CURRENT font scale (spToPx below reads it via displayMetrics), so converting
        // that fitted result to px up front locks in a size immune to any font-scale change
        // that happens later — the next tap/refresh will simply recompute fresh against
        // whatever the scale is by then.
        private fun setSp(context: Context, views: RemoteViews, viewId: Int, sizeSp: Float) {
            views.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_PX, spToPx(context, sizeSp))
        }

        private fun dpToPx(context: Context, dp: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).roundToInt()

        private fun spToPx(context: Context, sp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

        private fun textWidthPx(context: Context, text: String, sizeSp: Float, bold: Boolean): Float {
            if (text.isEmpty()) return 0f
            val paint = Paint().apply {
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = spToPx(context, sizeSp)
            }
            return paint.measureText(text)
        }

        // Ascent is negative in Android's convention, so descent - ascent is the font's
        // full cap-to-descender line height in px — used to budget vertical space the same
        // way textWidthPx budgets horizontal space.
        private fun textHeightPx(context: Context, sizeSp: Float, bold: Boolean): Float {
            val paint = Paint().apply {
                typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                textSize = spToPx(context, sizeSp)
            }
            val metrics = paint.fontMetrics
            return metrics.descent - metrics.ascent
        }

        // Shrinks (never grows) sizeSp so `text` renders within maxWidthPx at that size.
        private fun fitWidthSp(context: Context, text: String, maxWidthPx: Float, sizeSp: Float, minSp: Float, bold: Boolean): Float {
            if (maxWidthPx <= 0f) return sizeSp
            val measured = textWidthPx(context, text, sizeSp, bold)
            if (measured <= maxWidthPx || measured <= 0f) return sizeSp
            return (sizeSp * (maxWidthPx / measured)).coerceAtLeast(minSp)
        }

        // Two tap targets: "Refresh" at the top-left reloads from the server (or opens
        // MainActivity to ask for location permission, since a widget can't show that
        // dialog itself); the rest of the widget opens the app.
        // A PendingIntent is a "voucher" for an Intent (Android's message for "do this
        // action") that lets a DIFFERENT process — here, the home-screen launcher app,
        // since it's the one that actually detects the tap on this RemoteViews content —
        // trigger it later, on our app's behalf, as if our own app had triggered it. This
        // is the only way a widget can respond to a tap, since RemoteViews content has no
        // click listeners of its own the way a normal button would.
        /** While a refresh runs: "Refresh" turns yellow and the bottom line reads "Updating from server…" in cyan. */
        private fun showUpdatingState(context: Context, views: RemoteViews, statusText: String) {
            val updating = statusText == context.getString(R.string.widget_loading)
            views.setTextColor(R.id.refreshButton, context.getColor(if (updating) R.color.retro_yellow else R.color.retro_cyan))
            views.setTextColor(R.id.updatedText, context.getColor(if (updating) R.color.retro_cyan else R.color.retro_white_dim))
        }

        private fun setClickIntent(context: Context, views: RemoteViews, id: Int, locationId: String) {
            val openApp = PendingIntent.getActivity(
                context, id,
                Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Anywhere on the widget opens the app…
            views.setOnClickPendingIntent(R.id.weatherWidgetRoot, openApp)
            // …except "Refresh" at the top-left, which reloads the weather from the server
            // (or opens the app to ask for location permission if it's still needed).
            val needsPermission = locationId == WeatherCache.CURRENT_LOCATION_ID && !LocationHelper.hasPermission(context)
            val refresh = if (needsPermission) openApp else PendingIntent.getBroadcast(
                context, id,
                Intent(context, WeatherWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.refreshButton, refresh)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { renderFromCache(context, appWidgetManager, it) }
    }

    // Each removed widget's location binding is just dead weight once it's gone —
    // clear it out instead of letting these accumulate forever in SharedPreferences.
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { WidgetLocationBinding.clear(context, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        renderFromCache(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            // Different widget instances can now be bound to different locations (see
            // WeatherWidgetConfigActivity), so a tap only refreshes the ONE widget that
            // was actually tapped — refreshing every placed widget here would mean
            // tapping a widget showing one city also fetches for every other city's
            // widget, which isn't what "only updates when you touch it" means anymore.
            val tappedId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)

            // goAsync() + a background Thread: refreshing means a location fix plus an
            // HTTP call, both of which can take real time, but a BroadcastReceiver is
            // normally expected to finish in milliseconds and would otherwise get killed
            // mid-fetch.
            // A BroadcastReceiver's onReceive() is normally expected to return within
            // milliseconds — Android may kill the process once it returns, assuming the
            // work is done. goAsync() tells Android "wait, I'm not finished yet," buying
            // extra time for the background Thread below to actually complete the
            // location fix + network call; pending.finish() in the `finally` block then
            // signals "okay, NOW I'm really done."
            val pending = goAsync()
            Thread {
                try {
                    val manager = AppWidgetManager.getInstance(context)
                    if (tappedId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                        performRefresh(context, manager, tappedId)
                    } else {
                        // Defensive fallback only — every ACTION_REFRESH we send ourselves
                        // always carries EXTRA_APPWIDGET_ID (see setClickIntent).
                        allWidgetIds(context, manager).forEach { performRefresh(context, manager, it) }
                    }
                } finally {
                    pending.finish()
                }
            }.start()
        } else if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // After an app update the launcher shows each widget's bare initial
            // layout ("--") until something redraws it; repaint every placed
            // widget (both sizes) from cache right away.
            repaintAllWidgets(context)
        } else {
            super.onReceive(context, intent)
        }
    }
}
