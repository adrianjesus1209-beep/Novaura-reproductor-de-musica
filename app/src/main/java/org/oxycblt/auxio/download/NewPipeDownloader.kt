/*
 * Copyright (c) 2026 Auxio Project
 * NewPipeDownloader.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.download

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse

class NewPipeDownloader(
    val client: OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
) : Downloader() {

    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataBody = request.dataToSend()

        val okHttpBuilder = Request.Builder().url(url)

        for ((key, valueList) in headers) {
            for (value in valueList) {
                okHttpBuilder.addHeader(key, value)
            }
        }

        val body = dataBody?.toRequestBody(null)
        when (httpMethod.uppercase()) {
            "GET" -> okHttpBuilder.get()
            "POST" -> okHttpBuilder.post(body ?: "".toRequestBody(null))
            "HEAD" -> okHttpBuilder.head()
            else -> okHttpBuilder.method(httpMethod, body)
        }

        return client.newCall(okHttpBuilder.build()).execute().use { response ->
            val responseBodyString = readBoundedBody(response.body)
            val responseCode = response.code
            val responseMessage = response.message
            val responseHeaders = response.headers.toMultimap()
            val latestUrl = response.request.url.toString()

            ExtractorResponse(
                responseCode,
                responseMessage,
                responseHeaders,
                responseBodyString,
                latestUrl,
            )
        }
    }

    private fun readBoundedBody(body: okhttp3.ResponseBody?): String {
        if (body == null) return ""
        val contentLength = body.contentLength()
        if (contentLength > MAX_RESPONSE_BYTES) {
            throw IOException("Extractor response too large: $contentLength bytes")
        }
        val source = body.source()
        source.request(MAX_RESPONSE_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BYTES) {
            throw IOException("Extractor response exceeded max size of $MAX_RESPONSE_BYTES bytes")
        }
        return body.string()
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L
        private val instance = NewPipeDownloader()

        fun getInstance(): NewPipeDownloader = instance
    }
}
