package com.phonlynn.oreplan.domain.ai

import com.phonlynn.oreplan.domain.ai.protocol.ChatProtocol
import com.phonlynn.oreplan.domain.ai.protocol.ChatRequest
import com.phonlynn.oreplan.domain.ai.protocol.ChatResponse
import com.phonlynn.oreplan.domain.ai.protocol.StreamAccumulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次请求的失败原因。**给用户看的**，所以是可读文案。
 */
class AiException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** 凭据问题（401/403）——设置页据此提示"检查 API Key"。 */
    val isAuthFailure: Boolean get() = status == 401 || status == 403

    /** 限流（429）——可重试。 */
    val isRateLimited: Boolean get() = status == 429

    /** 服务端错误（5xx）——可重试。 */
    val isServerError: Boolean get() = status != null && status >= 500
}

/**
 * OpenAI 兼容协议的 HTTP 客户端。
 *
 * ## 为什么手写而不用 SDK
 *
 * 与 S2 的 R2 客户端同一理由：项目**零第三方依赖**，APK 4.6MB。
 * 而这里的请求形状很简单（一个 POST + SSE 逐行读），
 * 引一个 SDK（+ 它的 OkHttp/协程适配）不划算。
 *
 * ## 可测试性
 *
 * 走 [Transport] 抽象 —— 单元测试注入假 transport 就能覆盖
 * 协议解析、错误处理、流式拼接，**不需要真的联网、也不需要 API Key**。
 * 这让"AI 无法自证"的那部分里，**能被自证的部分尽量多**。
 */
class AiClient(
    private val transport: Transport = HttpUrlConnectionTransport(),
) {

    /** 可替换的网络层。 */
    interface Transport {
        /**
         * 发一次 POST。
         *
         * @param onLine 流式响应时逐行回调；返回 false 表示调用方要求中止
         * @return 完整响应文本（流式时为空串，内容已通过 [onLine] 给出）
         */
        suspend fun postJson(
            url: String,
            apiKey: String,
            body: String,
            stream: Boolean,
            onLine: (suspend (String) -> Unit)?,
        ): RawResponse
    }

    data class RawResponse(val status: Int, val body: String)

    /**
     * 发一次对话请求。
     *
     * @param onDelta 流式文本增量回调（逐字显示用）
     * @param onReasoning 思考内容增量回调
     */
    suspend fun chat(
        config: AiProviderConfig,
        request: ChatRequest,
        onDelta: suspend (String) -> Unit = {},
        onReasoning: suspend (String) -> Unit = {},
    ): ChatResponse {
        val url = endpoint(config.baseUrl, CHAT_PATH)
        val body = request.toJson().toString()

        val accumulator = if (request.stream) StreamAccumulator() else null

        val raw = try {
            transport.postJson(url, config.apiKey, body, request.stream) { line ->
                if (accumulator == null) return@postJson
                val delta = accumulator.feed(line)
                // 正文与推理**各自立刻回调** —— 推理往往先于正文到达，
                // 攒到流结束再给会让「思考过程」整段延迟出现（用户反馈过）。
                if (delta.reasoning.isNotEmpty()) onReasoning(delta.reasoning)
                if (delta.content.isNotEmpty()) onDelta(delta.content)
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Throwable) {
            throw AiException("网络不可达：${e.message ?: e::class.simpleName}", cause = e)
        }

        if (raw.status !in 200..299) {
            val detail = ChatProtocol.parseErrorMessage(raw.body)
            throw AiException(
                message = describeStatus(raw.status, detail),
                status = raw.status,
            )
        }

        if (accumulator != null) {
            // 增量已在上面逐片回调过，这里只给出累积后的完整结果。
            return accumulator.build()
        }

        return ChatProtocol.parseResponse(raw.body)
            ?: throw AiException("无法解析模型返回（响应体可能不是 OpenAI 格式）")
    }

    /**
     * 连通性测试（设置页的「测试连接」）。
     *
     * 用**最小的一次真实请求**而不是只 head 一下域名 ——
     * 那样只能证明"域名通了"，证明不了 Key 有效、模型名对。
     * 这里刻意用 `max_tokens = 1` 把成本压到最低。
     */
    suspend fun testConnection(config: AiProviderConfig): Result<String> = runCatching {
        val response = chat(
            config = config,
            request = ChatRequest(
                model = config.model,
                messages = listOf(
                    com.phonlynn.oreplan.domain.ai.protocol.ChatMessage.user("hi"),
                ),
                maxTokens = 1,
                stream = false,
                allowTools = false,
            ),
        )
        // 能走到这里就说明：地址对、Key 有效、模型名存在
        response.content?.take(20) ?: "连接成功"
    }.recoverCatching { e -> throw e }

    private fun describeStatus(status: Int, detail: String?): String = when {
        status == 401 || status == 403 -> "API Key 无效或没有权限"
        status == 404 -> "接口地址或模型名不存在"
        status == 429 -> "请求过于频繁，稍后再试"
        status >= 500 -> "模型服务暂时不可用"
        detail != null -> detail
        else -> "请求失败（HTTP $status）"
    }

    companion object {
        const val CHAT_PATH = "/chat/completions"

        /**
         * 拼出完整端点。
         *
         * 各家给的 `baseUrl` 写法不一（有的带 `/v1`、有的不带、末尾可能有斜杠），
         * 这里统一处理，避免用户填错就报 404。
         * **不自动补 `/v1`** —— 那会让 `https://api.deepseek.com` 变成
         * `https://api.deepseek.com/v1/chat/completions`，而 DeepSeek 的正确路径
         * 不带 `/v1`。用户填什么就是什么，只负责去掉多余的斜杠。
         */
        fun endpoint(baseUrl: String, path: String): String =
            baseUrl.trimEnd('/') + path
    }
}

/**
 * 基于 `HttpURLConnection` 的实现。
 *
 * S2 已经验证过这条路可行（R2 客户端就是这么写的），
 * 包括一个已知坑：`HttpURLConnection` 对某些头有限制。
 * 这里用 `Authorization`（不受限）而不是自定义鉴权头，没有那个问题。
 */
class HttpUrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) : AiClient.Transport {

    override suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        stream: Boolean,
        onLine: (suspend (String) -> Unit)?,
    ): AiClient.RawResponse = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            // 流式响应下，服务端会一直 hold 着连接 —— 读超时要足够长
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            if (stream) setRequestProperty("Accept", "text/event-stream")
        }

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val errorStream = if (status in 200..299) connection.inputStream else connection.errorStream

            if (errorStream == null) {
                return@withContext AiClient.RawResponse(status, "")
            }

            if (stream && status in 200..299 && onLine != null) {
                BufferedReader(InputStreamReader(errorStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLine(line)
                    }
                }
                AiClient.RawResponse(status, "")
            } else {
                val text = errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                AiClient.RawResponse(status, text)
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
