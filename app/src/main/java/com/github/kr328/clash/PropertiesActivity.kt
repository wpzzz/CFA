package com.github.kr328.clash

import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.uuid
import com.github.kr328.clash.design.PropertiesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.design.util.showExceptionToast
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class PropertiesActivity : BaseActivity<PropertiesDesign>() {
    private var canceled: Boolean = false

    override suspend fun main() {
        setResult(RESULT_CANCELED)

        val uuid = intent.uuid ?: return finish()
        val design = PropertiesDesign(this)

        val original = withProfile { queryByUUID(uuid) } ?: return finish()

        design.profile = original

        setContentDesign(design)

        defer {
            canceled = true
            withProfile { release(uuid) }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStop -> {
                            val profile = design.profile

                            if (!canceled && profile != original) {
                                withProfile {
                                    patch(profile.uuid, profile.name, profile.source, profile.interval)
                                }
                            }
                        }
                        Event.ServiceRecreated -> finish()
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        PropertiesDesign.Request.BrowseFiles -> {
                            startActivity(FilesActivity::class.intent.setUUID(uuid))
                        }
                        PropertiesDesign.Request.Commit -> {
                            design.verifyAndCommit()
                        }
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        design?.apply {
            launch {
                if (!progressing) {
                    if (requestExitWithoutSaving())
                        finish()
                }
            }
        } ?: return super.onBackPressed()
    }

    private suspend fun PropertiesDesign.verifyAndCommit() {
        when {
            profile.name.isBlank() -> {
                showToast(R.string.empty_name, ToastDuration.Long)
            }
            profile.type != Profile.Type.File && profile.source.isBlank() -> {
                showToast(R.string.invalid_url, ToastDuration.Long)
            }
            else -> {
                try {
                    withProcessing { updateStatus ->
                        val fetchedName: String? =
                            if (profile.type == Profile.Type.Url && profile.source.isNotBlank()) {
                                withContext(Dispatchers.IO) {
                                    fetchNameFromUrl(profile.source)
                                }
                            } else null

                        val finalName = fetchedName ?: profile.name

                        withProfile {
                            patch(profile.uuid, finalName, profile.source, profile.interval)

                            coroutineScope {
                                commit(profile.uuid) {
                                    launch { updateStatus(it) }
                                }
                            }
                        }
                    }

                    setResult(RESULT_OK)
                    finish()
                } catch (e: Exception) {
                    showExceptionToast(e)
                }
            }
        }
    }

    private fun parseFilenameStar(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null

        val key = "filename*="
        val idx = contentDisposition.indexOf(key, ignoreCase = true)
        if (idx < 0) return null

        var v = contentDisposition.substring(idx + key.length).trim()

        val semi = v.indexOf(';')
        if (semi >= 0) v = v.substring(0, semi).trim()

        v = v.trim('"')

        val encoded = v.substringAfter("''", v)
        return URLDecoder.decode(encoded, "UTF-8").takeIf { it.isNotBlank() }
    }

    private fun open(url: String, method: String): HttpURLConnection {
        val c = (URL(url).openConnection() as HttpURLConnection)
        c.instanceFollowRedirects = true
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 8000

        // 关键：Cloudflare/某些站点对 Java 默认 UA 可能给挑战页，导致拿不到你想要的 header
        c.setRequestProperty("User-Agent", "ClashForAndroid/${BuildConfig.VERSION_NAME}")

        // 关键：避免服务端返回 zstd/gzip 之类导致读取 body 时异常（我们只要 header）
        c.setRequestProperty("Accept-Encoding", "identity")

        // 可选但无害：更像普通客户端
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    private fun fetchNameFromUrl(url: String): String? {
        // 先 HEAD
        runCatching {
            val c = open(url, "HEAD")
            c.connect()
            val cd = c.getHeaderField("Content-Disposition")
            c.disconnect()
            parseFilenameStar(cd)?.let { return it }
        }

        // 再 GET（只为拿 header；body 不读内容，直接关）
        runCatching {
            val c = open(url, "GET")
            c.connect()
            val cd = c.getHeaderField("Content-Disposition")
            c.inputStream.close()
            c.disconnect()
            return parseFilenameStar(cd)
        }

        return null
    }
}
