package app.hisho.sync

import android.net.Uri
import app.hisho.scheduling.DeterministicScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId

class GoogleCalendarApi(private val accessToken: String) {
    data class CalendarEvent(val id: String, val start: Instant, val end: Instant)

    class HttpFailure(val status: Int, message: String) : IOException(message)

    fun busyIntervals(from: Instant, to: Instant, zoneId: ZoneId): List<DeterministicScheduler.BusyInterval> {
        val body = JSONObject()
            .put("timeMin", from.toString())
            .put("timeMax", to.toString())
            .put("timeZone", zoneId.id)
            .put("items", JSONArray().put(JSONObject().put("id", PRIMARY_CALENDAR)))
        val response = request("POST", "/calendar/v3/freeBusy", body)
        val busy = response.getJSONObject("calendars")
            .getJSONObject(PRIMARY_CALENDAR)
            .optJSONArray("busy") ?: JSONArray()
        return buildList {
            for (index in 0 until busy.length()) {
                val interval = busy.getJSONObject(index)
                add(
                    DeterministicScheduler.BusyInterval(
                        Instant.parse(interval.getString("start")),
                        Instant.parse(interval.getString("end")),
                    ),
                )
            }
        }
    }

    fun findEvent(captureId: String, from: Instant): CalendarEvent? {
        val privateProperty = Uri.encode("$CAPTURE_PROPERTY=$captureId")
        val path = "/calendar/v3/calendars/$PRIMARY_CALENDAR/events" +
            "?singleEvents=true&maxResults=1&timeMin=${Uri.encode(from.toString())}" +
            "&privateExtendedProperty=$privateProperty"
        val items = request("GET", path).optJSONArray("items") ?: JSONArray()
        if (items.length() == 0) return null
        return items.getJSONObject(0).toEvent()
    }

    fun createEvent(
        captureId: String,
        googleTaskId: String,
        title: String,
        slot: DeterministicScheduler.Slot,
        zoneId: ZoneId,
    ): CalendarEvent {
        val body = JSONObject()
            .put("summary", title)
            .put("description", "Hishoが自動配置しました。\nGoogle Task ID: $googleTaskId")
            .put("start", JSONObject().put("dateTime", slot.start.toString()).put("timeZone", zoneId.id))
            .put("end", JSONObject().put("dateTime", slot.end.toString()).put("timeZone", zoneId.id))
            .put(
                "extendedProperties",
                JSONObject().put("private", JSONObject().put(CAPTURE_PROPERTY, captureId)),
            )
        return request("POST", "/calendar/v3/calendars/$PRIMARY_CALENDAR/events", body).toEvent()
    }

    fun deleteEvent(eventId: String) {
        request("DELETE", "/calendar/v3/calendars/$PRIMARY_CALENDAR/events/${Uri.encode(eventId)}")
    }

    private fun JSONObject.toEvent(): CalendarEvent = CalendarEvent(
        id = getString("id"),
        start = Instant.parse(getJSONObject("start").getString("dateTime")),
        end = Instant.parse(getJSONObject("end").getString("dateTime")),
    )

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
            if (status !in 200..299) throw HttpFailure(status, "Google Calendar API HTTP $status")
            return if (payload.isBlank()) JSONObject() else JSONObject(payload)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://www.googleapis.com"
        const val PRIMARY_CALENDAR = "primary"
        const val CAPTURE_PROPERTY = "hishoCaptureId"
    }
}
