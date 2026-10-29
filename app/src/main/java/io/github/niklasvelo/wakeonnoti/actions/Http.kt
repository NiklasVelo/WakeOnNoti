package io.github.niklasvelo.wakeonnoti.actions

import io.github.niklasvelo.wakeonnoti.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Blocking HTTP on HttpURLConnection - callers are already on worker threads. */
object Http {
    class Response(val code: Int, val body: String)

    private val userAgent = "WakeOnNoti/${BuildConfig.VERSION_NAME}"

    fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 15_000,
    ): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val text = (if (code >= 400) c.errorStream else c.inputStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(code, text)
        } finally {
            c.disconnect()
        }
    }

    /** Streams [url] into [dest]; [onChunk] sees every chunk (for hashing) and the running total. */
    fun download(url: String, dest: File, onChunk: (ByteArray, Int, Long, Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            val total = c.contentLengthLong
            var done = 0L
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onChunk(buf, n, done, total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }
}
