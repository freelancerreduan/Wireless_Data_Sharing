package com.reduan.smartinfo.localserver

import com.reduan.smartinfo.security.Tokens
import com.reduan.smartinfo.storage.FileNames
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/** What the receiver's browser will see and download. Rebuilt whenever the saved profile changes. */
data class CardContent(
    val displayName: String,
    val summary: List<Pair<String, String>>,
    val vcard: String,
)

sealed interface ServerEvent {
    data class Started(val url: String, val expiresAt: Long) : ServerEvent
    data object PageOpened : ServerEvent
    data object Accepted : ServerEvent
    // Terminal events — the server is closed after any of these:
    data object Delivered : ServerEvent
    data object Rejected : ServerEvent
    data object Expired : ServerEvent
    data object Stopped : ServerEvent
    data class Failed(val message: String) : ServerEvent
}

/**
 * Minimal single-session HTTP server for sending the card to a phone that does NOT have the app.
 *  - Binds only to the chosen local (Wi-Fi / hotspot) address, never 0.0.0.0.
 *  - Rejects clients that are not on a private/link-local network.
 *  - Random session token in the URL, short expiry, receiver must press Accept,
 *    the download link is single-use (replay protection), then the server shuts down.
 *  - Never logs profile content.
 */
class LocalCardServer(
    private val bindAddress: InetAddress,
    initialContent: CardContent,
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onEvent: (ServerEvent) -> Unit,
) {
    private enum class State { WAITING, ACCEPTED, DONE }

    @Volatile private var content = initialContent
    private val token = Tokens.newToken()
    private var downloadKey: String? = null
    private var state = State.WAITING
    private var pageOpened = false
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    var expiresAt = 0L
        private set
    var url: String = ""
        private set

    fun updateContent(c: CardContent) { content = c }

    @Throws(IOException::class)
    fun start(): String {
        val ss = ServerSocket(0, 8, bindAddress)
        ss.soTimeout = 1_000
        serverSocket = ss
        running = true
        expiresAt = clock() + ttlMs
        url = "http://${bindAddress.hostAddress}:${ss.localPort}/s/$token"
        Thread({ loop(ss) }, "local-card-server").apply { isDaemon = true }.start()
        onEvent(ServerEvent.Started(url, expiresAt))
        return url
    }

    fun stop() = finish(ServerEvent.Stopped)

    @Synchronized
    private fun finish(event: ServerEvent) {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        onEvent(event)
    }

    private fun loop(ss: ServerSocket) {
        while (running) {
            if (clock() > expiresAt) { finish(ServerEvent.Expired); break }
            val client: Socket = try {
                ss.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (running) finish(ServerEvent.Failed("The local server stopped unexpectedly."))
                break
            }
            try {
                client.use { handle(it) }
            } catch (_: Exception) {
                // A broken client connection must not kill the session.
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val out = socket.getOutputStream()
        val remote = socket.inetAddress
        if (!(remote.isSiteLocalAddress || remote.isLinkLocalAddress || remote.isLoopbackAddress)) {
            respond(out, 403, "text/plain", "Forbidden".toByteArray()); return
        }
        val req = HttpRequest.parse(BufferedInputStream(socket.getInputStream()))
            ?: run { respond(out, 400, "text/plain", "Bad request".toByteArray()); return }

        val segments = req.path.trim('/').split('/')
        if (segments.size < 2 || segments[0] != "s" || !Tokens.matches(token, segments[1])) {
            respond(out, 404, "text/plain", "Not found".toByteArray()); return
        }
        val action = segments.getOrNull(2)
        synchronized(this) {
            if (!running || state == State.DONE) {
                html(out, 410, Pages.message("Session ended", "This sharing session has ended. Ask the sender to start a new one.")); return
            }
            when {
                action == null && req.method == "GET" -> {
                    if (!pageOpened) { pageOpened = true; onEvent(ServerEvent.PageOpened) }
                    val c = content
                    if (state == State.ACCEPTED) html(out, 200, Pages.ready(c, downloadHref()))
                    else html(out, 200, Pages.consent(c, "/s/$token"))
                }
                action == "accept" && req.method == "POST" -> {
                    if (state == State.WAITING) {
                        state = State.ACCEPTED
                        downloadKey = Tokens.newToken(16)
                        onEvent(ServerEvent.Accepted)
                    }
                    html(out, 200, Pages.ready(content, downloadHref()))
                }
                action == "reject" && req.method == "POST" -> {
                    html(out, 200, Pages.message("Declined", "You declined. Nothing was downloaded."))
                    state = State.DONE
                    finish(ServerEvent.Rejected)
                }
                action == "card.vcf" && req.method == "GET" -> {
                    val key = downloadKey
                    if (state != State.ACCEPTED || key == null || !Tokens.matches(key, req.query["k"])) {
                        respond(out, 403, "text/plain", "This download link is not valid.".toByteArray()); return
                    }
                    val c = content
                    val name = FileNames.sanitize(c.displayName) + ".vcf"
                    respond(
                        out, 200, "text/vcard; charset=utf-8", c.vcard.toByteArray(Charsets.UTF_8),
                        extra = mapOf("Content-Disposition" to "attachment; filename=\"$name\""),
                    )
                    state = State.DONE
                    finish(ServerEvent.Delivered)
                }
                else -> respond(out, 405, "text/plain", "Method not allowed".toByteArray())
            }
        }
    }

    private fun downloadHref() = "/s/$token/card.vcf?k=$downloadKey"

    private fun html(out: OutputStream, code: Int, body: String) =
        respond(out, code, "text/html; charset=utf-8", body.toByteArray(Charsets.UTF_8))

    private fun respond(
        out: OutputStream, code: Int, type: String, body: ByteArray, extra: Map<String, String> = emptyMap(),
    ) {
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 410 to "Gone")[code] ?: "OK"
        val headers = StringBuilder()
            .append("HTTP/1.1 $code $reason\r\n")
            .append("Content-Type: $type\r\n")
            .append("Content-Length: ${body.size}\r\n")
            .append("Cache-Control: no-store\r\n")
            .append("X-Content-Type-Options: nosniff\r\n")
            .append("Referrer-Policy: no-referrer\r\n")
            .append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'\r\n")
            .append("Connection: close\r\n")
        extra.forEach { (k, v) -> headers.append("$k: $v\r\n") }
        headers.append("\r\n")
        out.write(headers.toString().toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }
}

internal data class HttpRequest(val method: String, val path: String, val query: Map<String, String>) {
    companion object {
        private const val MAX_HEADER = 8 * 1024

        fun parse(input: InputStream): HttpRequest? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val b = input.read()
                if (b == -1) return null
                if (++total > MAX_HEADER) return null
                if (b == '\n'.code) {
                    val l = line.toString().trimEnd('\r')
                    if (l.isEmpty()) break
                    lines += l
                    line.clear()
                } else line.append(b.toChar())
            }
            val first = lines.firstOrNull()?.split(' ') ?: return null
            if (first.size < 3) return null
            val contentLength = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            if (contentLength in 1..4096) repeat(contentLength) { if (input.read() == -1) return@repeat }
            val target = first[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
            return HttpRequest(first[0].uppercase(), path, query)
        }
    }
}

internal object Pages {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;").replace("\n", "<br>")

    private fun shell(title: String, body: String) = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#0B1938;color:#fff}
.w{max-width:480px;margin:auto;padding:24px}.c{background:#142D5F;border-radius:16px;padding:20px;margin:16px 0}
h1{font-size:22px;margin:0 0 4px}.m{color:#9fb3d9;font-size:14px}.r{padding:8px 0;border-bottom:1px solid #22407a}
.k{color:#7fe7ff;font-size:12px}.warn{background:#3a2a00;color:#ffd66b;border-radius:12px;padding:12px;font-size:14px}
button,a.b{display:block;width:100%;box-sizing:border-box;text-align:center;border:0;border-radius:12px;padding:14px;
font-size:16px;font-weight:600;margin:8px 0;text-decoration:none}.p{background:#00D4FF;color:#0B1938}.s{background:#22407a;color:#fff}
</style></head><body><div class="w"><div class="m">Reduan Smart Info · local Wi-Fi share</div>$body</div></body></html>"""

    fun consent(c: CardContent, base: String): String {
        val rows = c.summary.joinToString("") { (k, v) -> "<div class=r><div class=k>${esc(k)}</div>${esc(v)}</div>" }
        return shell("Contact from ${c.displayName}", """
<div class=c><h1>${esc(c.displayName)}</h1><div class=m>wants to share a contact card with you<br>আপনার সাথে একটি কন্টাক্ট কার্ড শেয়ার করতে চায়</div></div>
<div class=warn>The sender's identity cannot be verified. Only accept if you know who is sharing with you.<br>প্রেরকের পরিচয় যাচাই করা সম্ভব নয়।</div>
<div class=c><div class=k>${c.summary.size} item(s) · 1 file (.vcf)</div>$rows</div>
<form method=post action="$base/accept"><button class=p type=submit>Accept · গ্রহণ করুন</button></form>
<form method=post action="$base/reject"><button class=s type=submit>Reject · প্রত্যাখ্যান</button></form>""")
    }

    fun ready(c: CardContent, href: String) = shell("Download contact", """
<div class=c><h1>${esc(c.displayName)}</h1><div class=m>Tap below to download the contact, then open it to add it to your contacts.<br>
ডাউনলোড করে ফাইলটি খুললে কন্টাক্টে সেভ করতে পারবেন।</div></div>
<a class="b p" href="${esc(href)}">Download contact (.vcf)</a>
<div class=m>This link works once.</div>""")

    fun message(title: String, text: String) = shell(title, "<div class=c><h1>${esc(title)}</h1><div class=m>${esc(text)}</div></div>")
}

/** Finds IPv4 addresses on Wi-Fi / hotspot interfaces the receiver can reach. */
object LocalNetwork {
    data class Candidate(val interfaceName: String, val address: Inet4Address) {
        val label: String
            get() = when {
                interfaceName.startsWith("ap") || interfaceName.startsWith("swlan") || interfaceName.contains("softap") ->
                    "Hotspot ($interfaceName) · ${address.hostAddress}"
                interfaceName.startsWith("wlan") -> "Wi-Fi ($interfaceName) · ${address.hostAddress}"
                else -> "$interfaceName · ${address.hostAddress}"
            }
    }

    fun candidates(): List<Candidate> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { Candidate(nif.name, it) }
            }
            .sortedBy { c ->
                val n = c.interfaceName
                when {
                    n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap") -> 0
                    n.startsWith("wlan") -> 1
                    else -> 2
                }
            }
    } catch (_: Exception) { emptyList() }
}
