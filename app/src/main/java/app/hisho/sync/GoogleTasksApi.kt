package app.hisho.sync

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GoogleTasksApi(private val accessToken: String) {
    data class CreatedTask(val id: String)

    class HttpFailure(val status: Int, message: String) : IOException(message)

    fun findOrCreateTaskList(title: String): String {
        val lists = request("GET", "/tasks/v1/users/@me/lists?maxResults=100")
            .optJSONArray("items") ?: JSONArray()
        for (index in 0 until lists.length()) {
            val list = lists.getJSONObject(index)
            if (list.optString("title") == title) return list.getString("id")
        }

        return request(
            "POST",
            "/tasks/v1/users/@me/lists",
            JSONObject().put("title", title),
        ).getString("id")
    }

    fun findTaskByMarker(taskListId: String, marker: String): CreatedTask? {
        var pageToken: String? = null
        do {
            val query = buildString {
                append("?maxResults=100&showCompleted=true&showHidden=true")
                if (pageToken != null) append("&pageToken=${Uri.encode(pageToken)}")
            }
            val response = request(
                "GET",
                "/tasks/v1/lists/${Uri.encode(taskListId)}/tasks$query",
            )
            val items = response.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val task = items.getJSONObject(index)
                if (task.optString("notes").contains(marker)) {
                    return CreatedTask(task.getString("id"))
                }
            }
            pageToken = response.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null)
        return null
    }

    fun createTask(taskListId: String, title: String, notes: String, due: String?): CreatedTask {
        val task = JSONObject().put("title", title).put("notes", notes)
        due?.let { task.put("due", it) }
        val response = request(
            "POST",
            "/tasks/v1/lists/${Uri.encode(taskListId)}/tasks",
            task,
        )
        return CreatedTask(response.getString("id"))
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = (URL("$BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw HttpFailure(status, "Google Tasks API HTTP $status")
            return if (payload.isBlank()) JSONObject() else JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://tasks.googleapis.com"
    }
}
