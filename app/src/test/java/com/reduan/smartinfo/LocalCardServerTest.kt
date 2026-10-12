package com.reduan.smartinfo

import com.reduan.smartinfo.localserver.CardContent
import com.reduan.smartinfo.localserver.LocalCardServer
import com.reduan.smartinfo.localserver.ServerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class LocalCardServerTest {
    private val content = CardContent("Test <User>", listOf("Full name" to "Test <User>"), "BEGIN:VCARD\r\nFN:Test\r\nEND:VCARD\r\n")

    private fun http(url: String, method: String = "GET"): Pair<Int, String> {
        val u = java.net.URI(url)
        Socket(u.host, u.port).use { s ->
            val target = u.rawPath + (u.rawQuery?.let { "?$it" } ?: "")
            s.getOutputStream().write("$method $target HTTP/1.1\r\nHost: x\r\nContent-Length: 0\r\n\r\n".toByteArray())
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            return resp.substringAfter(' ').substringBefore(' ').toInt() to resp
        }
    }

    private fun waitFor(events: List<ServerEvent>, e: ServerEvent) {
        repeat(50) { if (e in events) return; Thread.sleep(20) }
    }

    @Test fun fullConsentFlowIsSingleUse() {
        val events = CopyOnWriteArrayList<ServerEvent>()
        val server = LocalCardServer(InetAddress.getLoopbackAddress(), content) { events += it }
        val url = server.start()

        val (code, page) = http(url)
        assertEquals(200, code)
        assertTrue(page.contains("Test &lt;User&gt;"))          // HTML-escaped
        assertTrue(!page.contains("card.vcf"))                  // no download before consent

        assertEquals(404, http(url.replace("/s/", "/s/x")).first)   // wrong token
        val (c2, ready) = http("$url/accept", "POST")
        assertEquals(200, c2)
        val href = Regex("href=\"([^\"]+)\"").find(ready)!!.groupValues[1].replace("&amp;", "&")
        val base = url.substringBefore("/s/")

        assertEquals(403, http("$base${href.substringBefore("?k=")}?k=wrong").first)
        val (c3, file) = http(base + href)
        assertEquals(200, c3)
        assertTrue(file.contains("BEGIN:VCARD"))
        assertTrue(file.contains("Content-Disposition: attachment"))

        waitFor(events, ServerEvent.Delivered)
        assertTrue(ServerEvent.Accepted in events)
        assertTrue(ServerEvent.Delivered in events)
        // Server is closed after delivery: replay is impossible.
        assertTrue(runCatching { http(base + href) }.isFailure)
    }

    @Test fun rejectEndsSession() {
        val events = CopyOnWriteArrayList<ServerEvent>()
        val server = LocalCardServer(InetAddress.getLoopbackAddress(), content) { events += it }
        val url = server.start()
        assertEquals(200, http("$url/reject", "POST").first)
        waitFor(events, ServerEvent.Rejected)
        assertTrue(ServerEvent.Rejected in events)
    }

    @Test fun sessionExpires() {
        val events = CopyOnWriteArrayList<ServerEvent>()
        var now = 0L
        val server = LocalCardServer(InetAddress.getLoopbackAddress(), content, ttlMs = 1000, clock = { now }) { events += it }
        server.start()
        now = 5000
        repeat(100) { if (ServerEvent.Expired in events) return@repeat; Thread.sleep(30) }
        assertTrue(ServerEvent.Expired in events)
    }

    @Test fun latestContentIsServed() {
        val server = LocalCardServer(InetAddress.getLoopbackAddress(), content) {}
        val url = server.start()
        server.updateContent(content.copy(displayName = "Updated Name"))
        assertTrue(http(url).second.contains("Updated Name"))
        server.stop()
    }
}
