package eu.remk.coaching.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * RE:MK Pixel Watch 4 – All-in-One Vertical Sports Cockpit (v28.5 Pro)
 * - KEIN mühsames Links/Rechts-Wischen mehr: Alle 5 Module (Workout, Plan/Tagesform, Cardio/WOD,
 *   Ernährung/Wasser, Einkauf & Live-Sync) liegen in EINEM durchgängigen vertikalen Watch-Interface!
 * - Bedienbar per Hoch/Runter-Swipen, über die physische Drehkrone (Rotary) ODER über die 5 Schnellwahl-Icons oben.
 * - Direkte +/- Buttons für Gewicht & Wiederholungen im aktiven Satz + aufklappbare Scheiben-/Warm-up-/1RM-Hilfe.
 * - Nicht-blockierender Inline-Pausen-Timer (während der Satzpause frei zu Wasser/Plan/Ernährung scrollen).
 * - Echter bidirektionaler Live-Sync mit der Smartphone APK (v28.0 Master Pro) & Google Sheets.
 */
class WearMainActivity : Activity(), SensorEventListener, LocationListener {

    data class WatchExercise(
        val id: String,
        val name: String,
        var sets: Int,
        var targetReps: Int,
        var kg: Float,
        val prevRef: String,
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
        val title: String,
        var done: Boolean = false
    )

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rootLayout: LinearLayout
    private lateinit var stickyTopDock: LinearLayout
    private lateinit var mainScrollView: ScrollView
    private lateinit var streamContainer: LinearLayout

    // Referenzen für schnelles Scrollen per Icon-Tap und Live-Timer-Updates ohne Neuaufbau
    private var secWorkoutView: View? = null
    private var secPlanView: View? = null
    private var secCardioView: View? = null
    private var secNutritionView: View? = null
    private var secShopSyncView: View? = null

    private var restCountdownTv: TextView? = null
    private var cardioMetricsTv: TextView? = null
    private var wodMetricsTv: TextView? = null
    private var topStatusTv: TextView? = null

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var lastGpsLocation: Location? = null

    // Krone-Modus: "scroll" = Watch-Interface hoch/runter scrollen, "kg" = Gewicht +/- , "reps" = Wdh. +/-
    private var crownMode = "scroll"
    private var weightStep = 2.5f
    private var showProHelperDrawer = false
    private var proHelperTab = 0 // 0 = Hantelscheiben, 1 = Warm-up Pyramide, 2 = 1RM & Schrittweite

    private var activePartnerSlot = "p1"
    private var activeWeek = 1
    private var targetKcal = 2400
    private var targetProtein = 160

    private var currentDayIdx = 0
    private var currentExIdx = 0
    private var currentSetIdx = 1
    private var unilateralLeft = true
    private var todayMovedVolumeKg = 0
    private var readinessMode = 0 // 0 = 🟢 100%, 1 = 🟡 -5% Schicht/Müde, 2 = 🔴 -15% Gelenk-Schutz

    // Undo-Speicher
    private var canUndoLastSet = false
    private var undoDayIdx = 0
    private var undoExIdx = 0
    private var undoSetIdx = 1
    private var undoKg = 80.0f
    private var undoReps = 10

    private var liveKg = 80.0f
    private var liveReps = 10

    // Pausen-Timer Status (Inline & Nicht-Blockierend)
    private var restTimer: CountDownTimer? = null
    private var restSecondsLeft = 0
    private var restEndTimestampMs = 0L

    // GPS, Puls & WOD-Timer Status
    private var gpsRunning = false
    private var gpsTimer: CountDownTimer? = null
    private var gpsSeconds = 0
    private var gpsDistKm = 0.0f
    private var liveBpm = 128
    private var hasRealHrFix = false
    private var activeSportLabel = "🏃 Laufen"

    private var page2WodMode = false
    private var wodPresetIdx = 0 // 0 = Tabata 20/10, 1 = Zirkel 45/15, 2 = EMOM 60s
    private var wodRunning = false
    private var wodIsWorkPhase = true
    private var wodRound = 1
    private var wodSecLeft = 20
    private var wodTimer: CountDownTimer? = null

    // Hintergrund-Live-Sync Loop (Alle 8 Sekunden mit Google Sheets / Smartphone APK)
    private val syncHandler = Handler(Looper.getMainLooper())
    private var isSyncInProgress = false
    private var lastCloudSyncOk = true

    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                triggerBackgroundCloudPull(silent = true)
                syncHandler.postDelayed(this, 8500L)
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
            WatchExercise("c1", "Hanging Leg Raises", 3, 15, 0.0f, "BW × 15")
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
        WatchShopItem("1,2 kg Hähnchenbrust", false),
        WatchShopItem("1,5 kg Skyr / Magerquark", false),
        WatchShopItem("800 g Basmatireis & Hafer", false),
        WatchShopItem("1,0 kg Brokkoli & Spinat", false),
        WatchShopItem("10 Stück Eier (Größe M)", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences(WearOfflineSyncQueue.PREFS_NAME, Context.MODE_PRIVATE)
        loadPersistedWatchState()

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05070B"))
            isFocusable = true
            isFocusableInTouchMode = true
        }

        // Feste Schnellwahl-Leiste oben (für runde Lünette der Pixel Watch 4 eingerückt)
        stickyTopDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A0F1A"))
            setPadding(dp(18), dp(6), dp(18), dp(4))
        }
        rootLayout.addView(
            stickyTopDock,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Durchgängiger vertikaler Watch-Stream (Hoch & Runter wischen durch alle Menüs)
        mainScrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isSmoothScrollingEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        streamContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Großzügiges Padding oben/unten und seitlich für das runde Display der Pixel Watch 4
            setPadding(dp(14), dp(6), dp(14), dp(46))
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

        // Physische Dreh-Krone der Pixel Watch 4: Scrollt entweder butterweich durch das
        // gesamte Watch-Interface ODER verstellt direkt Gewicht / Wiederholungen!
        val rotaryListener = View.OnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL &&
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            ) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                val dir = if (delta > 0) 1 else -1
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                when (crownMode) {
                    "kg" -> {
                        stepWeight(dir * weightStep)
                        true
                    }
                    "reps" -> {
                        stepReps(dir)
                        true
                    }
                    else -> {
                        mainScrollView.smoothScrollBy(0, (delta * dp(64)).roundToInt())
                        true
                    }
                }
            } else {
                false
            }
        }
        rootLayout.setOnGenericMotionListener(rotaryListener)
        mainScrollView.setOnGenericMotionListener(rotaryListener)
        mainScrollView.requestFocus()

        renderAllInOneWatchInterface(keepScroll = false)
        rootLayout.postDelayed({ initWatchHardwareSensors() }, 350L)
    }

    override fun onResume() {
        super.onResume()
        loadPersistedWatchState()
        renderAllInOneWatchInterface(keepScroll = true)
        syncHandler.removeCallbacks(autoSyncRunnable)
        syncHandler.postDelayed(autoSyncRunnable, 1200L)
    }

    override fun onPause() {
        super.onPause()
        syncHandler.removeCallbacks(autoSyncRunnable)
        saveWatchStateAndRefreshTile()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showProHelperDrawer) {
            showProHelperDrawer = false
            renderAllInOneWatchInterface(keepScroll = true)
        } else {
            // Springt sanft nach ganz oben zum Satz-Cockpit (verhindert schwarzen Home-Screen)
            mainScrollView.smoothScrollTo(0, 0)
        }
    }

    private fun scrollToSection(target: View?) {
        if (target == null) return
        triggerVibration(18)
        mainScrollView.post {
            mainScrollView.smoothScrollTo(0, (target.top - dp(6)).coerceAtLeast(0))
        }
    }

    // =========================================================================
    // ALL-IN-ONE WATCH INTERFACE: ALLE 5 MENÜS AUF EINEM VERTIKALEN SCREEN
    // =========================================================================
    private fun renderAllInOneWatchInterface(keepScroll: Boolean = true) {
        if (!::streamContainer.isInitialized || isFinishing || isDestroyed) return
        val prevScrollY = if (keepScroll) mainScrollView.scrollY else 0

        stickyTopDock.removeAllViews()
        streamContainer.removeAllViews()

        // 1. OBERE STATUS- & SCHNELLSPRUNG-LEISTE (1-Tap Sprung zu jedem Bereich)
        val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)
        val syncText = when {
            isSyncInProgress -> "🔄 Sync..."
            pendingCnt > 0 -> "⚡$pendingCnt Queue"
            lastCloudSyncOk -> "🟢 Live"
            else -> "🟠 Offline"
        }

        topStatusTv = TextView(this).apply {
            text = "❤️ $liveBpm   •   $syncText   •   W$activeWeek (${activePartnerSlot.uppercase()})"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10.5f
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
        dockRow.addView(buildDockIconBtn("🏋️") { scrollToSection(secWorkoutView) })
        dockRow.addView(buildDockIconBtn("📋") { scrollToSection(secPlanView) })
        dockRow.addView(buildDockIconBtn("⏱️") { scrollToSection(secCardioView) })
        dockRow.addView(buildDockIconBtn("🍎") { scrollToSection(secNutritionView) })
        dockRow.addView(buildDockIconBtn("🛒") { scrollToSection(secShopSyncView) })
        stickyTopDock.addView(dockRow)

        // 2. ALLE 5 SEKTIONEN UNTEREINANDER IM VERTIKALEN STREAM AUFBAUEN
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
    // SEKTION 1: 🏋️ AKTIVES SATZ-COCKPIT (MIT DIREKTEN +/- BUTTONS & PAUSEN-BAR)
    // =========================================================================
    private fun buildSection1ActiveWorkoutCard(): View {
        val card = createSectionCard("#101726", "#FF5E00")
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]

        val totalSetsDay = exList.sumOf { it.sets }
        val doneSetsDay = exList.sumOf { it.doneSets }
        val allDone = exList.isNotEmpty() && exList.all { it.doneSets >= it.sets }

        // Kopfzeile: Aktueller Tag + Krone-Modus-Schalter
        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val crownLabel = when (crownMode) {
            "kg" -> "👑 Krone: ±kg"
            "reps" -> "👑 Krone: ±Wdh"
            else -> "👑 Krone: Scroll"
        }
        val crownColor = when (crownMode) {
            "kg" -> "#FF5E00"
            "reps" -> "#10B981"
            else -> "#38BDF8"
        }
        topHeaderRow.addView(buildSmallActionPill(crownLabel, "#182235", crownColor) {
            triggerVibration(20)
            crownMode = when (crownMode) {
                "scroll" -> "kg"
                "kg" -> "reps"
                else -> "scroll"
            }
            renderAllInOneWatchInterface(keepScroll = true)
        })
        card.addView(topHeaderRow)

        if (allDone) {
            card.addView(TextView(this).apply {
                text = "🏆 WORKOUT ERLEDIGT!\n✅ $doneSetsDay/$totalSetsDay Sätze  •  🔥 $todayMovedVolumeKg kg"
                setTextColor(Color.parseColor("#10B981"))
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(6))
            })
            val celebRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            celebRow.addView(buildSmallActionPill("☁️ Syncen", "#10B981", "#000000") {
                triggerBackgroundCloudPull(silent = false)
            })
            celebRow.addView(buildSmallActionPill("🔄 Nächster Tag", "#182235", "#38BDF8") {
                triggerVibration(25)
                exList.forEach { it.doneSets = 0 }
                currentDayIdx = (currentDayIdx + 1) % trainingDays.size
                switchExerciseSafely(0)
            })
            card.addView(celebRow)
            return card
        }

        // Übungs-Umschalter: [◀] 1/4 Bankdrücken (LH) [▶]
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(2))
        }
        navRow.addView(buildSmallActionPill("◀", "#182235", "#38BDF8") {
            triggerVibration(20)
            switchExerciseSafely(currentExIdx - 1)
        })
        val sideSuffix = if (curEx.unilateral) (if (unilateralLeft) " [L]" else " [R]") else ""
        navRow.addView(TextView(this).apply {
            text = "${currentExIdx + 1}/${exList.size} ${curEx.name.take(16)}$sideSuffix"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                if (curEx.unilateral) {
                    unilateralLeft = !unilateralLeft
                    triggerVibration(18)
                    renderAllInOneWatchInterface(keepScroll = true)
                } else {
                    scrollToSection(secPlanView)
                }
            }
        })
        navRow.addView(buildSmallActionPill("▶", "#182235", "#38BDF8") {
            triggerVibration(20)
            switchExerciseSafely(currentExIdx + 1)
        })
        card.addView(navRow)

        // Satz-Fortschritt & Vorwochen-Referenz
        val setDots = (1..curEx.sets).joinToString(" ") { s ->
            when {
                s <= curEx.doneSets -> "🟢"
                s == currentSetIdx -> "🟠"
                else -> "⚪"
            }
        }
        card.addView(TextView(this).apply {
            text = "$setDots  SATZ $currentSetIdx/${curEx.sets}  (Vorher: ${curEx.prevRef})"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        // Falls gerade Satzpause läuft -> Kompaktes, nicht-blockierendes Inline-Pausen-Banner!
        if (restSecondsLeft > 0) {
            val restBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg("#231208", "#FF5E00", 14)
                setPadding(dp(8), dp(6), dp(8), dp(6))
            }
            val m = (restSecondsLeft / 60).toString().padStart(2, '0')
            val s = (restSecondsLeft % 60).toString().padStart(2, '0')
            restCountdownTv = TextView(this).apply {
                text = "⏱️ PAUSE: $m:$s"
                setTextColor(Color.WHITE)
                textSize = 20f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            }
            restBox.addView(restCountdownTv)

            val rBtns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            rBtns.addView(buildSmallActionPill("−15s", "#182235", "#E2E8F0") {
                restSecondsLeft = (restSecondsLeft - 15).coerceAtLeast(5)
                startRestCountdown(restSecondsLeft)
            })
            rBtns.addView(buildSmallActionPill("+30s", "#182235", "#E2E8F0") {
                restSecondsLeft += 30
                startRestCountdown(restSecondsLeft)
            })
            rBtns.addView(buildSmallActionPill("Weiter ›", "#10B981", "#000000") {
                stopRestCountdown()
            })
            restBox.addView(rBtns)
            card.addView(restBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        } else {
            restCountdownTv = null
        }

        // DIREKTE INLINE-STEPPER ZEILE 1: GEWICHT (Kein Popup nötig!)
        val kgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg("#0B101B", if (crownMode == "kg") "#FF5E00" else "#1E293B", 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        kgRow.addView(buildBigStepperBtn("−${fmtKg(weightStep)}", "#38BDF8") {
            crownMode = "kg"
            stepWeight(-weightStep)
        })
        kgRow.addView(TextView(this).apply {
            text = "⚖️ ${fmtKg(liveKg)} kg"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                triggerVibration(20)
                crownMode = "kg"
                showProHelperDrawer = !showProHelperDrawer
                renderAllInOneWatchInterface(keepScroll = true)
            }
        })
        kgRow.addView(buildBigStepperBtn("+${fmtKg(weightStep)}", "#38BDF8") {
            crownMode = "kg"
            stepWeight(weightStep)
        })
        card.addView(kgRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        // DIREKTE INLINE-STEPPER ZEILE 2: WIEDERHOLUNGEN
        val repsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg("#0B101B", if (crownMode == "reps") "#10B981" else "#1E293B", 14)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        repsRow.addView(buildBigStepperBtn("−1 Wdh", "#10B981") {
            crownMode = "reps"
            stepReps(-1)
        })
        repsRow.addView(TextView(this).apply {
            text = "🔄 $liveReps Wdh."
            setTextColor(Color.WHITE)
            textSize = 16.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                triggerVibration(20)
                crownMode = "reps"
                val rPresets = intArrayOf(5, 6, 8, 10, 12, 15, 20)
                liveReps = rPresets.firstOrNull { it > liveReps } ?: rPresets[0]
                curEx.targetReps = liveReps
                saveWatchStateAndRefreshTile()
                renderAllInOneWatchInterface(keepScroll = true)
            }
        })
        repsRow.addView(buildBigStepperBtn("+1 Wdh", "#10B981") {
            crownMode = "reps"
            stepReps(1)
        })
        card.addView(repsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(5)
        })

        // Aufklappbare Pro-Hilfe (Scheibenrechner, 4-Stufen Warm-up, 1RM & Schrittweite)
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
            val stepRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            stepRow.addView(buildSmallActionPill("± ${fmtKg(weightStep)}kg Schritt", "#182235", "#38BDF8") {
                weightStep = if (weightStep == 2.5f) 1.25f else if (weightStep == 1.25f) 5.0f else 2.5f
                saveWatchStateAndRefreshTile()
                renderAllInOneWatchInterface(keepScroll = true)
            })
            stepRow.addView(buildSmallActionPill("+10 kg Sprung", "#182235", "#FBBF24") {
                stepWeight(10.0f)
            })
            helperBox.addView(stepRow)
            card.addView(helperBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            })
        }

        // GROSSER GRÜNER HAUPT-BUTTON: SATZ ABHAKEN
        val logBtn = Button(this).apply {
            text = "✓ SATZ $currentSetIdx ABHAKEN"
            setTextColor(Color.BLACK)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#10B981", "#059669", 16)
            setPadding(dp(8), dp(9), dp(8), dp(9))
            setOnClickListener {
                completeCurrentSet()
            }
        }
        card.addView(logBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Untere Zusatzzeile: Scheiben/Warm-up Info | Undo | Gerät besetzt
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        subRow.addView(buildSmallActionPill(if (showProHelperDrawer) "🔼 Info zu" else "🧮 Scheiben/Warm-up", "#182235", "#38BDF8") {
            showProHelperDrawer = !showProHelperDrawer
            renderAllInOneWatchInterface(keepScroll = true)
        })
        if (canUndoLastSet) {
            subRow.addView(buildSmallActionPill("↩️ Undo", "#2D1215", "#F43F5E") {
                undoLastCompletedSet()
            })
        } else {
            subRow.addView(buildSmallActionPill("⏳ Besetzt", "#2B1F0E", "#FBBF24") {
                postponeOccupiedExercise()
            })
        }
        card.addView(subRow)

        return card
    }

    // =========================================================================
    // SEKTION 2: 📋 TRAININGSPLAN, TAG-WAHL, PARTNER & TAGESFORM-AMPEL
    // =========================================================================
    private fun buildSection2TrainingPlanAndReadinessCard(): View {
        val card = createSectionCard("#0E1524", "#38BDF8")
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val doneTotal = exList.sumOf { it.doneSets }
        val targetTotal = exList.sumOf { it.sets }

        card.addView(buildSectionTitle("📋 PLAN & TAGESFORM ($doneTotal/$targetTotal Sätze)", "#38BDF8"))

        // Tag-Umschalter & Tagesform-Ampel
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        ctrlRow.addView(buildSmallActionPill("📅 ${dayPair.first.take(14)} ⟳", "#182235", "#FFFFFF") {
            triggerVibration(22)
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            switchExerciseSafely(0)
        })
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
        ctrlRow.addView(buildSmallActionPill(readLabel, "#101827", readCol) {
            triggerVibration(25)
            applyReadinessAutoregulation((readinessMode + 1) % 3)
        })
        card.addView(ctrlRow)

        // Vollständige Übungsliste des heutigen Tages (Direkt antippbar ohne Seiten-Limit!)
        exList.forEachIndexed { idx, ex ->
            val isCur = (idx == currentExIdx)
            val isDone = (ex.doneSets >= ex.sets)
            val badge = if (isDone) "✅ ${ex.sets}/${ex.sets}" else "${ex.doneSets}/${ex.sets}"
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
                text = "${idx + 1}. ${ex.name.take(18)}  •  ${fmtKg(ex.kg)}kg × ${ex.targetReps} ($badge)"
                setTextColor(Color.parseColor(txtHex))
                textSize = 12f
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
    // SEKTION 3: ⏱️ CARDIO, LIVE-PULS, GPS & WOD/ZIRKEL-TIMER
    // =========================================================================
    private fun buildSection3CardioGpsAndWodCard(): View {
        val card = createSectionCard("#0E1524", "#F59E0B")
        card.addView(buildSectionTitle("⏱️ CARDIO, PULS & WOD-TIMER", "#F59E0B"))

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(4))
        }
        switchRow.addView(buildSmallActionPill(if (page2WodMode) "🔥 Modus: WOD/Zirkel" else "📡 Modus: GPS & Puls", "#182235", "#FF5E00") {
            triggerVibration(20)
            page2WodMode = !page2WodMode
            renderAllInOneWatchInterface(keepScroll = true)
        })

        if (page2WodMode) {
            val presetNames = listOf("Tabata 20/10", "Zirkel 45/15", "EMOM 60s")
            switchRow.addView(buildSmallActionPill("${presetNames[wodPresetIdx]} ⟳", "#101827", "#38BDF8") {
                triggerVibration(20)
                wodPresetIdx = (wodPresetIdx + 1) % presetNames.size
                stopWodTimer(reset = true)
            })
            card.addView(switchRow)

            val phaseTxt = if (wodIsWorkPhase) "🔥 POWER / ARBEIT" else "🧊 PAUSE / WECHSEL"
            val phaseCol = if (wodIsWorkPhase) "#10B981" else "#38BDF8"
            wodMetricsTv = TextView(this).apply {
                text = "$phaseTxt (Runde $wodRound)\n⏱️ ${wodSecLeft}s   •   ❤️ $liveBpm BPM"
                setTextColor(Color.parseColor(phaseCol))
                textSize = 15f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(5))
            }
            card.addView(wodMetricsTv)

            val cRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            cRow.addView(buildSmallActionPill(if (wodRunning) "⏸️ Pause" else "▶️ Start WOD", if (wodRunning) "#F59E0B" else "#10B981", "#000000") {
                triggerVibration(35)
                toggleWodTimer()
            })
            cRow.addView(buildSmallActionPill("🔄 Reset", "#182235", "#94A3B8") {
                triggerVibration(25)
                stopWodTimer(reset = true)
            })
            card.addView(cRow)
        } else {
            switchRow.addView(buildSmallActionPill("$activeSportLabel ⟳", "#101827", "#38BDF8") {
                val sports = listOf("🏃 Laufen", "🚴 Rad", "⚡ Hyrox", "🚶 Gehen")
                activeSportLabel = sports[(sports.indexOf(activeSportLabel) + 1) % sports.size]
                renderAllInOneWatchInterface(keepScroll = true)
            })
            card.addView(switchRow)

            val m = (gpsSeconds / 60).toString().padStart(2, '0')
            val s = (gpsSeconds % 60).toString().padStart(2, '0')
            val kcal = (gpsDistKm * 68f + (gpsSeconds / 60f) * 5f).roundToInt()

            cardioMetricsTv = TextView(this).apply {
                text = "❤️ $liveBpm BPM  •  📡 ${String.format("%.2f", gpsDistKm)} km\n⏱️ $m:$s  •  🔥 $kcal kcal"
                setTextColor(Color.WHITE)
                textSize = 14.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(5))
            }
            card.addView(cardioMetricsTv)

            val gRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            gRow.addView(buildSmallActionPill(if (gpsRunning) "⏸️ Stopp" else "▶️ GPS Start", if (gpsRunning) "#F59E0B" else "#10B981", "#000000") {
                triggerVibration(35)
                toggleGpsTracking()
            })
            gRow.addView(buildSmallActionPill("💾 Loggen", "#182235", "#38BDF8") {
                triggerVibration(35)
                WearOfflineSyncQueue.enqueueEvent(
                    this,
                    "WATCH_CARDIO_SAVED",
                    "sport=$activeSportLabel&distKm=${String.format("%.2f", gpsDistKm)}&kcal=$kcal&bpm=$liveBpm"
                ) { ok ->
                    lastCloudSyncOk = ok
                    runOnUiThread { renderAllInOneWatchInterface(keepScroll = true) }
                }
                Toast.makeText(this, "✅ Cardio-Einheit gespeichert & synchronisiert!", Toast.LENGTH_SHORT).show()
            })
            card.addView(gRow)
        }

        return card
    }

    // =========================================================================
    // SEKTION 4: 🍎 ERNÄHRUNG, MAKROS & WASSER-TRACKER (ALLE MAHLZEITEN DIREKT)
    // =========================================================================
    private fun buildSection4NutritionAndWaterCard(): View {
        val card = createSectionCard("#0E1524", "#10B981")
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }

        card.addView(buildSectionTitle("🍎 ERNÄHRUNG & WASSER", "#10B981"))

        card.addView(TextView(this).apply {
            text = "🔥 $openKcal kcal offen (Ziel: $targetKcal | ${targetProtein}g P)\n💧 Wasser heute: ${String.format("%.2f", drankL).replace(".", ",")} Liter"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(5))
        })

        // 1-Tap Wasser-Buttons (+250ml | +500ml | -250ml Korrektur)
        val waterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(5))
        }
        waterRow.addView(buildSmallActionPill("💧 +250 ml", "#10263B", "#38BDF8") {
            addWaterLiters(0.25f)
        })
        waterRow.addView(buildSmallActionPill("💧 +500 ml", "#10263B", "#38BDF8") {
            addWaterLiters(0.50f)
        })
        if (drankL >= 0.25f) {
            waterRow.addView(buildSmallActionPill("−250", "#182235", "#94A3B8") {
                addWaterLiters(-0.25f)
            })
        }
        card.addView(waterRow)

        // Alle heutigen Mahlzeiten direkt untereinander zum Abhaken!
        dailyMeals.forEachIndexed { idx, meal ->
            val mBtn = TextView(this).apply {
                text = "${if (meal.done) "✅" else "☐"} ${meal.slot}: ${meal.title.take(18)} (${meal.kcal} kcal)"
                setTextColor(Color.parseColor(if (meal.done) "#10B981" else "#F8FAFC"))
                textSize = 12f
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
                        "idx=$idx&done=${if (meal.done) 1 else 0}&openKcal=${dailyMeals.filter { !it.done }.sumOf { it.kcal }}"
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

        return card
    }

    // =========================================================================
    // SEKTION 5: 🛒 EINKAUFSLISTE & 🔗 LIVE-SYNC MIT HANDY / GOOGLE SHEETS
    // =========================================================================
    private fun buildSection5ShoppingAndCloudSyncCard(): View {
        val card = createSectionCard("#0E1524", "#38BDF8")
        val openShopCnt = shoppingItems.count { !it.done }

        card.addView(buildSectionTitle("🛒 EINKAUFSLISTE ($openShopCnt offen)", "#38BDF8"))

        if (shoppingItems.isEmpty()) {
            card.addView(TextView(this).apply {
                text = "Keine offenen Einkäufe."
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                gravity = Gravity.CENTER
            })
        } else {
            shoppingItems.forEachIndexed { idx, item ->
                val sBtn = TextView(this).apply {
                    text = "${if (item.done) "✅ " else "☐ "}${item.title.take(24)}"
                    setTextColor(Color.parseColor(if (item.done) "#10B981" else "#FFFFFF"))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTypeface(null, Typeface.BOLD)
                    background = roundedBg(if (item.done) "#0D281E" else "#151E30", if (item.done) "#10B981" else "#26324B", 12)
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setOnClickListener {
                        triggerVibration(20)
                        item.done = !item.done
                        prefs.edit().putBoolean("shop_done_$idx", item.done).apply()
                        saveWatchStateAndRefreshTile()
                        WearOfflineSyncQueue.enqueueEvent(
                            this@WearMainActivity,
                            "WATCH_SHOP_TOGGLED",
                            "idx=$idx&done=${if (item.done) 1 else 0}"
                        )
                        renderAllInOneWatchInterface(keepScroll = true)
                    }
                }
                card.addView(sBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(3)
                })
            }
        }

        // LIVE-SYNC ZENTRALE (Handy-APK & Google Sheets Lizenzen)
        val syncKey = WearOfflineSyncQueue.getOrCreateSyncKey(this)
        val pendingCnt = WearOfflineSyncQueue.getPendingCount(this)

        card.addView(TextView(this).apply {
            text = "🔗 LIVE-SYNC (Key: $syncKey • Queue: $pendingCnt)"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })

        val syncNowBtn = Button(this).apply {
            text = if (isSyncInProgress) "⏳ SYNCHRONISIERE..." else "🔄 JETZT LIVE-SYNCEN"
            setTextColor(Color.BLACK)
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#38BDF8", "#0284C7", 14)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setOnClickListener {
                triggerVibration(30)
                triggerBackgroundCloudPull(silent = false)
            }
        }
        card.addView(syncNowBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(4)
        })

        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        keyRow.addView(buildSmallActionPill("🔑 Key / Code", "#182235", "#38BDF8") {
            openWatchPlanSyncDialog()
        })
        keyRow.addView(buildSmallActionPill("⌚ Zum Zifferblatt", "#182235", "#94A3B8") {
            moveTaskToBack(true)
        })
        card.addView(keyRow)

        return card
    }

    // =========================================================================
    // HELPER & AKTIONEN FÜR WORKOUT, WASSER, TAGESFORM & LIVE-SYNC
    // =========================================================================
    private fun switchExerciseSafely(newExIdx: Int) {
        val exList = trainingDays[currentDayIdx].second
        if (exList.isEmpty()) return
        currentExIdx = (newExIdx + exList.size) % exList.size
        val curEx = exList[currentExIdx]
        currentSetIdx = (curEx.doneSets + 1).coerceAtMost(curEx.sets)
        liveKg = curEx.kg
        liveReps = curEx.targetReps
        saveWatchStateAndRefreshTile()
        renderAllInOneWatchInterface(keepScroll = true)
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

    private fun stepWeight(delta: Float) {
        liveKg = ((liveKg + delta).coerceAtLeast(0f) * 4f).roundToInt() / 4f
        trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        saveWatchStateAndRefreshTile()
        WearOfflineSyncQueue.enqueueEvent(this, "WATCH_KG_CHANGED", "exId=${trainingDays[currentDayIdx].second[currentExIdx].id}&kg=$liveKg")
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun stepReps(delta: Int) {
        liveReps = (liveReps + delta).coerceAtLeast(1)
        trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
        saveWatchStateAndRefreshTile()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    private fun completeCurrentSet() {
        triggerVibration(70)
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

        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_SET_COMPLETED",
            "exId=${curEx.id}&setIdx=$currentSetIdx&kg=$liveKg&reps=$liveReps&dayIdx=$currentDayIdx"
        ) { ok ->
            lastCloudSyncOk = ok
        }

        if (currentSetIdx < curEx.sets) {
            currentSetIdx += 1
        } else {
            val nextOpenIdx = exList.indexOfFirst { it.doneSets < it.sets }
            if (nextOpenIdx >= 0) {
                currentExIdx = nextOpenIdx
                val nextEx = exList[currentExIdx]
                currentSetIdx = (nextEx.doneSets + 1).coerceAtMost(nextEx.sets)
                liveKg = nextEx.kg
                liveReps = nextEx.targetReps
            } else {
                saveWatchStateAndRefreshTile()
                triggerRestAlarmPattern()
                renderAllInOneWatchInterface(keepScroll = true)
                return
            }
        }

        saveWatchStateAndRefreshTile()
        startRestCountdown(90)
    }

    private fun undoLastCompletedSet() {
        if (!canUndoLastSet) return
        triggerVibration(40)
        canUndoLastSet = false
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
    // PAUSEN-TIMER & WOD-INTERVALL-TIMER (MIT PERFORMANTEN IN-PLACE UPDATES)
    // =========================================================================
    private fun startRestCountdown(seconds: Int) {
        restTimer?.cancel()
        restSecondsLeft = seconds
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
                if (restCountdownTv != null) {
                    restCountdownTv?.text = "⏱️ PAUSE: $m:$s"
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
                    val phaseTxt = if (wodIsWorkPhase) "🔥 POWER / ARBEIT" else "🧊 PAUSE / WECHSEL"
                    wodMetricsTv?.text = "$phaseTxt (Runde $wodRound)\n⏱️ ${wodSecLeft}s   •   ❤️ $liveBpm BPM"
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
                val syncText = if (pendingCnt > 0) "⚡$pendingCnt Queue" else if (lastCloudSyncOk) "🟢 Live" else "🟠 Offline"
                topStatusTv?.text = "❤️ $liveBpm   •   $syncText   •   W$activeWeek (${activePartnerSlot.uppercase()})"
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
                cardioMetricsTv?.text = "❤️ $liveBpm BPM  •  📡 ${String.format("%.2f", gpsDistKm)} km\n⏱️ $m:$s  •  🔥 $kcal kcal"
            }
            override fun onFinish() { gpsRunning = false }
        }.start()
        renderAllInOneWatchInterface(keepScroll = true)
    }

    // =========================================================================
    // PERSISTENZ, VOLLSTÄNDIGER JSON-PARSER (V28.0 KOMPATIBEL) & LIVE-SYNC
    // =========================================================================
    private fun loadPersistedWatchState() {
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
        liveReps = prefs.getInt("cur_reps", curEx.targetReps)
        todayMovedVolumeKg = prefs.getInt("today_volume_kg", 0)
    }

    private fun saveWatchStateAndRefreshTile() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx.coerceIn(0, (exList.size - 1).coerceAtLeast(0))]
        val openKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }

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

    /**
     * Liest das exakt von der RE:MK Smartphone APK (v28.0 Master Pro) generierte
     * watchPlanCompactJson-Format ein (inkl. p=prevRef, u=unilateral, ds=doneSets, dn=done und sh=Objekt/String!).
     */
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
                            val setsVal = eObj.optInt("s", 3).coerceAtLeast(1)
                            val wVal = eObj.optDouble("w", 60.0).toFloat()
                            val rVal = eObj.optInt("r", 10)
                            val prevStr = eObj.optString("p", "${fmtKg(wVal)}kg × $rVal")
                            val isUni = (eObj.optInt("u", 0) == 1) || eObj.optBoolean("u", false)
                            val cloudDoneSets = eObj.optInt("ds", 0).coerceIn(0, setsVal)
                            exList.add(
                                WatchExercise(
                                    id = exId,
                                    name = eObj.optString("n", "Übung ${j + 1}"),
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
                            shoppingItems.add(WatchShopItem(sTitle, sDone))
                        }
                    } else {
                        val sTxt = sArr.optString(i, "").trim()
                        if (sTxt.isNotEmpty() && !sTxt.startsWith("{")) {
                            shoppingItems.add(WatchShopItem(sTxt, prefs.getBoolean("shop_done_$i", false)))
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
        if (!silent) renderAllInOneWatchInterface(keepScroll = true)

        WearOfflineSyncQueue.pushWatchStateToCloudAsync(this) { _ ->
            WearOfflineSyncQueue.pullCloudStateToWatchAsync(this) { ok, cloudObj ->
                runOnUiThread {
                    isSyncInProgress = false
                    lastCloudSyncOk = ok
                    if (ok && cloudObj != null) {
                        val planJson = cloudObj.optString("watchPlanCompactJson", "")
                        if (planJson.isNotEmpty() && applyDecodedWatchPlanJson(planJson)) {
                            prefs.edit().putString("custom_watch_plan_json", planJson).apply()
                        }
                        val cSets = cloudObj.optInt("doneSetsToday", -1)
                        if (cSets >= 0) {
                            val localSets = prefs.getInt("done_sets_today", 0)
                            prefs.edit().putInt("done_sets_today", maxOf(localSets, cSets)).apply()
                        }
                        val cWater = cloudObj.optDouble("drankLiters", -1.0)
                        if (cWater >= 0.0) {
                            val localW = prefs.getFloat("drank_liters", 0f)
                            prefs.edit().putFloat("drank_liters", maxOf(localW, cWater.toFloat())).apply()
                        }
                        val cKcal = cloudObj.optInt("openKcal", -1)
                        if (cKcal >= 0) prefs.edit().putInt("open_kcal", cKcal).apply()

                        val mapStr = cloudObj.optString("completedSetsMap", "")
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
                        if (!silent) {
                            Toast.makeText(this, "✅ Live-Sync mit Handy & Sheets erfolgreich!", Toast.LENGTH_SHORT).show()
                        }
                    } else if (!silent) {
                        Toast.makeText(this, "⚠️ Offline-Puffer aktiv (Sync folgt automatisch)", Toast.LENGTH_SHORT).show()
                    }
                    renderAllInOneWatchInterface(keepScroll = true)
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
            text = "🔗 Live-Sync Schlüssel\nAktuell: $curKey"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        // 1-Tap Schnellwahl für deine bekannten Google-Sheets Keys (Kein Tippen auf der Uhr nötig!)
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        presetRow.addView(buildSmallActionPill("👑 26011990", "#182235", "#FF5E00") {
            WearOfflineSyncQueue.setSyncKey(this, "26011990")
            try { dialogRef?.dismiss() } catch (e: Exception) {}
            triggerBackgroundCloudPull(silent = false)
        })
        presetRow.addView(buildSmallActionPill("🔑 Key: 1", "#182235", "#38BDF8") {
            WearOfflineSyncQueue.setSyncKey(this, "1")
            try { dialogRef?.dismiss() } catch (e: Exception) {}
            triggerBackgroundCloudPull(silent = false)
        })
        box.addView(presetRow)

        val codeInput = EditText(this).apply {
            hint = "Eigener Key / REMK-W4 Code"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setBackgroundColor(Color.parseColor("#101520"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        box.addView(codeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        })

        box.addView(buildSmallActionPill("🔄 Speichern & Syncen", "#10B981", "#000000") {
            val raw = codeInput.text?.toString()?.trim() ?: ""
            if (raw.startsWith("REMK-W4-")) {
                try {
                    val decoded = String(Base64.decode(raw.removePrefix("REMK-W4-").trim(), Base64.DEFAULT), Charsets.UTF_8)
                    if (applyDecodedWatchPlanJson(decoded)) {
                        prefs.edit().putString("custom_watch_plan_json", decoded).apply()
                        saveWatchStateAndRefreshTile()
                        renderAllInOneWatchInterface(keepScroll = true)
                    }
                } catch (e: Exception) {}
            } else if (raw.isNotEmpty()) {
                WearOfflineSyncQueue.setSyncKey(this, raw)
            }
            try { dialogRef?.dismiss() } catch (e: Exception) {}
            triggerBackgroundCloudPull(silent = false)
        })

        dialogRef = AlertDialog.Builder(this).setView(scroll).create()
        dialogRef.show()
    }

    // =========================================================================
    // UI-BAUSTEINE & DESIGN-HELPER
    // =========================================================================
    private fun createSectionCard(bgHex: String, borderHex: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(bgHex, borderHex, 18)
            setPadding(dp(10), dp(8), dp(10), dp(9))
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
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(3))
        }
    }

    private fun buildDockIconBtn(emoji: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = emoji
            textSize = 13.5f
            gravity = Gravity.CENTER
            background = roundedBg("#162032", "#26344D", 10)
            setPadding(dp(7), dp(3), dp(7), dp(3))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), 0, dp(2), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun buildBigStepperBtn(label: String, txtHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#182235", txtHex, 10)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            setOnClickListener {
                triggerVibration(20)
                onClick()
            }
        }
    }

    private fun buildSmallActionPill(label: String, bgHex: String, txtHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(bgHex, txtHex, 11)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
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
        syncHandler.removeCallbacks(autoSyncRunnable)
        restTimer?.cancel()
        gpsTimer?.cancel()
        wodTimer?.cancel()
        try { sensorManager?.unregisterListener(this) } catch (e: Exception) {}
        try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
}
