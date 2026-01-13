package com.ai.phoneagent.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import retrofit2.HttpException

object ReleaseUiUtil {
    private val GITHUB_MIRRORS = linkedMapOf(
        "官方" to "",
        "Ghfast" to "https://ghfast.top/",
        "GhProxy" to "https://ghproxy.com/",
        "GhProxyNet" to "https://ghproxy.net/",
        "GhProxyMirror" to "https://mirror.ghproxy.com/",
        "Flash" to "https://flash.aaswordsman.org/",
        "Gh-Proxy" to "https://gh-proxy.com/",
        "GitMirror" to "https://hub.gitmirror.com/",
        "Moeyy" to "https://github.moeyy.xyz/",
        "Workers" to "https://github.abskoop.workers.dev/",
    )

    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse(url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun mirroredDownloadOptions(originalUrl: String?): List<Pair<String, String>> {
        if (originalUrl.isNullOrBlank()) return emptyList()
        val url = originalUrl.trim()
        val isReleaseAsset =
            url.contains("github.com") &&
                (url.contains("/releases/download/") || url.contains("/releases/latest/download/"))
        if (!isReleaseAsset) return listOf("官方" to url)

        val stableUrl =
            "https://github.com/${UpdateConfig.REPO_OWNER}/${UpdateConfig.REPO_NAME}/releases/latest/download/${UpdateConfig.APK_ASSET_NAME}"

        return GITHUB_MIRRORS.mapNotNull { (name, prefix) ->
            if (name == "官方") name to stableUrl else name to (prefix + stableUrl)
        }
    }

    data class ProbeResult(
        val ok: Boolean,
        val latencyMs: Long?,
        val error: String? = null,
    )

    suspend fun mirroredDownloadOptionsChecked(
        originalUrl: String?,
        timeoutMs: Int = 2500,
    ): List<Pair<String, String>> {
        val options = mirroredDownloadOptions(originalUrl)
        if (options.size <= 1) return options

        val probe = probeMirrorUrls(options.associate { it.first to it.second }, timeoutMs)
        val ok =
            options
                .mapNotNull { (name, u) ->
                    val r = probe[name]
                    if (r?.ok == true) name to u else null
                }
                .sortedWith(
                    compareBy<Pair<String, String>> {
                        probe[it.first]?.latencyMs ?: Long.MAX_VALUE
                    }
                )

        return if (ok.isNotEmpty()) ok else options
    }

    private suspend fun probeMirrorUrls(
        urls: Map<String, String>,
        timeoutMs: Int,
    ): Map<String, ProbeResult> {
        return withContext(Dispatchers.IO) {
            coroutineScope {
                urls.entries
                    .map { (name, u) ->
                        async { name to probeOneUrl(u, timeoutMs) }
                    }
                    .awaitAll()
                    .toMap()
            }
        }
    }

    private fun probeOneUrl(url: String, timeoutMs: Int): ProbeResult {
        val startNs = System.nanoTime()
        return try {
            val code =
                requestOnce(url, timeoutMs, method = "HEAD")
                    ?: requestOnce(url, timeoutMs, method = "GET")
                    ?: -1
            val costMs = (System.nanoTime() - startNs) / 1_000_000
            val ok = code in 200..399
            ProbeResult(ok = ok, latencyMs = costMs, error = if (ok) null else "HTTP $code")
        } catch (e: Exception) {
            ProbeResult(ok = false, latencyMs = null, error = e.javaClass.simpleName)
        }
    }

    private fun requestOnce(url: String, timeoutMs: Int, method: String): Int? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.requestMethod = method
            conn.setRequestProperty("User-Agent", "PhoneAgent")
            if (method == "GET") {
                conn.setRequestProperty("Range", "bytes=0-0")
            }
            conn.connect()
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_BAD_METHOD || code == 501) null else code
        } catch (_: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun formatError(t: Throwable): String {
        val http = t as? HttpException
        if (http != null) {
            val code = http.code()
            return when (code) {
                401, 403 -> "访问 GitHub 失败($code)：私有仓库需要 github.token（至少 repo 权限），或触发了 API 限流。"
                404 -> "仓库或 Release 不存在(404)。"
                else -> "网络错误：HTTP $code"
            }
        }

        val msg = t.message?.trim().orEmpty()
        val m = Regex("HTTP\\s+(\\d{3})").find(msg)
        if (m != null) {
            val code = m.groupValues.getOrNull(1)?.toIntOrNull()
            if (code != null) {
                return when (code) {
                    401, 403 -> "访问 GitHub 失败($code)：可能触发了 API 限流，建议配置 github.token。"
                    404 -> "仓库或 Release 不存在(404)。"
                    else -> "网络错误：HTTP $code"
                }
            }
        }
        return if (msg.isNotBlank()) msg else t.javaClass.simpleName
    }
}
