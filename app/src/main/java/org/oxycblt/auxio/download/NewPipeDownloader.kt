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
            val responseBodyString = response.body?.string()
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

    companion object {
        private val instance = NewPipeDownloader()

        fun getInstance(): NewPipeDownloader = instance
    }
}
