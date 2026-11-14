package com.weatherwidget.app

import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime

/**
 * Talks to the Weather Donkey Hub server on the home network (see ../server) —
 * never to a weather service directly. The server pulls from Open-Meteo and
 * MET Norway, falls back to whichever one is up, records everything hourly,
 * and answers history lookups.
 *
 * The server only listens on the home network, so away from home Wi-Fi every
 * call here ends in [Outcome.ServerUnreachable].
 *
 * Every call is blocking — always run it on a background thread.
 */
object WeatherClient {
    private const val TAG = "WeatherClient"
    private const val CONNECT_TIMEOUT_MS = 5_000
    // The server may retry each weather source once before answering, so give it room.
    private const val READ_TIMEOUT_MS = 30_000

    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        /** Phone couldn't reach the server at all — usually "not on home Wi-Fi". */
        object ServerUnreachable : Outcome<Nothing>()
        /** Server answered with an error, e.g. both weather services are down. */
        data class Failed(val message: String) : Outcome<Nothing>()
    }

    /**
     * Fresh weather for one location. Also tells the server about this location
     * (id, name, coordinates), which is what keeps it recording hourly.
     */
    fun fetchWeather(locationId: String, name: String?, lat: Double, lon: Double): Outcome<WeatherSnapshot> {
        val params = buildMap {
            put("id", locationId)
            if (name != null) put("name", name)
            put("lat", lat.toString())
            put("lon", lon.toString())
        }
        return when (val result = request("GET", "/api/weather", params)) {
            is Outcome.Ok -> WeatherSnapshot.fromJson(result.value)
                ?.let { Outcome.Ok(it) }
                ?: Outcome.Failed("Server sent weather the app couldn't read")
            is Outcome.Failed -> result
            Outcome.ServerUnreachable -> Outcome.ServerUnreachable
        }
    }

    /** What the weather was on [date] — at [time] too, if given (the server rounds to the hour). */
    fun lookupHistory(locationId: String, lat: Double, lon: Double, date: LocalDate, time: LocalTime?): Outcome<HistoryResult> {
        val params = buildMap {
            put("id", locationId)
            put("lat", lat.toString())
            put("lon", lon.toString())
            put("date", date.toString())
            if (time != null) put("time", "%02d:%02d".format(time.hour, time.minute))
        }
        return when (val result = request("GET", "/api/history", params)) {
            is Outcome.Ok -> HistoryResult.fromJson(result.value)
                ?.let { Outcome.Ok(it) }
                ?: Outcome.Failed("Server sent history the app couldn't read")
            is Outcome.Failed -> result
            Outcome.ServerUnreachable -> Outcome.ServerUnreachable
        }
    }

    /** One location the server knows about. `active` = still being recorded hourly. */
    data class ServerLocation(val id: String, val name: String, val lat: Double, val lon: Double, val active: Boolean)

    /** Every location the server has ever recorded, including removed ones (active = false). */
    fun listLocations(): Outcome<List<ServerLocation>> =
        when (val result = request("GET", "/api/locations", mapOf("all" to "1"))) {
            is Outcome.Ok -> try {
                val array = JSONObject(result.value).getJSONArray("locations")
                Outcome.Ok((0 until array.length()).map { i ->
                    val o = array.getJSONObject(i)
                    ServerLocation(
                        id = o.getString("id"),
                        name = o.optString("name").ifBlank { o.getString("id") },
                        lat = o.getDouble("lat"),
                        lon = o.getDouble("lon"),
                        active = o.optInt("active", 1) == 1
                    )
                })
            } catch (e: Exception) {
                Outcome.Failed("Server sent a location list the app couldn't read")
            }
            is Outcome.Failed -> result
            Outcome.ServerUnreachable -> Outcome.ServerUnreachable
        }

    /** Starts hourly recording for a city added in the app. Best-effort. */
    fun addLocation(name: String, lat: Double, lon: Double) {
        request("POST", "/api/locations", mapOf("name" to name, "lat" to lat.toString(), "lon" to lon.toString()))
    }

    /** Stops hourly recording for a removed tab. Its history stays on the server. Best-effort. */
    fun forgetLocation(locationId: String) {
        request("DELETE", "/api/locations", mapOf("id" to locationId))
    }

    private fun request(method: String, path: String, params: Map<String, String>): Outcome<String> {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val connection = try {
            (URL("${BuildConfig.SERVER_URL}$path?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
        } catch (e: IOException) {
            Log.w(TAG, "$method $path: ${e.javaClass.simpleName}: ${e.message}")
            return Outcome.ServerUnreachable
        }
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                Outcome.Ok(body)
            } else {
                val message = runCatching { JSONObject(body).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "Server error ($code)"
                Log.w(TAG, "$method $path: HTTP $code $message")
                Outcome.Failed(message)
            }
        } catch (e: IOException) {
            // Connection refused / timed out / no route: the server isn't reachable from here.
            Log.w(TAG, "$method $path: ${e.javaClass.simpleName}: ${e.message}")
            Outcome.ServerUnreachable
        } finally {
            connection.disconnect()
        }
    }
}

/** The answer to "what was the weather on this day / at this time?". */
data class HistoryResult(
    val date: LocalDate,
    val time: String?,            // "15:00", or null for a whole-day lookup
    val fromRecordings: Boolean,  // false = filled in from Open-Meteo's archive
    val sourceLabel: String,
    val highF: Double,
    val lowF: Double,
    val dayCode: Int,
    val dayWindMph: Double,
    val dayRainChancePercent: Int, // -1 when unknown
    val dayPrecipInches: Double?,
    val sunrise: String,
    val sunset: String,
    val hourTempF: Double?,
    val hourCode: Int?,
    val hourIsDay: Boolean,
    val hourWindMph: Double?
) {
    companion object {
        fun fromJson(json: String): HistoryResult? = try {
            val root = JSONObject(json)
            val day = root.getJSONObject("day")
            val hour = root.optJSONObject("hour")
            HistoryResult(
                date = LocalDate.parse(root.getString("date")),
                time = root.optString("time").takeUnless { root.isNull("time") || it.isEmpty() },
                fromRecordings = root.optString("source") == "recorded",
                sourceLabel = root.optString("source_label"),
                highF = day.getDouble("high_f"),
                lowF = day.getDouble("low_f"),
                dayCode = day.getInt("code"),
                dayWindMph = day.optDouble("wind_mph", 0.0),
                dayRainChancePercent = day.optInt("rain_pct", -1),
                dayPrecipInches = if (day.isNull("precip_in")) null else day.optDouble("precip_in"),
                sunrise = day.optString("sunrise"),
                sunset = day.optString("sunset"),
                hourTempF = hour?.getDouble("temp_f"),
                hourCode = hour?.getInt("code"),
                hourIsDay = hour?.optBoolean("is_day", true) ?: true,
                hourWindMph = hour?.optDouble("wind_mph", 0.0)
            )
        } catch (e: Exception) {
            null
        }
    }
}
