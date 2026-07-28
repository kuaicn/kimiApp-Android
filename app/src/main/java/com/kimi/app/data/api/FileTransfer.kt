package com.kimi.app.data.api

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kimi.app.data.wire.WireFileMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 二进制端点（Retrofit 之外的裸 OkHttp 实现）：文件上传、会话导出、文件下载 */
class FileTransfer(
    private val okHttp: OkHttpClient,
    private val profile: ServerProfile,
) {

    /** POST /files — multipart 上传，返回文件元信息 */
    suspend fun uploadFile(bytes: ByteArray, name: String, mediaType: String): WireFileMeta =
        withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", name, bytes.toRequestBody(mediaType.toMediaType()))
                .addFormDataPart("name", name)
                .build()
            val request = Request.Builder()
                .url("${profile.apiBaseUrl}files")
                .post(body)
                .build()
            okHttp.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiException(response.code, "上传失败 (${response.code})")
                val envelope = wireJson.parseToJsonElement(text) as JsonObject
                val code = envelope["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                if (code != 0) throw ApiException(code, envelope["msg"]?.jsonPrimitive?.content ?: "上传失败")
                wireJson.decodeFromJsonElement(
                    WireFileMeta.serializer(),
                    envelope["data"] ?: throw ApiException(-1, "上传响应为空"),
                )
            }
        }

    /** POST /sessions/{id}/export — 导出会话 zip 到下载目录，返回展示名 */
    suspend fun exportSessionToDownloads(context: Context, sessionId: String, title: String): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${profile.apiBaseUrl}sessions/$sessionId/export")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            val client = okHttp.newBuilder()
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "导出失败 (${response.code})")
                val bytes = response.body?.bytes() ?: throw ApiException(-1, "导出响应为空")
                val safeTitle = title.replace(Regex("""[\\/:*?"<>|]"""), "_").take(40).ifBlank { "session" }
                val fileName = "kimi-${safeTitle}-${sessionId.takeLast(6)}.zip"
                saveToDownloads(context, fileName, "application/zip", bytes)
                fileName
            }
        }

    private fun saveToDownloads(context: Context, name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw ApiException(-1, "无法写入下载目录")
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw ApiException(-1, "无法写入下载目录")
    }
}
