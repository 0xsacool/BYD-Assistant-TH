package com.bydassistantng.gemini

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchBatchTest {
    @Test
    fun executesInOrderAndContinuesAfterFailure() = runBlocking {
        val calls = listOf("ac", "fan", "open_app").map { GeminiFunctionCall(it) }
        val visited = mutableListOf<String>()
        val responses = dispatchBatch(calls) { call ->
            visited += call.name
            if (call.name == "fan") error("failed")
            buildJsonObject { put("status", "ok") }
        }
        assertEquals(listOf("ac", "fan", "open_app"), visited)
        assertEquals("ok", responses[0]["status"]?.jsonPrimitive?.content)
        assertTrue(responses[1].containsKey("error"))
        assertEquals("ok", responses[2]["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun cancellationStopsTheBatch() = runBlocking {
        val visited = mutableListOf<String>()
        try {
            dispatchBatch(listOf("one", "two").map { GeminiFunctionCall(it) }) { call ->
                visited += call.name
                throw CancellationException("cancelled")
            }
            throw AssertionError("Cancellation was swallowed")
        } catch (_: CancellationException) {
            assertEquals(listOf("one"), visited)
        }
    }
}
