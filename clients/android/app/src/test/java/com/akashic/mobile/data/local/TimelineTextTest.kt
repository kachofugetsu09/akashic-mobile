package com.akashic.mobile.data.local

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineTextTest {
    @Test
    fun `output summary reads only text parts and preserves the full body`() {
        val body = buildJsonObject {
            put("kind", "output")
            put("finish", "complete")
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("kind", "text")
                    put("value", "第一段")
                })
                add(buildJsonObject {
                    put("kind", "model.facts")
                    put("value", buildJsonObject {
                        put("call_record_id", "call-1")
                        put("thinking", "完整思考")
                    })
                })
                add(buildJsonObject {
                    put("kind", "artifact_ref")
                    put("value", "artifact-1")
                })
                add(buildJsonObject {
                    put("kind", "text")
                    put("value", "第二段")
                })
            })
        }
        val original = body.toString()

        assertEquals("第一段\n第二段", timelineText(body))
        assertEquals(original, body.toString())
    }
}
