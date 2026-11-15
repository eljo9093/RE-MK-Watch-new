package eu.remk.coaching.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RE:MK Pixel Watch 4 – All-in-One Sports Cockpit & Multi-Watchface Studio (v31.0 Ultimate Pro)
 *
 * NEU IN V31.0 (DAS 10/10 SPEZIALISTEN- & ZIFFERBLATT-UPGRADE):
 * 1. ⌚ Multi-Watchface Studio (3 umschaltbare Zifferblatt-Designs + echte Canvas-Fortschrittsringe + OLED-Ambient-Modus):
 *    - Stil 1 ("🎯 Rings"): Echte konzentrische 3-fach Grafik-Ringe (🟠 Sätze, 🟢 Kcal, 🔵 Wasser) + Digital-Chrono + Akku-% (🔋).
 *    - Stil 2 ("🏋️ Gym XL"): Direkt-Satz-Zifferblatt (zeigt Übung, Gewicht, Tonnage & erlaubt "✓ Satz abhaken" direkt auf dem Zifferblatt!).
 *    - Stil 3 ("🌙 OLED Eco"): Reines #000000 Stromspar-Zifferblatt (schaltet sich im Vollbild-Modus auch automatisch nach 20s Inaktivität ein).
 * 2. 🔄 1-Tap "Übung tauschen (Alternative)" direkt auf der Uhr inkl. biomechanischer Gewichts-Umrechnung:
 *    - Schlägt bei besetztem Gerät sofort 3 anatomisch identische Ersatzübungen vor und rechnet LH/KH/Maschinen-Gewichte automatisch um.
 * 3. 🏷️ Satz-Typ-Modus (Normal | 🔥 Drop-Satz -20% | ⚡ Myo-Reps 15s) & ⚡ 30-Min Express-Workout-Schalter.
 * 4. 🏃 5-Farben-Herzfrequenzzonen (Z1..Z5), Live-Pace (min/km) & visuelle Fortschrittsbalken im Pausen-Timer.
 * 5. 🌅 Automatischer Mitternachts-Reset (00:00 Uhr), ±150 kcal Spontan-Snack-Button & 🛒 Bring!-Auto-Sortierung im Supermarkt.
 */
class WearMainActivity : Activity(), SensorEventListener, LocationListener {

    data class WatchExercise(
        val id: String,
        var name: String,
        var sets: Int,
        var targetReps: Int,
        var kg: Float,
        var prevRef: String,
        val unilateral: Boolean = false,
        var doneSets: Int = 0
    )

    data class WatchMeal(
        val slot: String,
        val title: String,
        val kcal: Int,
        var done: Boolean = false
    )

    data class WatchShopItem(
        val origIdx: Int,
        val title: String,
        var done: Boolean = false
    )

    /**
     * Echte grafische 3-Ring-Anzeige (Canvas Arc Gauge wie bei Apple Activity Rings / Garmin Epix)
     * Zeichnet 3 konzentrische Leuchtringe: Außen = Workout-Sätze (Orange), Mitte = Kalorien (Grün), Innen = Wasser (Blau)
     */
    inner class RemkTripleRingGaugeView(
        context: Context,
        private val setsPct: Float,
        private val kcalPct: Float,
        private val waterPct: Float,
        private val centerTopText: String,
        private val centerSubText: String
    ) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val size = min(w, h)
            val cx = w / 2f
            val cy = h / 2f

            val stroke = (size * 0.095f).coerceAtLeast(6f)
            val gap = stroke * 1.22f
            bgPaint.strokeWidth = stroke
            fgPaint.strokeWidth = stroke

            val rings = listOf(
                Triple(0f, setsPct.coerceIn(0.04f, 1f), "#FF5E00" to "#2A160D"),
                Triple(1f, kcalPct.coerceIn(0.04f, 1f), "#10B981" to "#0D261C"),
                Triple(2f, waterPct.coerceIn(0.04f, 1f), "#38BDF8" to "#0E2238")
            )

            for ((idxFactor, pct, colors) in rings) {
                val radius = (size / 2f) - (stroke / 2f) - (idxFactor * gap) - dp(2)
                if (radius <= 4f) continue
                rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
                bgPaint.color = Color.parseColor(colors.second)
                fgPaint.color = Color.parseColor(colors.first)
                canvas.drawArc(rect, -90f, 360f, false, bgPaint)
                canvas.drawArc(rect, -90f, 360f * pct, false, fgPaint)
            }

            txtPaint.textSize = size * 0.19f
            subPaint.textSize = size * 0.125f
            canvas.drawText(centerTopText, cx, cy + (txtPaint.textSize * 0.15f), txtPaint)
            canvas.drawText(centerSubText, cx, cy + (txtPaint.textSize * 0.15f) + subPaint.textSize + dp(2), subPaint)
        }
    }

    /**
     * Visueller Fortschritts-Balken für Satzpausen, Zifferblatt-Ringe und WOD-Intervalle
     */
    inner class RemkLinearProgressBarView(
        context: Context,
        var progressPct: Float,
        var fillHex: String = "#FF5E00",
        var trackHex: String = "#182235"
    ) : View(context) {
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            trackPaint.color = Color.parseColor(trackHex)
            fillPaint.color = Color.parseColor(fillHex)
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, trackPaint)
            val fw = (w * progressPct.coerceIn(0.03f, 1f))
            rect.set(0f, 0f, fw, h)
            canvas.drawRoundRect(rect, r, r, fillPaint)
        }
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rootLayout: LinearLayout
    private lateinit var stickyTopDock: LinearLayout
    private lateinit var mainScrollView: ScrollView
    private lateinit var streamContainer: LinearLayout

    private var secWatchfaceView: View? = null
    private var secWorkoutView: View? = null
    private var secPlanView: View? = null
    private var secCardioView: View? = null
    private var secNutritionView: View? = null
    private var secShopSyncView: View? = null

    private var watchfaceClockTv: TextView? = null
    private var watchfaceDateTv: TextView? = null
    private var watchfacePulsePillTv: TextView? = null
    private var restCountdownTv: TextView? = null
    private var restHrRecoveryTv: TextView? = null
    private var restProgressBarView: RemkLinearProgressBarView? = null
    private var isoCountdownTv: TextView? = null
    private var cardioMetricsTv: TextView? = null
    private var wodMetricsTv: TextView? = null
    private var topStatusTv: TextView? = null

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var lastGpsLocation: Location? = null

    // ⌚ Multi-Watchface Studio: 0 = 🎯 Chrono Rings, 1 = 🏋️ Gym Focus XL, 2 = 🌙 OLED Eco
    private var watchfaceStyleIdx = 0
    private var fullWatchfaceMode = false
    private var isOledAmbientDimmed = false
    private var expandPlanSection = true
    private var expandCardioSection = false
    private var expandNutritionSection = false
    private var expandShopSection = false

    // 📏 1-Tap Auto-Close Inline-Zahlenstrahl ("none", "kg", "reps")
    private var inlineRulerMode = "none"

    // 👑 Temporärer 4-Sekunden-Kronen-Fokus ("scroll", "kg", "reps")
    private var crownMode = "scroll"
    private var crownFocusExpireMs = 0L
    private val crownResetHandler = Handler(Looper.getMainLooper())
    private val crownResetRunnable = Runnable {
        if (crownMode != "scroll") {
            crownMode = "scroll"
            renderAllInOneWatchInterface(keepScroll = true)
        }
    }

    private var weightStep = 2.5f
    private var showProHelperDrawer = false

    // Satz-Typ ("normal", "drop", "myo"), RPE, Express-Modus & Supersatz
    private var currentSetType = "normal" // "normal", "drop", "myo"
    private var expressWorkout30Min = false
    private var currentRpeMode = 1 // 0 = 🟢 Leicht, 1 = 🎯 Optimal, 2 = 🔥 Limit
    private var supersetPartnerExIdx = -1
    private var suggestBackoffForNextSet = false
    private val warmupDoneExIds = mutableSetOf<String>()

    // Isometrie-Übungstimer
    private var isoTimer: CountDownTimer? = null
    private var isoSecondsLeft = 0
    private var isoTimerRunning = false

    private var activePartnerSlot = "p1"
    private var activeWeek = 1
    private var targetKcal = 2400
    private var targetProtein = 160
    private var manualSnackDeltaKcal = 0

    private var currentDayIdx = 0
    private var currentExIdx = 0
    private var currentSetIdx = 1
    private var unilateralLeft = true
    private var todayMovedVolumeKg = 0
    private var readinessMode = 0 // 0 = 🟢 100%, 1 = 🟡 -5%, 2 = 🔴 -15%

    // Undo-Speicher
    private var canUndoLastSet = false
    private var undoDayIdx = 0
    private var undoExIdx = 0
    private var undoSetIdx = 1
    private var undoKg = 80.0f
    private var undoReps = 10

    private var liveKg = 80.0f
    private var liveReps = 10

    // Pausen-Timer & HR-Recovery
    private var restTimer: CountDownTimer? = null
    private var restSecondsLeft = 0
    private var restTotalSeconds = 90
    private var restEndTimestampMs = 0L
    private var lastAutoRestLabel = "90s Hypertrophie"
    private var hrRecoveryNotifiedThisRest = false
    private val hrRecoveryThresholdBpm = 122

    // GPS, Puls & WOD-Timer Status
    private var gpsRunning = false
    private var gpsTimer: CountDownTimer? = null
    private var gpsSeconds = 0
    private var gpsDistKm = 0.0f
    private var liveBpm = 118
    private var hasRealHrFix = false
    private var activeSportLabel = "🏃 Laufen"

    private var page2WodMode = false
    private var wodPresetIdx = 0 // 0 = Tabata 20/10, 1 = Zirkel 45/15, 2 = EMOM 60s
    private var wodRunning = false
    private var wodIsWorkPhase = true
    private var wodRound = 1
    private var wodSecLeft = 20
    private var wodTimer: CountDownTimer? = null

    // Live-Uhrzeit & Auto-OLED-Ambient-Ticker (1s In-Place Update)
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                updateWatchfaceClockInPlace()
                val idleMs = System.currentTimeMillis() - lastTouchTimestampMs
                if (fullWatchfaceMode && !isOledAmbientDimmed && idleMs > 20000L) {
                    isOledAmbientDimmed = true
                    renderAllInOneWatchInterface(keepScroll = false)
                }
                clockHandler.postDelayed(this, 1000L)
            }
        }
    }

    // Smart-Diff Hintergrund-Sync
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isSyncInProgress = false
    private var lastCloudSyncOk = true
    private var lastTouchTimestampMs = 0L
    private var lastCloudDataSignature = ""

    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                val idleMs = System.currentTimeMillis() - lastTouchTimestampMs
                if (idleMs > 2800L) {
                    triggerBackgroundCloudPull(silent = true)
                }
                syncHandler.postDelayed(this, 9000L)
            }
        }
    }

    private var trainingDays = mutableListOf(
        "Tag 1 – Ganzkörper / Push" to mutableListOf(
            WatchExercise("p1", "Bankdrücken (LH)", 3, 10, 80.0f, "80kg × 10"),
            WatchExercise("l1", "Kniebeugen / Beinpresse", 3, 10, 110.0f, "107,5kg × 10"),
            WatchExercise("pu2", "Latzug breit zur Brust", 3, 10, 65.0f, "65kg × 10"),
            WatchExercise("p5", "KH-Schulterdrücken", 3, 12, 22.5f, "22,5kg × 12")
        ),
        "Tag 2 – Unterkörper & Core" to mutableListOf(
            WatchExercise("pu7", "Rumänisches Kreuzheben", 3, 10, 90.0f, "87,5kg × 10"),
            WatchExercise("l6", "Bulgarian Split Squats", 3, 10, 20.0f, "20kg × 10", unilateral = true),
            WatchExercise("l7", "Beinbeuger Maschine", 3, 12, 55.0f, "52,5kg × 12"),
            WatchExercise("c1", "Plank Hold", 3, 45, 0.0f, "BW × 45s")
        ),
        "Tag 3 – Oberkörper Hybrid" to mutableListOf(
            WatchExercise("p2", "KH-Schrägbankdrücken", 3, 10, 30.0f, "30kg × 10"),
            WatchExercise("pu1", "Rudern am Kabelzug", 3, 10, 70.0f, "67,5kg × 10"),
            WatchExercise("p8", "Seitheben (Schulter)", 3, 15, 12.5f, "12,5kg × 15"),
            WatchExercise("a1", "SZ-Curls & Pushdowns", 3, 12, 32.5f, "32,5kg × 12")
        ),
        "Tag 4 – Calisthenics & Park" to mutableListOf(
            WatchExercise("ca1", "Pull-Ups / Muscle-Ups", 3, 8, 0.0f, "BW × 8"),
            WatchExercise("ca2", "Parallel-Bar Dips", 3, 12, 10.0f, "+10kg × 12"),
            WatchExercise("ca3", "Pistol Squats", 3, 8, 0.0f, "BW × 8", unilateral = true),
            WatchExercise("ca4", "Front-Lever Rows", 3, 10, 0.0f, "BW × 10")
        ),
        "Tag 5 – Athletik & Mobility" to mutableListOf(
            WatchExercise("at1", "Explosive Box Jumps", 3, 6, 0.0f, "Max × 6"),
            WatchExercise("at2", "Nordic Hamstring Curls", 3, 8, 0.0f, "BW × 8"),
            WatchExercise("at3", "Copenhagen Plank", 3, 30, 0.0f, "30s / Seite", unilateral = true),
            WatchExercise("at4", "World's Greatest Stretch", 2, 45, 0.0f, "Flow 45s")
        )
    )

    private val dailyMeals = mutableListOf(
        WatchMeal("☕ Frühstück", "High-Protein Porridge", 590, false),
        WatchMeal("🍲 Mittag", "Hähnchen-Reis-Bowl", 740, false),
        WatchMeal("🍎 Snack", "Skyr & Reiswaffeln", 390, false),
        WatchMeal("🥗 Abend", "Ofen-Lachs & Kartoffeln", 680, false)
    )

    private val shoppingItems = mutableListOf(
        WatchShopItem(0, "1,2 kg Hähnchenbrust", false),
        WatchShopItem(1, "1,5 kg Skyr / Magerquark", false),
        WatchShopItem(2, "800 g Basmatireis & Hafer", false),
        WatchShopItem(3, "1,0 kg Brokkoli & Spinat", false),
        WatchShopItem(4, "10 Stück Eier (Größe M)", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lastTouchTimestampMs = System.currentTimeMillis()

        prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        checkAndPerformMidnightDailyReset()
        loadPersistedWatchState()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05070B"))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        stickyTopDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A0F1A"))
            setPadding(dp(18), dp(4), dp(18), dp(4))
        }
        rootLayout.addView(
            stickyTopDock,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        mainScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isSmoothScrollingEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        streamContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(5), dp(12), dp(48))
        }

        mainScrollView.addView(
            streamContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        rootLayout.addView(
            mainScrollView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(rootLayout)
        mainScrollView.requestFocus()

        renderAllInOneWatchInterface(keepScroll = false)
        rootLayout.postDelayed({ initWatchHardwareSensors() }, 350L)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        lastTouchTimestampMs = System.currentTimeMillis()
        if (isOledAmbientDimmed && ev?.action == MotionEvent.ACTION_DOWN) {
            isOledAmbientDimmed = false
            renderAllInOneWatchInterface(keepScroll = true)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun activateTemporaryCrownFocus(targetMode: String) {
        crownMode = targetMode
        crownFocusExpireMs = System.currentTimeMillis() + 4200L
        crownResetHandler.removeCallbacks(crownResetRunnable)
        if (targetMode != "scroll") {
            crownResetHandler.postDelayed(crownResetRunnable, 4200L)
        }
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent?): Boolean {
        if (ev != null && ev.action == MotionEvent.ACTION_SCROLL &&
            ev.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            lastTouchTimestampMs = System.currentTimeMillis()
            if (isOledAmbientDimmed) {
                isOledAmbientDimmed = false
                renderAllInOneWatchInterface(keepScroll = false)
                return true
            }
            val delta = -ev.getAxisValue(MotionEvent.AXIS_SCROLL)
            val dir = if (delta > 0) 1 else -1
            rootLayout.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

            val now = System.currentTimeMillis()
            if (crownMode != "scroll" && now < crownFocusExpireMs) {
                activateTemporaryCrownFocus(crownMode)
                if (crownMode == "kg") {
                    stepWeight(dir * weightStep)
                } else if (crownMode == "reps") {
                    val isTimed = isIsometricOrTimedExercise(trainingDays[currentDayIdx].second[currentExIdx].name)
                    stepReps(if (isTimed) dir * 5 else dir)
                }
                return true
            } else {
                crownMode = "scroll"
                mainScrollView.smoothScrollBy(0, (delta * dp(68)).roundToInt())
                return true
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        lastTouchTimestampMs = System.currentTimeMillis()
        checkAndPerformMidnightDailyReset()
        loadPersistedWatchState()
        renderAllInOneWatchInterface(keepScroll = true)
        clockHandler.removeCallbacks(clockTickRunnable)
        clockHandler.postDelayed(clockTickRunnable, 1000L)
        syncHandler.removeCallbacks(autoSyncRunnable)
        syncHandler.postDelayed(autoSyncRunnable, 1500L)
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTickRunnable)
        syncHandler.removeCallbacks(autoSyncRunnable)
        crownResetHandler.removeCallbacks(crownResetRunnable)
        saveWatchStateAndRefreshTile()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            isOledAmbientDimmed -> {
                isOledAmbientDimmed = false
                renderAllInOneWatchInterface(keepScroll = false)
            }
            inlineRulerMode != "none" -> {
                inlineRulerMode = "none"
                crownMode = "scroll"
                renderAllInOneWatchInterface(keepScroll = true)
            }
            showProHelperDrawer -> {
                showProHelperDrawer = false
                renderAllInOneWatchInterface(keepScroll = true)
            }
            fullWatchfaceMode -> {
                fullWatchfaceMode = false
                renderAllInOneWatchInterface(keepScroll = false)
            }
            else -> {
                mainScrollView.smoothScrollTo(0, 0)
            }
        }
    }

    /**
     * 🌅 Automatischer Mitternachts-Reset (00:00 Uhr):
     * Prüft das aktuelle Datum (yyyy-MM-dd). Bei einem neuen Tag werden Wasserstand, Snack-Korrektur
     * und erledigte Mahlzeiten automatisch für den neuen Tag auf 0 zurückgesetzt!
     */
    private fun checkAndPerformMidnightDailyReset() {
        try {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date())
            val lastDate = prefs.getString("last_daily_reset_date", "") ?: ""
            if (lastDate.isNotEmpty() && lastDate != todayKey) {
                val ed = prefs.edit()
                ed.putString("last_daily_reset_date", todayKey)
                ed.putFloat("drank_liters", 0.0f)
                ed.putInt("done_sets_today", 0)
                ed.putInt("today_volume_kg", 0)
                ed.putInt("manual_snack_delta_kcal", 0)
                for (i in 0..10) {
                    ed.putBoolean("meal_done_$i", false)
                }
                ed.apply()
                todayMovedVolumeKg = 0
                manualSnackDeltaKcal = 0
            } else if (lastDate.isEmpty()) {
                prefs.edit().putString("last_daily_reset_date", todayKey).apply()
            }
        } catch (e: Exception) {}
    }

    private fun getWatchBatteryPercent(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(1, 100) ?: 92
        } catch (e: Exception) {
            92
        }
    }

    /**
     * 5-Zonen-Herzfrequenz-Klassifikation (Sportwissenschaftliche Trainingszonen):
     * Gibt (Zonen-Kürzel, Farb-Hex) zurück.
     */
    private fun getHeartRateZoneInfo(bpm: Int): Pair<String, String> {
        return when {
            bpm < 115 -> "Z1 Reg" to "#38BDF8"      // Blau: Regeneration
            bpm < 135 -> "Z2 Fett" to "#10B981"     // Grün: Grundlagenausdauer / Erholt
            bpm < 153 -> "Z3 Aerob" to "#FBBF24"    // Gelb: Aerob
            bpm < 172 -> "Z4 Schwelle" to "#FF5E00" // Orange: Anaerobe Schwelle
            else -> "Z5 Max" to "#F43F5E"           // Rot: VO2max / Spitzenlast
        }
    }

    private fun scrollToSection(target: View?) {
        if (target == null) return
        triggerVibration(18)
        if (fullWatchfaceMode || isOledAmbientDimmed) {
            fullWatchfaceMode = false
            isOledAmbientDimmed = false
            renderAllInOneWatchInterface(keepScroll = false)
        }
        mainScrollView.post {
            mainScrollView.smoothScrollTo(0, (target.top - dp(4)).coerceAtLeast(0))
        }
    }

    private fun isIsometricOrTimedExercise(exName: String): Boolean {
        val low = exName.lowercase()
        return low.contains("plank") || low.contains("hold") || low.contains("stretch") ||
            low.contains("wall sit") || low.contains("deadbug") || low.contains("hollow") ||
            low.contains("hängen") || low.contains("isometr")
    }

    private fun calculateOptimalRestSeconds(ex: WatchExercise, isSupersetJump: Boolean): Pair<Int, String> {
        if (currentSetType == "drop") return 15 to "15s Drop-Satz"
        if (currentSetType == "myo") return 15 to "15s Myo-Reps"
        if (isSupersetJump) return 45 to "45s Supersatz"
        if (expressWorkout30Min) return 60 to "60s Express-Modus"
        val low = ex.name.lowercase()
        if (isIsometricOrTimedExercise(ex.name) || low.contains("seitheben") || low.contains("curl") ||
            low.contains("pushdown") || low.contains("trizeps") || low.contains("bizeps") ||
            low.contains("waden") || low.contains("facepull") || low.contains("bauch")
        ) {
            return 60 to "60s Isolation/Core"
        }
        if (low.contains("kniebeug") || (low.contains("squat") && !low.contains("split")) ||
            low.contains("kreuzheben") || low.contains("deadlift") ||
            low.contains("bankdrücken") || low.contains("beinpresse") ||
            low.contains("muscle-up") || low.contains("pull-up") || low.contains("klimmzug")
        ) {
            return 120 to "120s Grundübung (ATP/ZNS)"
        }
        return 90 to "90s Hypertrophie"
    }

    private fun updateWatchfaceClockInPlace() {
        val now = Date()
        val timeFmt = SimpleDateFormat(if (isOledAmbientDimmed) "HH:mm" else "HH:mm:ss", Locale.GERMANY)
        val dateFmt = SimpleDateFormat("EEE, dd. MMM", Locale.GERMANY)
        val batPct = getWatchBatteryPercent()
        watchfaceClockTv?.text = timeFmt.format(now)
        watchfaceDateTv?.text = "${dateFmt.format(now).uppercase(Locale.GERMANY)} • 🔋$batPct%"
    }

    // =========================================================================
    // ALL-IN-ONE WATCH INTERFACE INKL. MULTI-WATCHFACE STUDIO (V31.0)
    // =========================================================================
    private fun renderAllInOneWatchInterface(keepScroll: Boolean = true) {
        if (!::streamContainer.isInitialized || isFinishing || isDestroyed) return
        val prevScrollY = if (keepScroll) mainScrollView.scrollY else 0

        stickyTopDock.removeAllViews()
        streamContainer.removeAllViews()

        // Falls OLED-Ambient-Stromsparmodus aktiv ist -> Reines #000000 Minimal-Display
        if (isOledAmbientDimmed) {
            stickyTopDock.visibility = View.GONE
            rootLayout.setBackgroundColor(Color.BLACK)
            streamContainer.addView(buildOledAmbientWatchfaceCard(), sectionLayoutParams())
            return
        } else {
            stickyTopDock.visibility = View.VISIBLE
            rootLayout.setBackgroundColor(Color.parseColor("#05070B"))
        }

        // 1. OBERE STATUS- & SCHNELLSPRUNG-LEISTE (Mit Live-Puls-Zone & Akku)
        val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
        val syncText = when {
            isSyncInProgress -> "🔄 Sync"
            pendingCnt > 0 -> "⚡$pendingCnt"
            lastCloudSyncOk -> "🟢 Live"
            else -> "🟠 Offline"
        }
        val (zoneName, zoneCol) = getHeartRateZoneInfo(liveBpm)
        val crownHint = when (crownMode) {
            "kg" -> "👑±kg"
            "reps" -> "👑±Wdh"
            else -> zoneName
        }

        topStatusTv = TextView(this).apply {
            text = "❤️ $liveBpm ($crownHint) • $syncText • ${activePartnerSlot.uppercase()}"
            setTextColor(Color.parseColor(if (crownMode != "scroll") "#FBBF24" else zoneCol))
            textSize = 10.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(2))
            setOnClickListener {
                triggerBackgroundCloudPull(silent = false)
            }
        }
        stickyTopDock.addView(topStatusTv)

        val dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dockRow.addView(buildDockIconBtn(if (fullWatchfaceMode) "⌚*" else "⌚") {
            triggerVibration(20)
            fullWatchfaceMode = !fullWatchfaceMode
            renderAllInOneWatchInterface(keepScroll = false)
        })
        dockRow.addView(buildDockIconBtn("🏋️") {
            scrollToSection(secWorkoutView)
        })
        dockRow.addView(buildDockIconBtn("📋") {
            expandPlanSection = true
            renderAllInOneWatchInterface(keepScroll = true)
            scrollToSection(secPlanView)
        })
        dockRow.addView(buildDockIconBtn("⏱️") {
            expandCardioSection = true
            renderAllInOneWatchInterface(keepScroll = true)
            scrollToSection(secCardioView)
        })
        dockRow.addView(buildDockIconBtn("🍎") {
            expandNutritionSection = true
            renderAllInOneWatchInterface(keepScroll = true)
            scrollToSection(secNutritionView)
        })
        dockRow.addView(buildDockIconBtn("🛒") {
            expandShopSection = true
            renderAllInOneWatchInterface(keepScroll = true)
            scrollToSection(secShopSyncView)
        })
        stickyTopDock.addView(dockRow)

        // 2. SEKTION 0: ⌚ MULTI-WATCHFACE STUDIO (3 UMSCHALTBARE ZIFFERBLATT-DESIGNS)
        secWatchfaceView = buildSection0RemkSportWatchfaceCard()
        streamContainer.addView(secWatchfaceView, sectionLayoutParams())

        if (fullWatchfaceMode) {
            return
        }

        // 3. ALLE 5 SPORT-SEKTIONEN UNTEREINANDER IM VERTIKALEN STREAM AUFBAUEN
        secWorkoutView = buildSection1ActiveWorkoutCard()
        streamContainer.addView(secWorkoutView, sectionLayoutParams())

        secPlanView = buildSection2TrainingPlanAndReadinessCard()
        streamContainer.addView(secPlanView, sectionLayoutParams())

        secCardioView = buildSection3CardioGpsAndWodCard()
        streamContainer.addView(secCardioView, sectionLayoutParams())

        secNutritionView = buildSection4NutritionAndWaterCard()
        streamContainer.addView(secNutritionView, sectionLayoutParams())

        secShopSyncView = buildSection5ShoppingAndCloudSyncCard()
        streamContainer.addView(secShopSyncView, sectionLayoutParams())

        if (keepScroll && prevScrollY > 0) {
            mainScrollView.post { mainScrollView.scrollTo(0, prevScrollY) }
        }
    }

    // =========================================================================
    // 🌙 ECHTES OLED-AMBIENT-ZIFFERBLATT (STROMSPARMODUS BEI GESENKTEM ARM)
    // =========================================================================
    private fun buildOledAmbientWatchfaceCard(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(12), dp(22), dp(12), dp(22))
        }
        val now = Date()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
        val dateFmt = SimpleDateFormat("EEE, dd. MMM", Locale.GERMANY)
        val batPct = getWatchBatteryPercent()

        watchfaceDateTv = TextView(this).apply {
            text = "${dateFmt.format(now).uppercase(Locale.GERMANY)} • 🔋$batPct%"
            setTextColor(Color.parseColor("#64748B"))
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        box.addView(watchfaceDateTv)

        watchfaceClockTv = TextView(this).apply {
            text = timeFmt.format(now)
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 36f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(6))
        }
        box.addView(watchfaceClockTv)

        val curEx = trainingDays[currentDayIdx].second[currentExIdx]
        val statusStr = if (restSecondsLeft > 0) {
            val m = (restSecondsLeft / 60).toString().padStart(2, '0')
            val s = (restSecondsLeft % 60).toString().padStart(2, '0')
            "⏱️ PAUSE $m:$s  •  ❤️ $liveBpm"
        } else {
            "🏋️ ${curEx.name.take(15)} • S$currentSetIdx (${fmtKg(liveKg)}kg)"
        }
        watchfacePulsePillTv = TextView(this).apply {
            text = statusStr
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        box.addView(watchfacePulsePillTv)

        box.addView(TextView(this).apply {
            text = "(Tippen oder Krone drehen zum Aufwecken)"
            setTextColor(Color.parseColor("#475569"))
            textSize = 9.5f
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        return box
    }

    // =========================================================================
    // SEKTION 0: ⌚ MULTI-WATCHFACE STUDIO (3 UMSCHALTBARE PROFI-ZIFFERBLÄTTER)
    // =========================================================================
    private fun buildSection0RemkSportWatchfaceCard(): View {
        val styleBorder = when (watchfaceStyleIdx) {
            1 -> "#10B981"
            2 -> "#38BDF8"
            else -> "#FF5E00"
        }
        val card = createSectionCard("#070B14", styleBorder)
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]

        val doneSetsDay = exList.sumOf { it.doneSets }
        val totalSetsDay = exList.sumOf { it.sets }.coerceAtLeast(1)
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val rawOpenKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }
        val openKcal = (rawOpenKcal - manualSnackDeltaKcal).coerceAtLeast(0)
        val eatenKcal = (targetKcal - openKcal).coerceAtLeast(0)

        val setsPct = (doneSetsDay.toFloat() / totalSetsDay.toFloat()).coerceIn(0f, 1f)
        val kcalPct = (eatenKcal.toFloat() / targetKcal.coerceAtLeast(1000).toFloat()).coerceIn(0f, 1f)
        val waterPct = (drankL / 3.0f).coerceIn(0f, 1f)

        val now = Date()
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
        val dateFmt = SimpleDateFormat("EEE, dd. MMM", Locale.GERMANY)
        val batPct = getWatchBatteryPercent()

        // Obere Zifferblatt-Kopfzeile: Datum + Akku + 1-Tap Zifferblatt-Stil-Umschalter
        val topWfRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        watchfaceDateTv = TextView(this).apply {
            text = "${dateFmt.format(now).uppercase(Locale.GERMANY)} • 🔋$batPct%"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 9.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val styleNames = listOf("🎯 Rings", "🏋️ Gym XL", "🌙 Eco")
        val styleBtn = buildWeightedCellBtn(styleNames[watchfaceStyleIdx], "#141E30", styleBorder, 9.5f) {
            triggerVibration(18)
            watchfaceStyleIdx = (watchfaceStyleIdx + 1) % 3
            prefs.edit().putInt("watchface_style_idx", watchfaceStyleIdx).apply()
            renderAllInOneWatchInterface(keepScroll = true)
        }
        topWfRow.addView(watchfaceDateTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.7f))
        topWfRow.addView(styleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
        card.addView(topWfRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        when (watchfaceStyleIdx) {
            0 -> {
                // =========================================================
                // STIL 1: 🎯 CHRONO RINGS (3 ECHTE KONZENTRISCHE GRAFIK-RINGE)
                // =========================================================
                val centerRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(4))
                }
                val ringGauge = RemkTripleRingGaugeView(
                    this,
                    setsPct = setsPct,
                    kcalPct = kcalPct,
                    waterPct = waterPct,
                    centerTopText = "${(setsPct * 100).roundToInt()}%",
                    centerSubText = "TAG"
                ).apply {
                    setOnClickListener {
                        triggerVibration(18)
                        scrollToSection(secWorkoutView)
                    }
                }
                centerRow.addView(ringGauge, LinearLayout.LayoutParams(dp(74), dp(74)).apply { rightMargin = dp(6) })

                val rightClockCol = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }
                watchfaceClockTv = TextView(this).apply {
                    text = timeFmt.format(now)
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    setSingleLine(true)
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setOnClickListener {
                        triggerVibration(18)
                        fullWatchfaceMode = !fullWatchfaceMode
                        renderAllInOneWatchInterface(keepScroll = false)
                    }
                }
                val (zName, zCol) = getHeartRateZoneInfo(liveBpm)
                val pulseSubTv = TextView(this).apply {
                    text = "❤️ $liveBpm BPM ($zName)\n🔥 $todayMovedVolumeKg kg Tonnage"
                    setTextColor(Color.parseColor(zCol))
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                }
                rightClockCol.addView(watchfaceClockTv)
                rightClockCol.addView(pulseSubTv)
                centerRow.addView(rightClockCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(centerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            1 -> {
                // =========================================================
                // STIL 2: 🏋️ GYM FOCUS XL (SATZ DIREKT AUF DEM ZIFFERBLATT ABHAKEN!)
                // =========================================================
                watchfaceClockTv = TextView(this).apply {
                    text = timeFmt.format(now)
                    setTextColor(Color.WHITE)
                    textSize = 18f
                    setSingleLine(true)
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(0, dp(1), 0, dp(2))
                }
                card.addView(watchfaceClockTv)

                val gymRow = buildProportionalTwoBtnRow(
                    "⚖️ ${fmtKg(liveKg)}kg × $liveReps",
                    "#131D31",
                    "#FFFFFF",
                    {
                        fullWatchfaceMode = false
                        inlineRulerMode = "kg"
                        renderAllInOneWatchInterface(keepScroll = false)
                        scrollToSection(secWorkoutView)
                    },
                    "✓ SATZ $currentSetIdx LOGGEN",
                    "#10B981",
                    "#000000",
                    {
                        completeCurrentSet()
                    }
                )
                card.addView(gymRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(4)
                })
            }

            else -> {
                // =========================================================
                // STIL 3: 🌙 OLED ECO (MAXIMALE UHRZEIT & AKKU-SPAR-BALKEN)
                // =========================================================
                watchfaceClockTv = TextView(this).apply {
                    text = timeFmt.format(now)
                    setTextColor(Color.parseColor("#38BDF8"))
                    textSize = 25f
                    setSingleLine(true)
                    gravity = Gravity.CENTER
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setPadding(0, dp(2), 0, dp(3))
                    setOnClickListener {
                        isOledAmbientDimmed = true
                        renderAllInOneWatchInterface(keepScroll = false)
                    }
                }
                card.addView(watchfaceClockTv)
            }
        }

        // 3 Live-Complications nebeneinander: [🟠 Sätze] | [🟢 Kcal] | [🔵 Wasser +250ml]
        val ringsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val ringSets = buildWeightedCellBtn("🟠 $doneSetsDay/$totalSetsDay Sätze", "#141D2F", "#FF5E00", 10.5f) {
            scrollToSection(secWorkoutView)
        }
        val ringKcal = buildWeightedCellBtn("🟢 ${openKcal}kcal", "#10241D", "#10B981", 10.5f) {
            expandNutritionSection = true
            renderAllInOneWatchInterface(keepScroll = true)
            scrollToSection(secNutritionView)
        }
        val ringWater = buildWeightedCellBtn("💧${String.format("%.2f", drankL).replace(".", ",")}L +", "#102236", "#38BDF8", 10.5f) {
            addWaterLiters(0.25f)
        }
        ringsRow.addView(ringSets, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.05f).apply { rightMargin = dp(2) })
        ringsRow.addView(ringKcal, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f).apply { rightMargin = dp(2) })
        ringsRow.addView(ringWater, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
        card.addView(ringsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Live-Puls / Pausen-Status & Direkter Satz-Einstieg auf dem Zifferblatt
        val liveBannerTxt = if (restSecondsLeft > 0) {
            val m = (restSecondsLeft / 60).toString().padStart(2, '0')
            val s = (restSecondsLeft % 60).toString().padStart(2, '0')
            val recIcon = if (liveBpm < hrRecoveryThresholdBpm) "💚 Erholt" else "❤️ $liveBpm"
            "⏱️ Pause $m:$s ($recIcon) ➔ Satz $currentSetIdx"
        } else {
            "🏋️ ${curEx.name.take(15)} (S$currentSetIdx • ${fmtKg(liveKg)}kg×$liveReps)"
        }
        watchfacePulsePillTv = buildWeightedCellBtn(
            liveBannerTxt,
            if (restSecondsLeft > 0) "#231208" else "#162238",
            if (restSecondsLeft > 0 && liveBpm < hrRecoveryThresholdBpm) "#10B981" else "#F8FAFC",
            11f
        ) {
            if (fullWatchfaceMode) {
                fullWatchfaceMode = false
                renderAllInOneWatchInterface(keepScroll = false)
            }
            scrollToSection(secWorkoutView)
        }
        card.addView(watchfacePulsePillTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (fullWatchfaceMode) {
            val fullBtns = buildProportionalTwoBtnRow(
                "🚀 Zum Training",
                "#10B981",
                "#000000",
                {
                    triggerVibration(25)
                    fullWatchfaceMode = false
                    renderAllInOneWatchInterface(keepScroll = false)
                },
                "🌙 OLED Dimmen",
                "#182235",
                "#38BDF8",
                {
                    triggerVibration(20)
                    isOledAmbientDimmed = true
                    renderAllInOneWatchInterface(keepScroll = false)
                }
            )
            card.addView(fullBtns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
            })
        }

        return card
    }

    // =========================================================================
    // SEKTION 1: 🏋️ AKTIVES SATZ-COCKPIT (INKL. ÜBUNGSTAUCH & DROP-/MYO-SETS)
    // =========================================================================
    private fun buildSection1ActiveWorkoutCard(): View {
        val card = createSectionCard("#101726", "#FF5E00")
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val isTimedEx = isIsometricOrTimedExercise(curEx.name)

        if (isTimedEx && liveKg == 44.0f) {
            liveKg = 0.0f
            curEx.kg = 0.0f
        }

        val totalSetsDay = exList.sumOf { it.sets }
        val doneSetsDay = exList.sumOf { it.doneSets }
        val allDone = exList.isNotEmpty() && exList.all { it.doneSets >= it.sets }

        if (allDone) {
            card.addView(TextView(this).apply {
                text = "🏆 WORKOUT ERLEDIGT!\n✅ $doneSetsDay/$totalSetsDay Sätze  •  🔥 $todayMovedVolumeKg kg"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(6))
            })
            val celebRow = buildProportionalTwoBtnRow(
                "☁️ Syncen", "#10B981", "#000000", { triggerBackgroundCloudPull(silent = false) },
                "🔄 Nächster Tag", "#182235", "#38BDF8", {
                    triggerVibration(25)
                    exList.forEach { it.doneSets = 0 }
                    currentDayIdx = (currentDayIdx + 1) % trainingDays.size
                    switchExerciseSafely(0)
                }
            )
            card.addView(celebRow)
            return card
        }

        // A) Übungs-Umschalter: [◀] 1/4 Bankdrücken (LH) [▶]
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(1), 0, dp(2))
        }
        val prevBtn = buildWeightedCellBtn("◀", "#182235", "#38BDF8", 12.5f) {
            triggerVibration(20)
            switchExerciseSafely(currentExIdx - 1)
        }
        val sideSuffix = if (curEx.unilateral) (if (unilateralLeft) " [L]" else " [R]") else ""
        val supersetBadge = if (supersetPartnerExIdx >= 0) " 🔗" else ""
        val exTitleTv = TextView(this).apply {
            text = "${currentExIdx + 1}/${exList.size} ${curEx.name}$sideSuffix$supersetBadge"
            setTextColor(Color.WHITE)
            textSize = 13f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnClickListener {
                if (curEx.unilateral) {
                    unilateralLeft = !unilateralLeft
                    triggerVibration(18)
                    renderAllInOneWatchInterface(keepScroll = true)
                } else {
                    openExerciseSwapDialog()
                }
            }
        }
        val nextBtn = buildWeightedCellBtn("▶", "#182235", "#38BDF8", 12.5f) {
            triggerVibration(20)
            switchExerciseSafely(currentExIdx + 1)
        }
        navRow.addView(prevBtn, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        navRow.addView(exTitleTv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(nextBtn, LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // B) Satz-Punkte + Satz-Typ-Umschalter (Normal / 🔥 Drop-Satz / ⚡ Myo-Reps) + PR-Detektor
        val setDots = (1..curEx.sets).joinToString("") { s ->
            when {
                s <= curEx.doneSets -> "🟢"
                s == currentSetIdx -> "🟠"
                else -> "⚪"
            }
        }
        val setTypeTag = when (currentSetType) {
            "drop" -> "🔥DROP"
            "myo" -> "⚡MYO"
            else -> "SATZ $currentSetIdx/${curEx.sets}"
        }
        val isNewPr = checkIfCurrentIsNewPR(curEx.prevRef, liveKg, liveReps)
        val prBadge = if (isNewPr && !isTimedEx) " 🏆PR!" else ""
        card.addView(TextView(this).apply {
            text = "$setDots $setTypeTag • Vorher: ${curEx.prevRef}$prBadge"
            setTextColor(Color.parseColor(if (isNewPr && !isTimedEx) "#FBBF24" else if (currentSetType != "normal") "#38BDF8" else "#FF5E00"))
            textSize = 11f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(3))
            setOnClickListener {
                triggerVibration(20)
                cycleSetTypeMode()
            }
        })

        // B2) Sportwissenschaftliches 1-Tap RAMP Warm-up vor Satz 1
        if (!isTimedEx && liveKg >= 35f && curEx.doneSets == 0 && currentSetIdx == 1 && !warmupDoneExIds.contains(curEx.id)) {
            val w50 = fmtKg(((liveKg * 0.50f) / 2.5f).toInt() * 2.5f)
            val w78 = fmtKg(((liveKg * 0.78f) / 2.5f).toInt() * 2.5f)
            val rampBtn = buildWeightedCellBtn(
                "🔥 RAMP Warm-up: 8×${w50}kg ➔ 3×${w78}kg (60s)",
                "#1E1B10",
                "#FBBF24",
                10.5f
            ) {
                triggerVibration(30)
                warmupDoneExIds.add(curEx.id)
                lastAutoRestLabel = "60s RAMP Warm-up"
                startRestCountdown(60)
                Toast.makeText(this, "🔥 Spezifisches Aufwärmen abgehakt (60s Pause)!", Toast.LENGTH_SHORT).show()
            }
            card.addView(rampBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }

        // B3) Sportwissenschaftlicher Ermüdungs-Back-off Coach
        if (suggestBackoffForNextSet && liveKg >= 20f && !isTimedEx) {
            val backoffKg = (((liveKg * 0.925f) * 4f).roundToInt() / 4f).coerceAtLeast(0f)
            val backoffBtn = buildWeightedCellBtn(
                "📉 Limit im Vor-Satz: −7,5% Back-off (${fmtKg(backoffKg)}kg) nutzen?",
                "#2D1215",
                "#F43F5E",
                10.5f
            ) {
                triggerVibration(25)
                suggestBackoffForNextSet = false
                setExactWeight(backoffKg, autoCloseRuler = true)
                Toast.makeText(this, "🎯 Back-off (${fmtKg(backoffKg)}kg) für saubere Wdh. übernommen!", Toast.LENGTH_SHORT).show()
            }
            card.addView(backoffBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }

        // C) Nicht-blockierendes Inline-Pausen-Banner INKL. VISUELLEM PROGRESS-BAR & 💚 HR-RECOVERY
        if (restSecondsLeft > 0) {
            val isHrRecovered = (liveBpm < hrRecoveryThresholdBpm)
            val restBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg(if (isHrRecovered) "#0D261C" else "#231208", if (isHrRecovered) "#10B981" else "#FF5E00", 14)
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            val m = (restSecondsLeft / 60).toString().padStart(2, '0')
            val s = (restSecondsLeft % 60).toString().padStart(2, '0')
            restCountdownTv = TextView(this).apply {
                text = "⏱️ PAUSE: $m:$s  ($lastAutoRestLabel)"
                setTextColor(Color.WHITE)
                textSize = 15f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            }
            restBox.addView(restCountdownTv)

            val pctLeft = (restSecondsLeft.toFloat() / restTotalSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            restProgressBarView = RemkLinearProgressBarView(
                this,
                progressPct = pctLeft,
                fillHex = if (isHrRecovered) "#10B981" else "#FF5E00",
                trackHex = "#141C2B"
            )
            restBox.addView(restProgressBarView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)).apply {
                topMargin = dp(3)
                bottomMargin = dp(2)
            })

            restHrRecoveryTv = TextView(this).apply {
                text = if (isHrRecovered) {
                    "💚 Puls erholt ($liveBpm BPM) – Bereit für Satz $currentSetIdx!"
                } else {
                    "❤️ Puls $liveBpm BPM (Ziel: unter $hrRecoveryThresholdBpm BPM)"
                }
                setTextColor(Color.parseColor(if (isHrRecovered) "#10B981" else "#FBBF24"))
                textSize = 10.5f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(1), 0, dp(3))
            }
            restBox.addView(restHrRecoveryTv)

            val rBtns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rBtns.addView(buildWeightedCellBtn("−15s", "#182235", "#E2E8F0", 11f) {
                restSecondsLeft = (restSecondsLeft - 15).coerceAtLeast(5)
                startRestCountdown(restSecondsLeft)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            rBtns.addView(buildWeightedCellBtn("+30s", "#182235", "#E2E8F0", 11f) {
                restSecondsLeft += 30
                restTotalSeconds = maxOf(restTotalSeconds, restSecondsLeft)
                startRestCountdown(restSecondsLeft)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            rBtns.addView(buildWeightedCellBtn("Weiter ›", "#10B981", "#000000", 11f) {
                stopRestCountdown()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            restBox.addView(rBtns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            card.addView(restBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        } else {
            restCountdownTv = null
            restHrRecoveryTv = null
            restProgressBarView = null
        }

        // D) GEWICHTS-ZEILE: [ −2,5 ]  [ ⚖️ 80 kg 📏 ]  [ +2,5 ]
        val isKgCrownActive = (crownMode == "kg")
        val kgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0B101B", if (isKgCrownActive || inlineRulerMode == "kg") "#FF5E00" else "#1E293B", 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val minusKgBtn = buildWeightedCellBtn("−${fmtKg(weightStep)}", "#182235", "#38BDF8", 12f) {
            activateTemporaryCrownFocus("kg")
            stepWeight(-weightStep)
        }
        val crownKgSuffix = if (isKgCrownActive) " 👑" else " 📏"
        val kgDisplayTxt = if (liveKg <= 0f) "🧘 BW (0kg)$crownKgSuffix" else "⚖️ ${fmtKg(liveKg)} kg$crownKgSuffix"
        val centerKgBtn = buildWeightedCellBtn(
            kgDisplayTxt,
            if (inlineRulerMode == "kg") "#FF5E00" else "#131D31",
            if (inlineRulerMode == "kg") "#000000" else "#FFFFFF",
            14f
        ) {
            triggerVibration(22)
            activateTemporaryCrownFocus("kg")
            inlineRulerMode = if (inlineRulerMode == "kg") "none" else "kg"
            renderAllInOneWatchInterface(keepScroll = true)
        }
        val plusKgBtn = buildWeightedCellBtn("+${fmtKg(weightStep)}", "#182235", "#38BDF8", 12f) {
            activateTemporaryCrownFocus("kg")
            stepWeight(weightStep)
        }
        kgRow.addView(minusKgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        kgRow.addView(centerKgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.3f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })
        kgRow.addView(plusKgBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        card.addView(kgRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        if (inlineRulerMode == "kg") {
            card.addView(buildAutoCloseInlineWeightRulerBox())
        }

        // E) WIEDERHOLUNGS-ZEILE: [ −1 ]  [ 🔄 10 Wdh. 📏 ]  [ +1 ]
        val isRepsCrownActive = (crownMode == "reps")
        val repsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0B101B", if (isRepsCrownActive || inlineRulerMode == "reps") "#10B981" else "#1E293B", 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val minusRepsBtn = buildWeightedCellBtn(if (isTimedEx) "−5s" else "−1", "#182235", "#10B981", 12f) {
            activateTemporaryCrownFocus("reps")
            stepReps(if (isTimedEx) -5 else -1)
        }
        val crownRepsSuffix = if (isRepsCrownActive) " 👑" else " 📏"
        val repsCenterLbl = if (isTimedEx) "⏱️ ${liveReps}s$crownRepsSuffix" else "🔄 $liveReps Wdh.$crownRepsSuffix"
        val centerRepsBtn = buildWeightedCellBtn(
            repsCenterLbl,
            if (inlineRulerMode == "reps") "#10B981" else "#131D31",
            if (inlineRulerMode == "reps") "#000000" else "#FFFFFF",
            14f
        ) {
            triggerVibration(22)
            activateTemporaryCrownFocus("reps")
            inlineRulerMode = if (inlineRulerMode == "reps") "none" else "reps"
            renderAllInOneWatchInterface(keepScroll = true)
        }
        val plusRepsBtn = buildWeightedCellBtn(if (isTimedEx) "+5s" else "+1", "#182235", "#10B981", 12f) {
            activateTemporaryCrownFocus("reps")
            stepReps(if (isTimedEx) 5 else 1)
        }
        repsRow.addView(minusRepsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        repsRow.addView(centerRepsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.3f).apply {
            leftMargin = dp(3)
            rightMargin = dp(3)
        })
        repsRow.addView(plusRepsBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        card.addView(repsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        if (inlineRulerMode == "reps") {
            card.addView(buildAutoCloseInlineRepsRulerBox())
        }

        // F) Isometrie Halte-Timer
        if (isTimedEx) {
            val isoBtnText = if (isoTimerRunning) "⏸️ HALTEN LÄUFT: ${isoSecondsLeft}s" else "▶️ ${liveReps}s HALTE-TIMER STARTEN"
            isoCountdownTv = buildWeightedCellBtn(
                isoBtnText,
                if (isoTimerRunning) "#F59E0B" else "#0284C7",
                "#000000",
                13f
            ) {
                toggleIsometricExerciseTimer(liveReps)
            }
            card.addView(isoCountdownTv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        } else {
            isoCountdownTv = null
        }

        // G) 1-Tap RPE / RIR + Satz-Typ-Leiste
        val rpeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val rpeItems = listOf(
            Triple(0, "🟢 Leicht", "#10B981"),
            Triple(1, "🎯 Optimal", "#38BDF8"),
            Triple(2, "🔥 Limit", "#F43F5E")
        )
        for ((rIdx, rLbl, rCol) in rpeItems) {
            val sel = (currentRpeMode == rIdx)
            val btn = buildWeightedCellBtn(
                rLbl,
                if (sel) rCol else "#151E30",
                if (sel) "#000000" else "#94A3B8",
                10.5f
            ) {
                triggerVibration(18)
                currentRpeMode = rIdx
                renderAllInOneWatchInterface(keepScroll = true)
            }
            rpeRow.addView(btn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        val typeLbl = when (currentSetType) {
            "drop" -> "🔥Drop"
            "myo" -> "⚡Myo"
            else -> "🏷️Typ"
        }
        val typeBtn = buildWeightedCellBtn(
            typeLbl,
            if (currentSetType != "normal") "#FF5E00" else "#151E30",
            if (currentSetType != "normal") "#000000" else "#E2E8F0",
            10.5f
        ) {
            triggerVibration(20)
            cycleSetTypeMode()
        }
        rpeRow.addView(typeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        card.addView(rpeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // H) GROSSER GRÜNER HAUPT-BUTTON: SATZ ABHAKEN
        val logBtn = Button(this).apply {
            text = "✓ SATZ $currentSetIdx ABHAKEN"
            setTextColor(Color.BLACK)
            textSize = 14.5f
            setSingleLine(true)
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#10B981", "#059669", 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                completeCurrentSet()
            }
        }
        card.addView(logBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // I) Aufklappbare Pro-Hilfe (Scheibenrechner, 4-Stufen Warm-up, 1RM & Supersatz)
        if (showProHelperDrawer) {
            val est1Rm = if (liveKg > 0f) (liveKg * (1f + liveReps / 30f)).roundToInt() else 0
            val w40 = fmtKg(((liveKg * 0.40f) / 2.5f).toInt() * 2.5f)
            val w60 = fmtKg(((liveKg * 0.60f) / 2.5f).toInt() * 2.5f)
            val w78 = fmtKg(((liveKg * 0.78f) / 2.5f).toInt() * 2.5f)
            val w90 = fmtKg(((liveKg * 0.90f) / 2.5f).toInt() * 2.5f)

            val helperBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg("#0D1524", "#38BDF8", 12)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            helperBox.addView(TextView(this).apply {
                text = "🏋️ ${calculateBarbellPlatesPerSide(liveKg)}\n🔥 Warm-up: 10×$w40 | 6×$w60 | 3×$w78 | 1×${w90}kg\n🏆 1RM ~${est1Rm}kg"
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 11f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            })
            val helperBtns = buildProportionalTwoBtnRow(
                "±${fmtKg(weightStep)}kg Schritt", "#182235", "#38BDF8", {
                    weightStep = if (weightStep == 2.5f) 1.25f else if (weightStep == 1.25f) 5.0f else 2.5f
                    saveWatchStateAndRefreshTile()
                    renderAllInOneWatchInterface(keepScroll = true)
                },
                if (supersetPartnerExIdx >= 0) "🔗 Supersatz AUS" else "🔗 Supersatz A1/A2",
                if (supersetPartnerExIdx >= 0) "#0E291E" else "#182235",
                "#10B981",
                {
                    supersetPartnerExIdx = if (supersetPartnerExIdx >= 0) -1 else ((currentExIdx + 1) % exList.size)
                    Toast.makeText(
                        this,
                        if (supersetPartnerExIdx >= 0) "🔗 Supersatz mit Übung ${supersetPartnerExIdx + 1} aktiv!" else "Supersatz beendet",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderAllInOneWatchInterface(keepScroll = true)
                }
            )
            helperBox.addView(helperBtns)
            card.addView(helperBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }

        // J) Untere 3er-Aktionszeile: [🧮 Scheiben] | [🔄 Tauschen] | [↩️ Undo / ⏳ Besetzt]
        val bottomSubRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomSubRow.addView(buildWeightedCellBtn(if (showProHelperDrawer) "🔼 Zu" else "🧮 1RM", "#182235", "#38BDF8", 10.5f) {
            showProHelperDrawer = !showProHelperDrawer
            renderAllInOneWatchInterface(keepScroll = true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f).apply { rightMargin = dp(2) })

        bottomSubRow.addView(buildWeightedCellBtn("🔄 Tauschen", "#142436", "#10B981", 10.5f) {
            triggerVibration(22)
            openExerciseSwapDialog()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f).apply { rightMargin = dp(2) })

        bottomSubRow.addView(buildWeightedCellBtn(
            if (canUndoLastSet) "↩️ Undo" else "⏳ Besetzt",
            if (canUndoLastSet) "#2D1215" else "#2B1F0E",
            if (canUndoLastSet) "#F43F5E" else "#FBBF24",
            10.5f
        ) {
            if (canUndoLastSet) undoLastCompletedSet() else postponeOccupiedExercise()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))

        card.addView(bottomSubRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        return card
    }

    // =========================================================================
    // 🔄 1-TAP BIOMECHANISCHER ÜBUNGSTAUSCH AUF DER UHR (MIT GEWICHTS-UMRECHNUNG)
    // =========================================================================
    private fun getBiomechanicAlternatives(curEx: WatchExercise): List<Pair<String, Float>> {
        val low = curEx.name.lowercase()
        val base = if (liveKg > 0f) liveKg else 60f
        fun round25(v: Float): Float = ((v / 2.5f).roundToInt() * 2.5f).coerceAtLeast(0f)

        return when {
            low.contains("bankdrücken") || low.contains("schrägbank") || low.contains("brust") || low.contains("dips") -> listOf(
                "KH-Bankdrücken (Paar)" to round25(base * 0.42f),
                "Brustpresse Maschine" to round25(base * 0.95f),
                "Weighted Dips / Push-Ups" to round25(base * 0.20f)
            )
            low.contains("kniebeug") || low.contains("beinpresse") || low.contains("squat") && !low.contains("split") -> listOf(
                "Beinpresse 45° Schlitten" to round25(base * 1.45f),
                "Hackenschmidt / V-Squat" to round25(base * 0.90f),
                "Goblet / Front Squat" to round25(base * 0.45f)
            )
            low.contains("kreuzheben") || low.contains("deadlift") || low.contains("beinbeuger") || low.contains("hamstring") -> listOf(
                "Rumänisches KH-Heben" to round25(base * 0.42f),
                "Hip Thrusts (Langhantel)" to round25(base * 1.05f),
                "Beinbeuger Maschine" to round25(base * 0.60f)
            )
            low.contains("latzug") || low.contains("pull-up") || low.contains("klimmzug") -> listOf(
                "Latzug eng (V-Griff)" to round25(base * 1.0f),
                "Klimmzüge / Assistiert" to 0f,
                "Überzüge am Kabelzug" to round25(base * 0.55f)
            )
            low.contains("rudern") || low.contains("row") -> listOf(
                "Brustgestütztes T-Bar Rudern" to round25(base * 0.90f),
                "Einarmiges KH-Rudern" to round25(base * 0.45f),
                "Kabelrudern breit" to round25(base * 0.95f)
            )
            low.contains("schulter") || low.contains("seitheben") -> listOf(
                "Schulterpresse Maschine" to round25(base * 1.15f),
                "Kabel-Seitheben" to round25(base * 0.45f),
                " Arnold Press (KH)" to round25(base * 0.90f)
            )
            else -> listOf(
                "Kabelzug-Variante" to round25(base * 0.85f),
                "Kurzhantel-Variante" to round25(base * 0.45f),
                "Bodyweight / Calisthenics" to 0f
            )
        }
    }

    private fun openExerciseSwapDialog() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]
        val alternatives = getBiomechanicAlternatives(curEx)

        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#070B14"))
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }
        scroll.addView(box)
        var dialogRef: AlertDialog? = null

        box.addView(TextView(this).apply {
            text = "🔄 Übung tauschen\nStatt: ${curEx.name.take(18)}"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(5))
        })

        for ((altName, altKg) in alternatives) {
            val kgTxt = if (altKg <= 0f) "BW" else "${fmtKg(altKg)}kg"
            val altBtn = buildWeightedCellBtn("⚡ $altName ($kgTxt)", "#141E30", "#FFFFFF", 11.5f) {
                triggerVibration(30)
                curEx.name = altName
                curEx.kg = altKg
                liveKg = altKg
                curEx.prevRef = "$kgTxt × ${curEx.targetReps}"
                saveWatchStateAndRefreshTile()
                WearOfflineSyncQueue.enqueueEvent(
                    this,
                    "WATCH_EXERCISE_SWAPPED",
                    "exId=${curEx.id}&newName=$altName&newKg=$altKg"
                )
                try { dialogRef?.dismiss() } catch (e: Exception) {}
                Toast.makeText(this, "✅ Gewechselt auf $altName ($kgTxt)!", Toast.LENGTH_SHORT).show()
                renderAllInOneWatchInterface(keepScroll = true)
            }
            box.addView(altBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })
        }

        val postponeBtn = buildWeightedCellBtn("⏳ Nur ans Ende schieben (Besetzt)", "#2B1F0E", "#FBBF24", 11f) {
            try { dialogRef?.dismiss() } catch (e: Exception) {}
            postponeOccupiedExercise()
        }
        box.addView(postponeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        val cancelBtn = buildWeightedCellBtn("Abbrechen", "#182235", "#94A3B8", 11f) {
            try { dialogRef?.dismiss() } catch (e: Exception) {}
        }
        box.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogRef = AlertDialog.Builder(this).setView(scroll).create()
        dialogRef.show()
    }

    private fun cycleSetTypeMode() {
        val prevType = currentSetType
        currentSetType = when (currentSetType) {
            "normal" -> "drop"
            "drop" -> "myo"
            else -> "normal"
        }
        if (currentSetType == "drop" && prevType != "drop" && liveKg >= 10f) {
            val dropKg = (((liveKg * 0.80f) / 2.5f).roundToInt() * 2.5f).coerceAtLeast(0f)
            setExactWeight(dropKg, autoCloseRuler = false)
            Toast.makeText(this, "🔥 Drop-Satz aktiv (−20% Gewicht = ${fmtKg(dropKg)}kg, 15s Pause)", Toast.LENGTH_SHORT).show()
        } else if (currentSetType == "myo") {
            Toast.makeText(this, "⚡ Myo-Reps aktiv (15s Atempause zwischen Mini-Sätzen)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "🏷️ Regulärer Arbeitssatz aktiv", Toast.LENGTH_SHORT).show()
        }
        renderAllInOneWatchInterface(keepScroll = true)
    }

    // =========================================================================
    // 📏 1-TAP AUTO-CLOSE INLINE-ZAHLENSTRAHL (GEWICHT & WIEDERHOLUNGEN)
    // =========================================================================
    private fun buildAutoCloseInlineWeightRulerBox(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg("#09101E", "#FF5E00", 14)
            setPadding(dp(6), dp(5), dp(6), dp(6))
        }

        box.addView(TextView(this).apply {
            text = "📏 Zahl antippen = Sofort übernehmen & schließen (👑 Krone aktiv)"
            setTextColor(Color.parseColor("#FBBF24"))
            textSize = 9.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(3))
        })

        val inlineScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val inlineTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }

        val weightStops = mutableListOf(0f, 2.5f, 5f, 7.5f, 10f, 12.5f, 15f, 17.5f)
        var w = 20f
        while (w <= 120f) {
            weightStops.add(w)
            w += 2.5f
        }
        var wBig = 125f
        while (wBig <= 220f) {
            weightStops.add(wBig)
            wBig += 5f
        }

        var activeChipView: View? = null
        for (wVal in weightStops) {
            val isSelected = abs(wVal - liveKg) < 0.15f
            val isTen = (wVal % 10f == 0f)
            val chipLbl = if (wVal == 0f) "BW" else fmtKg(wVal)
            val chip = TextView(this).apply {
                text = chipLbl
                setTextColor(Color.parseColor(if (isSelected) "#000000" else "#FFFFFF"))
                textSize = if (isSelected) 15f else 13f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(
                    if (isSelected) "#FF5E00" else if (isTen) "#1A2740" else "#121A2B",
                    if (isSelected) "#FFFFFF" else if (isTen) "#38BDF8" else "#26344D",
                    11
                )
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    triggerVibration(25)
                    setExactWeight(wVal, autoCloseRuler = true)
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), 0, dp(2), 0)
            inlineTrack.addView(chip, lp)
            if (isSelected) activeChipView = chip
        }
        inlineScroll.addView(inlineTrack)
        box.addView(inlineScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (activeChipView != null) {
            inlineScroll.post {
                val scrollX = (activeChipView.left - (inlineScroll.width / 2) + (activeChipView.width / 2)).coerceAtLeast(0)
                inlineScroll.scrollTo(scrollX, 0)
            }
        }

        val quickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        for (anchor in listOf(20f, 40f, 60f, 80f, 100f)) {
            val aBtn = buildWeightedCellBtn("${anchor.toInt()}", "#162032", "#38BDF8", 11f) {
                triggerVibration(20)
                setExactWeight(anchor, autoCloseRuler = true)
            }
            quickRow.addView(aBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        val numpadBtn = buildWeightedCellBtn("🔢 0–9", "#182235", "#FBBF24", 11f) {
            triggerVibration(20)
            inlineRulerMode = "none"
            openNumberLinePickerDialog(initialTab = "kg")
        }
        quickRow.addView(numpadBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.25f).apply {
            setMargins(dp(1), 0, dp(1), 0)
        })
        box.addView(quickRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val lpBox = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpBox.bottomMargin = dp(4)
        box.layoutParams = lpBox
        return box
    }

    private fun buildAutoCloseInlineRepsRulerBox(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg("#091512", "#10B981", 14)
            setPadding(dp(6), dp(5), dp(6), dp(6))
        }

        box.addView(TextView(this).apply {
            text = "📏 Wdh. antippen = Sofort übernehmen & schließen (👑 Krone aktiv)"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 9.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(3))
        })

        val repsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val repsTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }

        val repStops = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 25, 30, 40, 45, 60)
        var selectedRepView: View? = null
        for (rVal in repStops) {
            val isSel = (rVal == liveReps)
            val chip = TextView(this).apply {
                text = "$rVal"
                setTextColor(Color.parseColor(if (isSel) "#000000" else "#FFFFFF"))
                textSize = if (isSel) 15f else 13f
                setSingleLine(true)
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(
                    if (isSel) "#10B981" else "#121A2B",
                    if (isSel) "#FFFFFF" else "#26344D",
                    11
                )
                setPadding(dp(11), dp(6), dp(11), dp(6))
                setOnClickListener {
                    triggerVibration(25)
                    setExactReps(rVal, autoCloseRuler = true)
                }
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), 0, dp(2), 0)
            repsTrack.addView(chip, lp)
            if (isSel) selectedRepView = chip
        }
        repsScroll.addView(repsTrack)
        box.addView(repsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (selectedRepView != null) {
            repsScroll.post {
                val scrollX = (selectedRepView.left - (repsScroll.width / 2) + (selectedRepView.width / 2)).coerceAtLeast(0)
                repsScroll.scrollTo(scrollX, 0)
            }
        }

        val lpBox = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpBox.bottomMargin = dp(4)
        box.layoutParams = lpBox
        return box
    }

    // =========================================================================
    // 🔢 OPTIONALER 0-9 TASCHENRECHNER-DIALOG (FÜR FREIE ZAHLENEINGABE)
    // =========================================================================
    private fun openNumberLinePickerDialog(initialTab: String = "kg") {
        var activeTab = initialTab
        var typedBuffer = ""

        val dialogScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#050811"))
        }
        val rootBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(10), dp(12), dp(16))
        }
        dialogScroll.addView(rootBox)
        var dialogRef: AlertDialog? = null

        fun rebuildNumpadUI() {
            rootBox.removeAllViews()

            val tabRow = buildProportionalTwoBtnRow(
                "⚖️ ${fmtKg(liveKg)} kg",
                if (activeTab == "kg") "#FF5E00" else "#162032",
                if (activeTab == "kg") "#000000" else "#E2E8F0",
                {
                    activeTab = "kg"
                    typedBuffer = ""
                    rebuildNumpadUI()
                },
                "🔄 $liveReps Wdh.",
                if (activeTab == "reps") "#10B981" else "#162032",
                if (activeTab == "reps") "#000000" else "#E2E8F0",
                {
                    activeTab = "reps"
                    typedBuffer = ""
                    rebuildNumpadUI()
                }
            )
            rootBox.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })

            val displayTxt = if (typedBuffer.isEmpty()) {
                if (activeTab == "kg") "${fmtKg(liveKg)} kg" else "$liveReps Wdh."
            } else {
                if (activeTab == "kg") "$typedBuffer kg" else "$typedBuffer Wdh."
            }
            rootBox.addView(TextView(this).apply {
                text = displayTxt
                setTextColor(Color.parseColor("#FF5E00"))
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(4))
            })

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf(if (activeTab == "kg") ",5" else "00", "0", "⌫")
            )
            for (r in rows) {
                val rLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                for (key in r) {
                    val kBtn = buildWeightedCellBtn(key, "#182235", "#FFFFFF", 14f) {
                        triggerVibration(18)
                        when (key) {
                            "⌫" -> if (typedBuffer.isNotEmpty()) typedBuffer = typedBuffer.dropLast(1)
                            ",5" -> if (!typedBuffer.contains(".")) typedBuffer = (if (typedBuffer.isEmpty()) "0" else typedBuffer) + ".5"
                            "00" -> typedBuffer += "0"
                            else -> if (typedBuffer.length < 5) typedBuffer += key
                        }
                        val parsed = typedBuffer.toFloatOrNull()
                        if (parsed != null) {
                            if (activeTab == "kg") {
                                liveKg = parsed.coerceIn(0f, 500f)
                                trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
                            } else {
                                liveReps = parsed.toInt().coerceIn(1, 300)
                                trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
                            }
                            saveWatchStateAndRefreshTile()
                        }
                        rebuildNumpadUI()
                    }
                    rLayout.addView(kBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                }
                rootBox.addView(rLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }

            val actionRow = buildProportionalTwoBtnRow(
                "✓ Übernehmen",
                "#38BDF8",
                "#000000",
                {
                    renderAllInOneWatchInterface(keepScroll = true)
                    try { dialogRef?.dismiss() } catch (e: Exception) {}
                },
                "✓ Satz loggen",
                "#10B981",
                "#000000",
                {
                    try { dialogRef?.dismiss() } catch (e: Exception) {}
                    completeCurrentSet()
                }
            )
            rootBox.addView(actionRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
        }

        rebuildNumpadUI()
        dialogRef = AlertDialog.Builder(this).setView(dialogScroll).create()
        dialogRef.show()
    }

    // =========================================================================
    // SEKTION 2: 📋 TRAININGSPLAN, TAGESFORM & 30-MIN EXPRESS (AKKORDEON)
    // =========================================================================
    private fun buildSection2TrainingPlanAndReadinessCard(): View {
        val card = createSectionCard("#0E1524", "#38BDF8")
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val doneTotal = exList.sumOf { it.doneSets }
        val targetTotal = exList.sumOf { it.sets }

        val arrow = if (expandPlanSection) "▾" else "▸"
        val headerTv = buildSectionTitle("📋 PLAN & TAGESFORM ($doneTotal/$targetTotal) $arrow", "#38BDF8")
        headerTv.setOnClickListener {
            triggerVibration(18)
            expandPlanSection = !expandPlanSection
            renderAllInOneWatchInterface(keepScroll = true)
        }
        card.addView(headerTv)

        val readLabel = when (readinessMode) {
            1 -> "🟡 -5%"
            2 -> "🔴 -15%"
            else -> "🟢 100%"
        }
        val readCol = when (readinessMode) {
            1 -> "#FBBF24"
            2 -> "#F43F5E"
            else -> "#10B981"
        }
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ctrlRow.addView(buildWeightedCellBtn("📅 ${dayPair.first.take(10)} ⟳", "#182235", "#FFFFFF", 10.5f) {
            triggerVibration(22)
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            switchExerciseSafely(0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f).apply { rightMargin = dp(2) })

        ctrlRow.addView(buildWeightedCellBtn(readLabel, "#101827", readCol, 10.5f) {
            triggerVibration(25)
            applyReadinessAutoregulation((readinessMode + 1) % 3)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f).apply { rightMargin = dp(2) })

        ctrlRow.addView(buildWeightedCellBtn(
            if (expressWorkout30Min) "⚡30m AN" else "⚡30m",
            if (expressWorkout30Min) "#FF5E00" else "#151E30",
            if (expressWorkout30Min) "#000000" else "#38BDF8",
            10.5f
        ) {
            triggerVibration(25)
            expressWorkout30Min = !expressWorkout30Min
            supersetPartnerExIdx = if (expressWorkout30Min && exList.size > 1) ((currentExIdx + 1) % exList.size) else -1
            Toast.makeText(
                this,
                if (expressWorkout30Min) "⚡ 30-Min Express aktiv (Supersatz-Wechsel & 60s Pause)!" else "Reguläres Trainingstempo aktiv",
                Toast.LENGTH_SHORT
            ).show()
            renderAllInOneWatchInterface(keepScroll = true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))

        card.addView(ctrlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = if (expandPlanSection) dp(4) else 0
        })

        if (!expandPlanSection) return card

        exList.forEachIndexed { idx, ex ->
            val isCur = (idx == currentExIdx)
            val isDone = (ex.doneSets >= ex.sets)
            val isTimed = isIsometricOrTimedExercise(ex.name)
            val badge = if (isDone) "✅ ${ex.sets}/${ex.sets}" else "${ex.doneSets}/${ex.sets}"
            val specStr = if (isTimed) "⏱️ ${ex.targetReps}s" else "${fmtKg(ex.kg)}kg × ${ex.targetReps}"
            val bgHex = when {
                isCur -> "#FF5E00"
                isDone -> "#0D281E"
                else -> "#151E30"
            }
            val txtHex = when {
                isCur -> "#000000"
                isDone -> "#10B981"
                else -> "#F1F5F9"
            }
            val exBtn = TextView(this).apply {
                text = "${idx + 1}. ${ex.name.take(16)} • $specStr ($badge)"
                setTextColor(Color.parseColor(txtHex))
                textSize = 11.5f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(bgHex, if (isCur) "#FFFFFF" else "#26324B", 12)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    triggerVibration(22)
                    switchExerciseSafely(idx)
                    scrollToSection(secWorkoutView)
                }
            }
            card.addView(exBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(3)
            })
        }

        return card
    }

    // =========================================================================
    // SEKTION 3: ⏱️ CARDIO, LIVE-PACE, 5-PULS-ZONEN & WOD-TIMER (AKKORDEON)
    // =========================================================================
    private fun buildSection3CardioGpsAndWodCard(): View {
        val card = createSectionCard("#0E1524", "#F59E0B")
        val isActive = expandCardioSection || gpsRunning || wodRunning
        val (zName, zCol) = getHeartRateZoneInfo(liveBpm)
        val arrow = if (isActive) "▾" else "▸"
        val titleTv = buildSectionTitle("⏱️ CARDIO, PACE & WOD (❤️ $liveBpm $zName) $arrow", "#F59E0B")
        titleTv.setOnClickListener {
            triggerVibration(18)
            expandCardioSection = !expandCardioSection
            renderAllInOneWatchInterface(keepScroll = true)
        }
        card.addView(titleTv)

        if (!isActive) return card

        // Farbige 5-Puls-Zonen-Leiste oben im Cardio/WOD-Modul
        val zoneBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(4))
        }
        val zones = listOf(
            Triple("Z1", "#38BDF8", liveBpm < 115),
            Triple("Z2", "#10B981", liveBpm in 115..134),
            Triple("Z3", "#FBBF24", liveBpm in 135..152),
            Triple("Z4", "#FF5E00", liveBpm in 153..171),
            Triple("Z5", "#F43F5E", liveBpm >= 172)
        )
        for ((zLbl, col, active) in zones) {
            val zView = TextView(this).apply {
                text = if (active) "●$zLbl" else zLbl
                setTextColor(Color.parseColor(if (active) "#000000" else col))
                textSize = 9.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(if (active) col else "#121A2A", col, 8)
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            zoneBar.addView(zView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(1), 0, dp(1), 0)
            })
        }
        card.addView(zoneBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (page2WodMode) {
            val presetNames = listOf("Tabata 20/10", "Zirkel 45/15", "EMOM 60s")
            val topRow = buildProportionalTwoBtnRow(
                "🔥 WOD/Zirkel",
                "#182235",
                "#FF5E00",
                {
                    triggerVibration(20)
                    page2WodMode = false
                    renderAllInOneWatchInterface(keepScroll = true)
                },
                "${presetNames[wodPresetIdx]} ⟳",
                "#101827",
                "#38BDF8",
                {
                    triggerVibration(20)
                    wodPresetIdx = (wodPresetIdx + 1) % presetNames.size
                    stopWodTimer(reset = true)
                }
            )
            card.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })

            val phaseTxt = if (wodIsWorkPhase) "🔥 POWER / ARBEIT" else "🧊 PAUSE / WECHSEL"
            val phaseCol = if (wodIsWorkPhase) "#10B981" else "#38BDF8"
            wodMetricsTv = TextView(this).apply {
                text = "$phaseTxt (Runde $wodRound)\n⏱️ ${wodSecLeft}s   •   ❤️ $liveBpm ($zName)"
                setTextColor(Color.parseColor(phaseCol))
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(3), 0, dp(5))
            }
            card.addView(wodMetricsTv)

            val ctrlRow = buildProportionalTwoBtnRow(
                if (wodRunning) "⏸️ Pause" else "▶️ Start WOD",
                if (wodRunning) "#F59E0B" else "#10B981",
                "#000000",
                {
                    triggerVibration(35)
                    toggleWodTimer()
                },
                "🔄 Reset",
                "#182235",
                "#94A3B8",
                {
                    triggerVibration(25)
                    stopWodTimer(reset = true)
                }
            )
            card.addView(ctrlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            val topRow = buildProportionalTwoBtnRow(
                "📡 GPS & Pace",
                "#182235",
                "#FF5E00",
                {
                    triggerVibration(20)
                    page2WodMode = true
                    renderAllInOneWatchInterface(keepScroll = true)
                },
                "$activeSportLabel ⟳",
                "#101827",
                "#38BDF8",
                {
                    val sports = listOf("🏃 Laufen", "🚴 Rad", "⚡ Hyrox", "🚶 Gehen")
                    activeSportLabel = sports[(sports.indexOf(activeSportLabel) + 1) % sports.size]
                    renderAllInOneWatchInterface(keepScroll = true)
                }
            )
            card.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            })

            val m = (gpsSeconds / 60).toString().padStart(2, '0')
            val s = (gpsSeconds % 60).toString().padStart(2, '0')
            val kcal = (gpsDistKm * 68f + (gpsSeconds / 60f) * 5f).roundToInt()
            val paceStr = formatPaceMinPerKm(gpsSeconds, gpsDistKm)

            cardioMetricsTv = TextView(this).apply {
                text = "📡 ${String.format("%.2f", gpsDistKm)} km  •  ⚡ $paceStr /km\n⏱️ $m:$s  •  🔥 $kcal kcal"
                setTextColor(Color.WHITE)
                textSize = 13.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(3), 0, dp(5))
            }
            card.addView(cardioMetricsTv)

            val gRow = buildProportionalTwoBtnRow(
                if (gpsRunning) "⏸️ Stopp" else "▶️ GPS Start",
                if (gpsRunning) "#F59E0B" else "#10B981",
                "#000000",
                {
                    triggerVibration(35)
                    toggleGpsTracking()
                },
                "💾 Loggen",
                "#182235",
                "#38BDF8",
                {
                    triggerVibration(35)
                    WearOfflineSyncQueue.enqueueEvent(
                        this,
                        "WATCH_CARDIO_SAVED",
                        "sport=$activeSportLabel&distKm=${String.format("%.2f", gpsDistKm)}&pace=$paceStr&kcal=$kcal&bpm=$liveBpm"
                    ) { ok ->
                        lastCloudSyncOk = ok
                        runOnUiThread { renderAllInOneWatchInterface(keepScroll = true) }
                    }
                    Toast.makeText(this, "✅ Cardio-Einheit gespeichert & synchronisiert!", Toast.LENGTH_SHORT).show()
                }
            )
            card.addView(gRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        return card
    }

    private fun formatPaceMinPerKm(sec: Int, distKm: Float): String {
        if (distKm < 0.03f || sec <= 0) return "5:30"
        val secPerKm = (sec / distKm).roundToInt().coerceIn(150, 1200)
        val pm = secPerKm / 60
        val ps = (secPerKm % 60).toString().padStart(2, '0')
        return "$pm:$ps"
    }

    // =========================================================================
    // SEKTION 4: 🍎 ERNÄHRUNG, ±150 KCAL SNACK & WASSER-TRACKER (AKKORDEON)
    // =========================================================================
    private fun buildSection4NutritionAndWaterCard(): View {
        val card = createSectionCard("#0E1524", "#10B981")
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val rawOpenKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }
        val openKcal = (rawOpenKcal - manualSnackDeltaKcal).coerceAtLeast(0)
        val doneMealsCnt = dailyMeals.count { it.done }

        val arrow = if (expandNutritionSection) "▾" else "▸"
        val titleTv = buildSectionTitle("🍎 ERNÄHRUNG (${openKcal}kcal • $doneMealsCnt/${dailyMeals.size}) $arrow", "#10B981")
        titleTv.setOnClickListener {
            triggerVibration(18)
            expandNutritionSection = !expandNutritionSection
            renderAllInOneWatchInterface(keepScroll = true)
        }
        card.addView(titleTv)

        val waterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, if (expandNutritionSection) dp(5) else 0)
        }
        waterRow.addView(buildWeightedCellBtn("💧 +250ml (${String.format("%.2f", drankL).replace(".", ",")}L)", "#10263B", "#38BDF8", 11f) {
            addWaterLiters(0.25f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.55f).apply { rightMargin = dp(3) })
        waterRow.addView(buildWeightedCellBtn("+500ml", "#10263B", "#38BDF8", 11f) {
            addWaterLiters(0.50f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.95f).apply { rightMargin = dp(3) })
        waterRow.addView(buildWeightedCellBtn(if (expandNutritionSection) "🔼" else "🍴Menü", "#182235", "#10B981", 11f) {
            expandNutritionSection = !expandNutritionSection
            renderAllInOneWatchInterface(keepScroll = true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f))
        card.addView(waterRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (!expandNutritionSection) return card

        dailyMeals.forEachIndexed { idx, meal ->
            val mBtn = TextView(this).apply {
                text = "${if (meal.done) "✅" else "☐"} ${meal.slot}: ${meal.title.take(16)} (${meal.kcal})"
                setTextColor(Color.parseColor(if (meal.done) "#10B981" else "#F8FAFC"))
                textSize = 11.5f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(if (meal.done) "#0D281E" else "#151E30", if (meal.done) "#10B981" else "#26324B", 12)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    triggerVibration(22)
                    meal.done = !meal.done
                    prefs.edit().putBoolean("meal_done_$idx", meal.done).apply()
                    saveWatchStateAndRefreshTile()
                    WearOfflineSyncQueue.enqueueEvent(
                        this@WearMainActivity,
                        "WATCH_MEAL_TOGGLED",
                        "idx=$idx&done=${if (meal.done) 1 else 0}&openKcal=$openKcal"
                    ) { ok ->
                        lastCloudSyncOk = ok
                    }
                    renderAllInOneWatchInterface(keepScroll = true)
                }
            }
            card.addView(mBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(3)
            })
        }

        // Spontan-Snack-Korrektur (±150 kcal für Kaffee/Riegel/Obst zwischendurch)
        val snackRow = buildProportionalTwoBtnRow(
            "☕ +150 kcal Snack",
            "#182235",
            "#FBBF24",
            {
                triggerVibration(20)
                manualSnackDeltaKcal += 150
                prefs.edit().putInt("manual_snack_delta_kcal", manualSnackDeltaKcal).apply()
                saveWatchStateAndRefreshTile()
                renderAllInOneWatchInterface(keepScroll = true)
            },
            "↩️ +150 kcal frei",
            "#182235",
            "#94A3B8",
            {
                triggerVibration(20)
                manualSnackDeltaKcal = (manualSnackDeltaKcal - 150).coerceAtLeast(-600)
                prefs.edit().putInt("manual_snack_delta_kcal", manualSnackDeltaKcal).apply()
                saveWatchStateAndRefreshTile()
                renderAllInOneWatchInterface(keepScroll = true)
            }
        )
        card.addView(snackRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        return card
    }

    // =========================================================================
    // SEKTION 5: 🛒 EINKAUFSLISTE (MIT BRING!-AUTO-SORTIERUNG) & 🔗 LIVE-SYNC
    // =========================================================================
    private fun buildSection5ShoppingAndCloudSyncCard(): View {
        val card = createSectionCard("#0E1524", "#38BDF8")
        val openShopCnt = shoppingItems.count { !it.done }

        val arrow = if (expandShopSection) "▾" else "▸"
        val titleTv = buildSectionTitle("🛒 EINKAUF ($openShopCnt offen) & SYNC $arrow", "#38BDF8")
        titleTv.setOnClickListener {
            triggerVibration(18)
            expandShopSection = !expandShopSection
            renderAllInOneWatchInterface(keepScroll = true)
        }
        card.addView(titleTv)

        if (expandShopSection) {
            if (shoppingItems.isEmpty()) {
                card.addView(TextView(this).apply {
                    text = "Keine offenen Einkäufe."
                    setTextColor(Color.parseColor("#94A3B8"))
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                })
            } else {
                // Bring!-Auto-Sortierung: Offene Artikel stehen immer ganz oben, erledigte wandern nach unten!
                val sortedShop = shoppingItems.sortedWith(compareBy<WatchShopItem> { it.done }.thenBy { it.origIdx })
                for (item in sortedShop) {
                    val sBtn = TextView(this).apply {
                        text = "${if (item.done) "✅ " else "☐ "}${item.title}"
                        setTextColor(Color.parseColor(if (item.done) "#10B981" else "#FFFFFF"))
                        textSize = 11.5f
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setTypeface(null, Typeface.BOLD)
                        background = roundedBg(if (item.done) "#0D281E" else "#151E30", if (item.done) "#10B981" else "#26324B", 12)
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                        setOnClickListener {
                            triggerVibration(20)
                            item.done = !item.done
                            prefs.edit().putBoolean("shop_done_${item.origIdx}", item.done).apply()
                            saveWatchStateAndRefreshTile()
                            WearOfflineSyncQueue.enqueueEvent(
                                this@WearMainActivity,
                                "WATCH_SHOP_TOGGLED",
                                "idx=${item.origIdx}&done=${if (item.done) 1 else 0}"
                            )
                            renderAllInOneWatchInterface(keepScroll = true)
                        }
                    }
                    card.addView(sBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(3)
                    })
                }
            }
        }

        val syncRow = buildProportionalTwoBtnRow(
            if (isSyncInProgress) "⏳ Sync..." else "🔄 Live-Sync",
            "#38BDF8",
            "#000000",
            {
                triggerVibration(30)
                triggerBackgroundCloudPull(silent = false)
            },
            if (expandShopSection) "🔑 Key/Setup" else "🛒 Liste ($openShopCnt)",
            "#182235",
            "#E2E8F0",
            {
                if (expandShopSection) {
                    openWatchPlanSyncDialog()
                } else {
                    expandShopSection = true
                    renderAllInOneWatchInterface(keepScroll = true)
                }
            }
        )
        card.addView(syncRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(2)
        })

        return card
    }

    // =========================================================================
    // HELPER & AKTIONEN FÜR WORKOUT, ZAHLENSTRAHL, PR-DETEKTOR & LIVE-SYNC
    // =========================================================================
    private fun checkIfCurrentIsNewPR(prevRef: String, curKg: Float, curReps: Int): Boolean {
        if (curKg <= 0f) return false
        return try {
            val regex = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*kg\s*[×x*]\s*([0-9]+)""", RegexOption.IGNORE_CASE)
            val m = regex.find(prevRef) ?: return false
            val pKg = m.groupValues[1].replace(",", ".").toFloatOrNull() ?: return false
            val pReps = m.groupValues[2].toIntOrNull() ?: return false
            if (pKg <= 0f) return false
            (curKg > pKg + 0.1f) || (abs(curKg - pKg) <= 0.1f && curReps > pReps)
        } catch (e: Exception) {
            false
        }
    }

    private fun switchExerciseSafely(newExIdx: Int) {
        val exList = trainingDays[currentDayIdx].second
        if (exList.isEmpty()) return
        isoTimer?.cancel()
        isoTimerRunning = false
        inlineRulerMode = "none"
        currentSetType = "normal"
        suggestBackoffForNextSet = false
        currentExIdx = (newExIdx + exList.size) % exList.size
        val curEx = exList[currentExIdx]
        currentSetIdx = (curEx.doneSets + 1).coerceAtMost(curEx.sets)
        liveKg = if (isIsometricOrTimedExercise(curEx.name) && curEx.kg == 44.0f) 0.0f else curEx.kg
        liveReps = curEx.targetReps
        saveWatchStateAndRefreshTile()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun setExactWeight(newKg: Float, autoCloseRuler: Boolean = false) {
        liveKg = ((newKg.coerceAtLeast(0f)) * 4f).roundToInt() / 4f
        trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        if (autoCloseRuler) {
            inlineRulerMode = "none"
        }
        saveWatchStateAndRefreshTile()
        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_KG_CHANGED",
            "exId=${trainingDays[currentDayIdx].second[currentExIdx].id}&kg=$liveKg"
        )
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun setExactReps(newReps: Int, autoCloseRuler: Boolean = false) {
        liveReps = newReps.coerceIn(1, 300)
        trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
        if (autoCloseRuler) {
            inlineRulerMode = "none"
        }
        saveWatchStateAndRefreshTile()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun stepWeight(delta: Float) {
        setExactWeight(liveKg + delta, autoCloseRuler = false)
    }

    private fun stepReps(delta: Int) {
        setExactReps(liveReps + delta, autoCloseRuler = false)
    }

    private fun addWaterLiters(deltaL: Float) {
        triggerVibration(25)
        val curL = prefs.getFloat("drank_liters", 0.0f)
        val nextL = ((curL + deltaL).coerceAtLeast(0.0f) * 100f).roundToInt() / 100f
        prefs.edit().putFloat("drank_liters", nextL).apply()
        saveWatchStateAndRefreshTile()
        val addedMl = (deltaL * 1000f).roundToInt()
        WearOfflineSyncQueue.enqueueEvent(this, "WATCH_WATER_ADDED", "addedMl=$addedMl&totalL=$nextL") { ok ->
            lastCloudSyncOk = ok
        }
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun toggleIsometricExerciseTimer(targetSec: Int) {
        triggerVibration(35)
        if (isoTimerRunning) {
            isoTimer?.cancel()
            isoTimerRunning = false
            renderAllInOneWatchInterface(keepScroll = true)
            return
        }
        isoTimerRunning = true
        isoSecondsLeft = targetSec.coerceAtLeast(5)
        val endMs = System.currentTimeMillis() + isoSecondsLeft * 1000L
        renderAllInOneWatchInterface(keepScroll = true)

        isoTimer = object : CountDownTimer(isoSecondsLeft * 1000L, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                val rem = ((endMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                if (rem != isoSecondsLeft) {
                    isoSecondsLeft = rem
                    isoCountdownTv?.text = "⏸️ HALTEN LÄUFT: ${isoSecondsLeft}s"
                }
            }

            override fun onFinish() {
                isoTimerRunning = false
                isoSecondsLeft = 0
                triggerRestAlarmPattern()
                completeCurrentSet()
            }
        }.start()
    }

    private fun completeCurrentSet() {
        triggerVibration(70)
        isoTimer?.cancel()
        isoTimerRunning = false
        inlineRulerMode = "none"
        crownMode = "scroll"

        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]

        canUndoLastSet = true
        undoDayIdx = currentDayIdx
        undoExIdx = currentExIdx
        undoSetIdx = currentSetIdx
        undoKg = liveKg
        undoReps = liveReps

        curEx.doneSets = currentSetIdx.coerceAtMost(curEx.sets)
        todayMovedVolumeKg += (liveKg * liveReps).roundToInt()

        val doneToday = prefs.getInt("done_sets_today", 0) + 1
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        val rpeTag = when (currentRpeMode) { 0 -> "easy"; 2 -> "limit"; else -> "optimal" }
        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_SET_COMPLETED",
            "exId=${curEx.id}&setIdx=$currentSetIdx&kg=$liveKg&reps=$liveReps&dayIdx=$currentDayIdx&rpe=$rpeTag&type=$currentSetType"
        ) { ok ->
            lastCloudSyncOk = ok
        }

        val (autoRestSec, autoRestLbl) = calculateOptimalRestSeconds(curEx, isSupersetJump = false)
        lastAutoRestLabel = autoRestLbl
        currentSetType = "normal"

        if (currentRpeMode == 0 && liveKg > 0f && !isIsometricOrTimedExercise(curEx.name)) {
            liveKg += weightStep
            curEx.kg = liveKg
            currentRpeMode = 1
            suggestBackoffForNextSet = false
        } else if (currentRpeMode == 2 && currentSetIdx < curEx.sets && liveKg >= 20f) {
            suggestBackoffForNextSet = true
            currentRpeMode = 1
        } else {
            suggestBackoffForNextSet = false
        }

        // Supersatz-Weiche (A1 <-> A2 oder 30-Min Express)
        if (supersetPartnerExIdx in exList.indices && supersetPartnerExIdx != currentExIdx) {
            val partnerEx = exList[supersetPartnerExIdx]
            if (partnerEx.doneSets < partnerEx.sets) {
                val prevIdx = currentExIdx
                currentExIdx = supersetPartnerExIdx
                supersetPartnerExIdx = prevIdx
                val nowEx = exList[currentExIdx]
                currentSetIdx = (nowEx.doneSets + 1).coerceAtMost(nowEx.sets)
                liveKg = nowEx.kg
                liveReps = nowEx.targetReps
                saveWatchStateAndRefreshTile()
                val (supRestSec, supRestLbl) = calculateOptimalRestSeconds(curEx, isSupersetJump = true)
                lastAutoRestLabel = supRestLbl
                startRestCountdown(supRestSec)
                return
            }
        }

        if (currentSetIdx < curEx.sets) {
            currentSetIdx += 1
        } else {
            suggestBackoffForNextSet = false
            val nextOpenIdx = exList.indexOfFirst { it.doneSets < it.sets }
            if (nextOpenIdx >= 0) {
                currentExIdx = nextOpenIdx
                val nextEx = exList[currentExIdx]
                currentSetIdx = (nextEx.doneSets + 1).coerceAtMost(nextEx.sets)
                liveKg = if (isIsometricOrTimedExercise(nextEx.name) && nextEx.kg == 44.0f) 0.0f else nextEx.kg
                liveReps = nextEx.targetReps
            } else {
                saveWatchStateAndRefreshTile()
                triggerRestAlarmPattern()
                renderAllInOneWatchInterface(keepScroll = true)
                return
            }
        }

        saveWatchStateAndRefreshTile()
        startRestCountdown(autoRestSec)
    }

    private fun undoLastCompletedSet() {
        if (!canUndoLastSet) return
        triggerVibration(40)
        canUndoLastSet = false
        suggestBackoffForNextSet = false
        currentDayIdx = undoDayIdx
        currentExIdx = undoExIdx
        currentSetIdx = undoSetIdx
        liveKg = undoKg
        liveReps = undoReps

        val curEx = trainingDays[currentDayIdx].second[currentExIdx]
        curEx.doneSets = (undoSetIdx - 1).coerceAtLeast(0)
        todayMovedVolumeKg = (todayMovedVolumeKg - (undoKg * undoReps).roundToInt()).coerceAtLeast(0)

        val doneToday = (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        WearOfflineSyncQueue.removeLastSetEventIfUndo(this, curEx.id, undoSetIdx)
        stopRestCountdown()
        saveWatchStateAndRefreshTile()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun postponeOccupiedExercise() {
        triggerVibration(35)
        val exList = trainingDays[currentDayIdx].second
        if (exList.size > 1) {
            val moved = exList.removeAt(currentExIdx)
            exList.add(moved)
            val nowCur = exList[currentExIdx]
            currentSetIdx = (nowCur.doneSets + 1).coerceAtMost(nowCur.sets)
            liveKg = nowCur.kg
            liveReps = nowCur.targetReps
            saveWatchStateAndRefreshTile()
            renderAllInOneWatchInterface(keepScroll = true)
        }
    }

    private fun applyReadinessAutoregulation(newMode: Int) {
        val prevFactor = when (readinessMode) { 1 -> 0.95f; 2 -> 0.85f; else -> 1.0f }
        val nextFactor = when (newMode) { 1 -> 0.95f; 2 -> 0.85f; else -> 1.0f }
        readinessMode = newMode
        if (liveKg > 0f) {
            val baseKg = liveKg / prevFactor
            liveKg = (((baseKg * nextFactor) * 4f).roundToInt() / 4f).coerceAtLeast(0f)
            trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        }
        val msg = when (readinessMode) {
            1 -> "🟡 Tagesform -5% (Schicht/Müde aktiv)"
            2 -> "🔴 Gelenk-Schutz -15% (Mini-Deload aktiv)"
            else -> "🟢 Tagesform 100% (Reguläres Gewicht)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        saveWatchStateAndRefreshTile()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    // =========================================================================
    // PAUSEN-TIMER INKL. FORTSCHRITTS-BALKEN & HR-RECOVERY (IN-PLACE UPDATES)
    // =========================================================================
    private fun startRestCountdown(seconds: Int) {
        restTimer?.cancel()
        restSecondsLeft = seconds
        restTotalSeconds = seconds.coerceAtLeast(1)
        hrRecoveryNotifiedThisRest = false
        restEndTimestampMs = System.currentTimeMillis() + (seconds * 1000L)
        prefs.edit()
            .putLong("last_rest_start_ms", System.currentTimeMillis())
            .putInt("rest_target_sec", seconds)
            .apply()

        renderAllInOneWatchInterface(keepScroll = true)

        restTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remSec = ((restEndTimestampMs - System.currentTimeMillis()) / 1000L).toInt()
                restSecondsLeft = remSec.coerceAtLeast(0)
                val m = (restSecondsLeft / 60).toString().padStart(2, '0')
                val s = (restSecondsLeft % 60).toString().padStart(2, '0')
                val isRec = (liveBpm < hrRecoveryThresholdBpm)

                if (isRec && !hrRecoveryNotifiedThisRest && restSecondsLeft < (seconds - 15)) {
                    hrRecoveryNotifiedThisRest = true
                    triggerVibration(45)
                }

                if (restCountdownTv != null) {
                    restCountdownTv?.text = "⏱️ PAUSE: $m:$s  ($lastAutoRestLabel)"
                    restProgressBarView?.progressPct = (restSecondsLeft.toFloat() / restTotalSeconds.toFloat()).coerceIn(0f, 1f)
                    restProgressBarView?.fillHex = if (isRec) "#10B981" else "#FF5E00"
                    restProgressBarView?.invalidate()
                    restHrRecoveryTv?.text = if (isRec) {
                        "💚 Puls erholt ($liveBpm BPM) – Bereit für Satz $currentSetIdx!"
                    } else {
                        "❤️ Puls $liveBpm BPM (Ziel: unter $hrRecoveryThresholdBpm BPM)"
                    }
                    restHrRecoveryTv?.setTextColor(Color.parseColor(if (isRec) "#10B981" else "#FBBF24"))
                    val recIcon = if (isRec) "💚 Erholt" else "❤️ $liveBpm"
                    watchfacePulsePillTv?.text = "⏱️ Pause $m:$s ($recIcon) ➔ Satz $currentSetIdx"
                } else {
                    renderAllInOneWatchInterface(keepScroll = true)
                }
            }

            override fun onFinish() {
                restSecondsLeft = 0
                triggerRestAlarmPattern()
                renderAllInOneWatchInterface(keepScroll = true)
            }
        }.start()
    }

    private fun stopRestCountdown() {
        restTimer?.cancel()
        restSecondsLeft = 0
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun getWodWorkRestPair(): Pair<Int, Int> {
        return when (wodPresetIdx) {
            1 -> 45 to 15
            2 -> 60 to 0
            else -> 20 to 10
        }
    }

    private fun toggleWodTimer() {
        if (wodRunning) {
            wodRunning = false
            wodTimer?.cancel()
            renderAllInOneWatchInterface(keepScroll = true)
            return
        }
        wodRunning = true
        startWodPhaseCountdown(wodSecLeft)
    }

    private fun startWodPhaseCountdown(seconds: Int) {
        wodTimer?.cancel()
        wodSecLeft = seconds.coerceAtLeast(1)
        val endMs = System.currentTimeMillis() + wodSecLeft * 1000L
        renderAllInOneWatchInterface(keepScroll = true)
        wodTimer = object : CountDownTimer(wodSecLeft * 1000L, 500L) {
            override fun onTick(millisUntilFinished: Long) {
                val rem = ((endMs - System.currentTimeMillis()) / 1000L).toInt().coerceAtLeast(0)
                if (rem != wodSecLeft) {
                    wodSecLeft = rem
                    val (zName, _) = getHeartRateZoneInfo(liveBpm)
                    val phaseTxt = if (wodIsWorkPhase) "🔥 POWER / ARBEIT" else "🧊 PAUSE / WECHSEL"
                    wodMetricsTv?.text = "$phaseTxt (Runde $wodRound)\n⏱️ ${wodSecLeft}s   •   ❤️ $liveBpm ($zName)"
                }
            }
            override fun onFinish() {
                triggerRestAlarmPattern()
                val (workS, restS) = getWodWorkRestPair()
                if (wodIsWorkPhase && restS > 0) {
                    wodIsWorkPhase = false
                    startWodPhaseCountdown(restS)
                } else {
                    wodIsWorkPhase = true
                    wodRound += 1
                    startWodPhaseCountdown(workS)
                }
            }
        }.start()
    }

    private fun stopWodTimer(reset: Boolean) {
        wodTimer?.cancel()
        wodRunning = false
        if (reset) {
            wodIsWorkPhase = true
            wodRound = 1
            wodSecLeft = getWodWorkRestPair().first
        }
        renderAllInOneWatchInterface(keepScroll = true)
    }

    // =========================================================================
    // HARDWARE-SENSOREN (HERZFREQUENZ & STANDALONE GPS)
    // =========================================================================
    private fun initWatchHardwareSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            heartRateSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val missing = mutableListOf<String>()
                if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.BODY_SENSORS)
                }
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (missing.isNotEmpty()) {
                    requestPermissions(missing.toTypedArray(), 404)
                } else {
                    heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
                }
            } else {
                heartRateSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
            }
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            val bpmVal = event.values[0].toInt()
            if (bpmVal in 42..220 && bpmVal != liveBpm) {
                liveBpm = bpmVal
                hasRealHrFix = true
                val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
                val syncText = if (pendingCnt > 0) "⚡$pendingCnt" else if (lastCloudSyncOk) "🟢 Live" else "🟠 Offline"
                val (zName, zCol) = getHeartRateZoneInfo(liveBpm)
                topStatusTv?.text = "❤️ $liveBpm ($zName) • $syncText • ${activePartnerSlot.uppercase()}"
                topStatusTv?.setTextColor(Color.parseColor(zCol))
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(loc: Location) {
        if (!gpsRunning) return
        val prev = lastGpsLocation
        if (prev != null) {
            val deltaMeters = prev.distanceTo(loc)
            if (deltaMeters in 1.5f..150.0f) {
                gpsDistKm += (deltaMeters / 1000.0f)
            }
        }
        lastGpsLocation = loc
    }

    @SuppressLint("MissingPermission")
    private fun toggleGpsTracking() {
        if (gpsRunning) {
            gpsRunning = false
            gpsTimer?.cancel()
            try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
            renderAllInOneWatchInterface(keepScroll = true)
            return
        }
        gpsRunning = true
        lastGpsLocation = null
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 2.0f, this)
            }
        } catch (e: Exception) {}

        gpsTimer = object : CountDownTimer(3600 * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                gpsSeconds += 1
                if (gpsSeconds % 4 == 0 && lastGpsLocation == null) {
                    gpsDistKm += 0.014f
                }
                val m = (gpsSeconds / 60).toString().padStart(2, '0')
                val s = (gpsSeconds % 60).toString().padStart(2, '0')
                val kcal = (gpsDistKm * 68f + (gpsSeconds / 60f) * 5f).roundToInt()
                val paceStr = formatPaceMinPerKm(gpsSeconds, gpsDistKm)
                cardioMetricsTv?.text = "📡 ${String.format("%.2f", gpsDistKm)} km  •  ⚡ $paceStr /km\n⏱️ $m:$s  •  🔥 $kcal kcal"
            }
            override fun onFinish() { gpsRunning = false }
        }.start()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    // =========================================================================
    // PERSISTENZ, VOLLSTÄNDIGER JSON-PARSER & FLACKERFREIER LIVE-SYNC
    // =========================================================================
    private fun loadPersistedWatchState() {
        watchfaceStyleIdx = prefs.getInt("watchface_style_idx", 0).coerceIn(0, 2)
        manualSnackDeltaKcal = prefs.getInt("manual_snack_delta_kcal", 0)

        try {
            val rawPlan = prefs.getString("custom_watch_plan_json", "") ?: ""
            if (rawPlan.isNotEmpty()) applyDecodedWatchPlanJson(rawPlan)
        } catch (e: Exception) {}

        try {
            val rawMap = prefs.getString("completed_sets_map_json", "{}") ?: "{}"
            val mapObj = JSONObject(rawMap)
            trainingDays.forEachIndexed { dIdx, pair ->
                pair.second.forEach { ex ->
                    if (mapObj.has("${dIdx}_${ex.id}")) {
                        ex.doneSets = mapObj.optInt("${dIdx}_${ex.id}", ex.doneSets).coerceIn(0, ex.sets)
                    }
                }
            }
        } catch (e: Exception) {}

        weightStep = prefs.getFloat("weight_step", 2.5f)
        currentDayIdx = prefs.getInt("day_idx", 0).coerceIn(0, (trainingDays.size - 1).coerceAtLeast(0))
        val exList = trainingDays[currentDayIdx].second
        currentExIdx = prefs.getInt("cur_ex_idx", 0).coerceIn(0, (exList.size - 1).coerceAtLeast(0))
        val curEx = exList[currentExIdx]
        currentSetIdx = prefs.getInt("cur_set_idx", (curEx.doneSets + 1).coerceAtMost(curEx.sets))
        liveKg = prefs.getFloat("cur_kg", curEx.kg)
        if (isIsometricOrTimedExercise(curEx.name) && liveKg == 44.0f) {
            liveKg = 0.0f
        }
        liveReps = prefs.getInt("cur_reps", curEx.targetReps)
        todayMovedVolumeKg = prefs.getInt("today_volume_kg", 0)
    }

    private fun saveWatchStateAndRefreshTile() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val rawOpenKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }
        val openKcal = (rawOpenKcal - manualSnackDeltaKcal).coerceAtLeast(0)

        val mapObj = JSONObject()
        trainingDays.forEachIndexed { dIdx, pair ->
            pair.second.forEach { ex ->
                if (ex.doneSets > 0) mapObj.put("${dIdx}_${ex.id}", ex.doneSets)
            }
        }

        prefs.edit()
            .putFloat("weight_step", weightStep)
            .putInt("day_idx", currentDayIdx)
            .putInt("cur_ex_idx", currentExIdx)
            .putInt("cur_set_idx", currentSetIdx)
            .putInt("cur_total_sets", curEx.sets)
            .putString("cur_ex_id", curEx.id)
            .putString("cur_ex_name", curEx.name)
            .putInt("today_volume_kg", todayMovedVolumeKg)
            .putFloat("cur_kg", liveKg)
            .putInt("cur_reps", liveReps)
            .putInt("open_kcal", openKcal)
            .putString("completed_sets_map_json", mapObj.toString())
            .apply()

        try {
            val updater = TileService.getUpdater(this)
            updater.requestUpdate(RemkTileService::class.java)
            updater.requestUpdate(RemkTimerTileService::class.java)
            updater.requestUpdate(RemkNutritionTileService::class.java)
            updater.requestUpdate(RemkCardioShopTileService::class.java)
        } catch (e: Exception) {}
    }

    private fun applyDecodedWatchPlanJson(jsonStr: String): Boolean {
        return try {
            val obj = JSONObject(jsonStr)
            targetKcal = obj.optInt("k", targetKcal)
            targetProtein = obj.optInt("p", targetProtein)
            activeWeek = obj.optInt("w", activeWeek)
            activePartnerSlot = obj.optString("slot", activePartnerSlot).ifEmpty { "p1" }

            val dArr = obj.optJSONArray("d")
            if (dArr != null && dArr.length() > 0) {
                val newDays = mutableListOf<Pair<String, MutableList<WatchExercise>>>()
                for (i in 0 until dArr.length()) {
                    val dObj = dArr.optJSONObject(i) ?: continue
                    val dTitle = dObj.optString("t", "Tag ${i + 1}")
                    val eArr = dObj.optJSONArray("e")
                    val exList = mutableListOf<WatchExercise>()
                    if (eArr != null) {
                        for (j in 0 until eArr.length()) {
                            val eObj = eArr.optJSONObject(j) ?: continue
                            val exId = eObj.optString("i", "ex_$j")
                            val exName = eObj.optString("n", "Übung ${j + 1}")
                            val setsVal = eObj.optInt("s", 3).coerceAtLeast(1)
                            val rawW = eObj.optDouble("w", 60.0).toFloat()
                            val wVal = if (isIsometricOrTimedExercise(exName) && rawW == 44.0f) 0.0f else rawW
                            val rVal = eObj.optInt("r", 10)
                            val prevStr = eObj.optString("p", if (isIsometricOrTimedExercise(exName)) "BW × ${rVal}s" else "${fmtKg(wVal)}kg × $rVal")
                            val isUni = (eObj.optInt("u", 0) == 1) || eObj.optBoolean("u", false)
                            val cloudDoneSets = eObj.optInt("ds", 0).coerceIn(0, setsVal)
                            exList.add(
                                WatchExercise(
                                    id = exId,
                                    name = exName,
                                    sets = setsVal,
                                    targetReps = rVal,
                                    kg = wVal,
                                    prevRef = prevStr,
                                    unilateral = isUni,
                                    doneSets = cloudDoneSets
                                )
                            )
                        }
                    }
                    if (exList.isNotEmpty()) newDays.add(dTitle to exList)
                }
                if (newDays.isNotEmpty()) trainingDays = newDays
            }

            val mArr = obj.optJSONArray("m")
            if (mArr != null && mArr.length() > 0) {
                dailyMeals.clear()
                for (i in 0 until mArr.length()) {
                    val mObj = mArr.optJSONObject(i) ?: continue
                    val cloudDone = (mObj.optInt("dn", -1) == 1) || mObj.optBoolean("dn", false)
                    val localDone = prefs.getBoolean("meal_done_$i", false)
                    dailyMeals.add(
                        WatchMeal(
                            slot = mObj.optString("s", "🍴 Mahlzeit"),
                            title = mObj.optString("n", "Gericht"),
                            kcal = mObj.optInt("k", 550),
                            done = cloudDone || localDone
                        )
                    )
                }
            }

            val sArr = obj.optJSONArray("sh")
            if (sArr != null && sArr.length() > 0) {
                shoppingItems.clear()
                for (i in 0 until sArr.length()) {
                    val itemObj = sArr.optJSONObject(i)
                    if (itemObj != null) {
                        val sTitle = itemObj.optString("n", itemObj.optString("title", "")).trim()
                        val sDone = (itemObj.optInt("dn", 0) == 1) || itemObj.optBoolean("dn", false) || prefs.getBoolean("shop_done_$i", false)
                        if (sTitle.isNotEmpty()) {
                            shoppingItems.add(WatchShopItem(i, sTitle, sDone))
                        }
                    } else {
                        val sTxt = sArr.optString(i, "").trim()
                        if (sTxt.isNotEmpty() && !sTxt.startsWith("{")) {
                            shoppingItems.add(WatchShopItem(i, sTxt, prefs.getBoolean("shop_done_$i", false)))
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun triggerBackgroundCloudPull(silent: Boolean) {
        if (isSyncInProgress) return
        isSyncInProgress = true
        if (!silent) {
            topStatusTv?.text = "❤️ $liveBpm  •  🔄 Sync...  •  ${activePartnerSlot.uppercase()}"
        }

        WearOfflineSyncQueue.pushWatchStateToCloudAsync(this) { _ ->
            WearOfflineSyncQueue.pullCloudStateToWatchAsync(this) { ok, cloudObj ->
                runOnUiThread {
                    isSyncInProgress = false
                    lastCloudSyncOk = ok
                    if (ok && cloudObj != null) {
                        val planJson = cloudObj.optString("watchPlanCompactJson", "")
                        val cSets = cloudObj.optInt("doneSetsToday", -1)
                        val cWater = cloudObj.optDouble("drankLiters", -1.0)
                        val cKcal = cloudObj.optInt("openKcal", -1)
                        val mapStr = cloudObj.optString("completedSetsMap", "")

                        val newSignature = "${planJson.hashCode()}_${cSets}_${cWater}_${cKcal}_${mapStr.hashCode()}"
                        val hasChanged = (newSignature != lastCloudDataSignature)
                        lastCloudDataSignature = newSignature

                        if (hasChanged || !silent) {
                            if (planJson.isNotEmpty() && applyDecodedWatchPlanJson(planJson)) {
                                prefs.edit().putString("custom_watch_plan_json", planJson).apply()
                            }
                            if (cSets >= 0) {
                                val localSets = prefs.getInt("done_sets_today", 0)
                                prefs.edit().putInt("done_sets_today", maxOf(localSets, cSets)).apply()
                            }
                            if (cWater >= 0.0) {
                                val localW = prefs.getFloat("drank_liters", 0f)
                                prefs.edit().putFloat("drank_liters", maxOf(localW, cWater.toFloat())).apply()
                            }
                            if (cKcal >= 0) prefs.edit().putInt("open_kcal", cKcal).apply()

                            if (mapStr.startsWith("{")) {
                                try {
                                    val cloudMap = JSONObject(mapStr)
                                    val localMap = JSONObject(prefs.getString("completed_sets_map_json", "{}") ?: "{}")
                                    val keys = cloudMap.keys()
                                    while (keys.hasNext()) {
                                        val k = keys.next()
                                        val v = cloudMap.optInt(k, 0)
                                        if (v > localMap.optInt(k, 0)) localMap.put(k, v)
                                    }
                                    prefs.edit().putString("completed_sets_map_json", localMap.toString()).apply()
                                } catch (e: Exception) {}
                            }

                            loadPersistedWatchState()
                            saveWatchStateAndRefreshTile()
                            renderAllInOneWatchInterface(keepScroll = true)
                        } else {
                            val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
                            val syncText = if (pendingCnt > 0) "⚡$pendingCnt" else "🟢 Live"
                            val (zName, _) = getHeartRateZoneInfo(liveBpm)
                            topStatusTv?.text = "❤️ $liveBpm ($zName) • $syncText • ${activePartnerSlot.uppercase()}"
                        }

                        if (!silent) {
                            Toast.makeText(this, "✅ Live-Sync mit Handy & Sheets erfolgreich!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
                        val syncText = if (pendingCnt > 0) "⚡$pendingCnt" else "🟠 Offline"
                        val (zName, _) = getHeartRateZoneInfo(liveBpm)
                        topStatusTv?.text = "❤️ $liveBpm ($zName) • $syncText • ${activePartnerSlot.uppercase()}"
                        if (!silent) {
                            Toast.makeText(this, "⚠️ Offline-Puffer aktiv (Sync folgt automatisch)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    private fun openWatchPlanSyncDialog() {
        val scroll = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#080C15"))
            setPadding(dp(14), dp(12), dp(14), dp(18))
        }
        scroll.addView(box)
        var dialogRef: AlertDialog? = null

        val curKey = WearOfflineSyncQueue.getOrCreateSyncKey(this)
        box.addView(TextView(this).apply {
            text = "🔗 Live-Sync & Kanal-Wahl\nAktuell: $curKey (${activePartnerSlot.uppercase()})"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        val presetRow = buildProportionalTwoBtnRow(
            "👑 26011990",
            "#182235",
            "#FF5E00",
            {
                WearOfflineSyncQueue.setSyncKey(this, "26011990")
                try { dialogRef?.dismiss() } catch (e: Exception) {}
                triggerBackgroundCloudPull(silent = false)
            },
            "🔑 Key: 1",
            "#182235",
            "#38BDF8",
            {
                WearOfflineSyncQueue.setSyncKey(this, "1")
                try { dialogRef?.dismiss() } catch (e: Exception) {}
                triggerBackgroundCloudPull(silent = false)
            }
        )
        box.addView(presetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        val partnerRow = buildProportionalTwoBtnRow(
            "👤 Partner P1",
            if (activePartnerSlot == "p1") "#10B981" else "#182235",
            if (activePartnerSlot == "p1") "#000000" else "#E2E8F0",
            {
                activePartnerSlot = "p1"
                try { dialogRef?.dismiss() } catch (e: Exception) {}
                renderAllInOneWatchInterface(keepScroll = true)
            },
            "❤️ Partner P2",
            if (activePartnerSlot == "p2") "#FF5E00" else "#182235",
            if (activePartnerSlot == "p2") "#000000" else "#E2E8F0",
            {
                activePartnerSlot = "p2"
                try { dialogRef?.dismiss() } catch (e: Exception) {}
                renderAllInOneWatchInterface(keepScroll = true)
            }
        )
        box.addView(partnerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        val closeBtn = buildWeightedCellBtn("✓ Schließen", "#38BDF8", "#000000", 12f) {
            try { dialogRef?.dismiss() } catch (e: Exception) {}
        }
        box.addView(closeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialogRef = AlertDialog.Builder(this).setView(scroll).create()
        dialogRef.show()
    }

    // =========================================================================
    // PROPORTIONALE UI-BAUSTEINE (0% QUETSCHUNG AUF DEM RUNDEN DISPLAY!)
    // =========================================================================
    private fun buildWeightedCellBtn(
        label: String,
        bgHex: String,
        txtHex: String,
        fontSp: Float = 12f,
        onClick: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = fontSp
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(bgHex, txtHex, 11)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun buildProportionalTwoBtnRow(
        lbl1: String,
        bg1: String,
        txt1: String,
        click1: () -> Unit,
        lbl2: String,
        bg2: String,
        txt2: String,
        click2: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                buildWeightedCellBtn(lbl1, bg1, txt1, 11.5f, click1),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) }
            )
            addView(
                buildWeightedCellBtn(lbl2, bg2, txt2, 11.5f, click2),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3) }
            )
        }
    }

    private fun createSectionCard(bgHex: String, borderHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(bgHex, borderHex, 18)
            setPadding(dp(9), dp(8), dp(9), dp(9))
        }
    }

    private fun sectionLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        }
    }

    private fun buildSectionTitle(txt: String, colHex: String): TextView {
        return TextView(this).apply {
            text = txt
            setTextColor(Color.parseColor(colHex))
            textSize = 11.5f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(3))
        }
    }

    private fun buildDockIconBtn(emoji: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = emoji
            textSize = 13f
            gravity = Gravity.CENTER
            background = roundedBg("#162032", "#26344D", 10)
            setPadding(dp(6), dp(3), dp(6), dp(3))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(1), 0, dp(1), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun calculateBarbellPlatesPerSide(totalKg: Float): String {
        if (totalKg <= 20f) return "Kurzhantel / 20kg Stange"
        var rem = (totalKg - 20f) / 2f
        val plates = floatArrayOf(25f, 20f, 15f, 10f, 5f, 2.5f, 1.25f)
        val out = mutableListOf<String>()
        for (p in plates) {
            while (rem >= p - 0.01f) {
                out.add(fmtKg(p))
                rem -= p
            }
        }
        return if (out.isEmpty()) "20kg Stange" else "Je Seite: " + out.joinToString("+")
    }

    private fun roundedBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun fmtKg(v: Float): String =
        if (v % 1.0f == 0.0f) v.toInt().toString() else v.toString().replace(".", ",")

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun triggerVibration(durationMs: Long) {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {}
    }

    private fun triggerRestAlarmPattern() {
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib != null && vib.hasVibrator()) {
                val pattern = longArrayOf(0, 260, 110, 260, 110, 420)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(pattern, -1)
                }
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockTickRunnable)
        syncHandler.removeCallbacks(autoSyncRunnable)
        crownResetHandler.removeCallbacks(crownResetRunnable)
        restTimer?.cancel()
        isoTimer?.cancel()
        gpsTimer?.cancel()
        wodTimer?.cancel()
        try { sensorManager?.unregisterListener(this) } catch (e: Exception) {}
        try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
}
