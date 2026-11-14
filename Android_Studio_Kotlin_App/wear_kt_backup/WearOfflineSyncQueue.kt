package eu.remk.coaching.wear

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * RE:MK Bidirektionale Store-&-Forward Offline-Queue & Google-Sheets Live-Sync-Engine (v27.4 Pro)
 * - Verlustfreier Rolling-Event-Puffer (behält die letzten 80 eindeutigen Event-IDs, damit auch
 *   bei ausgeschaltetem Handy im Spind kein einziger Satz verloren geht!)
 * - Synchronisiert abgehakte Sätze, Satz-Undos, Gewichte, Wasser, Mahlzeiten & Einkäufe in Echtzeit
 */
object WearOfflineSyncQueue {

    const val PREFS_NAME = "remk_wear_standalone_v260"
    private const val KEY_QUEUE = "offline_sync_queue_json"
    private const val KEY_UNSYNCED_CNT = "offline_unsynced_count"
    const val GOOGLE_SCRIPT_API_URL = "https://script.google.com/macros/s/AKfycbwdAgwJtubUPoI08ifSX3lPRgVhv5PFzq6YyZywxAB780JOZ2OFGMmojVvEj1lHz3ek/exec"
    private var eventSeq = 100

    fun getOrCreateSyncKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var key = (prefs.getString("sync_license_key", "") ?: "").trim()
        if (key.isEmpty()) {
            key = "FREE-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            prefs.edit().putString("sync_license_key", key).apply()
        }
        return key
    }

    fun setSyncKey(context: Context, newKey: String) {
        val clean = newKey.trim()
        if (clean.isNotEmpty()) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString("sync_license_key", clean)
                .apply()
        }
    }

    fun getOrCreateWatchDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var devId = prefs.getString("watch_device_id", "") ?: ""
        if (devId.isEmpty()) {
            devId = "WATCH-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString("watch_device_id", devId).apply()
        }
        return devId
    }

    fun enqueueEvent(context: Context, action: String, details: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            eventSeq = (eventSeq + 1) % 9999
            val obj = JSONObject()
            obj.put("id", "w_${System.currentTimeMillis()}_$eventSeq")
            obj.put("action", action)
            obj.put("details", details)
            obj.put("ts", System.currentTimeMillis())
            arr.put(obj)

            val trimmed = JSONArray()
            val startIdx = (arr.length() - 80).coerceAtLeast(0)
            for (i in startIdx until arr.length()) {
                trimmed.put(arr.get(i))
            }
            val unsynced = prefs.getInt(KEY_UNSYNCED_CNT, 0) + 1
            prefs.edit()
                .putString(KEY_QUEUE, trimmed.toString())
                .putInt(KEY_UNSYNCED_CNT, unsynced)
                .apply()

            val intent = Intent("eu.remk.coaching.ACTION_WEAR_SYNC_EVENT").apply {
                putExtra("action", action)
                putExtra("details", details)
                putExtra("timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(intent)

            // Sofortiger Hintergrund-Push an Google Sheets für Echtzeit-Sync mit der App
            pushWatchStateToCloudAsync(context)
        } catch (e: Exception) {}
    }

    fun removeLastSetEventIfUndo(context: Context, exId: String = "", setIdx: Int = 1) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            var removed = false
            val nextArr = JSONArray()
            for (i in (arr.length() - 1) downTo 0) {
                val item = arr.optJSONObject(i) ?: continue
                if (!removed && item.optString("action") == "WATCH_SET_COMPLETED") {
                    removed = true
                    continue
                }
            }
            // Baue Array in richtiger Reihenfolge ohne das letzte Set-Event wieder auf
            if (removed) {
                var skipIdx = -1
                for (i in (arr.length() - 1) downTo 0) {
                    val item = arr.optJSONObject(i)
                    if (item != null && item.optString("action") == "WATCH_SET_COMPLETED") {
                        skipIdx = i
                        break
                    }
                }
                for (i in 0 until arr.length()) {
                    if (i != skipIdx) nextArr.put(arr.get(i))
                }
                prefs.edit().putString(KEY_QUEUE, nextArr.toString()).apply()
            }
            enqueueEvent(context, "WATCH_SET_UNDO", "exId=$exId&setIdx=$setIdx")
        } catch (e: Exception) {}
    }

    fun getPendingCount(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getInt(KEY_UNSYNCED_CNT, 0).coerceAtLeast(0)
        } catch (e: Exception) {
            0
        }
    }

    fun markAllSynced(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            // Wichtig: KEY_QUEUE bleibt als Rolling-Log erhalten, damit kein Satz verloren geht,
            // wenn die Uhr mehrfach während des Trainings pusht, solange das Handy im Spind aus ist!
            prefs.edit()
                .putInt(KEY_UNSYNCED_CNT, 0)
                .putLong("last_sync_ts", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }

    fun buildWatchSyncSnapshot(context: Context): JSONObject {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snap = JSONObject()
        snap.put("source", "watch_v27_4")
        snap.put("ts", System.currentTimeMillis())
        snap.put("dayIdx", prefs.getInt("day_idx", 0))
        snap.put("exIdx", prefs.getInt("cur_ex_idx", 0))
        snap.put("setIdx", prefs.getInt("cur_set_idx", 1))
        snap.put("curKg", prefs.getFloat("cur_kg", 80f).toDouble())
        snap.put("curReps", prefs.getInt("cur_reps", 10))
        snap.put("doneSetsToday", prefs.getInt("done_sets_today", 0))
        snap.put("drankLiters", prefs.getFloat("drank_liters", 0f).toDouble())
        snap.put("openKcal", prefs.getInt("open_kcal", 2400))
        snap.put("completedSetsMap", prefs.getString("completed_sets_map_json", "{}") ?: "{}")
        snap.put("eventsQueue", JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]"))
        return snap
    }

    fun pushWatchStateToCloudAsync(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val appCtx = context.applicationContext
        Thread {
            var ok = false
            try {
                val syncKey = getOrCreateSyncKey(appCtx)
                val devId = getOrCreateWatchDeviceId(appCtx)
                val bodyObj = JSONObject()
                bodyObj.put("action", "syncPush")
                bodyObj.put("key", syncKey)
                bodyObj.put("deviceId", devId)
                bodyObj.put("syncData", buildWatchSyncSnapshot(appCtx))

                val url = URL(GOOGLE_SCRIPT_API_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 7000
                    doOutput = true
                    instanceFollowRedirects = true
                    setRequestProperty("Content-Type", "text/plain;charset=utf-8")
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(bodyObj.toString())
                    writer.flush()
                }
                val code = conn.responseCode
                if (code in 200..399) {
                    markAllSynced(appCtx)
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok)
        }.start()
    }

    fun pullCloudStateToWatchAsync(context: Context, onComplete: ((Boolean, JSONObject?) -> Unit)? = null) {
        val appCtx = context.applicationContext
        Thread {
            var ok = false
            var syncDataObj: JSONObject? = null
            try {
                val syncKey = getOrCreateSyncKey(appCtx)
                val devId = getOrCreateWatchDeviceId(appCtx)
                val fullUrl = "$GOOGLE_SCRIPT_API_URL?action=syncPull&key=${java.net.URLEncoder.encode(syncKey, "UTF-8")}&deviceId=${java.net.URLEncoder.encode(devId, "UTF-8")}"
                val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
                    instanceFollowRedirects = true
                }
                val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
                val respText = reader.readText()
                reader.close()
                val json = JSONObject(respText)
                if (json.optBoolean("valid", false)) {
                    syncDataObj = json.optJSONObject("syncData")
                    ok = true
                }
            } catch (e: Exception) {
                ok = false
            }
            onComplete?.invoke(ok, syncDataObj)
        }.start()
    }
}
