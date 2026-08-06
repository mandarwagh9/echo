package com.mandar.echo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the guard.
 *
 * `unitTests.isReturnDefaultValues = true` (app/build.gradle.kts) makes every
 * android.jar method a no-op returning a default. org.json ships inside the
 * Android platform, so without a real implementation on the test classpath
 * `has()` returns false and `optString()` returns "" for every input — and
 * BatchProtocolTest's whole table would pass while parsing nothing at all.
 *
 * That is precisely the bug this change exists to kill: a test that cannot
 * distinguish "the key is absent" from "the value is empty".
 */
class JsonRealityCheckTest {

    @Test
    fun `org_json on the test classpath is the real implementation`() {
        val json = JSONObject("""{"status":"completed","transcript":""}""")

        assertEquals("completed", json.optString("status"))
        // The live bug in one line: the key is present, the value is empty, and
        // only presence can tell those apart.
        assertTrue("has(transcript) must see an empty-valued key", json.has("transcript"))
        assertEquals("", json.optString("transcript"))
        assertFalse("has() must not invent absent keys", json.has("error_message"))
    }
}
