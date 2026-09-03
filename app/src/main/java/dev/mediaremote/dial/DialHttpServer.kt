package dev.mediaremote.dial

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
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
            client.soTimeout = 12_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = runCatching { readRequest(input, output) }.getOrElse { error ->
                Log.w(TAG, "Failed to parse DIAL HTTP request", error)
                writeResponse(output, 400, "Bad Request", "text/plain", "Bad request")
                return
            }

            val path = normalizePath(request.path)
            Log.i(
                TAG,
                "${request.method} $path from ${client.inetAddress.hostAddress} " +
                    "length=${request.body.toByteArray(Charsets.UTF_8).size} " +
                    "transfer=${request.headers["transfer-encoding"].orEmpty()}",
            )

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

                request.method == "GET" && path == APPS_PATH -> {
                    writeResponse(output, 204, "No Content", "text/plain", "")
                }

                request.method == "GET" && path == APP_PATH -> {
                    onStatus("YouTube MusicがDIALアプリ情報を確認")
                    writeResponse(
                        output,
                        200,
                        "OK",
                        "application/xml; charset=utf-8",
                        appStatusXml(),
                    )
                }

                request.method == "GET" && isAppInstancePath(path) -> {
                    writeResponse(
                        output,
                        200,
                        "OK",
                        "application/xml; charset=utf-8",
                        appStatusXml(),
                    )
                }

                request.method == "POST" && path == APP_PATH -> {
                    onStatus("YouTube MusicからDIAL起動要求を受信")
                    val form = parseForm(request.body)
                    val pairingCode = form["pairingCode"].orEmpty()
                    val theme = form["theme"].orEmpty()
                    Log.i(TAG, "DIAL launch fields=${form.keys} theme=$theme pairing=${pairingCode.isNotBlank()}")

                    if (pairingCode.isBlank()) {
                        onStatus("DIAL起動要求にpairingCodeがありません")
                        writeResponse(output, 400, "Bad Request", "text/plain", "Missing pairingCode")
                    } else if (theme.isNotBlank() && theme != "m") {
                        // DIAL advertises an app named YouTube; theme=m selects the YouTube Music
                        // Lounge client. This receiver intentionally accepts YouTube Music only.
                        writeResponse(output, 503, "Service Unavailable", "text/plain", "Unsupported theme")
                    } else if (loungeSession.registerPairingCode(pairingCode)) {
                        onStatus("DIAL起動成功 • Lounge接続待ち")
                        // The Lounge receiver is already running before DIAL launch. peer-dial returns
                        // 200 (not 201) in this state and Location points to the stable app instance.
                        writeResponse(
                            output,
                            200,
                            "OK",
                            "text/plain",
                            "OK",
                            extraHeaders = mapOf(
                                "Location" to appInstanceUrl(),
                                "Access-Control-Expose-Headers" to "Location",
                            ),
                        )
                    } else {
                        onStatus("DIAL起動失敗 • Loungeペアリング登録エラー")
                        writeResponse(output, 503, "Service Unavailable", "text/plain", "Pairing failed")
                    }
                }

                request.method == "DELETE" && isAppInstancePath(path) -> {
                    // YouTube's Lounge receiver is intentionally always running while the playback
                    // service is active, matching allowStop=false in the DIAL app descriptor.
                    writeResponse(output, 405, "Method Not Allowed", "text/plain", "Stop not allowed")
                }

                request.method == "OPTIONS" -> {
                    writeResponse(
                        output,
                        204,
                        "No Content",
                        "text/plain",
                        "",
                        extraHeaders = mapOf(
                            "Allow" to "GET, POST, DELETE, OPTIONS",
                            "Access-Control-Allow-Origin" to "*",
                            "Access-Control-Allow-Methods" to "GET, POST, DELETE, OPTIONS",
                            "Access-Control-Allow-Headers" to "Content-Type",
                            "Access-Control-Expose-Headers" to "Location",
                        ),
                    )
                }

                else -> {
                    Log.w(TAG, "Unhandled DIAL request ${request.method} $path")
                    writeResponse(output, 404, "Not Found", "text/plain", "Not found")
                }
            }
        }
    }

    private fun deviceDescriptionXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <URLBase>${xml(baseUrl())}</URLBase>
          <device>
            <deviceType>urn:dial-multiscreen-org:device:dial:1</deviceType>
            <friendlyName>${xml(friendlyName)}</friendlyName>
            <manufacturer>YT Music Remote</manufacturer>
            <modelName>YT Music Remote</modelName>
            <UDN>uuid:$identityUuid</UDN>
            <serviceList>
              <service>
                <serviceType>urn:dial-multiscreen-org:service:dial:1</serviceType>
                <serviceId>urn:dial-multiscreen-org:serviceId:dial</serviceId>
                <controlURL>/dial/notfound</controlURL>
                <eventSubURL>/dial/notfound</eventSubURL>
                <SCPDURL>/dial/notfound</SCPDURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    private fun appStatusXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <service xmlns="urn:dial-multiscreen-org:schemas:dial" dialVer="1.7">
          <name>YouTube</name>
          <options allowStop="false"/>
          <state>running</state>
          <link rel="run" href="${xml(identityUuid)}"/>
        </service>
    """.trimIndent()

    /** DIAL Application-URL is a base collection URL and must not end in '/'. */
    private fun applicationUrl(): String = "${baseUrl()}$APPS_PATH"

    private fun appInstanceUrl(): String = "$applicationUrl()/YouTube/$identityUuid"

    private fun baseUrl(): String = "http://${hostAddress()}:$port"

    private fun isAppInstancePath(path: String): Boolean = path == "$APP_PATH/$identityUuid"

    private fun normalizePath(rawPath: String): String {
        val raw = rawPath.substringBefore('?')
        val segments = raw.split('/').filter { it.isNotBlank() }
        return if (segments.isEmpty()) "/" else segments.joinToString("/", prefix = "/")
    }

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    private fun readRequest(
        input: BufferedInputStream,
        output: BufferedOutputStream,
    ): Request {
        val requestLine = readLine(input) ?: error("Missing request line")
        val parts = requestLine.split(' ')
        require(parts.size >= 2) { "Malformed request line" }

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

        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        val bodyBytes = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true ->
                readChunkedBody(input)
            headers["content-length"] != null -> {
                val length = headers.getValue("content-length").toIntOrNull()
                    ?: error("Invalid Content-Length")
                require(length in 0..MAX_BODY_BYTES) { "Request body too large" }
                readExactly(input, length)
            }
            else -> ByteArray(0)
        }

        return Request(
            method = parts[0].uppercase(),
            path = parts[1],
            headers = headers,
            body = bodyBytes.toString(Charsets.UTF_8),
        )
    }

    private fun readChunkedBody(input: BufferedInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: error("Missing chunk size")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: error("Invalid chunk size")
            if (size == 0) {
                // Consume optional trailer headers.
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            require(output.size() + size <= MAX_BODY_BYTES) { "Request body too large" }
            output.write(readExactly(input, size))
            // Every chunk payload is followed by CRLF.
            val delimiter = readLine(input) ?: error("Missing chunk delimiter")
            require(delimiter.isEmpty()) { "Malformed chunk delimiter" }
        }
        return output.toByteArray()
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count < 0) error("Unexpected end of request body")
            read += count
        }
        return bytes
    }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < MAX_LINE_BYTES) {
            val value = input.read()
            if (value < 0) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(Charsets.US_ASCII)
            }
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
        private const val APPS_PATH = "/apps"
        private const val APP_PATH = "/apps/YouTube"
        private const val MAX_BODY_BYTES = 64 * 1024
        private const val MAX_LINE_BYTES = 16 * 1024
    }
}
