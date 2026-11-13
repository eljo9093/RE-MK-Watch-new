package eu.remk.coaching.wear

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

/**
 * RE:MK Bidirektionale Store-&-Forward Offline-Queue & Phone-Sync-Engine (v26.1)
 * - Speichert alle Sätze, Satz-Undos, Wasser-Buchungen, Mahlzeiten, Einkäufe und GPS-Läufe lokal im Tresor der Pixel Watch 4
 * - Empfängt Plan-, Kalorien- und Einkaufs-Updates direkt von der Smartphone-App
 * - Funktioniert zu 100 % eigenständig, wenn das Smartphone zuhause liegt!
 */
object WearOfflineSyncQueue {

    const val PREFS_NAME = "remk_wear_standalone_v260"
    private const val KEY_QUEUE = "offline_sync_queue_json"

    fun enqueueEvent(context: Context, action: String, details: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val obj = JSONObject()
            obj.put("id", "w_" + System.currentTimeMillis())
            obj.put("action", action)
            obj.put("details", details)
            obj.put("ts", System.currentTimeMillis())
            arr.put(obj)

            val trimmed = JSONArray()
            val startIdx = (arr.length() - 150).coerceAtLeast(0)
            for (i in startIdx until arr.length()) {
                trimmed.put(arr.get(i))
            }
            prefs.edit().putString(KEY_QUEUE, trimmed.toString()).apply()

            val intent = Intent("eu.remk.coaching.ACTION_WEAR_SYNC_EVENT").apply {
                putExtra("action", action)
                putExtra("details", details)
                putExtra("timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {}
    }

    fun removeLastSetEventIfUndo(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
            val arr = JSONArray(raw)
            if (arr.length() > 0) {
                val nextArr = JSONArray()
                for (i in 0 until arr.length() - 1) {
                    nextArr.put(arr.get(i))
                }
                prefs.edit().putString(KEY_QUEUE, nextArr.toString()).apply()
            }
            enqueueEvent(context, "WATCH_SET_UNDO", "undo=1")
        } catch (e: Exception) {}
    }

    fun getPendingCount(context: Context): Int {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_QUEUE, "[]") ?: "[]")
            arr.length()
        } catch (e: Exception) {
            0
        }
    }

    fun markAllSynced(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_QUEUE, "[]")
                .putLong("last_sync_ts", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {}
    }
}
