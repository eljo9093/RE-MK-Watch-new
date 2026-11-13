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
 * RE:MK Native Wear OS Home-Screen Kachel (RemkTileService v27.0 Pro)
 * - Einheitliche androidx.wear.tiles.* Architektur (100% kompatibel mit Pixel Watch 4 / Wear OS 5)
 * - Direkte 1-Tap-Aktionen auf der Kachel (-2,5kg, +2,5kg, Satz abhaken, +250ml Wasser, Mahlzeit, App öffnen)
 */
@Suppress("DEPRECATION")
class RemkTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val prefs = getSharedPreferences("remk_wear_standalone_v260", Context.MODE_PRIVATE)

        val clickedId = requestParams.state?.lastClickableId ?: ""
        if (clickedId.isNotEmpty()) {
            handleTileQuickAction(clickedId, prefs)
        }

        val exName = prefs.getString("cur_ex_name", "Bankdrücken (LH)") ?: "Bankdrücken (LH)"
        val setIdx = prefs.getInt("cur_set_idx", 1)
        val totalSets = prefs.getInt("cur_total_sets", 3)
        val curKg = prefs.getFloat("cur_kg", 80.0f)
        val curReps = prefs.getInt("cur_reps", 10)
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = prefs.getInt("open_kcal", 2450)
        val doneSetsToday = prefs.getInt("done_sets_today", 0)
        val totalSetsToday = prefs.getInt("total_sets_today", 12)

        val kgStr = if (curKg % 1.0f == 0.0f) curKg.toInt().toString() else curKg.toString().replace(".", ",")
        val waterStr = String.format("%.2f", drankL).replace(".", ",")

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("RE:MK • ✓$doneSetsToday/$totalSetsToday • 💧${waterStr}L")
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(0xFFFF5E00.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText("Satz $setIdx/$totalSets: $exName")
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(12.5f))
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(5f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(buildMiniTileBtn("-2,5", "tile_kg_minus", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt()))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText("${kgStr}kg × $curReps")
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setSize(sp(15f))
                                    .setColor(argb(0xFF10B981.toInt()))
                                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                                    .build()
                            )
                            .setModifiers(
                                ModifiersBuilders.Modifiers.Builder()
                                    .setClickable(buildLaunchAppClickable("open_gear"))
                                    .build()
                            )
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(5f)).build())
                    .addContent(buildMiniTileBtn("+2,5", "tile_kg_plus", 0xFF1E293B.toInt(), 0xFF38BDF8.toInt()))
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                buildPrimaryTileBtn("✓ SATZ $setIdx LOGGEN", "tile_log_set", 0xFF10B981.toInt(), 0xFF000000.toInt())
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(buildMiniTileBtn("💧+250", "tile_water_250", 0xFF12263A.toInt(), 0xFF38BDF8.toInt()))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(buildMiniTileBtn("🍎${openKcal}", "tile_meal_check", 0xFF14291E.toInt(), 0xFF10B981.toInt()))
                    .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
                    .addContent(buildLaunchTileBtn("⚙️ App", "open_cockpit", 0xFF2A1810.toInt(), 0xFFFF5E00.toInt()))
                    .build()
            )
            .build()

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
            .setResourcesVersion("v27_0")
            .setFreshnessIntervalMillis(60000L)
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

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion("v27_0")
                .build()
        )
    }

    private fun handleTileQuickAction(actionId: String, prefs: android.content.SharedPreferences) {
        val ed = prefs.edit()
        when (actionId) {
            "tile_kg_minus" -> {
                val cur = prefs.getFloat("cur_kg", 80f)
                ed.putFloat("cur_kg", (cur - 2.5f).coerceAtLeast(0f))
            }
            "tile_kg_plus" -> {
                val cur = prefs.getFloat("cur_kg", 80f)
                ed.putFloat("cur_kg", cur + 2.5f)
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
                WearOfflineSyncQueue.enqueueEvent(
                    this,
                    "WATCH_SET_COMPLETED",
                    "kg=${prefs.getFloat("cur_kg", 80f)}&reps=${prefs.getInt("cur_reps", 10)}"
                )
            }
            "tile_water_250" -> {
                val curL = prefs.getFloat("drank_liters", 0.0f) + 0.25f
                ed.putFloat("drank_liters", curL)
                WearOfflineSyncQueue.enqueueEvent(this, "WATCH_WATER_ADDED", "addedMl=250&totalL=$curL")
            }
            "tile_meal_check" -> {
                val openKcal = (prefs.getInt("open_kcal", 2450) - 550).coerceAtLeast(0)
                ed.putInt("open_kcal", openKcal)
                WearOfflineSyncQueue.enqueueEvent(this, "WATCH_MEAL_TOGGLED", "openKcal=$openKcal")
            }
        }
        ed.apply()
    }

    private fun buildMiniTileBtn(label: String, clickId: String, bgColor: Int, txtColor: Int): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(7f)).setEnd(dp(7f)).setTop(dp(4f)).setBottom(dp(4f))
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
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(8f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildPrimaryTileBtn(label: String, clickId: String, bgColor: Int, txtColor: Int): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(12f)).setEnd(dp(12f)).setTop(dp(6f)).setBottom(dp(6f))
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
                            .setSize(sp(12f))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildLaunchAppClickable(id: String): ModifiersBuilders.Clickable {
        return ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(WearMainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildLaunchTileBtn(label: String, clickId: String, bgColor: Int, txtColor: Int): LayoutElementBuilders.LayoutElement {
        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(dp(7f)).setEnd(dp(7f)).setTop(dp(4f)).setBottom(dp(4f))
                            .build()
                    )
                    .setClickable(buildLaunchAppClickable(clickId))
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(bgColor))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(8f)).build())
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Text.Builder()
                    .setText(label)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(11f))
                            .setColor(argb(txtColor))
                            .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
