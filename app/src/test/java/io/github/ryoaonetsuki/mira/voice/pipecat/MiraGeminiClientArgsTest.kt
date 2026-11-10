package io.github.ryoaonetsuki.mira.voice.pipecat

import ai.pipecat.client.types.Value
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying that [ParsedFunctionCall] arg parsing in [MiraGeminiClient]
 * preserves JSON types (numbers, booleans, nested objects/arrays, strings) while
 * maintaining backward compatibility with string-arg consumers via [ParsedFunctionCall.stringArgs].
 */
class MiraGeminiClientArgsTest {

    @Test
    fun testParseFunctionCall_BasicStringArgs_Preserved() {
        val fc = JSONObject().apply {
            put("id", "call_abc123")
            put("name", "open_app")
            put("args", JSONObject().apply {
                put("app", "youtube")
            })
        }

        val parsed = parseFunctionCall(fc)
        assertEquals("call_abc123", parsed.id)
        assertEquals("open_app", parsed.name)
        assertEquals("youtube", parsed.args["app"])
        assertEquals("youtube", parsed.stringArgs["app"])
    }

    @Test
    fun testParseFunctionCall_NumericAndBooleanTypesPreserved() {
        val fc = JSONObject().apply {
            put("id", "call_num")
            put("name", "click_coordinate")
            put("args", JSONObject().apply {
                put("x", 123)
                put("y", 456.78)
                put("active", true)
                put("negative", -42)
            })
        }

        val parsed = parseFunctionCall(fc)
        assertEquals("call_num", parsed.id)
        assertEquals("click_coordinate", parsed.name)

        // Numbers must be preserved as numeric types, NOT coerced to String
        val x = parsed.args["x"]
        assertTrue("x should be a Number but was ${x?.javaClass}", x is Number)
        assertEquals(123, (x as Number).toInt())

        val y = parsed.args["y"]
        assertTrue("y should be a Number but was ${y?.javaClass}", y is Number)
        assertEquals(456.78, (y as Number).toDouble(), 0.001)

        val active = parsed.args["active"]
        assertTrue("active should be a Boolean but was ${active?.javaClass}", active is Boolean)
        assertEquals(true, active)

        val negative = parsed.args["negative"]
        assertTrue("negative should be a Number", negative is Number)
        assertEquals(-42, (negative as Number).toInt())

        // Backward compatibility: stringArgs must provide String representations
        assertEquals("123", parsed.stringArgs["x"])
        assertEquals("456.78", parsed.stringArgs["y"])
        assertEquals("true", parsed.stringArgs["active"])
        assertEquals("-42", parsed.stringArgs["negative"])
    }

    @Test
    fun testParseFunctionCall_NestedObjectsAndArraysPreserved() {
        val fc = JSONObject().apply {
            put("id", "call_nested")
            put("name", "complex_action")
            put("args", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("timeout", 5000)
                    put("mode", "strict")
                })
                put("tags", JSONArray().apply {
                    put("alpha")
                    put(99)
                    put(false)
                })
            })
        }

        val parsed = parseFunctionCall(fc)

        // Verify nested Object / Map
        val config = parsed.args["config"]
        assertTrue("config should be a Map", config is Map<*, *>)
        val configMap = config as Map<*, *>
        assertEquals("strict", configMap["mode"])
        val timeout = configMap["timeout"]
        assertTrue("timeout in nested config should be Number", timeout is Number)
        assertEquals(5000, (timeout as Number).toInt())

        // Verify nested Array / List
        val tags = parsed.args["tags"]
        assertTrue("tags should be a List", tags is List<*>)
        val tagsList = tags as List<*>
        assertEquals(3, tagsList.size)
        assertEquals("alpha", tagsList[0])
        assertEquals(99, (tagsList[1] as Number).toInt())
        assertEquals(false, tagsList[2])
    }

    @Test
    fun testToPipecatValue_PreservesValueTypes() {
        assertEquals(Value.Str("text"), "text".toPipecatValue())
        assertEquals(Value.Number(123.0), 123.toPipecatValue())
        assertEquals(Value.Number(45.67), 45.67.toPipecatValue())
        assertEquals(Value.Bool(true), true.toPipecatValue())
        assertEquals(Value.Bool(false), false.toPipecatValue())
        assertEquals(Value.Null, null.toPipecatValue())

        val listVal = listOf(1, "two").toPipecatValue()
        assertTrue(listVal is Value.Array)
        val arr = (listVal as Value.Array).value
        assertEquals(Value.Number(1.0), arr[0])
        assertEquals(Value.Str("two"), arr[1])

        val mapVal = mapOf("foo" to 42).toPipecatValue()
        assertTrue(mapVal is Value.Object)
        val obj = (mapVal as Value.Object).value
        assertEquals(Value.Number(42.0), obj["foo"])
    }
}
