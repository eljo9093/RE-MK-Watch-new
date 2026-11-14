package eu.remk.coaching.wear

import android.content.Context
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
 * RE:MK 4 Separate XL Wear OS Interfaces / Kacheln (v27.4 Pro)
 * Speziell für große Finger optimiert: Große Buttons, klare 16sp-22sp Schriften,
 * 4 spezialisierte Kacheln wie bei den Smartphone-Widgets!
 * 1. RemkTileService           -> Kraft & Satz-Logger XL
 * 2. RemkTimerTileService      -> Pausen-Timer & Undo XL
 * 3. RemkNutritionTileService  -> Ernährung & Wasser XL
 * 4. RemkCardioShopTileService -> Puls, GPS & Einkauf XL
 */
object RemkTileHelper {

    fun handleAction(context: Context, actionId: String, prefs: android.content.SharedPreferences) {
        val ed = prefs.edit()
        when (actionId) {
            "tile_kg_minus" -> {
                val cur = prefs.getFloat("cur_kg", 80f)
                ed.putFloat("cur_kg", (cur - 2.5f).coerceAtLeast(0f))
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=${(cur - 2.5f).coerceAtLeast(0f)}")
            }
            "tile_kg_plus" -> {
                val cur = prefs.getFloat("cur_kg", 80f)
                ed.putFloat("cur_kg", cur + 2.5f)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_KG_CHANGED", "kg=${cur + 2.5f}")
            }
            "tile_log_set" -> {
                val sIdx = prefs.getInt("cur_set_idx", 1)
                val totS = prefs.getInt("cur_total_sets", 3)
                val doneToday = prefs.getInt("done_sets_today", 0) + 1
                ed.putInt("done_sets_today", doneToday)
                if (sIdx < totS) {
                    ed.putInt("cur_set_idx", sIdx + 1)
                } else {
                    ed.putInt("cur_set_idx", 1)
                    val nextEx = (prefs.getInt("cur_ex_idx", 0) + 1) % 4
                    ed.putInt("cur_ex_idx", nextEx)
                    val names = arrayOf("Bankdrücken (LH)", "Kniebeugen / Beinpresse", "Latzug breit zur Brust", "KH-Schulterdrücken")
                    val defKgs = floatArrayOf(80f, 110f, 65f, 22.5f)
                    ed.putString("cur_ex_name", names[nextEx])
                    ed.putFloat("cur_kg", defKgs[nextEx])
                }
                ed.putLong("last_rest_start_ms", System.currentTimeMillis())
                ed.putInt("rest_target_sec", 90)
                WearOfflineSyncQueue.enqueueEvent(
                    context,
                    "WATCH_SET_COMPLETED",
                    "kg=${prefs.getFloat("cur_kg", 80f)}&reps=${prefs.getInt("cur_reps", 10)}&setIdx=$sIdx"
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
                val openKcal = (prefs.getInt("open_kcal", 2450) - 550).coerceAtLeast(0)
                ed.putInt("open_kcal", openKcal)
                WearOfflineSyncQueue.enqueueEvent(context, "WATCH_MEAL_TOGGLED", "openKcal=$openKcal")
            }
            "tile_cloud_sync" -> {
                WearOfflineSyncQueue.pushWatchStateToCloudAsync(context)
            }
        }
        ed.apply()
    }

    fun buildXLBtn(label: String, clickId: String, bgColor: Int, txtColor: Int, fontSp: Float = 14f, padH: Float = 14f, padV: Float = 9f): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(padH)).setEnd(dp(padH)).setTop(dp(padV)).setBottom(dp(padV))
                            .build()
                    )
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(clickId)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(14f)).build())
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
            .setResourcesVersion("v27_4")
            .setFreshnessIntervalMillis(30000L)
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
 * INTERFACE 1: 🏋️ KRAFT & SATZ-LOGGER XL
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

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("🏋️ SATZ $setIdx / $totalSets")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(13f)).setColor(argb(0xFFFF5E00.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(exName)
                    .setMaxLines(1)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(14.5f)).setColor(argb(0xFFFFFFFF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(RemkTileHelper.buildXLBtn("−2,5", "tile_kg_minus", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 15f, 13f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(
                        RemkTileHelper.buildLaunchAppBtn(packageName, "⚙️ ${kgStr}kg × $curReps", "open_picker_watch", 0xFF101827.toInt(), 0xFF10B981.toInt())
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(8f)).build())
                    .addContent(RemkTileHelper.buildXLBtn("+2,5", "tile_kg_plus", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 15f, 13f, 8f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(RemkTileHelper.buildXLBtn("✓ SATZ $setIdx LOGGEN", "tile_log_set", 0xFF10B981.toInt(), 0xFF000000.toInt(), 14.5f, 18f, 10f))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v27_4").build())
    }
}

/**
 * INTERFACE 2: ⏱️ PAUSEN-TIMER & UNDO XL
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
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(21f)).setColor(argb(0xFFFFFFFF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildXLBtn("60s", "tile_rest_60", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 14f, 11f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(RemkTileHelper.buildXLBtn("90s", "tile_rest_90", 0xFFFF5E00.toInt(), 0xFF000000.toInt(), 14f, 11f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(RemkTileHelper.buildXLBtn("120s", "tile_rest_120", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt(), 14f, 11f, 8f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildXLBtn("↩️ Satz Rückgängig", "tile_undo_set", 0xFF2D1215.toInt(), 0xFFF43F5E.toInt(), 13f, 14f, 8f))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v27_4").build())
    }
}

/**
 * INTERFACE 3: 🍎 ERNÄHRUNG & WASSER XL
 */
@Suppress("DEPRECATION")
class RemkNutritionTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) RemkTileHelper.handleAction(this, clickedId, prefs)

        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = prefs.getInt("open_kcal", 2450)
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val col = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("🍎 $openKcal kcal offen")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(16f)).setColor(argb(0xFF10B981.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("💧 Wasser: $waterStr L")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(14.5f)).setColor(argb(0xFF38BDF8.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .addContent(RemkTileHelper.buildXLBtn("💧+250ml", "tile_water_250", 0xFF12263A.toInt(), 0xFF38BDF8.toInt(), 13.5f, 12f, 8f))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    .addContent(RemkTileHelper.buildXLBtn("💧+500ml", "tile_water_500", 0xFF12263A.toInt(), 0xFF38BDF8.toInt(), 13.5f, 12f, 8f))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildXLBtn("✓ MAHLZEIT ABHAKEN", "tile_meal_check", 0xFF10B981.toInt(), 0xFF000000.toInt(), 13.5f, 16f, 9f))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v27_4").build())
    }
}

/**
 * INTERFACE 4: 🏃 PULS, GPS & EINKAUF XL
 */
@Suppress("DEPRECATION")
class RemkCardioShopTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) RemkTileHelper.handleAction(this, clickedId, prefs)

        val doneSets = prefs.getInt("done_sets_today", 0)
        val totSets = prefs.getInt("total_sets_today", 12)

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
                    .setText("Heute: ✓ $doneSets / $totSets Sätze")
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(15.5f)).setColor(argb(0xFFFFFFFF.toInt())).setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD).build())
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(RemkTileHelper.buildXLBtn("🔄 CLOUD-SYNC JETZT", "tile_cloud_sync", 0xFF1E293B.toInt(), 0xFF10B981.toInt(), 13.5f, 14f, 9f))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(7f)).build())
            .addContent(RemkTileHelper.buildLaunchAppBtn(packageName, "🚀 XL WATCH-APP ÖFFNEN", "open_main_watch", 0xFFFF5E00.toInt(), 0xFF000000.toInt()))
            .build()

        return RemkTileHelper.wrapInTile(col)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("v27_4").build())
    }
}
