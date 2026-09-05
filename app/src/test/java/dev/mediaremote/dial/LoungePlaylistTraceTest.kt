package dev.mediaremote.dial

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoungePlaylistTraceTest {
    @Test fun duplicatePendingSetPlaylistKeepsSenderSelectionGuard() {
        assertFalse(shouldResetSenderSelection(isSetPlaylist = true, duplicatePendingSelection = true))
        assertTrue(shouldResetSenderSelection(isSetPlaylist = true, duplicatePendingSelection = false))
        assertFalse(shouldResetSenderSelection(isSetPlaylist = false, duplicatePendingSelection = false))
    }

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

    @Test fun capturesOriginalPlaylistFromStringEntryWithoutOpaqueMetadata() {
        val entry = JSONObject()
            .put("videoId", "abcdefghijk")
            .put("sourceContainerPlaylistId", "PLoriginal")
            .put("serializedMdxMetadata", "must-stay-private")
            .put("unknown", JSONObject().put("token", "nested-secret"))
        val input = JSONObject().put("listId", "RQqueue").put("videoEntry", entry.toString())
        val fields = playlistTracePayload(input).getJSONObject("fields")
        assertEquals("RQqueue", fields.getString("listId"))
        val tracedEntry = JSONObject(fields.getString("videoEntry"))
        assertEquals("abcdefghijk", tracedEntry.getString("videoId"))
        assertEquals("PLoriginal", tracedEntry.getString("sourceContainerPlaylistId"))
        assertTrue(tracedEntry.getJSONArray("keys").toString().contains("serializedMdxMetadata"))
        assertFalse(fields.toString().contains("must-stay-private"))
        assertFalse(fields.toString().contains("nested-secret"))
        assertEquals("must-stay-private", entry.getString("serializedMdxMetadata"))
    }

    @Test fun distinguishesMissingNullAndMalformedSourceEntries() {
        val input = JSONObject()
            .put("videoEntries", JSONArray().put(JSONObject().put("videoId", "abcdefghijk"))
                .put(JSONObject().put("sourceContainerPlaylistId", JSONObject.NULL))
                .put("malformed-private-value"))
            .put("videoEntry", JSONObject.NULL)
        val fields = playlistTracePayload(input).getJSONObject("fields")
        val entries = fields.getJSONArray("videoEntries")
        assertTrue(fields.isNull("videoEntry"))
        assertFalse(entries.getJSONObject(0).has("sourceContainerPlaylistId"))
        assertTrue(entries.getJSONObject(1).isNull("sourceContainerPlaylistId"))
        assertTrue(entries.getJSONObject(2).getBoolean("unparsed"))
        assertFalse(fields.toString().contains("malformed-private-value"))
    }

    @Test fun handlesStringArraysAndDoesNotLeakNestedIdentityObjects() {
        val entry = JSONObject()
            .put("videoId", "abcdefghijk")
            .put("sourceContainerPlaylistId", JSONObject().put("token", "nested-secret"))
        val input = JSONObject().put("videoEntries", JSONArray().put(entry).toString())
        val fields = playlistTracePayload(input).getJSONObject("fields")
        val tracedEntry = JSONArray(fields.getString("videoEntries")).getJSONObject(0)
        assertEquals("abcdefghijk", tracedEntry.getString("videoId"))
        assertTrue(tracedEntry.getJSONObject("sourceContainerPlaylistId").getBoolean("unparsed"))
        assertFalse(fields.toString().contains("nested-secret"))
    }
}
