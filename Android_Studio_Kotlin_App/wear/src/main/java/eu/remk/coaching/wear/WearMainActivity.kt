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
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.view.GestureDetector
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
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService
import org.json.JSONObject
import kotlin.math.abs

/**
 * RE:MK Pixel Watch 4 – 100% Standalone Smartwatch App (v27.4 Pro – Kunden-Audit Edition)
 * 1. Satz-Gedächtnis pro Übung (doneSets bleibt beim Durchblättern der Übungen zu 100% erhalten!)
 * 2. Neues 2-Spalten-Großflächen-Touch-Zahlenmenü (wie Hevy / Apple Workout, ohne Rand-Abschnitt)
 * 3. Alle Übungen (auch 5–8 Übungen pro Tag) auf Seite 1 blätterbar mit ✅ Status
 * 4. Kein versehentlicher Übungs-Swipe beim Drücken auf "Satz abhaken" + 2-Button-Layout im runden Bogen
 * 5. 🏆 Motivierender Workout-Abschluss-Screen mit Gesamt-Tonnage & 1-Tap Cloud-Sync
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
    private lateinit var rootFrame: FrameLayout
    private lateinit var pageContainer: LinearLayout
    private lateinit var gestureDetector: GestureDetector

    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var lastGpsLocation: Location? = null

    // 5 Swipe-Seiten (Links/Rechts wischen):
    // 0 = Aktiver Satz (0-Scroll Hauptscreen mit 2-Spalten Touch-Zahlenmenü)
    // 1 = Übungs- & Tag-Auswahl (mit Pager für beliebig viele Übungen)
    // 2 = Standalone GPS & Live-Puls
    // 3 = Ernährung & Wasser (+250ml)
    // 4 = Einkaufsliste
    private var activePage = 0
    private val totalPages = 5

    private var rotaryFocus = "kg"
    private var weightStep = 2.5f
    private var currentDayIdx = 0
    private var currentExIdx = 0
    private var currentSetIdx = 1
    private var unilateralLeft = true
    private var exListPageOffset = 0
    private var todayMovedVolumeKg = 0

    // Undo-Speicher für den letzten abgehakten Satz
    private var canUndoLastSet = false
    private var undoDayIdx = 0
    private var undoExIdx = 0
    private var undoSetIdx = 1
    private var undoKg = 80.0f
    private var undoReps = 10

    private var liveKg = 80.0f
    private var liveReps = 10
    private var lastRpeTag = "🎯 Optimal"

    // Pausen-Timer Status
    private var restTimer: CountDownTimer? = null
    private var restSecondsLeft = 0
    private var restEndTimestampMs = 0L

    // GPS & Puls Status
    private var gpsRunning = false
    private var gpsTimer: CountDownTimer? = null
    private var gpsSeconds = 0
    private var gpsDistKm = 0.0f
    private var liveBpm = 128
    private var hasRealHrFix = false
    private var activeSportLabel = "🏃 Laufen"

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

    private var mealPageIdx = 0
    private var shopPageIdx = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("remk_wear_standalone_v260", Context.MODE_PRIVATE)
        loadPersistedWatchState()

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 52
            private val SWIPE_VELOCITY = 52

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (abs(diffX) > abs(diffY) * 1.25f) {
                    // Horizontales Wischen -> Nächste / Vorherige Watch-Seite
                    if (abs(diffX) > SWIPE_THRESHOLD && abs(velocityX) > SWIPE_VELOCITY) {
                        triggerVibration(20)
                        if (diffX < 0) {
                            activePage = (activePage + 1) % totalPages
                        } else {
                            activePage = if (activePage - 1 < 0) totalPages - 1 else activePage - 1
                        }
                        renderWatchScreen()
                        return true
                    }
                } else if (abs(diffY) > abs(diffX) * 1.25f) {
                    // Vertikales Wischen nur auf Seite 1 (Übungsliste), Seite 3 (Mahlzeiten) & Seite 4 (Einkauf),
                    // NICHT auf Seite 0 (damit man beim Antippen von "Satz abhaken" nie versehentlich die Übung wechselt!)
                    if (abs(diffY) > SWIPE_THRESHOLD && abs(velocityY) > SWIPE_VELOCITY) {
                        triggerVibration(18)
                        if (activePage == 1) {
                            val exList = trainingDays[currentDayIdx].second
                            if (exList.size > 3) {
                                exListPageOffset = if (diffY < 0) {
                                    if (exListPageOffset + 3 < exList.size) exListPageOffset + 3 else 0
                                } else {
                                    if (exListPageOffset - 3 >= 0) exListPageOffset - 3 else ((exList.size - 1) / 3) * 3
                                }
                                renderWatchScreen()
                                return true
                            }
                        } else if (activePage == 3 && dailyMeals.isNotEmpty()) {
                            mealPageIdx = if (diffY < 0) (mealPageIdx + 1) % dailyMeals.size
                            else if (mealPageIdx - 1 < 0) dailyMeals.size - 1 else mealPageIdx - 1
                            renderWatchScreen()
                            return true
                        } else if (activePage == 4 && shoppingItems.isNotEmpty()) {
                            shopPageIdx = if (diffY < 0) (shopPageIdx + 1) % shoppingItems.size
                            else if (shopPageIdx - 1 < 0) shoppingItems.size - 1 else shopPageIdx - 1
                            renderWatchScreen()
                            return true
                        }
                    }
                }
                return false
            }
        })

        rootFrame = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(ev)
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        pageContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Runder Sicherheitsabstand für Pixel Watch 4 (41mm / 45mm)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }

        rootFrame.addView(
            pageContainer,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        setContentView(rootFrame)

        // Physische Dreh-Krone der Pixel Watch 4 (Rotary Encoder)
        rootFrame.setOnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL &&
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            ) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                val dir = if (delta > 0) 1 else -1
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (activePage == 0) {
                    if (rotaryFocus == "reps") stepReps(dir) else stepWeight(dir * weightStep)
                    true
                } else {
                    activePage = (activePage + dir + totalPages) % totalPages
                    renderWatchScreen()
                    true
                }
            } else {
                false
            }
        }
        rootFrame.requestFocus()

        renderWatchScreen()
        rootFrame.postDelayed({ initWatchHardwareSensors() }, 350L)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (activePage != 0) {
            activePage = 0
            renderWatchScreen()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun switchExerciseSafely(newExIdx: Int) {
        val exList = trainingDays[currentDayIdx].second
        if (exList.isEmpty()) return
        currentExIdx = (newExIdx + exList.size) % exList.size
        val curEx = exList[currentExIdx]
        // Satz-Gedächtnis: Lädt genau den nächsten offenen Satz dieser Übung!
        currentSetIdx = (curEx.doneSets + 1).coerceAtMost(curEx.sets)
        liveKg = curEx.kg
        liveReps = curEx.targetReps
        saveWatchStateAndRefreshTile()
        renderWatchScreen()
    }

    // =========================================================================
    // HAUPT-RENDERER: 100% 0-SCROLL SWIPE-SEITEN FÜR DIE RUNDE PIXEL WATCH 4
    // =========================================================================
    private fun renderWatchScreen() {
        if (!::pageContainer.isInitialized || isFinishing || isDestroyed) return
        pageContainer.removeAllViews()

        // 1. Kompakte obere Status- & Swipe-Punkte-Leiste
        val pendingSync = WearOfflineSyncQueue.getPendingCount(this)
        val syncDot = if (pendingSync > 0) "⚡$pendingSync" else "●Sync"
        val dots = (0 until totalPages).joinToString(" ") { if (it == activePage) "🟠" else "•" }

        val topBar = TextView(this).apply {
            text = "$dots   ❤️$liveBpm  $syncDot"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(4), dp(1), dp(4), dp(3))
            setOnClickListener {
                triggerVibration(25)
                openWatchPlanSyncDialog()
            }
        }
        pageContainer.addView(topBar)

        // Falls auf Screen 0 gerade die Satzpause läuft -> Vollbild Pausen-Cockpit ohne Scrollen!
        if (activePage == 0 && restSecondsLeft > 0) {
            renderFullWatchRestOverlay()
            return
        }

        when (activePage) {
            0 -> renderSwipePage0ActiveSet()
            1 -> renderSwipePage1ExerciseAndDaySwitcher()
            2 -> renderSwipePage2GpsAndPulse()
            3 -> renderSwipePage3NutritionAndWater()
            4 -> renderSwipePage4ShoppingDeck()
        }
    }

    // =========================================================================
    // SEITE 0: AKTIVES WORKOUT-COCKPIT ODER 🏆 WORKOUT-ABSCHLUSS-SCREEN
    // =========================================================================
    private fun renderSwipePage0ActiveSet() {
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx]

        // Prüfe, ob alle Übungen des heutigen Trainingstages komplett abgeschlossen sind!
        val allExercisesDone = exList.isNotEmpty() && exList.all { it.doneSets >= it.sets }
        if (allExercisesDone) {
            renderWorkoutCompletedCelebrationScreen(dayPair.first, exList)
            return
        }

        // A) Antippsichere Übungs-Navigationszeile: [◀] 1/5 Bankdrücken [▶]
        val exNavRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val prevExBtn = buildCompactPill("◀", "#182032", "#38BDF8") {
            triggerVibration(20)
            switchExerciseSafely(currentExIdx - 1)
        }
        val sideTxt = if (curEx.unilateral) (if (unilateralLeft) " (L)" else " (R)") else ""
        val exHeader = TextView(this).apply {
            text = "${currentExIdx + 1}/${exList.size} ${curEx.name.take(15)}$sideTxt"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                if (curEx.unilateral) {
                    unilateralLeft = !unilateralLeft
                } else {
                    activePage = 1
                }
                triggerVibration(20)
                renderWatchScreen()
            }
        }
        val nextExBtn = buildCompactPill("▶", "#182032", "#38BDF8") {
            triggerVibration(20)
            switchExerciseSafely(currentExIdx + 1)
        }
        exNavRow.addView(prevExBtn)
        exNavRow.addView(exHeader)
        exNavRow.addView(nextExBtn)
        pageContainer.addView(exNavRow)

        // B) Visuelle Satz-Fortschritts-Punkte (mit echtem Satz-Gedächtnis pro Übung!)
        val setDots = (1..curEx.sets).joinToString(" ") { s ->
            when {
                s <= curEx.doneSets -> "🟢"
                s == currentSetIdx -> "🟠"
                else -> "⚪"
            }
        }
        val setInfoTv = TextView(this).apply {
            text = "$setDots   SATZ $currentSetIdx VON ${curEx.sets}"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(3))
        }
        pageContainer.addView(setInfoTv)

        // C) GROSSER TOUCH-BUTTON FÜR GEWICHT & WDH. (ÖFFNET DAS 2-SPALTEN ZAHLEN-MENÜ!)
        val valuePickerBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#101827", "#38BDF8", 18)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener {
                triggerVibration(30)
                openTouchNumberMenuDialog()
            }
        }
        val mainValTv = TextView(this).apply {
            text = "⚖️ ${fmtKg(liveKg)} kg  ×  🔄 $liveReps Wdh."
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val subHintTv = TextView(this).apply {
            text = "👆 Zahlen-Menü  •  ${calculateBarbellPlatesPerSide(liveKg)}"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        valuePickerBtn.addView(mainValTv)
        valuePickerBtn.addView(subHintTv)
        pageContainer.addView(
            valuePickerBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(5)
            }
        )

        // D) GROSSER GRÜNER 1-TAP SATZ-ABHAKEN-BUTTON
        val logSetBtn = Button(this).apply {
            text = "✓ SATZ $currentSetIdx ABHAKEN"
            setTextColor(Color.BLACK)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#10B981", "#059669", 16)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                completeCurrentSet()
            }
        }
        pageContainer.addView(
            logSetBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // E) Maximal 2 Buttons im unteren runden Display-Bogen (0% Rand-Abschnitt!)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, 0)
        }
        if (canUndoLastSet) {
            bottomRow.addView(buildCompactPill("↩️ Undo", "#2D1215", "#F43F5E") {
                undoLastCompletedSet()
            })
        } else {
            bottomRow.addView(buildCompactPill("📋 Alle (${exList.size})", "#182032", "#38BDF8") {
                activePage = 1
                renderWatchScreen()
            })
        }
        bottomRow.addView(buildCompactPill("⏳ Besetzt", "#2B1F0E", "#FBBF24") {
            postponeOccupiedExercise()
        })
        pageContainer.addView(bottomRow)
    }

    // =========================================================================
    // 🏆 MOTIVIERENDER WORKOUT-ABSCHLUSS-SCREEN (WIE HEVY / STRAVA)
    // =========================================================================
    private fun renderWorkoutCompletedCelebrationScreen(dayTitle: String, exList: List<WatchExercise>) {
        val totalSets = exList.sumOf { it.sets }
        val totalVol = if (todayMovedVolumeKg > 0) todayMovedVolumeKg else exList.sumOf { (it.kg * it.targetReps * it.sets).toInt() }

        pageContainer.addView(TextView(this).apply {
            text = "🏆 WORKOUT GESCHAFFT!"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        pageContainer.addView(TextView(this).apply {
            text = dayTitle.take(22)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(4))
        })

        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#101827", "#10B981", 16)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        summaryCard.addView(TextView(this).apply {
            text = "✅ $totalSets / $totalSets Sätze erledigt
🔥 $totalVol kg Gesamt-Tonnage"
            setTextColor(Color.WHITE)
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        pageContainer.addView(summaryCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(buildCompactPill("☁️ Jetzt Syncen", "#10B981", "#000000") {
            triggerVibration(35)
            WearOfflineSyncQueue.pushWatchStateToCloudAsync(this) {
                runOnUiThread {
                    Toast.makeText(this, "✅ Workout an Smartphone & Cloud gesendet!", Toast.LENGTH_SHORT).show()
                    renderWatchScreen()
                }
            }
        })
        row.addView(buildCompactPill("🔄 Nächster Tag", "#182032", "#38BDF8") {
            triggerVibration(25)
            exList.forEach { it.doneSets = 0 }
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            currentExIdx = 0
            currentSetIdx = 1
            val first = trainingDays[currentDayIdx].second[0]
            liveKg = first.kg
            liveReps = first.targetReps
            saveWatchStateAndRefreshTile()
            renderWatchScreen()
        })
        pageContainer.addView(row)
    }

    // =========================================================================
    // VOLLBILD PAUSEN-OVERLAY (2x2 RUNDES LAYOUT OHNE RAND-ABSCHNITT)
    // =========================================================================
    private fun renderFullWatchRestOverlay() {
        val m = (restSecondsLeft / 60).toString().padStart(2, '0')
        val s = (restSecondsLeft % 60).toString().padStart(2, '0')
        val curEx = trainingDays[currentDayIdx].second[currentExIdx]

        val lbl = TextView(this).apply {
            text = "⏱️ SATZPAUSE LÄUFT"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val timerTv = TextView(this).apply {
            text = "$m:$s"
            setTextColor(Color.WHITE)
            textSize = 32f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        val nextTv = TextView(this).apply {
            text = "Nächster: $currentSetIdx/${curEx.sets} • ${fmtKg(liveKg)}kg × $liveReps"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(1), 0, dp(4))
            setOnClickListener { openTouchNumberMenuDialog() }
        }

        // Zeile 1: Zeit-Korrektur (-15s | +30s)
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row1.addView(buildCompactPill("−15s", "#182032", "#F8FAFC") {
            restSecondsLeft = (restSecondsLeft - 15).coerceAtLeast(5)
            startRestCountdown(restSecondsLeft)
        })
        row1.addView(buildCompactPill("+30s", "#182032", "#F8FAFC") {
            restSecondsLeft += 30
            startRestCountdown(restSecondsLeft)
        })

        // Zeile 2: Undo & Weiter (perfekt zentriert im runden Bogen)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        if (canUndoLastSet) {
            row2.addView(buildCompactPill("↩️ Undo", "#2D1215", "#F43F5E") {
                undoLastCompletedSet()
            })
        }
        row2.addView(buildCompactPill("Weiter ›", "#10B981", "#000000") {
            stopRestCountdown()
        })

        pageContainer.addView(lbl)
        pageContainer.addView(timerTv)
        pageContainer.addView(nextTv)
        pageContainer.addView(row1)
        pageContainer.addView(row2)
    }

    // =========================================================================
    // NEUES 2-SPALTEN TOUCH-ZAHLEN-MENÜ (WIE HEVY / APPLE WORKOUT – 0% CLIPPING)
    // =========================================================================
    private fun openTouchNumberMenuDialog() {
        val dialogRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#060A12"))
            setPadding(dp(12), dp(10), dp(12), dp(12))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        var dialogRef: AlertDialog? = null

        fun rebuildMenuContent() {
            dialogRoot.removeAllViews()

            // 1. Oben: Kompakte Hantelscheiben-Anzeige & Schrittweiten-Umschalter
            val topInfo = TextView(this).apply {
                text = "🏋️ ${calculateBarbellPlatesPerSide(liveKg)}  (±${fmtKg(weightStep)}kg)"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
                setOnClickListener {
                    triggerVibration(18)
                    weightStep = if (weightStep == 2.5f) 1.25f else if (weightStep == 1.25f) 5.0f else 2.5f
                    rebuildMenuContent()
                }
            }
            dialogRoot.addView(topInfo)

            // 2. Mitte: 2 Große ergonomische Spalten nebeneinander ([⚖️ GEWICHT] | [🔄 WDH.])
            val twoColRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            // LINKE SPALTE: GEWICHT (KG)
            val kgCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg("#101827", if (rotaryFocus == "kg") "#FF5E00" else "#26324B", 16)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    rotaryFocus = "kg"
                    rebuildMenuContent()
                }
            }
            kgCol.addView(buildCompactPill("+${fmtKg(weightStep)}", "#182032", "#38BDF8") {
                rotaryFocus = "kg"
                stepWeight(weightStep)
                rebuildMenuContent()
            })
            kgCol.addView(TextView(this).apply {
                text = "${fmtKg(liveKg)} kg"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
                // Tippen auf die Zahl springt durch 20 / 40 / 60 / 80 / 100 / 120 kg Presets!
                setOnClickListener {
                    triggerVibration(22)
                    rotaryFocus = "kg"
                    val presets = floatArrayOf(20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f, 120f)
                    val nextPreset = presets.firstOrNull { it > liveKg + 0.1f } ?: presets[0]
                    liveKg = nextPreset
                    trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
                    saveWatchStateAndRefreshTile()
                    rebuildMenuContent()
                }
            })
            kgCol.addView(buildCompactPill("−${fmtKg(weightStep)}", "#182032", "#38BDF8") {
                rotaryFocus = "kg"
                stepWeight(-weightStep)
                rebuildMenuContent()
            })

            // RECHTE SPALTE: WIEDERHOLUNGEN (WDH.)
            val repsCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg("#101827", if (rotaryFocus == "reps") "#10B981" else "#26324B", 16)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    rotaryFocus = "reps"
                    rebuildMenuContent()
                }
            }
            repsCol.addView(buildCompactPill("+1 Wdh", "#182032", "#10B981") {
                rotaryFocus = "reps"
                stepReps(1)
                rebuildMenuContent()
            })
            repsCol.addView(TextView(this).apply {
                text = "${liveReps}×"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
                // Tippen auf die Zahl springt durch 6 / 8 / 10 / 12 / 15 / 20 Wdh.!
                setOnClickListener {
                    triggerVibration(22)
                    rotaryFocus = "reps"
                    val rPresets = intArrayOf(5, 6, 8, 10, 12, 15, 20)
                    val nextR = rPresets.firstOrNull { it > liveReps } ?: rPresets[0]
                    liveReps = nextR
                    trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
                    saveWatchStateAndRefreshTile()
                    rebuildMenuContent()
                }
            })
            repsCol.addView(buildCompactPill("−1 Wdh", "#182032", "#10B981") {
                rotaryFocus = "reps"
                stepReps(-1)
                rebuildMenuContent()
            })

            twoColRow.addView(kgCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
            twoColRow.addView(repsCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
            dialogRoot.addView(twoColRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // 3. Unten: 2 klare Abschluss-Buttons im sicheren runden Bogen
            val doneRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            }
            doneRow.addView(buildCompactPill("✓ Übernehmen", "#38BDF8", "#000000") {
                renderWatchScreen()
                dialogRef?.dismiss()
            })
            doneRow.addView(buildCompactPill("✓ Satz loggen", "#10B981", "#000000") {
                dialogRef?.dismiss()
                completeCurrentSet()
            })
            dialogRoot.addView(doneRow)
        }

        // Krone auch direkt im Zahlenmenü unterstützen
        dialogRoot.setOnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL &&
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            ) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                val dir = if (delta > 0) 1 else -1
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if (rotaryFocus == "reps") stepReps(dir) else stepWeight(dir * weightStep)
                rebuildMenuContent()
                true
            } else {
                false
            }
        }

        rebuildMenuContent()
        dialogRef = AlertDialog.Builder(this).setView(dialogRoot).create()
        dialogRef.show()
        dialogRoot.requestFocus()
    }

    // =========================================================================
    // SEITE 1: TAG- & ÜBUNGS-KARUSSELL (ALLE ÜBUNGEN DES TAGES ERREICHBAR!)
    // =========================================================================
    private fun renderSwipePage1ExerciseAndDaySwitcher() {
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second

        val dayBtn = buildCompactPill("📅 ${dayPair.first.take(19)} ⟳", "#182032", "#38BDF8") {
            triggerVibration(25)
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            exListPageOffset = 0
            switchExerciseSafely(0)
        }
        pageContainer.addView(dayBtn)

        val safeOffset = exListPageOffset.coerceIn(0, (exList.size - 1).coerceAtLeast(0))
        val visibleSlice = exList.drop(safeOffset).take(3)

        visibleSlice.forEachIndexed { localIdx, ex ->
            val realIdx = safeOffset + localIdx
            val isSel = (realIdx == currentExIdx)
            val isDone = (ex.doneSets >= ex.sets)
            val statusIcon = if (isDone) "✅" else "${ex.doneSets}/${ex.sets}"
            val row = buildCompactPill(
                "${realIdx + 1}. ${ex.name.take(15)} ($statusIcon)",
                if (isSel) "#FF5E00" else if (isDone) "#0E291E" else "#101827",
                if (isSel) "#000000" else if (isDone) "#10B981" else "#E2E8F0"
            ) {
                triggerVibration(25)
                activePage = 0
                switchExerciseSafely(realIdx)
            }
            pageContainer.addView(row)
        }

        if (exList.size > 3) {
            val pagerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            pagerRow.addView(buildCompactPill("▲ Vorige", "#182032", "#94A3B8") {
                exListPageOffset = if (exListPageOffset - 3 >= 0) exListPageOffset - 3 else ((exList.size - 1) / 3) * 3
                renderWatchScreen()
            })
            pagerRow.addView(buildCompactPill("Weitere ▼", "#182032", "#38BDF8") {
                exListPageOffset = if (exListPageOffset + 3 < exList.size) exListPageOffset + 3 else 0
                renderWatchScreen()
            })
            pageContainer.addView(pagerRow)
        }
    }

    // =========================================================================
    // SEITE 2: STANDALONE GPS & PULS (OHNE HANDY IM WALD / STADION NUTZBAR)
    // =========================================================================
    private fun renderSwipePage2GpsAndPulse() {
        val m = (gpsSeconds / 60).toString().padStart(2, '0')
        val s = (gpsSeconds % 60).toString().padStart(2, '0')
        val kcal = Math.round(gpsDistKm * 68f + (gpsSeconds / 60f) * 5f)

        val sportBtn = buildCompactPill("$activeSportLabel ⟳", "#182032", "#38BDF8") {
            val sports = listOf("🏃 Laufen", "🚴 Radfahren", "⚡ Hyrox/HIIT", "🚶 Gehen")
            activeSportLabel = sports[(sports.indexOf(activeSportLabel) + 1) % sports.size]
            renderWatchScreen()
        }
        pageContainer.addView(sportBtn)

        val metricsTv = TextView(this).apply {
            text = "❤️ $liveBpm BPM  •  📡 ${String.format("%.2f", gpsDistKm)} km\n⏱️ $m:$s  •  🔥 $kcal kcal"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        }
        pageContainer.addView(metricsTv)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(buildCompactPill(if (gpsRunning) "⏸️ Stopp" else "▶️ GPS Start", if (gpsRunning) "#F59E0B" else "#10B981", "#000000") {
            triggerVibration(35)
            toggleGpsTracking()
        })
        row.addView(buildCompactPill("💾 Speichern", "#102336", "#38BDF8") {
            triggerVibration(40)
            WearOfflineSyncQueue.enqueueEvent(
                this,
                "WATCH_CARDIO_SAVED",
                "sport=$activeSportLabel&distKm=${String.format("%.2f", gpsDistKm)}&kcal=$kcal&bpm=$liveBpm"
            )
            Toast.makeText(this, "✅ Einheit offline auf Uhr gespeichert!", Toast.LENGTH_SHORT).show()
            renderWatchScreen()
        })
        pageContainer.addView(row)
    }

    // =========================================================================
    // SEITE 3: ERNÄHRUNG & WASSER (HOCH/RUNTER WISCHEN WECHSELT DIE MAHLZEIT)
    // =========================================================================
    private fun renderSwipePage3NutritionAndWater() {
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }
        val curMeal = dailyMeals[mealPageIdx.coerceIn(0, dailyMeals.size - 1)]

        val statTv = TextView(this).apply {
            text = "🔥 $openKcal kcal offen  •  💧 ${String.format("%.2f", drankL).replace(".", ",")} L"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        pageContainer.addView(statTv)

        val mealCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#101827", if (curMeal.done) "#10B981" else "#FF5E00", 16)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener {
                triggerVibration(25)
                curMeal.done = !curMeal.done
                prefs.edit().putBoolean("meal_done_$mealPageIdx", curMeal.done).apply()
                WearOfflineSyncQueue.enqueueEvent(this@WearMainActivity, "WATCH_MEAL_TOGGLED", "idx=$mealPageIdx&done=${if (curMeal.done) 1 else 0}")
                saveWatchStateAndRefreshTile()
                renderWatchScreen()
            }
        }
        mealCard.addView(TextView(this).apply {
            text = "${curMeal.slot} (${mealPageIdx + 1}/${dailyMeals.size} • ↕ Wischen)"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 11f
            gravity = Gravity.CENTER
        })
        mealCard.addView(TextView(this).apply {
            text = "${if (curMeal.done) "✅ " else "☐ "}${curMeal.title}\n${curMeal.kcal} kcal (Antippen)"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        pageContainer.addView(
            mealCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(5)
            }
        )

        val waterBtn = buildCompactPill("💧 +250 ml Wasser trinken", "#102336", "#38BDF8") {
            triggerVibration(25)
            val nextL = drankL + 0.25f
            prefs.edit().putFloat("drank_liters", nextL).apply()
            WearOfflineSyncQueue.enqueueEvent(this, "WATCH_WATER_ADDED", "addedMl=250&totalL=$nextL")
            saveWatchStateAndRefreshTile()
            renderWatchScreen()
        }
        pageContainer.addView(waterBtn)
    }

    // =========================================================================
    // SEITE 4: SUPERMARKT-EINKAUFS-DECK (0-SCROLL, HOCH/RUNTER WISCHEN)
    // =========================================================================
    private fun renderSwipePage4ShoppingDeck() {
        val openCnt = shoppingItems.count { !it.done }
        val curItem = shoppingItems[shopPageIdx.coerceIn(0, shoppingItems.size - 1)]

        pageContainer.addView(TextView(this).apply {
            text = "🛒 Einkauf ($openCnt offen • ↕ Wischen)"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        val itemBtn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#101827", if (curItem.done) "#10B981" else "#38BDF8", 16)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                triggerVibration(25)
                curItem.done = !curItem.done
                prefs.edit().putBoolean("shop_done_$shopPageIdx", curItem.done).apply()
                WearOfflineSyncQueue.enqueueEvent(this@WearMainActivity, "WATCH_SHOP_TOGGLED", "idx=$shopPageIdx&done=${if (curItem.done) 1 else 0}")
                if (curItem.done && openCnt > 1) {
                    val nextOpen = shoppingItems.indexOfFirst { !it.done }
                    if (nextOpen >= 0) shopPageIdx = nextOpen
                }
                saveWatchStateAndRefreshTile()
                renderWatchScreen()
            }
        }
        itemBtn.addView(TextView(this).apply {
            text = "${if (curItem.done) "✅ Im Korb:\n" else "🛒 Noch offen:\n"}${curItem.title}"
            setTextColor(Color.WHITE)
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })
        pageContainer.addView(
            itemBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
        )

        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        navRow.addView(buildCompactPill("◀ Vorige", "#182032", "#94A3B8") {
            shopPageIdx = if (shopPageIdx - 1 < 0) shoppingItems.size - 1 else shopPageIdx - 1
            renderWatchScreen()
        })
        navRow.addView(buildCompactPill("Nächste ▶", "#182032", "#38BDF8") {
            shopPageIdx = (shopPageIdx + 1) % shoppingItems.size
            renderWatchScreen()
        })
        pageContainer.addView(navRow)
    }

    // =========================================================================
    // HELPER, SENSOREN & SYNC-FUNKTIONEN
    // =========================================================================
    private fun buildCompactPill(label: String, bgHex: String, txtHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(bgHex, txtHex, 12)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun calculateBarbellPlatesPerSide(totalKg: Float): String {
        if (totalKg <= 20f) return "Kurzhantel / Stange"
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

    private fun stepWeight(delta: Float) {
        liveKg = ((liveKg + delta).coerceAtLeast(0f) * 4f).toInt() / 4f
        trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        saveWatchStateAndRefreshTile()
        renderWatchScreen()
    }

    private fun stepReps(delta: Int) {
        liveReps = (liveReps + delta).coerceAtLeast(1)
        trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
        saveWatchStateAndRefreshTile()
        renderWatchScreen()
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
        todayMovedVolumeKg += (liveKg * liveReps).toInt()

        val doneToday = prefs.getInt("done_sets_today", 0) + 1
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        WearOfflineSyncQueue.enqueueEvent(
            this,
            "WATCH_SET_COMPLETED",
            "exId=${curEx.id}&setIdx=$currentSetIdx&kg=$liveKg&reps=$liveReps"
        )

        if (currentSetIdx < curEx.sets) {
            currentSetIdx += 1
        } else {
            // Suche nächste noch nicht abgeschlossene Übung des heutigen Tages
            val nextOpenIdx = exList.indexOfFirst { it.doneSets < it.sets }
            if (nextOpenIdx >= 0) {
                currentExIdx = nextOpenIdx
                val nextEx = exList[currentExIdx]
                currentSetIdx = (nextEx.doneSets + 1).coerceAtMost(nextEx.sets)
                liveKg = nextEx.kg
                liveReps = nextEx.targetReps
            } else {
                // Alle Übungen des Tages erledigt!
                saveWatchStateAndRefreshTile()
                triggerRestAlarmPattern()
                renderWatchScreen()
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
        todayMovedVolumeKg = (todayMovedVolumeKg - (undoKg * undoReps).toInt()).coerceAtLeast(0)

        val doneToday = (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        WearOfflineSyncQueue.removeLastSetEventIfUndo(this, curEx.id, undoSetIdx)
        stopRestCountdown()
        saveWatchStateAndRefreshTile()
        renderWatchScreen()
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
            renderWatchScreen()
        }
    }

    private fun startRestCountdown(seconds: Int) {
        restTimer?.cancel()
        restSecondsLeft = seconds
        restEndTimestampMs = System.currentTimeMillis() + (seconds * 1000L)
        restTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remSec = ((restEndTimestampMs - System.currentTimeMillis()) / 1000L).toInt()
                restSecondsLeft = remSec.coerceAtLeast(0)
                if (activePage == 0) renderWatchScreen()
            }

            override fun onFinish() {
                restSecondsLeft = 0
                triggerRestAlarmPattern()
                if (activePage == 0) renderWatchScreen()
            }
        }.start()
        renderWatchScreen()
    }

    private fun stopRestCountdown() {
        restTimer?.cancel()
        restSecondsLeft = 0
        renderWatchScreen()
    }

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
            if (bpmVal in 42..220) {
                liveBpm = bpmVal
                hasRealHrFix = true
                if (activePage == 2) renderWatchScreen()
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
        if (activePage == 2) renderWatchScreen()
    }

    @SuppressLint("MissingPermission")
    private fun toggleGpsTracking() {
        if (gpsRunning) {
            gpsRunning = false
            gpsTimer?.cancel()
            try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
            renderWatchScreen()
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
                if (activePage == 2) renderWatchScreen()
            }
            override fun onFinish() { gpsRunning = false }
        }.start()
        renderWatchScreen()
    }

    private fun loadPersistedWatchState() {
        try {
            val rawPlan = prefs.getString("custom_watch_plan_json", "") ?: ""
            if (rawPlan.isNotEmpty()) applyDecodedWatchPlanJson(rawPlan)
        } catch (e: Exception) {}

        // Lade gespeichertes Satz-Gedächtnis pro Übung
        try {
            val rawMap = prefs.getString("completed_sets_map_json", "{}") ?: "{}"
            val mapObj = JSONObject(rawMap)
            trainingDays.forEachIndexed { dIdx, pair ->
                pair.second.forEach { ex ->
                    ex.doneSets = mapObj.optInt("${dIdx}_${ex.id}", 0).coerceIn(0, ex.sets)
                }
            }
        } catch (e: Exception) {}

        weightStep = prefs.getFloat("weight_step", 2.5f)
        currentDayIdx = prefs.getInt("day_idx", 0).coerceIn(0, trainingDays.size - 1)
        val exList = trainingDays[currentDayIdx].second
        currentExIdx = prefs.getInt("cur_ex_idx", 0).coerceIn(0, exList.size - 1)
        val curEx = exList[currentExIdx]
        currentSetIdx = prefs.getInt("cur_set_idx", (curEx.doneSets + 1).coerceAtMost(curEx.sets))
        liveKg = prefs.getFloat("cur_kg", curEx.kg)
        liveReps = prefs.getInt("cur_reps", curEx.targetReps)

        dailyMeals.forEachIndexed { idx, m -> m.done = prefs.getBoolean("meal_done_$idx", false) }
        shoppingItems.forEachIndexed { idx, s -> s.done = prefs.getBoolean("shop_done_$idx", false) }
    }

    private fun saveWatchStateAndRefreshTile() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]
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
            .putString("cur_ex_name", curEx.name)
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
                            val wVal = eObj.optDouble("w", 60.0).toFloat()
                            val rVal = eObj.optInt("r", 10)
                            exList.add(
                                WatchExercise(
                                    id = eObj.optString("i", "ex_$j"),
                                    name = eObj.optString("n", "Übung ${j + 1}"),
                                    sets = eObj.optInt("s", 3),
                                    targetReps = rVal,
                                    kg = wVal,
                                    prevRef = "${fmtKg(wVal)}kg × $rVal"
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
                    dailyMeals.add(
                        WatchMeal(
                            slot = mObj.optString("s", "🍴 Mahlzeit"),
                            title = mObj.optString("n", "Gericht"),
                            kcal = mObj.optInt("k", 550),
                            done = prefs.getBoolean("meal_done_$i", false)
                        )
                    )
                }
            }
            val sArr = obj.optJSONArray("sh")
            if (sArr != null && sArr.length() > 0) {
                shoppingItems.clear()
                for (i in 0 until sArr.length()) {
                    val sTxt = sArr.optString(i, "")
                    if (sTxt.isNotEmpty()) {
                        shoppingItems.add(WatchShopItem(sTxt, prefs.getBoolean("shop_done_$i", false)))
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openWatchPlanSyncDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#080C15"))
            setPadding(dp(12), dp(12), dp(12), dp(14))
        }
        var dialogRef: AlertDialog? = null

        box.addView(TextView(this).apply {
            text = "⌚📲 Cloud-Sync (${WearOfflineSyncQueue.getOrCreateSyncKey(this@WearMainActivity)})"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        })

        val codeInput = EditText(this).apply {
            hint = "Lizenz-Key oder REMK-W4 Code"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            setBackgroundColor(Color.parseColor("#101520"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        box.addView(codeInput)

        box.addView(buildCompactPill("🔄 Jetzt synchronisieren", "#38BDF8", "#000000") {
            val raw = codeInput.text?.toString()?.trim() ?: ""
            if (raw.startsWith("REMK-W4-")) {
                try {
                    val decoded = String(Base64.decode(raw.removePrefix("REMK-W4-").trim(), Base64.DEFAULT), Charsets.UTF_8)
                    if (applyDecodedWatchPlanJson(decoded)) {
                        prefs.edit().putString("custom_watch_plan_json", decoded).apply()
                        saveWatchStateAndRefreshTile()
                        renderWatchScreen()
                        dialogRef?.dismiss()
                    }
                } catch (e: Exception) {}
            } else {
                if (raw.isNotEmpty()) WearOfflineSyncQueue.setSyncKey(this, raw)
                WearOfflineSyncQueue.pushWatchStateToCloudAsync(this)
                WearOfflineSyncQueue.pullCloudStateToWatchAsync(this) { ok, cloudObj ->
                    runOnUiThread {
                        if (ok && cloudObj != null) {
                            val planJson = cloudObj.optString("watchPlanCompactJson", "")
                            if (planJson.isNotEmpty() && applyDecodedWatchPlanJson(planJson)) {
                                prefs.edit().putString("custom_watch_plan_json", planJson).apply()
                            }
                            val cSets = cloudObj.optInt("doneSetsToday", -1)
                            if (cSets >= 0) prefs.edit().putInt("done_sets_today", cSets).apply()
                            val cWater = cloudObj.optDouble("drankLiters", -1.0)
                            if (cWater >= 0.0) prefs.edit().putFloat("drank_liters", cWater.toFloat()).apply()
                            val cKcal = cloudObj.optInt("openKcal", -1)
                            if (cKcal >= 0) prefs.edit().putInt("open_kcal", cKcal).apply()
                            saveWatchStateAndRefreshTile()
                            renderWatchScreen()
                        }
                        dialogRef?.dismiss()
                    }
                }
            }
        })

        dialogRef = AlertDialog.Builder(this).setView(box).create()
        dialogRef.show()
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
        restTimer?.cancel()
        gpsTimer?.cancel()
        try { sensorManager?.unregisterListener(this) } catch (e: Exception) {}
        try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
        super.onDestroy()
    }
}
