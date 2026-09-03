package app.hisho.ai

import android.content.Context
import app.hisho.data.CaptureQueueDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAiSchedulingAdvisor(context: Context) {
    private val preferences = AiPreferences(context)

    fun prioritize(captures: List<CaptureQueueDatabase.PendingCapture>): List<CaptureQueueDatabase.PendingCapture> {
        if (captures.size < 2 || !preferences.isReady()) return captures
        val key = preferences.apiKey() ?: return captures
        return runCatching {
            val now = System.currentTimeMillis()
            val anonymous = captures.map { AnonymousSchedulingTaskMapper.from(it, now) }
            val order = requestOrder(key, AnonymousSchedulingTaskMapper.toJson(anonymous))
            val byId = captures.associateBy { "TASK-${it.id}" }
            val ordered = order.mapNotNull(byId::get)
            require(ordered.size == captures.size && ordered.map { it.id }.toSet().size == captures.size)
            preferences.lastStatus = "AI優先順位を適用 (${captures.size}件)"
            ordered
        }.getOrElse {
            preferences.lastStatus = "AI失敗・端末内処理を使用"
            captures
        }
    }

    private fun requestOrder(apiKey: String, anonymousPayload: String): List<String> {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("store", false)
            put("max_output_tokens", 500)
            put("instructions", "You prioritize anonymous tasks for scheduling. Never infer identity or content. Return every task_id exactly once, ordered by deadline risk, priority, failed attempts, task age, and effort feasibility.")
            put("input", anonymousPayload)
            put("text", JSONObject().put("format", JSONObject().apply {
                put("type", "json_schema")
                put("name", "hisho_schedule_priority")
                put("strict", true)
                put("schema", JSONObject().apply {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", JSONObject().put("ordered_task_ids", JSONObject().apply {
                        put("type", "array")
                        put("items", JSONObject().put("type", "string"))
                    }))
                    put("required", JSONArray().put("ordered_task_ids"))
                })
            }))
        }
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val responseText = (if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(connection.responseCode in 200..299) { "OpenAI HTTP ${connection.responseCode}" }
            val response = JSONObject(responseText)
            val output = response.getJSONArray("output")
            for (index in 0 until output.length()) {
                val content = output.getJSONObject(index).optJSONArray("content") ?: continue
                for (contentIndex in 0 until content.length()) {
                    val item = content.getJSONObject(contentIndex)
                    if (item.optString("type") == "output_text") {
                        val result = JSONObject(item.getString("text")).getJSONArray("ordered_task_ids")
                        return List(result.length()) { result.getString(it) }
                    }
                }
            }
            error("OpenAI response contained no output_text")
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.com/v1/responses"
        const val MODEL = "gpt-5.4-mini"
    }
}
