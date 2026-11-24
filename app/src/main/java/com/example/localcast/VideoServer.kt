package com.example.localcast

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.net.Uri
import fi.iki.elonen.NanoHTTPD
import java.io.FileInputStream
import java.io.InputStream

/** Serves the selected local video over HTTP (with Range support) so the Chromecast can fetch it. */
class VideoServer(private val resolver: ContentResolver, port: Int) : NanoHTTPD(port) {

    @Volatile var uri: Uri? = null
    @Volatile var mime: String = "video/mp4"

    override fun serve(session: IHTTPSession): Response {
        val source = uri ?: return notFound()
        val afd = try {
            resolver.openAssetFileDescriptor(source, "r")
        } catch (e: Exception) {
            null
        } ?: return notFound()

        val total = if (afd.length >= 0) afd.length else afd.parcelFileDescriptor.statSize
        var start = 0L
        var end = total - 1
        val rangeHeader = session.headers["range"]
        val isRange = rangeHeader != null && rangeHeader.startsWith("bytes=")

        if (isRange) {
            val parts = rangeHeader!!.removePrefix("bytes=").substringBefore(",").split("-", limit = 2)
            val first = parts[0].trim().toLongOrNull()
            val second = parts.getOrNull(1)?.trim()?.toLongOrNull()
            if (first != null) {
                start = first
                if (second != null) end = minOf(second, total - 1)
            } else if (second != null) {
                start = maxOf(0L, total - second)
            }
        }

        if (start >= total || start > end) {
            afd.close()
            return newFixedLengthResponse(
                Response.Status.REQUESTED_RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, ""
            ).apply { addHeader("Content-Range", "bytes */$total") }
        }

        val length = end - start + 1
        val status = if (isRange) Response.Status.PARTIAL_CONTENT else Response.Status.OK
        return newFixedLengthResponse(status, mime, RangeStream(afd, start, length), length).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Access-Control-Allow-Origin", "*")
            if (isRange) addHeader("Content-Range", "bytes $start-$end/$total")
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "No video selected")

    private class RangeStream(
        private val afd: AssetFileDescriptor,
        start: Long,
        private var remaining: Long,
    ) : InputStream() {
        private val input = FileInputStream(afd.fileDescriptor).also {
            it.channel.position(afd.startOffset + start)
        }

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = input.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        override fun close() {
            input.close()
            afd.close()
        }
    }
}
