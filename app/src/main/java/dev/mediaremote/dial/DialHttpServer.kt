package dev.mediaremote.dial

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class DialHttpServer(
    private val loungeSession: YouTubeLoungeSession,
    private val identityUuid: String,
    private val friendlyName: String,
    private val hostAddress: () -> String,
    private val onStatus: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val clientPool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val port: Int get() = serverSocket?.localPort ?: 0

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val server = runCatching { ServerSocket(0) }.getOrElse {
            running.set(false)
            Log.e(TAG, "Unable to bind DIAL HTTP server", it)
            return false
        }
        server.reuseAddress = true
        serverSocket = server
        acceptThread = Thread {
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                clientPool.execute { handle(socket) }
            }
        }.apply {
            name = "YTMusicRemote-DIAL-HTTP"
            start()
        }
        Log.i(TAG, "DIAL HTTP listening on ${server.localPort}")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        clientPool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = runCatching { readRequest(input) }.getOrElse {
                writeResponse(output, 400, "Bad Request", "text/plain", "Bad request")
                return
            }

            val path = request.path.substringBefore('?')
            Log.d(TAG, "${request.method} $path from ${client.inetAddress.hostAddress}")
            when {
                request.method == "GET" && path == DEVICE_DESCRIPTION_PATH -> {
                    writeResponse(
                        output,
                        200,
                        "OK",
                        "application/xml; charset=utf-8",
                        deviceDescriptionXml(),
                        extraHeaders = mapOf("Application-URL" to applicationUrl()),
                    )
                }

                request.method == "GET" && normalizeAppPath(path) == APP_PATH -> {
                    writeResponse(
                        output,
                        200,
                        "OK",
                        "application/xml; charset=utf-8",
                        appStatusXml(),
                    )
                }

                request.method == "POST" && normalizeAppPath(path) == APP_PATH -> {
                    val form = parseForm(request.body)
                    val pairingCode = form["pairingCode"].orEmpty()
                    val theme = form["theme"].orEmpty()
                    if (pairingCode.isBlank()) {
                        writeResponse(output, 400, "Bad Request", "text/plain", "Missing pairingCode")
                    } else if (theme.isNotBlank() && theme != "m") {
                        // This receiver is intentionally YouTube Music specific. Advertising a
                        // YouTube app is required by DIAL, while theme=m selects the YT Music Lounge.
                        writeResponse(output, 503, "Service Unavailable", "text/plain", "Unsupported theme")
                    } else if (loungeSession.registerPairingCode(pairingCode)) {
                        onStatus("YouTube Music Cast接続を受付")
                        writeResponse(
                            output,
                            201,
                            "Created",
                            "text/plain",
                            "",
                            extraHeaders = mapOf("Location" to "${applicationUrl()}YouTube/run"),
                        )
                    } else {
                        writeResponse(output, 503, "Service Unavailable", "text/plain", "Pairing failed")
                    }
                }

                request.method == "GET" && path.trimEnd('/') == "$APP_PATH/run" -> {
                    writeResponse(output, 200, "OK", "text/plain", DialIdentityStore.pidForHttp(identityUuid))
                }

                request.method == "DELETE" && path.trimEnd('/') == "$APP_PATH/run" -> {
                    // allowStop=false in the app descriptor. Keep the Lounge session alive so a
                    // subsequent Cast connection does not need to rebuild screen/token state.
                    writeResponse(output, 403, "Forbidden", "text/plain", "Stop not allowed")
                }

                request.method == "OPTIONS" -> {
                    writeResponse(
                        output,
                        204,
                        "No Content",
                        "text/plain",
                        "",
                        extraHeaders = mapOf(
                            "Allow" to "GET, POST, OPTIONS",
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                            "Access-Control-Allow-Headers" to "Content-Type",
                        ),
                    )
                }

                else -> writeResponse(output, 404, "Not Found", "text/plain", "Not found")
            }
        }
    }

    private fun deviceDescriptionXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:dial-multiscreen-org:device:dialreceiver:1</deviceType>
            <friendlyName>${xml(friendlyName)}</friendlyName>
            <manufacturer>YT Music Remote</manufacturer>
            <modelName>YT Music Remote</modelName>
            <UDN>uuid:$identityUuid</UDN>
          </device>
        </root>
    """.trimIndent()

    private fun appStatusXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <service xmlns="urn:dial-multiscreen-org:schemas:dial">
          <name>YouTube</name>
          <options allowStop="false"/>
          <state>running</state>
          <link rel="run" href="run"/>
        </service>
    """.trimIndent()

    private fun applicationUrl(): String = "http://${hostAddress()}:$port/apps/"

    private fun normalizeAppPath(path: String): String = path.trimEnd('/').ifBlank { "/" }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(input: BufferedInputStream): Request {
        val requestLine = readLine(input) ?: error("Missing request line")
        val parts = requestLine.split(' ')
        require(parts.size >= 2)
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase()] =
                    line.substring(separator + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) break
            read += count
        }
        return Request(
            method = parts[0].uppercase(),
            path = parts[1],
            headers = headers,
            body = bytes.copyOf(read).toString(Charsets.UTF_8),
        )
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            extraHeaders.forEach { (key, value) -> append("$key: $value\r\n") }
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun parseForm(raw: String): Map<String, String> = raw.split('&')
        .mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val separator = pair.indexOf('=')
            val key = if (separator >= 0) pair.substring(0, separator) else pair
            val value = if (separator >= 0) pair.substring(separator + 1) else ""
            decodeForm(key) to decodeForm(value)
        }
        .toMap()

    private fun decodeForm(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val TAG = "DialHttpServer"
        private const val DEVICE_DESCRIPTION_PATH = "/dd.xml"
        private const val APP_PATH = "/apps/YouTube"
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 16 * 1024
    }
}

private fun DialIdentityStore.pidForHttp(identityUuid: String): String = identityUuid
