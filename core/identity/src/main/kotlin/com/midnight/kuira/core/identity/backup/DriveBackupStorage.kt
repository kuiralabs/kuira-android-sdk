package com.midnight.kuira.core.identity.backup

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * [BackupStorage] over Google Drive's `appDataFolder` — a hidden, per-account
 * folder that syncs across the user's devices and is invisible in the Drive UI.
 * Right for the large (~500 KB) encrypted dust-state blob that exceeds Block
 * Store's 4 KB/entry cap.
 *
 * The blob is already AES-256-GCM encrypted before it reaches here (Drive is
 * pure transport — Google sees only ciphertext). REST calls go over plain
 * [HttpURLConnection] with a Bearer token, mirroring [ProvingKeyManager]'s
 * download path, so no heavy `google-api-services-drive` client (and its
 * transitive httpclient/guava conflicts) is pulled in.
 *
 * @param fileName the single file we keep in appDataFolder (overwritten on each store)
 * @param tokenProvider yields a valid `drive.appdata` access token; called again
 *   (with forceRefresh=true) once on a 401 so an expired token is transparently renewed.
 */
class DriveBackupStorage(
    private val fileName: String,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String,
) : BackupStorage {

    override suspend fun store(encryptedBlob: ByteArray): Unit = withContext(Dispatchers.IO) {
        withFreshTokenOn401 { token ->
            val id = findFileId(token) ?: createFile(token)
            uploadMedia(token, id, encryptedBlob)
        }
    }

    override suspend fun retrieve(): ByteArray? = withContext(Dispatchers.IO) {
        withFreshTokenOn401 { token ->
            val id = findFileId(token) ?: return@withFreshTokenOn401 null
            downloadMedia(token, id)
        }
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        withFreshTokenOn401 { token ->
            val id = findFileId(token) ?: return@withFreshTokenOn401
            httpDelete(token, "$DRIVE_V3/files/$id")
        }
    }

    /** True iff we can obtain a token (consent already granted). Never triggers UI. */
    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { tokenProvider(false) }.isSuccess
    }

    // ── REST helpers ────────────────────────────────────────────────────────

    /** Run [block] with a token; on HTTP 401 refresh the token once and retry. */
    private suspend fun <T> withFreshTokenOn401(block: (token: String) -> T): T {
        val token = tokenProvider(false)
        return try {
            block(token)
        } catch (_: UnauthorizedException) {
            Log.i(TAG, "Drive token rejected (401) — refreshing + retrying once")
            block(tokenProvider(true))
        }
    }

    /** Find the backup file's id in appDataFolder by name, or null if absent. */
    private fun findFileId(token: String): String? {
        val q = URLEncoder.encode("name = '$fileName'", "UTF-8")
        val url = "$DRIVE_V3/files?spaces=appDataFolder&q=$q&fields=files(id,name)"
        val body = httpGet(token, url) ?: return null
        val files = JSONObject(body).optJSONArray("files") ?: return null
        if (files.length() == 0) return null
        return files.getJSONObject(0).getString("id")
    }

    /** Create an empty file parented in appDataFolder; returns its id. */
    private fun createFile(token: String): String {
        val metadata = JSONObject()
            .put("name", fileName)
            .put("parents", JSONArray().put("appDataFolder"))
            .toString()
        val (code, resp) = request(
            method = "POST",
            urlString = "$DRIVE_V3/files?fields=id",
            token = token,
            contentType = "application/json; charset=UTF-8",
            body = metadata.toByteArray(Charsets.UTF_8),
        )
        if (!code.isHttpSuccess()) fail("createFile", code, resp)
        return JSONObject(String(resp, Charsets.UTF_8)).getString("id")
    }

    private fun uploadMedia(token: String, fileId: String, bytes: ByteArray) {
        val (code, resp) = request(
            method = "PATCH",
            urlString = "$DRIVE_UPLOAD_V3/files/$fileId?uploadType=media",
            token = token,
            contentType = "application/octet-stream",
            body = bytes,
        )
        if (!code.isHttpSuccess()) fail("uploadMedia", code, resp)
    }

    private fun downloadMedia(token: String, fileId: String): ByteArray {
        val (code, resp) = request(
            method = "GET",
            urlString = "$DRIVE_V3/files/$fileId?alt=media",
            token = token,
        )
        if (!code.isHttpSuccess()) fail("downloadMedia", code, resp)
        return resp
    }

    private fun httpGet(token: String, urlString: String): String? {
        val (code, resp) = request("GET", urlString, token)
        if (code == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (!code.isHttpSuccess()) fail("get", code, resp)
        return String(resp, Charsets.UTF_8)
    }

    private fun httpDelete(token: String, urlString: String) {
        val (code, resp) = request("DELETE", urlString, token)
        // 404 = already gone; treat as success (delete is idempotent).
        if (code != HttpURLConnection.HTTP_NOT_FOUND && !code.isHttpSuccess()) fail("delete", code, resp)
    }

    private fun Int.isHttpSuccess(): Boolean = this in 200..299

    /**
     * Single REST round-trip. Returns (status, body bytes). Throws
     * [UnauthorizedException] on 401 so the caller can refresh + retry.
     */
    private fun request(
        method: String,
        urlString: String,
        token: String,
        contentType: String? = null,
        body: ByteArray? = null,
    ): Pair<Int, ByteArray> {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw UnauthorizedException()
            val stream = if (code.isHttpSuccess()) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(op: String, code: Int, resp: ByteArray): Nothing {
        val snippet = String(resp, Charsets.UTF_8).take(200)
        throw IOException("Drive $op failed: HTTP $code $snippet")
    }

    private class UnauthorizedException : IOException("Drive returned 401")

    companion object {
        private const val TAG = "DriveBackupStorage"
        private const val DRIVE_V3 = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_V3 = "https://www.googleapis.com/upload/drive/v3"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}
