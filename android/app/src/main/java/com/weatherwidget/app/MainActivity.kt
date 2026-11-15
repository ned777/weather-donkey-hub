package com.weatherwidget.app

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.tabs.TabLayout
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * The screen you land on when you tap the widget (before granting location)
 * or the app icon. One tab per location: "Current" (GPS, always first — the
 * widget itself only ever mirrors this tab) plus any cities the user has
 * searched for and added. Same "only updates when you touch it" rule as the
 * widget applies to every tab: onCreate/tab-switch only ever render whatever
 * that location has cached — nothing is fetched automatically on launch,
 * resume, or switching tabs. The only ways to trigger a fetch are swiping
 * down to refresh, granting location permission for the first time (itself
 * an explicit tap), or showing a location whose cached snapshot predates the
 * hourly strip (a one-time catch-up, see fetchMissingHourlyOnce).
 */
// `class MainActivity : AppCompatActivity()` — an Activity is Android's term for
// "one full screen the user can be looking at." AppCompatActivity is the standard
// base class that provides the modern toolbar/theme support; `:` here means MainActivity
// IS one of these (inheritance), and can override its lifecycle methods below like
// onCreate(). This screen is declared as the app's entry point in AndroidManifest.xml.
class MainActivity : AppCompatActivity() {

    companion object {
        private const val SERVER_HOME_ID = "home"
        private const val TEMP_MAX_SP = 104f
        private const val TEMP_MIN_SP = 44f

        // Page side padding (12dp each side) + the card's inner padding (12dp each side) +
        // the 8dp gap between the hero's two columns, from activity_main.xml.
        private const val HERO_CHROME_DP = 56f
        // The temperature's column takes 1.1 of the hero row's 2.1 total weight.
        private const val TEMP_COLUMN_SHARE = 1.1f / 2.1f
    }

    // `lateinit var` is a promise to Kotlin: "this will definitely be set before
    // anything reads it, just not right here at declaration time." Each of these
    // views can't actually be created until setContentView() runs in onCreate() below
    // (that's what turns activity_main.xml into real on-screen objects) — lateinit
    // avoids needing every one of these to be nullable (`SwipeRefreshLayout?`) just to
    // cover the brief moment before onCreate() has run.
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var tabLayout: TabLayout
    private lateinit var fahrenheitButton: TextView
    private lateinit var celsiusButton: TextView
    private lateinit var todayLabelText: TextView
    private lateinit var tempText: TextView
    private lateinit var todayHighLowText: TextView
    private lateinit var feelsText: TextView
    private lateinit var placeText: TextView
    private lateinit var sourceChips: LinearLayout
    private lateinit var sourceScroll: View
    private lateinit var sourceLabelText: TextView
    private lateinit var statsGrid: LinearLayout
    private lateinit var dayLengthText: TextView
    private lateinit var sunFill: View
    private lateinit var sunRest: View
    private lateinit var sunDot: View
    private lateinit var hourlyCard: View
    private lateinit var forecastCard: View
    private lateinit var forecastMeta: TextView
    private lateinit var historyChips: LinearLayout
    private lateinit var conditionIcon: ImageView
    private lateinit var conditionText: TextView
    private lateinit var sunriseText: TextView
    private lateinit var sunsetText: TextView
    private lateinit var updatedText: TextView
    private lateinit var grantPermissionButton: TextView
    private lateinit var removeLocationButton: TextView
    private lateinit var hourlyScroll: View
    private lateinit var hourlyContainer: LinearLayout
    private lateinit var forecastContainer: LinearLayout
    private lateinit var fahrenheitInput: EditText
    private lateinit var celsiusInput: EditText
    private lateinit var converterSection: View
    private lateinit var noticeText: TextView
    private lateinit var historyDateButton: TextView
    private lateinit var historyTimeButton: TextView
    private lateinit var historyLookupButton: TextView
    private lateinit var historyResult: View
    private lateinit var historyTitle: TextView
    private lateinit var historyIcon: ImageView
    private lateinit var historyTemp: TextView
    private lateinit var historyCondition: TextView
    private lateinit var historyDetails: TextView
    private lateinit var historySource: TextView
    private lateinit var historyMessage: TextView

    // The "What Was the Weather?" picker — defaults to this day one year ago, whole day.
    private var historyDate: LocalDate = LocalDate.now().minusYears(1)
    private var historyTime: LocalTime? = null
    private val historyDateFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

    // Locations already given their one catch-up fetch this launch, so a fetch that comes
    // back with no hourly data can't re-trigger itself through renderFromCache().
    private val hourlyCatchUpAttempted = mutableSetOf<String>()

    private var savedLocations: List<SavedLocation> = emptyList()
    private var activeLocationId: String = WeatherCache.CURRENT_LOCATION_ID

    // Guards against the two converter boxes' TextWatchers feeding off each other —
    // set right before/after a programmatic setText() on the OTHER box.
    private var isConvertingTemp = false

    // Set on every ACTION_DOWN — whether THIS gesture started inside the converter or the
    // hourly strip, so a swipe there never changes tabs (the converter "stays still", per
    // request, and the strip needs that sideways swipe to scroll itself) while a swipe
    // starting anywhere else on the weather content does.
    private var gestureBlocksTabSwipe = false

    // Sideways-vs-down gesture tracking, so a tab swipe never turns into a refresh.
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var horizontalGesture = false
    private val touchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    // Which way the content should slide in after a tab change (-1, 0, 1).
    private var pendingSlide = 0
    private lateinit var contentRoot: ViewGroup

    private val tabSwipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (gestureBlocksTabSwipe || e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                // Mostly horizontal, and a deliberate flick — not an incidental wobble
                // during a vertical scroll or the SwipeRefreshLayout's own pull-down.
                if (abs(diffX) <= abs(diffY) * 1.5f) return false
                val metrics = resources.displayMetrics
                val minDistancePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60f, metrics)
                val minVelocity = ViewConfiguration.get(this@MainActivity).scaledMinimumFlingVelocity
                if (abs(diffX) < minDistancePx || abs(velocityX) < minVelocity) return false
                // Finger moving left (diffX negative) reveals the next tab, like flipping
                // to the next card — right moves back to the previous one.
                goToAdjacentTab(if (diffX < 0) 1 else -1)
                return true
            }
        })
    }

    // registerForActivityResult is the modern Android API for "ask the user for
    // something (here, a permission) and run this code once they've answered."
    // `{ granted -> ... }` is a lambda — an inline, unnamed function — that Android
    // calls later with the result (`granted`, a Boolean) once the user taps
    // Allow/Deny on the system permission dialog.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            grantPermissionButton.visibility = if (granted) View.GONE else View.VISIBLE
            if (granted) {
                startRefresh()
            } else {
                swipeRefresh.isRefreshing = false
                updatedText.text = getString(R.string.widget_error_no_location)
            }
        }

    // onCreate() is the first lifecycle method Android calls when this screen is
    // being built — the standard place to inflate the layout and wire everything up.
    // `override` means this replaces AppCompatActivity's default version, and
    // `super.onCreate(...)` still runs that original version first so the base class
    // gets to do its own setup too.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Turns res/layout/activity_main.xml into real on-screen View objects.
        setContentView(R.layout.activity_main)

        // findViewById looks up one of those real views by the android:id it was
        // given in the XML — this is what connects a Kotlin variable to a specific
        // box/button/text drawn on screen.
        swipeRefresh = findViewById(R.id.swipeRefresh)
        tabLayout = findViewById(R.id.tabLayout)
        fahrenheitButton = findViewById(R.id.fahrenheitButton)
        celsiusButton = findViewById(R.id.celsiusButton)
        todayLabelText = findViewById(R.id.todayLabelText)
        tempText = findViewById(R.id.tempText)
        todayHighLowText = findViewById(R.id.todayHighLowText)
        feelsText = findViewById(R.id.feelsText)
        placeText = findViewById(R.id.placeText)
        sourceChips = findViewById(R.id.sourceChips)
        sourceScroll = findViewById(R.id.sourceScroll)
        sourceLabelText = findViewById(R.id.sourceLabelText)
        statsGrid = findViewById(R.id.statsGrid)
        dayLengthText = findViewById(R.id.dayLengthText)
        sunFill = findViewById(R.id.sunFill)
        sunRest = findViewById(R.id.sunRest)
        sunDot = findViewById(R.id.sunDot)
        hourlyCard = findViewById(R.id.hourlyCard)
        forecastCard = findViewById(R.id.forecastCard)
        forecastMeta = findViewById(R.id.forecastMeta)
        historyChips = findViewById(R.id.historyChips)
        conditionIcon = findViewById(R.id.conditionIcon)
        conditionText = findViewById(R.id.conditionText)
        sunriseText = findViewById(R.id.sunriseText)
        sunsetText = findViewById(R.id.sunsetText)
        updatedText = findViewById(R.id.updatedText)
        grantPermissionButton = findViewById(R.id.grantPermissionButton)
        removeLocationButton = findViewById(R.id.removeLocationButton)
        hourlyScroll = findViewById(R.id.hourlyScroll)
        hourlyContainer = findViewById(R.id.hourlyContainer)
        forecastContainer = findViewById(R.id.forecastContainer)
        fahrenheitInput = findViewById(R.id.fahrenheitInput)
        celsiusInput = findViewById(R.id.celsiusInput)
        converterSection = findViewById(R.id.converterSection)
        noticeText = findViewById(R.id.noticeText)
        historyDateButton = findViewById(R.id.historyDateButton)
        historyTimeButton = findViewById(R.id.historyTimeButton)
        historyLookupButton = findViewById(R.id.historyLookupButton)
        historyResult = findViewById(R.id.historyResult)
        historyTitle = findViewById(R.id.historyTitle)
        historyIcon = findViewById(R.id.historyIcon)
        historyTemp = findViewById(R.id.historyTemp)
        historyCondition = findViewById(R.id.historyCondition)
        historyDetails = findViewById(R.id.historyDetails)
        historySource = findViewById(R.id.historySource)
        historyMessage = findViewById(R.id.historyMessage)
        setupHistory()

        contentRoot = findViewById(R.id.contentRoot)
        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.retro_cyan))
        // A refresh needs a deliberate pull (default is ~64dp), so small downward
        // drift while swiping or scrolling doesn't set one off.
        swipeRefresh.setDistanceToTriggerSync((140 * resources.displayMetrics.density).toInt())
        swipeRefresh.setOnRefreshListener { startRefresh() }
        setupTempConverter()

        grantPermissionButton.setOnClickListener {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        removeLocationButton.setOnClickListener { confirmRemoveActiveLocation() }
        fahrenheitButton.setOnClickListener { setUnit(fahrenheit = true) }
        celsiusButton.setOnClickListener { setUnit(fahrenheit = false) }

        savedLocations = LocationStore.list(this)
        setupTabs()
        updateUnitButtonsUi()
        renderFromCache()
    }

    // Opening the app never fetches. It only catches up when a scheduled 3-hour refresh
    // was missed (phone off, no Wi-Fi at the time), and repaints when the next one lands
    // while the app is open. Both use the server's scheduled snapshot, not a new fetch.
    private val slotHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val slotTick = Runnable { catchUpWithSchedule() }

    override fun onResume() {
        super.onResume()
        ScheduledRefresh.schedule(this)
        catchUpWithSchedule()
    }

    override fun onPause() {
        super.onPause()
        slotHandler.removeCallbacks(slotTick)
    }

    private fun catchUpWithSchedule() {
        slotHandler.removeCallbacks(slotTick)
        val wait = ScheduledRefresh.nextSlotMillis() + 90_000L - System.currentTimeMillis()
        slotHandler.postDelayed(slotTick, wait.coerceAtLeast(5_000L))
        if (!ScheduledRefresh.isBehind(this)) {
            renderFromCache()
            return
        }
        Thread {
            ScheduledRefresh.runBlocking(applicationContext)
            runOnUiThread { if (!isFinishing) renderFromCache() }
        }.start()
    }

    // Observes every touch the Activity sees (without consuming it — normal scrolling,
    // button taps, and EditText focus/typing all still work exactly as before) purely to
    // feed tabSwipeDetector's fling detection and to remember whether THIS gesture started
    // inside the converter or the hourly strip.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                horizontalGesture = false
                gestureBlocksTabSwipe = isPointInsideView(ev.rawX, ev.rawY, converterSection) ||
                    isPointInsideView(ev.rawX, ev.rawY, hourlyScroll) ||
                    isPointInsideView(ev.rawX, ev.rawY, sourceScroll)
            }
            MotionEvent.ACTION_MOVE -> if (!horizontalGesture) {
                val dx = abs(ev.rawX - touchDownX)
                val dy = abs(ev.rawY - touchDownY)
                // As soon as a gesture is clearly sideways, pull-to-refresh sits it out
                // until the finger lifts, however much the finger drifts down.
                if (dx > touchSlop && dx > dy) {
                    horizontalGesture = true
                    if (!swipeRefresh.isRefreshing) swipeRefresh.isEnabled = false
                }
            }
        }
        tabSwipeDetector.onTouchEvent(ev)
        val handled = super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            swipeRefresh.isEnabled = true
        }
        return handled
    }

    /** Slides the weather content in from the side the user swiped toward (or just fades for a tap). */
    private fun animateContentIn(direction: Int) {
        val firstContent = contentRoot.indexOfChild(tabLayout) + 1
        val offset = 60 * resources.displayMetrics.density * direction
        for (i in firstContent until contentRoot.childCount) {
            val child = contentRoot.getChildAt(i)
            child.animate().cancel()
            child.translationX = offset
            child.alpha = 0f
            child.animate().translationX(0f).alpha(1f).setDuration(220)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    private fun isPointInsideView(rawX: Float, rawY: Float, view: View): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
    }

    // Swipes to the next/previous REAL location tab — never onto the trailing "+" tab,
    // which is an action (opens the search dialog), not a page to land on.
    private fun goToAdjacentTab(delta: Int) {
        val lastRealIndex = tabLayout.tabCount - 2 // last tab is always "+"
        if (lastRealIndex < 0) return
        val newIndex = (tabLayout.selectedTabPosition + delta).coerceIn(0, lastRealIndex)
        if (newIndex != tabLayout.selectedTabPosition) {
            pendingSlide = delta
            tabLayout.getTabAt(newIndex)?.select()
        }
    }

    // --- Tabs -----------------------------------------------------------

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val tag = tab.tag as? String
                if (tag == null) {
                    // The trailing "+" tab has no tag — it's an action, not a real
                    // page, so open the search dialog and snap the strip back to
                    // whichever tab was actually active.
                    showAddLocationDialog()
                    rebuildTabs()
                    return
                }
                val switched = activeLocationId != tag
                activeLocationId = tag
                renderFromCache()
                if (switched) animateContentIn(pendingSlide)
                pendingSlide = 0
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        rebuildTabs()
    }

    private fun rebuildTabs() {
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(getString(R.string.tab_current)).setTag(WeatherCache.CURRENT_LOCATION_ID))
        savedLocations.forEach { loc ->
            tabLayout.addTab(tabLayout.newTab().setText(loc.displayName.substringBefore(",")).setTag(loc.id))
        }
        tabLayout.addTab(tabLayout.newTab().setText("+")) // no tag — see setupTabs()

        val activeIndex = if (activeLocationId == WeatherCache.CURRENT_LOCATION_ID) {
            0
        } else {
            savedLocations.indexOfFirst { it.id == activeLocationId }.let { if (it < 0) 0 else it + 1 }
        }
        tabLayout.getTabAt(activeIndex)?.select()
    }

    // --- Adding / removing locations -------------------------------------

    private fun showAddLocationDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.add_location_hint)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.add_location_title)
            .setView(input)
            .setPositiveButton(R.string.search) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) performLocationSearch(query)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // This method demonstrates a pattern used throughout the app: Android runs all UI
    // code on one single "main thread," and anything slow (network calls, this geocode
    // lookup) would freeze the whole screen if run there directly. `Thread { ... }.start()`
    // spins up a separate background thread to do the slow work instead. But that
    // background thread also ISN'T allowed to touch the UI directly — so once it has a
    // result, `runOnUiThread { ... }` hands a small block of code back to the main
    // thread to actually update what's on screen.
    private fun performLocationSearch(query: String) {
        Thread {
            val results = GeocodeHelper.search(this, query)
            runOnUiThread {
                if (results.isEmpty()) {
                    Toast.makeText(this, getString(R.string.no_location_matches, query), Toast.LENGTH_SHORT).show()
                } else {
                    showSearchResultsDialog(results)
                }
            }
        }.start()
    }

    private fun showSearchResultsDialog(results: List<LocationSearchResult>) {
        val labels = results.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_location)
            .setItems(labels) { _, index -> addSavedLocation(results[index]) }
            .show()
    }

    private fun addSavedLocation(result: LocationSearchResult) {
        val location = SavedLocation(
            // Same id format the server uses ("%.4f,%.4f"), so both sides name a city the same way.
            id = String.format(Locale.US, "%.4f,%.4f", result.lat, result.lon),
            displayName = result.displayName,
            lat = result.lat,
            lon = result.lon
        )
        LocationStore.add(this, location)
        Thread { WeatherClient.addLocation(location.displayName, location.lat, location.lon) }.start()
        savedLocations = LocationStore.list(this)
        activeLocationId = location.id
        rebuildTabs() // selecting the new tab triggers renderFromCache() via the listener
    }

    private fun confirmRemoveActiveLocation() {
        val id = activeLocationId
        if (id == WeatherCache.CURRENT_LOCATION_ID) return
        AlertDialog.Builder(this)
            .setMessage(R.string.remove_location_confirm)
            .setPositiveButton(R.string.remove) { _, _ ->
                LocationStore.remove(this, id)
                WeatherCache.clear(this, id)
                Thread { WeatherClient.forgetLocation(id) }.start()
                savedLocations = LocationStore.list(this)
                activeLocationId = WeatherCache.CURRENT_LOCATION_ID
                rebuildTabs()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Rendering / refreshing -------------------------------------------

    // A unit switch is purely a display change — reformat whatever's cached and
    // repaint any placed widget, no network call.
    private fun setUnit(fahrenheit: Boolean) {
        UnitPreference.setFahrenheit(this, fahrenheit)
        updateUnitButtonsUi()
        renderFromCache()
        WeatherWidgetProvider.repaintAllWidgets(this)
    }

    private fun updateUnitButtonsUi() {
        val fahrenheit = UnitPreference.isFahrenheit(this)
        val onColor = ContextCompat.getColor(this, R.color.black)
        val offColor = ContextCompat.getColor(this, R.color.app_dim)
        listOf(fahrenheitButton to fahrenheit, celsiusButton to !fahrenheit).forEach { (button, on) ->
            button.setTextColor(if (on) onColor else offColor)
            button.setBackgroundResource(if (on) R.drawable.app_seg_on else 0)
        }
    }

    private fun renderFromCache() {
        val id = activeLocationId
        val isCurrentTab = id == WeatherCache.CURRENT_LOCATION_ID
        val hasPermission = LocationHelper.hasPermission(this)
        grantPermissionButton.visibility = if (isCurrentTab && !hasPermission) View.VISIBLE else View.GONE
        removeLocationButton.visibility = if (isCurrentTab) View.GONE else View.VISIBLE

        val full = WeatherCache.read(this, id)
        if (full != null) {
            val picked = SourcePreference.get(this)
            val snapshot = full.forSource(picked)
            renderSourceChips(full, picked)
            render(snapshot)
            val next = java.time.Instant.ofEpochMilli(ScheduledRefresh.nextSlotMillis()).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
            updatedText.text = "Updated " + WeatherFormat.lastUpdatedTimestamp(snapshot.fetchedAt) + " · next " + next
            sourceLabelText.text = snapshot.sourceLabel
            sourceLabelText.visibility = if (snapshot.sourceLabel.isEmpty()) View.GONE else View.VISIBLE
            val pickedMissing = picked != SourcePreference.AVERAGE && full.bySource.isNotEmpty() && picked !in full.bySource
            showNotice(if (pickedMissing) getString(R.string.source_unavailable, sourceName(full, picked)) else full.notice)
            if (snapshot.hourly.isEmpty()) fetchMissingHourlyOnce(id, isCurrentTab && !hasPermission)
        } else {
            clearWeatherFieldsToPlaceholder()
            showNotice(null)
            sourceChips.removeAllViews()
            sourceLabelText.visibility = View.GONE
            hourlyCard.visibility = View.GONE
            hourlyContainer.removeAllViews()
            forecastCard.visibility = View.GONE
            forecastContainer.removeAllViews()
            updatedText.text = when {
                isCurrentTab && !hasPermission -> getString(R.string.location_permission_rationale)
                else -> getString(R.string.app_swipe_to_load)
            }
        }
    }

    // A snapshot saved before the hourly strip existed has nothing to fill it with — fetch
    // once so that location doesn't need a manual swipe just to catch up. Never prompts
    // for location permission on its own; that stays behind an explicit tap.
    private fun fetchMissingHourlyOnce(locationId: String, needsPermission: Boolean) {
        if (needsPermission || !hourlyCatchUpAttempted.add(locationId)) return
        startRefresh()
    }

    // So switching to a tab with nothing cached yet doesn't keep showing the
    // previous tab's numbers underneath the "swipe down to load" message.
    private fun clearWeatherFieldsToPlaceholder() {
        applyTempTextSize("--°")
        tempText.text = "--°"
        todayHighLowText.text = "H:--°  L:--°"
        feelsText.text = ""
        placeText.text = ""
        conditionIcon.setImageResource(R.drawable.ic_weather_cloudy)
        conditionText.text = "--"
        sunriseText.text = "↑ --:--"
        sunsetText.text = "↓ --:--"
        dayLengthText.text = ""
        setSunProgress(null)
        renderStats(null)
    }

    /**
     * Brings the city tabs in line with the server (runs on every pull-to-refresh):
     * cities added on the web page appear, cities removed there disappear, and
     * cities added here while away from home get registered.
     */
    private fun syncLocationsWithServer() {
        val server = (WeatherClient.listLocations() as? WeatherClient.Outcome.Ok)?.value ?: return
        val serverById = server.associateBy { it.id }
        val local = LocationStore.list(this)
        var changed = false
        local.forEach { loc ->
            val match = serverById[loc.id]
            when {
                // The server's Home is where the phone usually is too, so it would just
                // duplicate the Current tab — the app never shows it.
                loc.id == SERVER_HOME_ID -> {
                    LocationStore.remove(this, loc.id)
                    WeatherCache.clear(this, loc.id)
                    changed = true
                }
                match == null -> WeatherClient.addLocation(loc.displayName, loc.lat, loc.lon)
                !match.active -> {
                    LocationStore.remove(this, loc.id)
                    WeatherCache.clear(this, loc.id)
                    changed = true
                }
            }
        }
        val localIds = local.map { it.id }.toSet()
        server.filter {
            it.active && it.id != WeatherCache.CURRENT_LOCATION_ID && it.id != SERVER_HOME_ID && it.id !in localIds
        }.forEach {
            LocationStore.add(this, SavedLocation(it.id, it.name, it.lat, it.lon))
            changed = true
        }
        if (changed) {
            runOnUiThread {
                savedLocations = LocationStore.list(this)
                if (activeLocationId != WeatherCache.CURRENT_LOCATION_ID && savedLocations.none { it.id == activeLocationId }) {
                    activeLocationId = WeatherCache.CURRENT_LOCATION_ID
                }
                rebuildTabs()
            }
        }
    }

    /**
     * Pull-to-refresh updates EVERY tab at once — Current plus each saved city —
     * with all requests to the server running in parallel. The spinner stops
     * when they've all answered.
     */
    private fun startRefresh() {
        val hasPermission = LocationHelper.hasPermission(this)
        if (activeLocationId == WeatherCache.CURRENT_LOCATION_ID && !hasPermission) {
            swipeRefresh.isRefreshing = false
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            return
        }
        swipeRefresh.isRefreshing = true
        Thread {
            syncLocationsWithServer() // picks up cities added/removed on the web page first
            val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
            val workers = mutableListOf<Thread>()
            if (hasPermission) {
                workers += Thread {
                    val location = LocationHelper.getLocationBlocking(this)
                    if (location == null) {
                        errors[WeatherCache.CURRENT_LOCATION_ID] = getString(R.string.widget_error_no_location)
                    } else {
                        WeatherCache.saveCurrentCoords(this, location.latitude, location.longitude)
                        fetchOne(WeatherCache.CURRENT_LOCATION_ID, location.latitude, location.longitude,
                            GeocodeHelper.cityName(this, location))?.let { errors[WeatherCache.CURRENT_LOCATION_ID] = it }
                    }
                }
            }
            LocationStore.list(this).forEach { loc ->
                workers += Thread { fetchOne(loc.id, loc.lat, loc.lon, loc.displayName)?.let { errors[loc.id] = it } }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            runOnUiThread {
                swipeRefresh.isRefreshing = false
                renderFromCache()
                errors[activeLocationId]?.let { updatedText.text = it }
                WeatherWidgetProvider.repaintAllWidgets(this)
            }
        }.start()
    }

    /** Fetches one location from the server and caches it. Returns an error message, or null on success. */
    private fun fetchOne(locationId: String, lat: Double, lon: Double, cityName: String?): String? =
        when (val outcome = WeatherClient.fetchWeather(locationId, cityName, lat, lon, fresh = true)) {
            is WeatherClient.Outcome.Ok -> {
                WeatherCache.save(this, locationId, outcome.value.copy(cityName = cityName ?: outcome.value.cityName))
                null
            }
            is WeatherClient.Outcome.Failed -> outcome.message
            WeatherClient.Outcome.ServerUnreachable -> getString(R.string.app_error_server)
        }

    private fun showNotice(notice: String?) {
        noticeText.text = notice.orEmpty()
        noticeText.visibility = if (notice.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ---- "What Was the Weather?" ------------------------------------------------

    private fun setupHistory() {
        updateHistoryPickers()
        historyDateButton.setOnClickListener {
            DatePickerDialog(this, { _, year, month, day ->
                historyDate = LocalDate.of(year, month + 1, day)
                updateHistoryPickers()
            }, historyDate.year, historyDate.monthValue - 1, historyDate.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        historyTimeButton.setOnClickListener {
            val start = historyTime ?: LocalTime.NOON
            TimePickerDialog(this, { _, hour, minute ->
                historyTime = LocalTime.of(hour, minute)
                updateHistoryPickers()
            }, start.hour, start.minute, true).apply {
                setButton(TimePickerDialog.BUTTON_NEUTRAL, getString(R.string.history_whole_day)) { _, _ ->
                    historyTime = null
                    updateHistoryPickers()
                }
            }.show()
        }
        historyLookupButton.setOnClickListener { lookUpHistory() }
        val dp = resources.displayMetrics.density
        listOf(1, 5, 10, 25).forEach { years ->
            historyChips.addView(TextView(this).apply {
                text = "$years yr ago"
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_dim))
                setBackgroundResource(R.drawable.app_chip)
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = (6 * dp).toInt() }
                setOnClickListener {
                    historyDate = LocalDate.now().minusYears(years.toLong())
                    updateHistoryPickers()
                    lookUpHistory()
                }
            })
        }
    }

    private fun updateHistoryPickers() {
        historyDateButton.text = historyDate.format(historyDateFormatter)
        historyTimeButton.text = historyTime?.let { "%02d:%02d".format(it.hour, it.minute) }
            ?: getString(R.string.history_whole_day)
    }

    private fun lookUpHistory() {
        val id = activeLocationId
        val coords = if (id == WeatherCache.CURRENT_LOCATION_ID) {
            WeatherCache.readCurrentCoords(this)
        } else {
            savedLocations.find { it.id == id }?.let { it.lat to it.lon }
        }
        if (coords == null) {
            showHistoryMessage(getString(R.string.history_need_location))
            return
        }
        val date = historyDate
        val time = historyTime
        val placeName = WeatherCache.read(this, id)?.cityName
            ?: savedLocations.find { it.id == id }?.displayName?.substringBefore(",")
            ?: getString(R.string.current_location)
        showHistoryMessage(getString(R.string.history_looking))
        historyLookupButton.isEnabled = false
        historyLookupButton.alpha = 0.5f
        Thread {
            val outcome = WeatherClient.lookupHistory(id, coords.first, coords.second, date, time)
            runOnUiThread {
                historyLookupButton.isEnabled = true
                historyLookupButton.alpha = 1f
                when (outcome) {
                    is WeatherClient.Outcome.Ok -> renderHistory(outcome.value, placeName)
                    is WeatherClient.Outcome.Failed -> showHistoryMessage(outcome.message)
                    WeatherClient.Outcome.ServerUnreachable -> showHistoryMessage(getString(R.string.app_error_server))
                }
            }
        }.start()
    }

    private fun showHistoryMessage(message: String) {
        historyResult.visibility = View.GONE
        historyMessage.text = message
        historyMessage.visibility = View.VISIBLE
    }

    private fun renderHistory(result: HistoryResult, placeName: String) {
        val fahrenheit = UnitPreference.isFahrenheit(this)
        historyMessage.visibility = View.GONE
        historyResult.visibility = View.VISIBLE

        val whenText = result.date.format(historyDateFormatter) + (result.time?.let { " · $it" } ?: "")
        historyTitle.text = "$placeName · $whenText"

        val hourKnown = result.hourTempF != null && result.hourCode != null
        val code = if (hourKnown) result.hourCode!! else result.dayCode
        val wind = if (hourKnown) result.hourWindMph ?: 0.0 else result.dayWindMph
        val condition = WeatherCondition.fromCodeAndWind(code, wind)
        historyIcon.setImageResource(condition.iconRes(if (hourKnown) result.hourIsDay else true))
        historyCondition.text = condition.label
        historyTemp.text = if (hourKnown) {
            WeatherFormat.tempString(result.hourTempF!!, fahrenheit)
        } else {
            WeatherFormat.tempString(result.highF, fahrenheit)
        }

        val lines = mutableListOf(WeatherFormat.highLowString(result.highF, result.lowF, fahrenheit))
        val rain = when {
            result.dayRainChancePercent >= 0 -> WeatherFormat.rainChanceString(result.dayRainChancePercent)
            result.dayPrecipInches != null -> "Rain: %.2f in".format(result.dayPrecipInches)
            else -> null
        }
        lines += listOfNotNull(rain, WeatherFormat.windString(wind)).joinToString("  ·  ")
        lines += "↑ ${WeatherFormat.clockTime(result.sunrise)}   ↓ ${WeatherFormat.clockTime(result.sunset)}"
        if (result.time != null && !hourKnown) lines += "(No reading for that hour — showing the day)"
        historyDetails.text = lines.joinToString("\n")
        historySource.text = result.sourceLabel
    }

    private fun render(snapshot: WeatherSnapshot) {
        val fahrenheit = UnitPreference.isFahrenheit(this)
        val tempStr = WeatherFormat.tempString(snapshot.tempF, fahrenheit)
        applyTempTextSize(tempStr)
        tempText.text = tempStr
        todayHighLowText.text = WeatherFormat.highLowString(snapshot.todayHighF, snapshot.todayLowF, fahrenheit)
        feelsText.text = snapshot.details.feelsLikeF?.let { "Feels like ${WeatherFormat.tempString(it, fahrenheit)}" } ?: ""
        placeText.text = snapshot.cityName ?: savedLocations.find { it.id == activeLocationId }?.displayName ?: ""
        val condition = snapshot.condition
        conditionIcon.setImageResource(condition.iconRes(snapshot.isDay))
        conditionText.text = if (condition == WeatherCondition.SUNNY && !snapshot.isDay) "Clear" else condition.label
        renderStats(snapshot)
        renderSun(snapshot)
        populateHourly(snapshot.hourly, fahrenheit)
        populateForecast(snapshot, fahrenheit)
    }

    // ---- source chips: Average / Open-Meteo / MET Norway / 7Timer! ----------------

    private fun sourceName(full: WeatherSnapshot, key: String): String =
        full.sourceNames[key] ?: full.bySource[key]?.sourceLabel ?: key

    private fun renderSourceChips(full: WeatherSnapshot, picked: String) {
        sourceChips.removeAllViews()
        if (full.bySource.isEmpty()) {
            sourceScroll.visibility = View.GONE // cached before the server sent every source
            return
        }
        sourceScroll.visibility = View.VISIBLE
        val fahrenheit = UnitPreference.isFahrenheit(this)
        val active = if (picked in full.bySource) picked else SourcePreference.AVERAGE
        val order = full.sourceOrder.ifEmpty { full.bySource.keys.toList() }
        val chips = listOf(Triple(SourcePreference.AVERAGE, getString(R.string.source_average), full.tempF)) +
            order.map { Triple(it, sourceName(full, it), full.bySource[it]?.tempF) }
        chips.forEach { (key, name, temp) ->
            sourceChips.addView(sourceChip(key, name, temp, fahrenheit, key == active, temp != null))
        }
    }

    private fun sourceChip(key: String, name: String, tempF: Double?, fahrenheit: Boolean, active: Boolean, ok: Boolean): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(if (active) R.drawable.app_chip_active else R.drawable.app_chip)
            setPadding((11 * dp).toInt(), (5 * dp).toInt(), (11 * dp).toInt(), (5 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * dp).toInt() }
            alpha = if (ok) 1f else 0.6f
        }
        row.addView(View(this).apply {
            setBackgroundResource(if (ok) R.drawable.app_dot_ok else R.drawable.app_dot_down)
            layoutParams = LinearLayout.LayoutParams((7 * dp).toInt(), (7 * dp).toInt()).apply { marginEnd = (6 * dp).toInt() }
        })
        row.addView(TextView(this).apply {
            text = name
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, if (active) R.color.retro_cyan else R.color.app_dim))
        })
        if (tempF != null) row.addView(TextView(this).apply {
            text = " " + WeatherFormat.tempString(tempF, fahrenheit)
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
        })
        if (ok) row.setOnClickListener {
            SourcePreference.set(this, key)
            renderFromCache()
            WeatherWidgetProvider.repaintAllWidgets(this)
        }
        return row
    }

    // ---- details grid: 4 rows × 2 boxes, like the web page --------------------------

    private fun renderStats(snapshot: WeatherSnapshot?) {
        statsGrid.removeAllViews()
        val fahrenheit = UnitPreference.isFahrenheit(this)
        val d = snapshot?.details ?: WeatherDetails()
        val dir = d.windDir
        val boxes = listOf(
            Triple("Rain chance", snapshot?.let { WeatherFormat.rainPct(it.todayRainChancePercent) } ?: "--", "today"),
            Triple("Wind", snapshot?.let { WeatherFormat.mph(it.windSpeedMph) } ?: "--",
                dir?.let { "from ${WeatherFormat.compass(it)} (${Math.round(it)}°)" } ?: ""),
            Triple("Gusts", WeatherFormat.mph(d.gustMph), "max right now"),
            Triple("Humidity", WeatherFormat.pct(d.humidity), "relative"),
            Triple("Pressure", d.pressureHpa?.let { "${Math.round(it)} hPa" } ?: "--",
                d.pressureHpa?.let { "%.2f inHg".format(it * 0.02953) } ?: ""),
            Triple("Cloud cover", WeatherFormat.pct(d.cloudPct), "of the sky"),
            Triple("UV index", d.uv?.let { "%.1f".format(it) } ?: "--", d.uvMax?.let { "max today %.1f".format(it) } ?: ""),
            Triple("Feels like", d.feelsLikeF?.let { WeatherFormat.tempString(it, fahrenheit) } ?: "--", "apparent")
        )
        val dp = resources.displayMetrics.density
        val lineColor = ContextCompat.getColor(this, R.color.app_line)
        boxes.chunked(2).forEach { pair ->
            statsGrid.addView(View(this).apply {
                setBackgroundColor(lineColor)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (1 * dp).toInt())
            })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (label, value, sub) ->
                if (i == 1) row.addView(View(this).apply {
                    setBackgroundColor(lineColor)
                    layoutParams = LinearLayout.LayoutParams((1 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
                })
                row.addView(statBox(label, value, sub, label == "Wind" && dir != null, dir ?: 0.0))
            }
            statsGrid.addView(row)
        }
    }

    private fun statBox(label: String, value: String, sub: String, arrow: Boolean, windFrom: Double): View {
        val dp = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (7 * dp).toInt(), (10 * dp).toInt(), (7 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        box.addView(TextView(this).apply {
            text = label.uppercase()
            textSize = 10.5f
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_dim))
        })
        val valueRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        if (arrow) valueRow.addView(TextView(this).apply {
            text = "↑"
            textSize = 15f
            rotation = (windFrom + 180).toFloat() // points where the wind is going
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.retro_teal))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (5 * dp).toInt() }
        })
        valueRow.addView(TextView(this).apply {
            text = value
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            maxLines = 1
        })
        box.addView(valueRow)
        box.addView(TextView(this).apply {
            text = sub
            textSize = 11f
            maxLines = 1
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.app_faint))
        })
        return box
    }

    // ---- sunrise / sunset bar --------------------------------------------------------

    private fun renderSun(snapshot: WeatherSnapshot) {
        sunriseText.text = "↑ Sunrise ${WeatherFormat.clockTime(snapshot.sunrise)}"
        sunsetText.text = "↓ Sunset ${WeatherFormat.clockTime(snapshot.sunset)}"
        val rise = WeatherFormat.minutesOf(snapshot.sunrise)
        val set = WeatherFormat.minutesOf(snapshot.sunset)
        if (rise == null || set == null || set <= rise) {
            dayLengthText.text = ""
            setSunProgress(null)
            return
        }
        val len = set - rise
        dayLengthText.text = "${len / 60}h ${len % 60}m of daylight"
        val now = WeatherFormat.minutesOf(snapshot.localTime)
            ?: java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        setSunProgress(((now - rise).toFloat() / len).coerceIn(0f, 1f), now in rise..set)
    }

    private fun setSunProgress(fraction: Float?, up: Boolean = true) {
        val p = fraction ?: 0f
        (sunFill.layoutParams as LinearLayout.LayoutParams).weight = p
        (sunRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - p
        sunFill.requestLayout()
        sunDot.visibility = if (fraction == null) View.INVISIBLE else View.VISIBLE
        sunDot.alpha = if (up) 1f else 0.35f
        if (p == 0f) (sunDot.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        else (sunDot.layoutParams as LinearLayout.LayoutParams).marginStart = (-6 * resources.displayMetrics.density).toInt()
    }

    // Measures the exact string against the screen's actual available width and picks the
    // largest size (within TEMP_MAX_SP/TEMP_MIN_SP) that still fits on one line — same
    // "measure once, scale proportionally" trick WeatherWidgetProvider already uses, just
    // run here in the Activity instead of relying on framework autosize (which turned out
    // to be unreliable combined with OutlinedTextView's custom onDraw).
    private fun applyTempTextSize(text: String) {
        val metrics = resources.displayMetrics
        val paint = Paint().apply {
            typeface = Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEMP_MAX_SP, metrics)
        }
        // The temperature has the hero row's left column: its share of the screen width
        // left after the page/card padding.
        val availableWidthPx = (metrics.widthPixels -
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, HERO_CHROME_DP, metrics)) * TEMP_COLUMN_SHARE
        val measuredPx = paint.measureText(text)
        val fitSp = if (measuredPx > availableWidthPx && measuredPx > 0f) {
            (TEMP_MAX_SP * (availableWidthPx / measuredPx)).coerceAtLeast(TEMP_MIN_SP)
        } else {
            TEMP_MAX_SP
        }
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_SP, fitSp)
    }

    private fun populateHourly(hourly: List<HourlyForecast>, fahrenheit: Boolean) {
        hourlyContainer.removeAllViews()
        hourlyCard.visibility = if (hourly.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        val dp = resources.displayMetrics.density
        hourly.forEachIndexed { i, hour ->
            val cell = inflater.inflate(R.layout.item_forecast_hour, hourlyContainer, false)
            cell.findViewById<TextView>(R.id.hourLabel).text = WeatherFormat.clockTime(hour.time)
            cell.findViewById<ImageView>(R.id.conditionIcon).setImageResource(hour.condition.iconRes(hour.isDay))
            cell.findViewById<TextView>(R.id.tempText).text = WeatherFormat.tempString(hour.tempF, fahrenheit)
            cell.findViewById<TextView>(R.id.rainText).text = WeatherFormat.rainPct(hour.rainChancePercent)
            val rain = hour.rainChancePercent.coerceAtLeast(0)
            cell.findViewById<View>(R.id.rainBar).layoutParams.height =
                if (rain == 0) 0 else maxOf(2f, rain / 100f * 22f).times(dp).toInt()
            if (i == hourly.lastIndex) cell.findViewById<View>(R.id.divider).visibility = View.GONE
            hourlyContainer.addView(cell)
        }
    }

    private fun populateForecast(snapshot: WeatherSnapshot, fahrenheit: Boolean) {
        val forecast = snapshot.forecast
        forecastContainer.removeAllViews()
        forecastCard.visibility = if (forecast.isEmpty()) View.GONE else View.VISIBLE
        if (forecast.isEmpty()) return
        val lo = forecast.minOf { it.lowF }
        val hi = forecast.maxOf { it.highF }
        val span = maxOf(1.0, hi - lo)
        forecastMeta.text = "${WeatherFormat.tempString(lo, fahrenheit)} – ${WeatherFormat.tempString(hi, fahrenheit)} this week"
        val today = runCatching { LocalDate.parse(snapshot.localTime.take(10)) }.getOrElse { LocalDate.now() }
        val dateFormat = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        val inflater = LayoutInflater.from(this)
        forecast.forEachIndexed { i, day ->
            val row = inflater.inflate(R.layout.item_forecast_day, forecastContainer, false)
            row.findViewById<TextView>(R.id.dayLabel).text = day.dateLabel.uppercase()
            row.findViewById<TextView>(R.id.dateText).text = today.plusDays(i + 1L).format(dateFormat)
            row.findViewById<ImageView>(R.id.conditionIcon).setImageResource(day.condition.iconRes(isDay = true))
            row.findViewById<TextView>(R.id.lowText).text = WeatherFormat.tempString(day.lowF, fahrenheit)
            row.findViewById<TextView>(R.id.highText).text = WeatherFormat.tempString(day.highF, fahrenheit)
            row.findViewById<TextView>(R.id.rainText).text = "☂ " + WeatherFormat.rainPct(day.rainChancePercent)
            row.findViewById<TextView>(R.id.windText).text = WeatherFormat.mph(day.windSpeedMph)
            val before = ((day.lowF - lo) / span).toFloat()
            val width = maxOf(0.04f, ((day.highF - day.lowF) / span).toFloat())
            (row.findViewById<View>(R.id.rangeBefore).layoutParams as LinearLayout.LayoutParams).weight = before
            (row.findViewById<View>(R.id.rangeFill).layoutParams as LinearLayout.LayoutParams).weight = width
            (row.findViewById<View>(R.id.rangeAfter).layoutParams as LinearLayout.LayoutParams).weight =
                maxOf(0f, 1f - before - width)
            if (i == forecast.lastIndex) row.findViewById<View>(R.id.divider).visibility = View.GONE
            forecastContainer.addView(row)
        }
    }

    // Standalone unit converter at the bottom of the screen — unrelated to the weather
    // display above (no location, no tabs, just arithmetic). Typing in one box live-fills
    // the other; isConvertingTemp stops that programmatic setText() from re-triggering
    // the watcher on the box that was just filled in, which would otherwise loop forever.
    // `object : TextWatcher { ... }` creates a one-off, unnamed implementation of the
    // TextWatcher interface right here inline — Kotlin/Java's way of passing "an object
    // that responds to these specific events" without declaring a whole separate named
    // class for it. Android calls afterTextChanged() every time the text in the box changes.
    private fun setupTempConverter() {
        fahrenheitInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isConvertingTemp) return
                val f = s?.toString()?.toDoubleOrNull()
                isConvertingTemp = true
                celsiusInput.setText(if (f != null) formatConvertedTemp((f - 32.0) * 5.0 / 9.0) else "")
                isConvertingTemp = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        celsiusInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (isConvertingTemp) return
                val c = s?.toString()?.toDoubleOrNull()
                isConvertingTemp = true
                fahrenheitInput.setText(if (c != null) formatConvertedTemp(c * 9.0 / 5.0 + 32.0) else "")
                isConvertingTemp = false
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun formatConvertedTemp(value: Double): String {
        val rounded = Math.round(value * 10.0) / 10.0
        // Whole numbers show without a trailing ".0" — 37.0 reads as "37", 37.2 stays "37.2".
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }
}
