package com.weatherwidget.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Wraps the plain android.location.LocationManager (no Google Play Services
 * dependency needed) to get one best-effort location fix. Deliberately only
 * uses NETWORK_PROVIDER — that's the coarse-precision provider this app's
 * ACCESS_COARSE_LOCATION permission actually grants access to.
 */
object LocationHelper {
    private const val FIX_TIMEOUT_MS = 8000L
    /** The 12 AM / 12 PM run can wait longer: nobody's watching, and a fresh fix matters more. */
    const val SCHEDULED_FIX_TIMEOUT_MS = 20_000L
    private const val MAX_LAST_KNOWN_AGE_MS = 10 * 60 * 1000L
    /**
     * Oldest saved fix we'll fall back to when no fresh fix arrives. Anything older
     * may be from a trip you're already back from (e.g. still saying Seattle after
     * you've come home), so the widget keeps its last weather and says it couldn't
     * get your location instead of showing the wrong city.
     */
    private const val MAX_FALLBACK_AGE_MS = 60 * 60 * 1000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * "Allow all the time". Without it Android refuses location to the widget's
     * 12 AM / 12 PM update (the app isn't open then), so the "Current" widget would be
     * stuck on wherever the app was last opened.
     */
    fun hasBackgroundPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L

    /**
     * Blocks the calling (background) thread for up to [timeoutMs] and returns where the
     * phone is now: a saved fix under 10 minutes old, otherwise a brand-new fix. If none
     * arrives in time it falls back to a saved fix under an hour old, else null. Never
     * call this from the main thread.
     */
    @SuppressLint("MissingPermission") // guarded by the hasPermission() check below
    fun getLocationBlocking(context: Context, timeoutMs: Long = FIX_TIMEOUT_MS): Location? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        // The fused provider mixes Wi-Fi, cell and sensors and works with coarse
        // permission (Android 12+); older phones use the network provider.
        val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            manager.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER
            else LocationManager.NETWORK_PROVIDER

        val lastKnown = listOf(provider, LocationManager.NETWORK_PROVIDER).distinct()
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .minByOrNull { ageMs(it) }
        if (lastKnown != null && ageMs(lastKnown) < MAX_LAST_KNOWN_AGE_MS) return lastKnown
        val fallback = lastKnown?.takeIf { ageMs(it) < MAX_FALLBACK_AGE_MS }
        if (!manager.isProviderEnabled(provider)) return fallback

        val handlerThread = HandlerThread("WeatherLocationUpdate").apply { start() }
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                fresh = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // getCurrentLocation always works out a new position rather than
                // handing back a cached one.
                val executor = Executor { Handler(handlerThread.looper).post(it) }
                manager.getCurrentLocation(provider, null, executor) { location ->
                    fresh = location
                    latch.countDown()
                }
            } else {
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, handlerThread.looper)
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            fresh ?: fallback
        } catch (e: Exception) {
            fresh ?: fallback
        } finally {
            manager.removeUpdates(listener)
            handlerThread.quitSafely()
        }
    }
}
