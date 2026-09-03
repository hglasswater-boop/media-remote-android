package dev.mediaremote.dial

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

internal object LoungeHttp {
    data class Response(val code: Int, val body: String)

    fun get(url: String, readTimeoutMs: Int = 10_000): Response = request(
        method = "GET",
        url = url,
        form = null,
        readTimeoutMs = readTimeoutMs,
    )

    fun postForm(
        url: String,
        form: Map<String, String>,
        readTimeoutMs: Int = 10_000,
    ): Response = request(
        method = "POST",
        url = url,
        form = form,
        readTimeoutMs = readTimeoutMs,
    )

    fun openLongPoll(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 65_000
            useCaches = false
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", USER_AGENT)
            connect()
            if (responseCode !in 200..299) {
                val error = runCatching { errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                disconnect()
                error("Lounge RPC HTTP $responseCode ${error.orEmpty()}")
            }
        }
    }

    private fun request(
        method: String,
        url: String,
        form: Map<String, String>?,
        readTimeoutMs: Int,
    ): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            if (form != null) {
                val body = LoungeBindParams.encode(form).toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val code = connection.responseCode
            val source = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = source?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
            }.orEmpty()
            return Response(code, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private const val USER_AGENT = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/537.36 TV Safari/537.36"
}
