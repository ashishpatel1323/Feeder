package com.nononsenseapps.feeder.model.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadOptionsTest {
    @Test
    fun bestOfEachCodecAndHeightWithSoundSize() {
        val json =
            """
            {"id":"x","duration":100,"formats":[
              {"format_id":"140","vcodec":"none","acodec":"mp4a","ext":"m4a","protocol":"https","url":"u","tbr":128,"filesize":1000},
              {"format_id":"137","vcodec":"avc1.640028","acodec":"none","ext":"mp4","protocol":"https","url":"u","height":1080,"tbr":4000,"filesize":50000},
              {"format_id":"248","vcodec":"vp9","acodec":"none","ext":"webm","protocol":"https","url":"u","height":1080,"tbr":3000,"filesize":40000},
              {"format_id":"136","vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","protocol":"https","url":"u","height":720,"tbr":2000,"filesize":20000},
              {"format_id":"136low","vcodec":"avc1.4d401f","acodec":"none","ext":"mp4","protocol":"https","url":"u","height":720,"tbr":1000,"filesize":10000},
              {"format_id":"399","vcodec":"av01.0.08M.08","acodec":"none","ext":"mp4","protocol":"https","url":"u","height":1080,"tbr":2500},
              {"format_id":"sb0","vcodec":"none","acodec":"none","ext":"mhtml","protocol":"mhtml","url":"u"}
            ]}
            """.trimIndent()
        val options = VideoDetails.fromJson(Json.parseToJsonElement(json).jsonObject).downloadOptions
        assertEquals(listOf("248", "137", "136"), options.map { it.formatId })
        assertEquals(listOf("VP9", "H264", "H264"), options.map { it.codec })
        assertEquals(41000L, options.first().approxBytes)
    }
}
