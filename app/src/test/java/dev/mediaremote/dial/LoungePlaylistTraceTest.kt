package dev.mediaremote.dial

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoungePlaylistTraceTest {
    @Test fun preservesRawContextAndOmitsUnrelatedValues() {
        val input = JSONObject()
            .put("videoId", "8yW3K--5000")
            .put("listId", "RQexample")
            .put("currentIndex", -1)
            .put("videoIds", JSONArray(listOf("8yW3K--5000", "Ybpj5zKy6Xc")))
            .put("ctt", "opaque-context")
            .put("params", "opaque-params")
            .put("currentTime", "12.5")
            .put("loungeToken", "must-not-be-logged")
            .put("unknownField", "private-value")
        val output = playlistTracePayload(input)
        val fields = output.getJSONObject("fields")
        assertEquals(-1, fields.getInt("currentIndex"))
        assertEquals(2, fields.getJSONArray("videoIds").length())
        assertEquals("opaque-context", fields.getString("ctt"))
        assertEquals("opaque-params", fields.getString("params"))
        assertEquals("12.5", fields.get("currentTime"))
        assertEquals("8yW3K--5000", fields.getString("videoId"))
        assertEquals("RQexample", fields.getString("listId"))
        assertFalse(output.toString().contains("must-not-be-logged"))
        assertFalse(output.toString().contains("private-value"))
        assertTrue(output.getJSONArray("keys").toString().contains("unknownField"))
    }

    @Test fun preservesAbsentNullAndStringQueueDistinctions() {
        val input = JSONObject().put("params", JSONObject.NULL).put("videoIds", "a,b")
        val fields = playlistTracePayload(input).getJSONObject("fields")
        assertFalse(fields.has("ctt"))
        assertTrue(fields.has("params"))
        assertTrue(fields.isNull("params"))
        assertEquals("a,b", fields.get("videoIds"))
        assertEquals(2, input.length())
    }

    @Test fun handlesMissingPayload() {
        val output = playlistTracePayload(null)
        assertFalse(output.getBoolean("payloadPresent"))
        assertFalse(output.has("fields"))
    }
}
