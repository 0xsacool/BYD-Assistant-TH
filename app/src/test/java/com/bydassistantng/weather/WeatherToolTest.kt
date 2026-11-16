package com.bydassistantng.weather

import com.bydassistantng.gemini.GeminiFunctionCall
import com.bydassistantng.gemini.assistantTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class WeatherToolTest {
    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun call(city: String? = null) = GeminiFunctionCall(name = WeatherTool.FUNCTION_NAME,
        args = JsonObject(if (city == null) emptyMap() else mapOf("city" to JsonPrimitive(city))))
    private val forecast = parse("""{"timezone":"Asia/Bangkok","current":{"time":"2026-01-01T12:00","temperature_2m":30,"weather_code":3},"daily":{"time":["2026-01-01"],"temperature_2m_max":[33],"temperature_2m_min":[25],"precipitation_probability_max":[40]}}""")
    private val air = parse("""{"current":{"time":"2026-01-01T12:00","pm2_5":18}}""")

    @Test fun declarationIsOptInInBothModesWithoutCarControl() {
        for (conversation in listOf(false, true)) {
            fun names(on: Boolean) = assistantTools(false, conversation = conversation, weather = on).flatMap { it.functionDeclarations.orEmpty() }.map { it.name }
            assertFalse(WeatherTool.FUNCTION_NAME in names(false))
            assertTrue(WeatherTool.FUNCTION_NAME in names(true))
        }
    }
    @Test fun disabledAndMissingLocationNeverFetch() = runBlocking {
        var requests = 0
        val fetch: (String) -> JsonObject = { requests++; forecast }
        assertTrue(WeatherTool.execute(call("Bangkok"), false, true, { 1.0 to 2.0 }, fetch).containsKey("error"))
        assertTrue(WeatherTool.execute(call(), true, false, { 1.0 to 2.0 }, fetch).containsKey("error"))
        assertTrue(WeatherTool.execute(call(), true, true, { null }, fetch).containsKey("error"))
        assertEquals(0, requests)
    }
    @Test fun namedThaiCityWinsAndReportsForecastAndEstimate() = runBlocking {
        val urls = mutableListOf<String>()
        val result = WeatherTool.execute(call("กรุงเทพ"), true, true, { fail("location should not be read"); null }) { url ->
            urls += url
            when {
                url.contains("geocoding-api") -> parse("""{"results":[{"name":"Bangkok","country":"Thailand","latitude":13.7,"longitude":100.5}]}""")
                url.contains("air-quality-api") -> air
                else -> forecast
            }
        }
        assertTrue(urls.first().contains("name=Bangkok"))
        assertEquals("Bangkok, Thailand", result.getValue("location").jsonPrimitive.content)
        assertEquals("30.0", result.getValue("temperature_c").jsonPrimitive.content)
        assertEquals("18.0", result.getValue("pm2_5_ug_m3").jsonPrimitive.content)
        assertTrue(result.getValue("pm2_5_note").jsonPrimitive.content.contains("model estimate"))
        assertEquals("40.0", result.getValue("rain_probability_max_percent").jsonPrimitive.content)
    }
    @Test fun locationFallbackAndAirFailureAreExplicit() = runBlocking {
        val urls = mutableListOf<String>()
        val result = WeatherTool.execute(call(), true, true, { 13.7 to 100.5 }) { url ->
            urls += url
            if (url.contains("air-quality-api")) throw IllegalStateException() else forecast
        }
        assertEquals(2, urls.size)
        assertTrue(urls.first().contains("latitude=13.7"))
        assertTrue(result.getValue("pm2_5_note").jsonPrimitive.content.contains("unavailable"))
    }
    @Test fun ambiguousCityNeverFetchesForecast() = runBlocking {
        val urls = mutableListOf<String>()
        val result = WeatherTool.execute(call("Springfield"), true, false, { null }) { url ->
            urls += url
            parse("""{"results":[{"name":"Springfield","admin1":"Illinois","country":"United States","latitude":39.8,"longitude":-89.6},{"name":"Springfield","admin1":"Missouri","country":"United States","latitude":37.2,"longitude":-93.3}]}""")
        }
        assertEquals(1, urls.size)
        assertTrue(result.getValue("error").jsonPrimitive.content.contains("Multiple"))
    }
    @Test fun invalidLocationFailsWithoutFetch() = runBlocking {
        var requests = 0
        val result = WeatherTool.execute(call(), true, true, { 100.0 to 181.0 }) { requests++; forecast }
        assertTrue(result.containsKey("error"))
        assertEquals(0, requests)
    }
    @Test fun roundsLocationAndHandlesNetworkFailure() = runBlocking {
        val urls = mutableListOf<String>()
        val result = WeatherTool.execute(call(), true, true, { 13.76543 to 100.56789 }) { url ->
            urls += url
            throw IllegalStateException("network")
        }
        assertTrue(result.containsKey("error"))
        assertEquals(1, urls.size)
        assertTrue(urls.single().contains("latitude=13.8"))
        assertTrue(urls.single().contains("longitude=100.6"))
        assertFalse(urls.single().contains("13.76543"))
    }
    @Test fun preservesCancellation() = runBlocking {
        try {
            WeatherTool.execute(call(), true, true, { 13.8 to 100.6 }) { throw CancellationException("stop") }
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            // Expected: cancellation should never be converted to a tool result.
        }
    }
}
