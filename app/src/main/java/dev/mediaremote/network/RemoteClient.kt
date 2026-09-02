package dev.mediaremote.network

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

object RemoteClient {
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun send(
        host: String,
        token: String,
        command: String,
        value: Long = 0L,
        port: Int = RemoteServerService.PORT,
        callback: (RemoteResponse) -> Unit,
    ) {
        executor.execute {
            val response = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host.trim(), port), 3_000)
                    socket.soTimeout = 3_000

                    val writer = PrintWriter(socket.getOutputStream(), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                    writer.println(RemoteRequest(token, command, value).toJson())
                    val line = reader.readLine() ?: error("No response from host")
                    RemoteResponse.fromJson(line)
                }
            }.getOrElse {
                RemoteResponse(ok = false, message = it.message ?: "Connection failed")
            }

            mainHandler.post { callback(response) }
        }
    }
}
