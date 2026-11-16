package com.bydassistantng.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.GeminiFunctionDeclaration
import com.bydassistantng.gemini.GeminiSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.math.round

object WeatherTool {
    const val FUNCTION_NAME = "online_weather"
    val declaration = GeminiFunctionDeclaration(
        name = FUNCTION_NAME,
        description = "Only when the user requests online weather, current outdoor air temperature, today's forecast or outdoor PM2.5, get Open-Meteo estimates. Supply the explicitly named city, including region or country when given; omit city for weather at the device's approximate location. For cabin temperature, or specifically the car's sensors, use read_car instead. Cite the source, place and freshness; never guess missing data.",
        parameters = GeminiSchema(type = "OBJECT", properties = mapOf(
            "city" to GeminiSchema(type = "STRING", description = "Named city or province; if specified include region/country separated by commas. Omit when not specified."),
            "include_pm25" to GeminiSchema(type = "BOOLEAN", description = "True only if the user asks about PM2.5 or outdoor air quality."),
        )),
    )
    private val client by lazy {
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    private val aliases = mapOf(
        "กรุงเทพ" to "Bangkok", "กรุงเทพฯ" to "Bangkok", "กรุงเทพมหานคร" to "Bangkok",
        "เชียงใหม่" to "Chiang Mai", "ภูเก็ต" to "Phuket", "พัทยา" to "Pattaya",
        "ชลบุรี" to "Chon Buri", "ปทุมธานี" to "Pathum Thani", "ขอนแก่น" to "Khon Kaen",
        "หาดใหญ่" to "Hat Yai", "ไทย" to "Thailand", "ประเทศไทย" to "Thailand",
    )
    internal fun cityQuery(city: String) = aliases[city.trim()] ?: city.trim()
    internal fun url(base: String, params: Map<String, String>): String {
        val builder = base.toHttpUrl().newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }
    private fun get(url: String): JsonObject = client.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) error("Weather service unavailable")
        Json.parseToJsonElement(it.body?.string() ?: error("Empty response")).jsonObject
    }
    private const val MAX_LOCATION_AGE_MS = 60_000L
    private const val LOCATION_TIMEOUT_MS = 4_000L

    internal fun isFreshLocation(now: Long, fix: Long): Boolean = now - fix in 0L..MAX_LOCATION_AGE_MS

    // A single bounded request, only when the user asks for weather and has opted in.
    // No background permission, persistent coordinates or continuous tracking.
    private suspend fun approximateLocation(context: Context): Pair<Double, Double>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
            val cached = providers.mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: Exception) { null }
            }.filter { isFreshLocation(System.currentTimeMillis(), it.time) }.maxByOrNull { it.time }
            val location = cached ?: withTimeoutOrNull(LOCATION_TIMEOUT_MS) { currentLocation(context, manager, providers) }
            location?.let { it.latitude to it.longitude }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun currentLocation(context: Context, manager: LocationManager, providers: List<String>): Location? =
        suspendCancellableCoroutine { continuation ->
            if (providers.isEmpty() || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val signals = providers.map { CancellationSignal() }
            val remaining = AtomicInteger(providers.size)
            val completed = AtomicBoolean(false)
            continuation.invokeOnCancellation { signals.forEach { it.cancel() } }
            fun finish(location: Location?) {
                val valid = location?.takeIf { isFreshLocation(System.currentTimeMillis(), it.time) }
                if ((valid != null || remaining.decrementAndGet() == 0) && completed.compareAndSet(false, true)) {
                    signals.forEach { it.cancel() }
                    if (continuation.isActive) continuation.resume(valid)
                }
            }
            providers.forEachIndexed { index, provider ->
                if (!completed.get() && continuation.isActive) {
                    try {
                        LocationManagerCompat.getCurrentLocation(manager, provider, signals[index], ContextCompat.getMainExecutor(context)) { location ->
                            finish(location)
                        }
                    } catch (_: Exception) { finish(null) }
                }
            }
        }
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    suspend fun handle(context: Context, call: GeminiFunctionCall, enabled: Boolean, locationEnabled: Boolean): JsonObject {
        val result = execute(call, enabled, locationEnabled, { approximateLocation(context) }, ::get)
        if (!enabled) return result
        // Save only a successfully resolved explicitly requested city, never device coordinates.
        val prefs = context.getSharedPreferences("weather_city", Context.MODE_PRIVATE)
        val city = (call.args["city"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        if (city.isNotEmpty() && result["error"] == null) {
            result.string("location")?.let { prefs.edit().putString("last_city", it).apply() }
        }
        if ((result["needs_city"] as? JsonPrimitive)?.booleanOrNull == true) {
            val last = prefs.getString("last_city", null)
            if (!last.isNullOrBlank()) return JsonObject(result + ("last_named_city" to JsonPrimitive(last)))
        }
        return result
    }

    internal suspend fun execute(call: GeminiFunctionCall, enabled: Boolean, locationEnabled: Boolean,
        location: suspend () -> Pair<Double, Double>?, fetch: (String) -> JsonObject): JsonObject = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext errorAnswer("Online weather is disabled in Settings.")
        val rawCity = call.args["city"]
        if (rawCity != null && (rawCity !is JsonPrimitive || !rawCity.isString)) return@withContext errorAnswer("Please specify a city name.")
        val city = (rawCity as? JsonPrimitive)?.content?.trim().orEmpty()
        try {
            val place: String
            val coordinates: Pair<Double, Double>
            if (city.isNotEmpty()) {
                if (city.length > 100 || city.any { it.isISOControl() }) return@withContext errorAnswer("Please provide a shorter city name.")
                val parts = city.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.isEmpty()) return@withContext errorAnswer("Please provide a city name.")
                val query = cityQuery(parts.first())
                val params = mapOf("name" to query, "count" to "10", "language" to "en", "format" to "json")
                val geo = fetch(url("https://geocoding-api.open-meteo.com/v1/search", params))
                val results = (geo["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                val thaiAlias = query != parts.first()
                val eligible = results.filter { candidate ->
                    (!thaiAlias || candidate.string("country_code")?.equals("TH", ignoreCase = true) == true || candidate.string("country") == "Thailand") &&
                    parts.drop(1).all { part -> listOf("admin1", "country", "country_code").any {
                        candidate.string(it)?.equals(cityQuery(part), ignoreCase = true) == true
                    } }
                }
                val exact = eligible.filter { it.string("name")?.equals(query, ignoreCase = true) == true }
                val choices = exact.ifEmpty { eligible }
                if (choices.size != 1) return@withContext errorAnswer(
                    if (choices.isEmpty()) "City not found. Ask the user for a city and country."
                    else "Multiple locations match. Ask the user to specify the province or country."
                )
                val result = choices.single()
                coordinates = (result.number("latitude") ?: error("Missing city coordinates")) to (result.number("longitude") ?: error("Missing city coordinates"))
                place = listOfNotNull(result.string("name"), result.string("admin1"), result.string("country")).distinct().joinToString(", ")
            } else {
                val approximate = (if (locationEnabled) location() else null)
                    ?: return@withContext buildJsonObject {
                        put("error", "Current approximate location is unavailable")
                        put("needs_city", true)
                    }
                coordinates = round(approximate.first * 10) / 10 to round(approximate.second * 10) / 10
                place = "Approximate device location"
            }
            if (!coordinates.first.isFinite() || !coordinates.second.isFinite() || coordinates.first !in -90.0..90.0 || coordinates.second !in -180.0..180.0)
                return@withContext errorAnswer("Location unavailable. Ask the user for a city.")
            val common = mapOf("latitude" to coordinates.first.toString(), "longitude" to coordinates.second.toString(), "timezone" to "auto")
            val forecast = fetch(url("https://api.open-meteo.com/v1/forecast", common + mapOf("current" to "temperature_2m,weather_code", "daily" to "temperature_2m_max,temperature_2m_min,precipitation_probability_max", "forecast_days" to "1")))
            val includePm25 = (call.args["include_pm25"] as? JsonPrimitive)?.booleanOrNull == true
            val air = if (includePm25) try { fetch(url("https://air-quality-api.open-meteo.com/v1/air-quality", common + mapOf("current" to "pm2_5"))) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
            report(place, forecast, air, includePm25)
        } catch (e: CancellationException) { throw e } catch (_: Exception) {
            errorAnswer("Online weather is unavailable. Please try again or provide another city.")
        }
    }
    internal fun condition(code: Int): String = when (code) {
        0 -> "Clear sky"
        1, 2 -> "Mainly clear or partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77 -> "Snow"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown condition"
    }
    internal fun errorAnswer(message: String) = buildJsonObject { put("error", message) }
    internal fun report(place: String, forecast: JsonObject, air: JsonObject?, includePm25: Boolean = true): JsonObject {
        val current = forecast["current"]?.jsonObject ?: error("Missing current forecast")
        val daily = forecast["daily"]?.jsonObject ?: error("Missing daily forecast")
        val aq = air?.get("current") as? JsonObject
        val temperature = current.number("temperature_2m") ?: error("Missing temperature")
        val time = current.string("time") ?: error("Missing forecast time")
        fun first(key: String) = (daily[key] as? JsonArray)?.firstOrNull() as? JsonPrimitive
        return buildJsonObject {
            put("location", place)
            put("source", "Open-Meteo (https://open-meteo.com/); online outdoor forecast, not vehicle sensors")
            forecast.string("timezone")?.let { put("timezone", it) }
            put("current_time", time)
            put("temperature_c", temperature)
            current.number("weather_code")?.toInt()?.let { put("condition", condition(it)) }
            first("time")?.contentOrNull?.let { put("forecast_date", it) }
            first("temperature_2m_max")?.doubleOrNull?.takeIf { it.isFinite() }?.let { put("high_c", it) }
            first("temperature_2m_min")?.doubleOrNull?.takeIf { it.isFinite() }?.let { put("low_c", it) }
            first("precipitation_probability_max")?.doubleOrNull?.takeIf { it in 0.0..100.0 }?.let { put("rain_probability_max_percent", it) }
            val pm = aq?.number("pm2_5")
            if (aq != null && pm != null && pm >= 0 && aq.string("time") != null) {
                put("pm2_5_ug_m3", pm)
                put("pm2_5_time", aq.string("time"))
                put("pm2_5_note", "Outdoor model estimate, not a measurement or cabin reading; CAMS via Open-Meteo")
            } else if (includePm25) put("pm2_5_note", "Outdoor model estimate unavailable")
        }
    }
}
