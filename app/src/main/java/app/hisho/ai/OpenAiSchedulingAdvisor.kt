package app.hisho.ai

import android.content.Context
import app.hisho.data.CaptureQueueDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class OpenAiSchedulingAdvisor(context: Context) {
    private val preferences = AiPreferences(context)

    data class PlannedCapture(
        val capture: CaptureQueueDatabase.PendingCapture,
        val maximumBlockMinutes: Int = 60,
        val riskLevel: String = "unknown",
    )

    data class Advice(
        val orderedTaskIds: List<String>,
        val decisions: Map<String, Decision>,
        val overloadedDates: List<String>,
    )

    data class Decision(val riskLevel: String, val maximumBlockMinutes: Int)

    fun isEnabled() = preferences.isReady()

    fun plan(
        captures: List<CaptureQueueDatabase.PendingCapture>,
        availability: List<AnonymousAvailabilityDay>,
    ): List<PlannedCapture> {
        if (captures.isEmpty() || !preferences.isReady()) return captures.map(::PlannedCapture)
        val key = preferences.apiKey() ?: return captures.map(::PlannedCapture)
        return runCatching {
            val now = System.currentTimeMillis()
            val anonymous = captures.map { AnonymousSchedulingTaskMapper.from(it, now) }
            val advice = requestAdvice(key, AnonymousSchedulingTaskMapper.toJson(anonymous), availability)
            val byId = captures.associateBy { "TASK-${it.id}" }
            val ordered = advice.orderedTaskIds.mapNotNull(byId::get)
            require(ordered.size == captures.size && ordered.map { it.id }.toSet().size == captures.size)
            preferences.lastStatus = if (advice.overloadedDates.isEmpty()) {
                "AI計画を適用 (${captures.size}件)"
            } else {
                "AI計画を適用・過密 ${advice.overloadedDates.size}日"
            }
            ordered.map { capture ->
                val decision = advice.decisions["TASK-${capture.id}"]
                PlannedCapture(
                    capture = capture,
                    maximumBlockMinutes = decision?.maximumBlockMinutes
                        ?.takeIf { capture.effortMinutes > 60 && it in ALLOWED_BLOCK_MINUTES } ?: 60,
                    riskLevel = decision?.riskLevel ?: "unknown",
                )
            }
        }.getOrElse {
            preferences.lastStatus = "AI失敗・端末内処理を使用"
            captures.map(::PlannedCapture)
        }
    }

    private fun requestAdvice(
        apiKey: String,
        anonymousPayload: String,
        availability: List<AnonymousAvailabilityDay>,
    ): Advice {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("store", false)
            put("max_output_tokens", 1200)
            put("instructions", "You plan anonymous tasks. Never infer identity or content. Order every task_id exactly once by deadline risk, priority, failed attempts, age, effort feasibility, and available capacity. Mark overloaded dates where due workload cannot fit. For tasks over 60 minutes, choose a maximum block size from 25, 30, 45, or 60 minutes; use 60 for shorter tasks.")
            put("input", JSONObject().apply {
                put("anonymous_tasks", JSONObject(anonymousPayload).getJSONArray("tasks"))
                put("daily_availability", AnonymousAvailabilityCalculator.toJson(availability))
            }.toString())
            put("text", JSONObject().put("format", JSONObject().apply {
                put("type", "json_schema")
                put("name", "hisho_schedule_priority")
                put("strict", true)
                put("schema", JSONObject().apply {
                    put("type", "object")
                    put("additionalProperties", false)
                    put("properties", JSONObject().apply {
                        put("ordered_task_ids", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                        put("decisions", JSONObject().put("type", "array").put("items", JSONObject().apply {
                            put("type", "object")
                            put("additionalProperties", false)
                            put("properties", JSONObject().apply {
                                put("task_id", JSONObject().put("type", "string"))
                                put("risk_level", JSONObject().put("type", "string").put("enum", JSONArray(listOf("low", "medium", "high", "critical"))))
                                put("maximum_block_minutes", JSONObject().put("type", "integer").put("enum", JSONArray(ALLOWED_BLOCK_MINUTES.toList())))
                            })
                            put("required", JSONArray(listOf("task_id", "risk_level", "maximum_block_minutes")))
                        }))
                        put("overloaded_dates", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                    })
                    put("required", JSONArray(listOf("ordered_task_ids", "decisions", "overloaded_dates")))
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
                        val result = JSONObject(item.getString("text"))
                        val order = result.getJSONArray("ordered_task_ids")
                        val decisions = result.getJSONArray("decisions")
                        return Advice(
                            orderedTaskIds = List(order.length()) { order.getString(it) },
                            decisions = List(decisions.length()) { decisions.getJSONObject(it) }.associate { decision ->
                                decision.getString("task_id") to Decision(
                                    decision.getString("risk_level"),
                                    decision.getInt("maximum_block_minutes"),
                                )
                            },
                            overloadedDates = result.getJSONArray("overloaded_dates").let { dates ->
                                List(dates.length()) { dates.getString(it) }
                            },
                        )
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
        val ALLOWED_BLOCK_MINUTES = setOf(25, 30, 45, 60)
    }
}
