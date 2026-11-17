package eu.remk.coaching.wear

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ColorBuilders.argb
import androidx.wear.tiles.DimensionBuilders.dp
import androidx.wear.tiles.DimensionBuilders.expand
import androidx.wear.tiles.DimensionBuilders.sp
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * RE:MK 4 Separate XL Wear OS Kacheln (v32.0 Pro – 100% Instant-Action Fix)
 * - Alle Buttons auf allen 4 Kacheln nutzen jetzt ActionBuilders.LaunchAction auf ultraschnelle
 *   NoDisplay-Micro-Activities (exakt derselbe Mechanismus wie "ALL-IN-ONE HUB", der auf Wear OS 5
 *   zu 100 % beim ersten Antippen auslöst!).
 * - Sofortiges Vibrations-Feedback (40ms), Live-Kachel-Refresh & Hintergrund-Cloud-Sync.
 * - Automatische Bereinigung des 9833-kcal-Familientopf-Bugs auf persönliche Athleten-Kalorien.
 */
abstract class BaseTileQuickActionActivity(private val actionId: String, private val toastMsg: String = "") : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(40L)
                }
            }
        } catch (e: Exception) {}

        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        RemkTileHelper.handleAction(this, actionId, prefs)
        RemkTileHelper.requestAllTilesRefresh(this)

        if (toastMsg.isNotEmpty()) {
            Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}

class TileKgMinusActivity : BaseTileQuickActionActivity("tile_kg_minus")
class TileKgPlusActivity : BaseTileQuickActionActivity("tile_kg_plus")
class TileLogSetActivity : BaseTileQuickActionActivity("tile_log_set", "✅ Satz geloggt! (Pause läuft)")
class TileRest60Activity : BaseTileQuickActionActivity("tile_rest_60", "⏱️ 60s Pause gestartet")
class TileRest90Activity : BaseTileQuickActionActivity("tile_rest_90", "⏱️ 90s Pause gestartet")
class TileRest120Activity : BaseTileQuickActionActivity("tile_rest_120", "⏱️ 120s Pause gestartet")
class TileUndoSetActivity : BaseTileQuickActionActivity("tile_undo_set", "↩️ Letzter Satz rückgängig")
class TileWater250Activity : BaseTileQuickActionActivity("tile_water_250", "💧 +250 ml Wasser gebucht!")
class TileWater500Activity : BaseTileQuickActionActivity("tile_water_500", "💧 +500 ml Wasser gebucht!")
class TileMealCheckActivity : BaseTileQuickActionActivity("tile_meal_check", "🍎 Mahlzeit abgehakt!")
class TileCloudSyncActivity : BaseTileQuickActionActivity("tile_cloud_sync", "🔄 Cloud-Sync gestartet...")

object RemkTileHelper {

    fun getSanitizedOpenKcal(prefs: android.content.SharedPreferences): Int {
        val raw = prefs.getInt("open_kcal", 2450)
        val target = prefs.getInt("personal_target_kcal", 2450).let { if (it in 1100..4600) it else 2450 }
        return if (raw > 4800) {
            prefs.edit().putInt("open_kcal", target).apply()
            target
        } else {
            raw.coerceAtLeast(0)
        }
    }

    fun requestAllTilesRefresh(context: Context) {
        try {
            val updater = TileService.getUpdater(context)
            updater.requestUpdate(RemkTileService::class.java)
            updater.requestUpdate(RemkTimerTileService::class.java)
            updater.requestUpdate(RemkNutritionTileService::class.java)
            updater.requestUpdate(RemkCardioShopTileService::class.java)
        } catch (e: Exception) {}
    }

    fun getActionActivityClassName(clickId: String): String {
        return when (clickId) {
            "tile_kg_minus" -> TileKgMinusActivity::class.java.name
            "tile_kg_plus" -> TileKgPlusActivity::class.java.name
            "tile_log_set" -> TileLogSetActivity::class.java.name
            "tile_rest_60" -> TileRest60Activity::class.java.name
            "tile_rest_90" -> TileRest90Activity::class.java.name
            "tile_rest_120" -> TileRest120Activity::class.java.name
            "tile_undo_set" -> TileUndoSetActivity::class.java.name
            "tile_water_250" -> TileWater250Activity::class.java.name
            "tile_water_500" -> TileWater500Activity::class.java.name
            "tile_meal_check" -> TileMealCheckActivity::class.java.name
            "tile_cloud_sync" -> TileCloudSyncActivity::class.java.name
            else -> WearMainActivity::class.java.name
        }
    }

    fun handleAction(context: Context, actionId: String, prefs: android.content.SharedPreferences) {
        val ed = prefs.edit()
        when (actionId) {
            "tile_kg_minus" -> {
                val cur = prefs.getFloat("cur_kg", 80f)
                val nextKg = (cur - 2.5f).coerceAtLeast(0f)
                ed.putFloat("cur_kg", nextKg)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_kg_plus" -> {
                val cur = prefs.getFloat("cur_kg", 80f)
                val nextKg = cur + 2.5f
                ed.putFloat("cur_kg", nextKg)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=$nextKg")
            }
            "tile_log_set" -> {
                val dIdx = prefs.getInt("day_idx", 0)
                val exId = prefs.getString("cur_ex_id", "p1") ?: "p1"
                val sIdx = prefs.getInt("cur_set_idx", 1)
                val totS = prefs.getInt("cur_total_sets", 3)
                val curKg = prefs.getFloat("cur_kg", 80f)
                val curReps = prefs.getInt("cur_reps", 10)
                val doneToday = prefs.getInt("done_sets_today", 0) + 1
                val movedVol = prefs.getInt("today_volume_kg", 0) + (curKg * curReps).toInt()
                ed.putInt("done_sets_today", doneToday)
                ed.putInt("today_volume_kg", movedVol)
                try {
                    val mapObj = org.json.JSONObject(prefs.getString("completed_sets_map_json", "{}") ?: "{}")
                    mapObj.put("${dIdx}_${exId}", sIdx.coerceAtMost(totS))
                    ed.putString("completed_sets_map_json", mapObj.toString())
                } catch (e: Exception) {}
                if (sIdx < totS) {
                    ed.putInt("cur_set_idx", sIdx + 1)
                } else {
                    ed.putInt("cur_set_idx", 1)
                    val nextEx = (prefs.getInt("cur_ex_idx", 0) + 1) % 4
                    ed.putInt("cur_ex_idx", nextEx)
                    val ids = arrayOf("p1", "l1", "pu2", "p5")
                    val names = arrayOf("Bankdrücken (LH)", "Kniebeugen / Beinpresse", "Latzug breit zur Brust", "KH-Schulterdrücken")
                    val defKgs = floatArrayOf(80f, 110f, 65f, 22.5f)
                    ed.putString("cur_ex_id", ids[nextEx])
                    ed.putString("cur_ex_name", names[nextEx])
                    ed.putFloat("cur_kg", defKgs[nextEx])
                }
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 90)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_SET_COMPLETED",
                    "exId=$exId&kg=$curKg&reps=$curReps&setIdx=$sIdx"
                )
            }
            "tile_rest_60" -> {
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 60)
            }
            "tile_rest_90" -> {
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 90)
            }
            "tile_rest_120" -> {
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 120)
            }
            "tile_undo_set" -> {
                val doneToday = (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)
                val sIdx = (prefs.getInt("cur_set_idx", 2) - 1).coerceAtLeast(1)
                ed.putInt("done_sets_today", doneToday)
                ed.putInt("cur_set_idx", sIdx)
                WearOfflineSyncQueue.removeLastSetEventIfUndo(context)
            }
            "tile_water_250" -> {
                val curL = prefs.getFloat("drank_liters", 0.0f) + 0.25f
                ed.putFloat("drank_liters", curL)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_WATER_ADDED", "addedMl=250&totalL=$curL")
            }
            "tile_water_500" -> {
                val curL = prefs.getFloat("drank_liters", 0.0f) + 0.50f
                ed.putFloat("drank_liters", curL)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_WATER_ADDED", "addedMl=500&totalL=$curL")
            }
            "tile_meal_check" -> {
                val curOpen = getSanitizedOpenKcal(prefs)
                val nextOpen = (curOpen - 550).coerceAtLeast(0)
                ed.putInt("open_kcal", nextOpen)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_MEAL_TOGGLED", "openKcal=$nextOpen")
            }
            "tile_cloud_sync" -> {
                WearOfflineSyncQueue.pushWatchStateToCloudAsync(context)
            }
        }
        ed.apply()
    }

    fun buildXLBtn(
        pkg: String,
        label: String,
        clickId: String,
        bgColor: Int,
        txtColor: Int,
        fontSp: Float = 13.5f,
        padH: Float = 12f,
        padV: Float = 8f
    ): LayoutElementBuilders.LayoutElement {
        val targetCls = getActionActivityClassName(clickId)
        val launchClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(pkg)
                            .setClassName(targetCls)
                            .build()
                    )
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                            .build()
                    )
                    .setClickable(launchClickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(13f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(fontSp))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    fun buildLaunchAppBtn(pkg: String, label: String, clickId: String, bgColor: Int, txtColor: Int): LayoutElementBuilders.LayoutElement {
        val launchClickable = ModifiersBuilders.Clickable.Builder()
            .setId(clickId)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(pkg)
                            .setClassName(WearMainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(12f)).setEnd(dp(12f)).setTop(dp(7f)).setBottom(dp(7f))
                            .build()
                    )
                    .setClickable(launchClickable)
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(12f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12.5f))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    fun wrapInTile(column: LayoutElementBuilders.Column): ListenableFuture<TileBuilders.Tile> {
        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0xFF000000.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("v32_0")
            .setFreshnessIntervalMillis(20000L)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(rootBox)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()

        return Futures.immediateFuture(tile)
    }
}

/**
 * KACHEL 1: 🏋️ RE:MK 1. Kraft
 */
@Suppress("DEPRECATION")
class RemkTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) RemkTileHelper.handleAction(this, clickedId, prefs)

        val exName = prefs.getString("cur_ex_name", "Bankdrücken (LH)") ?: "Bankdrücken (LH)"
        val setIdx = prefs.getInt("cur_set_idx", 1)
        val totalSets = prefs.getInt("cur_total_sets", 3)
        val curKg = prefs.getFloat("cur_kg", 80.0f)
        val curReps = prefs.getInt("cur_reps", 10)
        val kgStr = if (curKg % 1.0f == 0.0f) curKg.toInt().toString() else curKg.toString().replace(".", ",")

        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = RemkTileHelper.getSanitizedOpenKcal(prefs)
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("🏋️ $setIdx/$totalSets • ${exName.take(15)}")
                    .setMaxLines(1)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(13f)).setColor(argb(0xFFFF5E00.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "−2,5", "tile_kg_minus", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 13.5f, 10f, 7f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(
                        RemkTileHelper.buildLaunchAppBtn(packageName, "⚖️ ${kgStr}kg × $curReps", "open_picker_watch", 0xFF101827.toInt(), 0xFFFFFFFF.toInt())
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "+2,5", "tile_kg_plus", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 13.5f, 10f, 7f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "✓ SATZ $setIdx", "tile_log_set", 0xFF10B981.toInt(), 0xFF000000.toInt(), 13.5f, 12f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "💧+250 ($waterStr)", "tile_water_250", 0xFF12263A.toInt(), 0xFF38BDF8.toInt(), 12f, 9f, 8f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                RemkTileHelper.buildLaunchAppBtn(packageName, "🚀 COCKPIT ($openKcal kcal)", "open_all_hub", 0xFF1E293B.toInt(), 0xFFFF5E00.toInt())
            )
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v32_0").build())
    }
}

/**
 * KACHEL 2: ⏱️ RE:MK 2. Timer
 */
@Suppress("DEPRECATION")
class RemkTimerTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) RemkTileHelper.handleAction(this, clickedId, prefs)

        val lastStart = prefs.getLong("last_rest_start_ms", 0L)
        val targetSec = prefs.getInt("rest_target_sec", 90)
        val elapsed = ((System.currentTimeMillis() - lastStart) / 1000L).toInt()
        val remSec = (targetSec - elapsed).coerceAtLeast(0)
        val timeTxt = if (remSec > 0) "${remSec}s Pause" else "Bereit (${targetSec}s)"

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("⏱️ PAUSE & TIMER")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(12.5f)).setColor(argb(0xFFFF5E00.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(timeTxt)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(20f)).setColor(argb(0xFFFFFFFF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "60s", "tile_rest_60", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 13.5f, 11f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "90s", "tile_rest_90", 0xFFFF5E00.toInt(), 0xFF000000.toInt(), 13.5f, 11f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "120s", "tile_rest_120", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 13.5f, 11f, 8f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildXLBtn(packageName, "↩️ Satz Undo", "tile_undo_set", 0xFF2D1215.toInt(), 0xFFF43F5E.toInt(), 13f, 14f, 8f))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v32_0").build())
    }
}

/**
 * KACHEL 3: 🍎 RE:MK 3. Makros
 */
@Suppress("DEPRECATION")
class RemkNutritionTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) RemkTileHelper.handleAction(this, clickedId, prefs)

        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = RemkTileHelper.getSanitizedOpenKcal(prefs)
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("🍎 $openKcal kcal offen")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(15.5f)).setColor(argb(0xFF10B981.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("💧 Wasser: $waterStr L")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(14f)).setColor(argb(0xFF38BDF8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "💧+250ml", "tile_water_250", 0xFF12263A.toInt(), 0xFF38BDF8.toInt(), 13f, 11f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(RemkTileHelper.buildXLBtn(packageName, "💧+500ml", "tile_water_500", 0xFF12263A.toInt(), 0xFF38BDF8.toInt(), 13f, 11f, 8f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildXLBtn(packageName, "✓ MAHLZEIT ABHAKEN", "tile_meal_check", 0xFF10B981.toInt(), 0xFF000000.toInt(), 13f, 14f, 8f))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v32_0").build())
    }
}

/**
 * KACHEL 4: 🏃 RE:MK 4. Cardio
 */
@Suppress("DEPRECATION")
class RemkCardioShopTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) RemkTileHelper.handleAction(this, clickedId, prefs)

        val doneSets = prefs.getInt("done_sets_today", 0)
        val totSets = prefs.getInt("total_sets_today", 12)
        val movedVol = prefs.getInt("today_volume_kg", 0)

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("⌚ RE:MK LIVE-HUB")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(13f)).setColor(argb(0xFFFF5E00.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("✓ $doneSets/$totSets Sätze • ${movedVol}kg")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(14.5f)).setColor(argb(0xFFFFFFFF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildXLBtn(packageName, "🔄 CLOUD-SYNC JETZT", "tile_cloud_sync", 0xFF1E293B.toInt(), 0xFF10B981.toInt(), 13f, 14f, 8f))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildLaunchAppBtn(packageName, "🚀 WATCH-APP ÖFFNEN", "open_main_watch", 0xFFFF5E00.toInt(), 0xFF000000.toInt()))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v32_0").build())
    }
}
