package id.ciganstore.qrislistener.upload

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Posts listener requests with [HttpURLConnection]. Every [post] call asks [requests] for a newly
 * signed request right before connecting, so retries never reuse a timestamp, nonce or signature.
 */
class HttpListenerTransport(
    private val requests: ListenerRequestFactory,
    private val allowCleartext: Boolean,
    private val timeoutMillis: Int = TIMEOUT_MILLIS,
) : ListenerTransport {

    override fun post(path: String, body: ByteArray): HttpResult {
        val request = requests.create(path, body)
        val connection = URI.create(request.url).toURL().openConnection() as? HttpURLConnection
            ?: throw IOException("Unsupported listener URL")
        try {
            // Settings validation and the network security config already refuse plain HTTP outside
            // debug builds; this is a last check before any payment data leaves the phone.
            if (connection !is HttpsURLConnection && !allowCleartext) throw IOException("HTTPS is required")

            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            // A redirect means the URL is wrong; never re-send signed payment data somewhere else.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(request.body.size)
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }

            connection.outputStream.use { output -> output.write(request.body) }
            val status = connection.responseCode
            if (status < 100) throw IOException("Invalid HTTP response")
            val stream: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResult(status, stream?.use { input -> readLimited(input) })
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0
        while (total < MAX_RESPONSE_BYTES) {
            val read = stream.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - total))
            if (read < 0) break
            output.write(buffer, 0, read)
            total += read
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000
        const val BUFFER_BYTES = 1_024
        const val MAX_RESPONSE_BYTES = 16 * 1_024
    }
}
