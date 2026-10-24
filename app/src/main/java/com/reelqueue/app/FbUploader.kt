package com.reelqueue.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Facebook Graph API version. If Facebook retires it, change this one line (e.g. "v26.0"). */
const val GRAPH_VERSION = "v25.0"

class FbException(message: String, val fatal: Boolean = false) : Exception(message)

/**
 * Uploads one video to a Page using the chunked (resumable) Graph API upload
 * and schedules it with published=false + scheduled_publish_time.
 */
class FbUploader(
    private val ctx: Context,
    pageId: String,
    private val token: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val endpoint = "https://graph-video.facebook.com/$GRAPH_VERSION/$pageId/videos"

    fun upload(
        item: Item,
        publishAtSec: Long,
        shouldStop: () -> Boolean,
        onProgress: (Int) -> Unit
    ): String {
        val uri = Uri.parse(item.videoUri)
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r")
            ?: throw FbException("ভিডিও ফাইল খোলা যায়নি")

        pfd.use {
            val size = fileSize(uri, pfd.statSize)
            if (size <= 0) throw FbException("ভিডিওর সাইজ পাওয়া যায়নি")
            val channel = FileInputStream(pfd.fileDescriptor).channel

            // 1) start
            val start = post(
                FormBody.Builder()
                    .add("access_token", token)
                    .add("upload_phase", "start")
                    .add("file_size", size.toString())
                    .build()
            )
            val sessionId = start.getString("upload_session_id")
            val videoId = start.optString("video_id")
            var startOff = start.getString("start_offset").toLong()
            var endOff = start.getString("end_offset").toLong()

            // 2) transfer chunks
            while (startOff < endOff) {
                if (shouldStop()) throw FbException("ব্যবহারকারী বন্ধ করেছেন")
                val len = (endOff - startOff).toInt()
                val buf = ByteArray(len)
                channel.position(startOff)
                val bb = ByteBuffer.wrap(buf)
                while (bb.hasRemaining()) {
                    if (channel.read(bb) < 0) break
                }
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("access_token", token)
                    .addFormDataPart("upload_phase", "transfer")
                    .addFormDataPart("upload_session_id", sessionId)
                    .addFormDataPart("start_offset", startOff.toString())
                    .addFormDataPart(
                        "video_file_chunk", "chunk",
                        buf.toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                val r = post(body)
                startOff = r.getString("start_offset").toLong()
                endOff = r.getString("end_offset").toLong()
                onProgress(((startOff * 100) / size).toInt().coerceIn(0, 99))
            }

            // 3) finish + schedule
            val fin = post(
                FormBody.Builder()
                    .add("access_token", token)
                    .add("upload_phase", "finish")
                    .add("upload_session_id", sessionId)
                    .add("title", item.title)
                    .add("description", item.description)
                    .add("published", "false")
                    .add("scheduled_publish_time", publishAtSec.toString())
                    .build()
            )
            if (fin.has("success") && !fin.optBoolean("success", true)) {
                throw FbException("ফেসবুক ভিডিও গ্রহণ করেনি")
            }
            return videoId
        }
    }

    private fun fileSize(uri: Uri, statSize: Long): Long {
        if (statSize > 0) return statSize
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return -1
    }

    private fun post(body: RequestBody): JSONObject {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                val req = Request.Builder().url(endpoint).post(body).build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                    if (resp.isSuccessful && !json.has("error")) return json

                    val err = json.optJSONObject("error")
                    val msg = err?.optString("message")?.ifBlank { null } ?: "HTTP ${resp.code}"
                    val code = err?.optInt("code") ?: 0
                    if (code == 190 || code == 10 || code in 200..299) throw FbException(msg, fatal = true)
                    if (resp.code < 500) throw FbException(msg)
                    last = FbException(msg)
                }
            } catch (e: IOException) {
                last = e
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        throw last ?: FbException("অজানা সমস্যা")
    }
}
