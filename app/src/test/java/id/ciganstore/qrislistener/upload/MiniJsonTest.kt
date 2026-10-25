package id.ciganstore.qrislistener.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniJsonTest {
    @Test
    fun `reads the Worker response envelopes`() {
        val error = MiniJson.parseObject(
            "{\"error\":{\"code\":\"replayed_request\",\"message\":\"Permintaan listener sudah pernah diterima.\"}}",
        )!!
        val accepted = MiniJson.parseObject(" {\"accepted\": true, \"duplicate\": false, \"matchStatus\": null} ")!!

        assertEquals("replayed_request", (error["error"] as Map<*, *>)["code"])
        assertEquals(true, accepted["accepted"])
        assertEquals(false, accepted["duplicate"])
        assertNull(accepted["matchStatus"])
    }

    @Test
    fun `decodes string escapes`() {
        val value = MiniJson.parseObject("{\"a\":\"\\u00e9\\n\\\"\\\\\\/\"}")!!["a"]

        assertEquals("é\n\"\\/", value)
    }

    @Test
    fun `anything that is not a single JSON object is refused`() {
        listOf(
            null,
            "",
            "   ",
            "[]",
            "\"accepted\"",
            "{",
            "{\"a\":1,}",
            "{\"a\":1} trailing",
            "{\"a\":1}{\"b\":2}",
            "<!DOCTYPE html><html><body>Error 1101</body></html>",
            "{\"a\":\"\\u-001\"}",
            "{\"a\":\"\\x\"}",
            "{\"a\":tru}",
            "{a:1}",
            "{\"a\":\"line\nbreak\"}",
        ).forEach { text -> assertNull(text, MiniJson.parseObject(text)) }
    }

    @Test
    fun `deep nesting is refused`() {
        val deep = "{\"a\":".repeat(40) + "1" + "}".repeat(40)

        assertNull(MiniJson.parseObject(deep))
    }
}
