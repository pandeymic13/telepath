package dev.plume

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The server answered [status] instead of 200 (404: there is nothing at that address). */
internal class HttpStatusException(val status: Int) : IOException("Server returned HTTP $status.")

/**
 * A small GET for the widgets and the Up next line: HTTPS redirects only within the same host, 10 s timeouts and a
 * size cap, so a misbehaving server can't stall or flood the launcher. Call off the main thread.
 */
internal fun download(address: String, limit: Int = 4 * 1024 * 1024): String {
    var target = URL(address)
    val originalHost = target.host
    repeat(4) {
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "telepath/1.0")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val next = URL(target, connection.getHeaderField("Location")
                    ?: throw IOException("Redirect address missing."))
                if (next.protocol != "https" || !next.host.equals(originalHost, true) ||
                    next.userInfo != null || next.port !in listOf(-1, 443)) {
                    throw IOException("Unsafe redirect rejected.")
                }
                target = next
            } else {
                if (status != 200) throw HttpStatusException(status)
                val bytes = connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > limit) throw IOException("Response exceeded the size limit.")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                return String(bytes, Charsets.UTF_8)
            }
        } finally {
            connection.disconnect()
        }
    }
    throw IOException("Too many redirects.")
}
