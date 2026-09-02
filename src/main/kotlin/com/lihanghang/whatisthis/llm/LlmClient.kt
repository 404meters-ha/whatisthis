package com.lihanghang.whatisthis.llm

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Error returned for non-200 responses, with a human-friendly Chinese summary. */
class ApiException(val code: Int, val detail: String) : RuntimeException("HTTP $code: $detail") {
    fun friendlyMessage(): String = when (code) {
        401 -> "API Key 无效或未授权"
        403 -> "无权限访问该模型（检查 Key 权限 / 模型是否开通）"
        404 -> "模型或接口地址不存在（检查模型名与 base_url）"
        429 -> "请求过于频繁或余额不足"
        in 500..599 -> "服务商服务端错误，请稍后重试"
        else -> "请求失败（HTTP $code）"
    } + " · " + detail.take(160)
}

/**
 * Handle to a streaming request. [cancel] closes the socket immediately,
 * which is what keeps "瞬间" true even on a slow provider.
 */
class StreamHandle internal constructor(
    private val future: Future<*>,
    private val cancelled: AtomicBoolean,
    private val closer: () -> Unit,
) {
    val isCancelled: Boolean get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            runCatching { closer() }
            future.cancel(true)
        }
    }
}

/**
 * Minimal zero-dependency OpenAI-compatible SSE client built on the JDK
 * HttpClient that already ships inside the JBR - no extra class loading,
 * no bundled libraries, smallest possible time-to-first-token.
 */
object LlmClient {
    private val log = Logger.getInstance(LlmClient::class.java)

    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("WhatIsThis-LLM", 4)

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Streams `POST {baseUrl}/chat/completions`.
     * All callbacks are invoked on a background thread; the caller owns EDT marshaling.
     */
    fun chatStream(
        baseUrl: String,
        apiKey: String,
        requestBody: JsonObject,
        onStart: () -> Unit,
        onDelta: (String) -> Unit,
        onFinish: (Throwable?) -> Unit,
    ): StreamHandle {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
            // Applies to time-until-response-headers, i.e. our first-packet budget.
            .timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody.toString().toByteArray(StandardCharsets.UTF_8)))
            .build()

        val cancelled = AtomicBoolean(false)
        val streamRef = AtomicReference<InputStream?>()

        val future = executor.submit {
            try {
                val response = sendWithRetry(request, cancelled)
                val body = response.body().also { streamRef.set(it) }
                if (response.statusCode() != 200) {
                    onFinish(ApiException(response.statusCode(), readErrorBody(body)))
                } else {
                    consume(body, cancelled, onStart, onDelta)
                    onFinish(null)
                }
            } catch (e: HttpTimeoutException) {
                if (!cancelled.get()) onFinish(RuntimeException("请求超时：15 秒内未收到服务端响应"))
            } catch (e: Exception) {
                // Closing the stream on cancel surfaces as IOException - that is a normal stop, not an error.
                if (!cancelled.get()) onFinish(friendlyText(e)?.let(::RuntimeException) ?: e)
            }
        }

        return StreamHandle(future, cancelled) { runCatching { streamRef.get()?.close() } }
    }

    /**
     * One silent retry for failures that happen BEFORE the request reaches the
     * server: flaky local proxies intermittently kill TLS handshakes ("Remote
     * host terminated the handshake") and reset connect attempts. When the
     * handshake dies nothing was ever delivered, so a retry cannot
     * double-answer or double-bill - it just turns "close, reopen, works"
     * into something the user never sees. Header timeouts (the request DID
     * arrive, the server is merely slow) and mid-stream failures (headers
     * received, answer partially shown) are deliberately not retried.
     */
    private fun sendWithRetry(request: HttpRequest, cancelled: AtomicBoolean): HttpResponse<InputStream> {
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: HttpTimeoutException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (cancelled.get()) throw e
                if (attempt == 0) {
                    log.info("WhatIsThis request failed before response headers, retrying once: $e")
                    runCatching { Thread.sleep(200) }
                }
            }
        }
        throw last ?: IllegalStateException("unreachable retry loop")
    }

    /**
     * Connect-level failures become actionable Chinese text (the raw JDK
     * message "Remote host terminated the handshake" sends nobody anywhere
     * useful). Null = not a recognized connect failure, pass the error as-is.
     */
    internal fun friendlyText(e: Throwable): String? {
        val message = e.message?.lowercase() ?: return null
        return when {
            e is java.net.ConnectException -> "无法建立连接（网络不可达或被代理阻断），请检查网络与代理设置"
            "handshake" in message -> "TLS 握手被中断（多为 IDE/系统代理干扰），已自动重试仍失败，请检查代理设置"
            "connection reset" in message -> "连接被重置（网络或代理不稳定），请重试"
            else -> null
        }
    }

    /** Tiny request used by the settings page "测试连接" button. */
    fun test(baseUrl: String, apiKey: String, model: String, onFinish: (Throwable?) -> Unit): StreamHandle =
        chatStream(
            baseUrl, apiKey,
            buildJsonObject {
                put("model", model)
                put("stream", true)
                put("max_tokens", 1)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    })
                })
            },
            onStart = {},
            onDelta = {},
            onFinish = onFinish,
        )

    private fun consume(
        body: InputStream,
        cancelled: AtomicBoolean,
        onStart: () -> Unit,
        onDelta: (String) -> Unit,
    ) {
        var started = false
        var sse = false
        val jsonBuffer = StringBuilder()
        body.bufferedReader(StandardCharsets.UTF_8).forEachLine { raw ->
            if (cancelled.get()) return@forEachLine
            if (!started) {
                onStart()
                started = true
            }
            when {
                raw.startsWith("data:") -> {
                    sse = true
                    handleSseData(raw.removePrefix("data:").trim(), onDelta)
                }
                sse -> Unit // event:/id:/comments - irrelevant
                else -> jsonBuffer.append(raw).append('\n')
            }
        }
        // Some providers ignore stream=true and answer with a single JSON document.
        if (!sse && jsonBuffer.isNotBlank()) {
            extractMessageContent(jsonBuffer.toString())?.let { if (it.isNotEmpty()) onDelta(it) }
        }
    }

    private fun handleSseData(data: String, onDelta: (String) -> Unit) {
        if (data.isEmpty() || data == "[DONE]") return
        val element = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return
        val delta = runCatching {
            element.jsonObject["choices"]
                ?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("delta")
                ?.jsonObject?.get("content")
        }.getOrNull()
        if (delta is JsonPrimitive && delta.isString && delta.content.isNotEmpty()) {
            onDelta(delta.content)
        }
        // reasoning_content and other side channels are deliberately ignored:
        // the user asked a quick question, not for a chain of thought.
    }

    private fun extractMessageContent(payload: String): String? = runCatching {
        val content = json.parseToJsonElement(payload.trim())
            .jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")
            ?.jsonObject?.get("content")
        (content as? JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()

    private fun readErrorBody(body: InputStream): String {
        val text = String(body.readNBytes(4096), StandardCharsets.UTF_8)
        val message = runCatching {
            (json.parseToJsonElement(text).jsonObject["error"] as? JsonObject)
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        return message ?: text.take(300)
    }
}
