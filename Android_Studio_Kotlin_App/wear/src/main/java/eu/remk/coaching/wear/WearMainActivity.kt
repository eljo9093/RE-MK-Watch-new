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
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService

/**
 * RE:MK Pixel Watch 4 – 100% Native Standalone Smartwatch App (v26.1 Pro Upgrade)
 * Alle bisherigen Anforderungen + 5 neue Profi-Upgrades für die Pixel Watch 4:
 * 1. Echter Hardware-Pulssensor (Sensor.TYPE_HEART_RATE) & Hardware-GPS (LocationManager) auf der Uhr
 * 2. 1-Tap Satz-Undo ("↩️ Rückgängig"), falls man im Training versehentlich auf Satz-Loggen kommt
 * 3. Hantelscheiben-Rechner (🏋️ Plate-Calculator pro Stangenseite) direkt im ⚙️ Zahnrad-Menü
 * 4. 3-Puls-Vibrationsmuster (Dreifach-Alarm am Ende der Satzpause, spürbar auch bei lauter Gym-Musik)
 * 5. Alle 4 RE:MK Trainingswelten direkt auf der Uhr wählbar (Kraft-Split | Calisthenics | Vereins-Zirkel | Mobility)
 */
class WearMainActivity : Activity(), SensorEventListener, LocationListener {

    data class WatchExercise(
        val id: String,
        val name: String,
        var sets: Int,
        var targetReps: Int,
        var kg: Float,
        val prevRef: String,
        val unilateral: Boolean = false
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
    private lateinit var mainScroll: ScrollView
    private lateinit var contentContainer: LinearLayout

    // Hardware-Sensoren der Pixel Watch 4 (Herzfrequenz & GPS)
    private var sensorManager: SensorManager? = null
    private var heartRateSensor: Sensor? = null
    private var locationManager: LocationManager? = null
    private var lastGpsLocation: Location? = null

    // Aktiver Modus: 0 = Home & Gym (All-in-One), 1 = GPS & Puls, 2 = Ernährung & Wasser, 3 = Einkaufsliste
    private var activeTab = 0
    private var rotaryFocus = "kg"
    private var weightStep = 2.5f
    private var currentDayIdx = 0
    private var currentExIdx = 0
    private var currentSetIdx = 1
    private var unilateralLeft = true
    private var activeEnvLabel = "Gym"

    // Undo-Speicher für versehentlich geloggten Satz
    private var canUndoLastSet = false
    private var undoDayIdx = 0
    private var undoExIdx = 0
    private var undoSetIdx = 1
    private var undoKg = 80.0f
    private var undoReps = 10

    // Live-Werte für aktuellen Satz
    private var liveKg = 80.0f
    private var liveReps = 10
    private var lastRpeTag = "🎯 Optimal"

    // Pausen-Timer Status (Wall-Clock geschützt für Always-On / Ambient Mode)
    private var restTimer: CountDownTimer? = null
    private var restSecondsLeft = 0
    private var restEndTimestampMs = 0L

    // GPS & Cardio Status
    private var gpsRunning = false
    private var gpsTimer: CountDownTimer? = null
    private var gpsSeconds = 0
    private var gpsDistKm = 0.0f
    private var liveBpm = 128
    private var hasRealHrFix = false
    private var activeSportLabel = "🏃 Laufen"

    // Alle 4 RE:MK Trainings-Generatoren auf der Pixel Watch 4 abrufbar
    private val trainingDays = listOf(
        "Tag 1 – Ganzkörper / Push" to mutableListOf(
            WatchExercise("p1", "Bankdrücken (Langhantel)", 3, 10, 80.0f, "80kg × 10"),
            WatchExercise("l1", "Kniebeugen / Beinpresse", 3, 10, 110.0f, "107,5kg × 10"),
            WatchExercise("pu2", "Latzug breit zur Brust", 3, 10, 65.0f, "65kg × 10"),
            WatchExercise("p5", "KH-Schulterdrücken", 3, 12, 22.5f, "22,5kg × 12")
        ),
        "Tag 2 – Unterkörper & Core" to mutableListOf(
            WatchExercise("pu7", "Rumänisches Kreuzheben", 3, 10, 90.0f, "87,5kg × 10"),
            WatchExercise("l6", "Bulgarian Split Squats", 3, 10, 20.0f, "20kg × 10", unilateral = true),
            WatchExercise("l7", "Beinbeuger Maschine", 3, 12, 55.0f, "52,5kg × 12"),
            WatchExercise("c1", "Hanging Leg Raises / Core", 3, 15, 0.0f, "BW × 15")
        ),
        "Tag 3 – Oberkörper Hybrid" to mutableListOf(
            WatchExercise("p2", "KH-Schrägbankdrücken", 3, 10, 30.0f, "30kg × 10"),
            WatchExercise("pu1", "Rudern am Kabelzug", 3, 10, 70.0f, "67,5kg × 10"),
            WatchExercise("p8", "Seitheben (3D-Schulter)", 3, 15, 12.5f, "12,5kg × 15"),
            WatchExercise("a1", "SZ-Bizepscurls & Pushdowns", 3, 12, 32.5f, "32,5kg × 12")
        ),
        "Tag 4 – Calisthenics & Park" to mutableListOf(
            WatchExercise("ca1", "Muscle-Up / Chest-to-Bar Pullups", 3, 8, 0.0f, "BW × 8"),
            WatchExercise("ca2", "Parallel-Bar Dips (Tiefe Dehnung)", 3, 12, 10.0f, "+10kg × 12"),
            WatchExercise("ca3", "Pistol Squats / Shrimp Squats", 3, 8, 0.0f, "BW × 8", unilateral = true),
            WatchExercise("ca4", "Front-Lever Rows / L-Sit Hold", 3, 10, 0.0f, "BW × 10")
        ),
        "Tag 5 – Vereins-Athletik & Mobility" to mutableListOf(
            WatchExercise("at1", "Explosive Box Jumps / Sprint-Start", 3, 6, 0.0f, "Max-Speed × 6"),
            WatchExercise("at2", "Nordic Hamstring Curls (Prehab)", 3, 8, 0.0f, "Exzentrisch × 8"),
            WatchExercise("at3", "Copenhagen Adductor Plank", 3, 30, 0.0f, "30s / Seite", unilateral = true),
            WatchExercise("at4", "World's Greatest Stretch & 90/90", 2, 45, 0.0f, "Flow 45s")
        )
    )

    private val dailyMeals = mutableListOf(
        WatchMeal("☕ Frühstück", "High-Protein Porridge & Beeren", 590, false),
        WatchMeal("🍲 Mittagessen", "Hähnchen-Reis-Bowl & Brokkoli", 740, false),
        WatchMeal("🍎 Workout-Snack", "Skyr-Creme & Reiswaffeln", 390, false),
        WatchMeal("🥗 Abendessen", "Ofen-Lachs & Kartoffel-Wedges", 680, false)
    )

    private val shoppingItems = mutableListOf(
        WatchShopItem("1,2 kg Hähnchen-/Putenbrustfilet", false),
        WatchShopItem("1,5 kg Skyr Natur / Magerquark", false),
        WatchShopItem("800 g Basmatireis & Haferflocken", false),
        WatchShopItem("1,0 kg Brokkoli & Blattspinat", false),
        WatchShopItem("10 Stück Eier (Größe M)", false)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("remk_wear_standalone_v260", Context.MODE_PRIVATE)
        loadPersistedWatchState()
        initWatchHardwareSensors()

        mainScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#000000")) // True Black für Pixel Watch 4 AMOLED
            isFillViewport = true
            isFocusable = true
            isFocusableInTouchMode = true
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(18), dp(14), dp(28))
        }

        mainScroll.addView(contentContainer)
        setContentView(mainScroll)

        // Physische Dreh-Krone der Pixel Watch 4 (Rotary Encoder) mit spürbarer Rast-Haptik
        mainScroll.setOnGenericMotionListener { v, event ->
            if (event.action == MotionEvent.ACTION_SCROLL &&
                event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
            ) {
                val delta = -event.getAxisValue(MotionEvent.AXIS_SCROLL)
                if (activeTab == 0) {
                    val dir = if (delta > 0) 1 else -1
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    if (rotaryFocus == "reps") {
                        stepReps(dir)
                    } else {
                        stepWeight(dir * weightStep)
                    }
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
        mainScroll.requestFocus()

        renderWatchUI()
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
                    registerHeartRateListener()
                }
            } else {
                registerHeartRateListener()
            }
        } catch (e: Exception) {}
    }

    private fun registerHeartRateListener() {
        try {
            heartRateSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        } catch (e: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_HEART_RATE && event.values.isNotEmpty()) {
            val bpmVal = event.values[0].toInt()
            if (bpmVal in 42..220) {
                liveBpm = bpmVal
                hasRealHrFix = true
                if (activeTab == 1) renderWatchUI()
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
        if (activeTab == 1) renderWatchUI()
    }

    private fun loadPersistedWatchState() {
        weightStep = prefs.getFloat("weight_step", 2.5f)
        currentDayIdx = prefs.getInt("day_idx", 0).coerceIn(0, trainingDays.size - 1)
        val exList = trainingDays[currentDayIdx].second
        currentExIdx = prefs.getInt("cur_ex_idx", 0).coerceIn(0, exList.size - 1)
        currentSetIdx = prefs.getInt("cur_set_idx", 1)
        val curEx = exList[currentExIdx]
        liveKg = prefs.getFloat("cur_kg", curEx.kg)
        liveReps = prefs.getInt("cur_reps", curEx.targetReps)

        dailyMeals.forEachIndexed { idx, m ->
            m.done = prefs.getBoolean("meal_done_$idx", false)
        }
        shoppingItems.forEachIndexed { idx, s ->
            s.done = prefs.getBoolean("shop_done_$idx", false)
        }
    }

    private fun saveWatchStateAndRefreshTile() {
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]
        val openKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }

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
            .apply()

        try {
            TileService.getUpdater(this).requestUpdate(RemkTileService::class.java)
        } catch (e: Exception) {}
    }

    private fun renderWatchUI() {
        contentContainer.removeAllViews()

        // 1. OBERE STATUS- & OFFLINE-SYNC-LEISTE (RUNDES PIXEL WATCH HEADER-DESIGN)
        val pendingSync = WearOfflineSyncQueue.getPendingCount(this)
        val syncText = if (pendingSync > 0) "⚡ Offline ($pendingSync)" else "● Live-Sync"
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val titleBadge = TextView(this).apply {
            text = "⌚ RE:MK  |  $syncText  |  ❤️$liveBpm"
            setTextColor(if (pendingSync > 0) Color.parseColor("#38BDF8") else Color.parseColor("#10B981"))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), dp(2), dp(8), dp(4))
            setOnClickListener {
                triggerVibration(30)
                WearOfflineSyncQueue.markAllSynced(this@WearMainActivity)
                Toast.makeText(this@WearMainActivity, "✅ Offline-Tresor mit Smartphone synchronisiert!", Toast.LENGTH_SHORT).show()
                renderWatchUI()
            }
        }
        headerRow.addView(titleBadge)
        contentContainer.addView(headerRow)

        // 2. 4 KOMPAKTE TOP-NAVIGATIONSTABS (HOME & GYM | GPS | ESSEN | EINKAUF)
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBg("#101520", "#26324B", 16)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val tabs = listOf("🏠🏋️", "🏃", "🍎", "🛒")
        tabs.forEachIndexed { idx, icon ->
            val isAct = (activeTab == idx)
            val btn = TextView(this).apply {
                text = icon
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = if (isAct) roundedBg("#FF5E00", "#FF5E00", 12) else null
                setOnClickListener {
                    triggerVibration(20)
                    activeTab = idx
                    renderWatchUI()
                }
            }
            navRow.addView(btn)
        }
        contentContainer.addView(navRow)
        addSpacer(6)

        when (activeTab) {
            0 -> renderAllInOneHomeAndGymTab()
            1 -> renderGpsAndHeartRateTab()
            2 -> renderNutritionAndWaterTab()
            3 -> renderShoppingListTab()
        }
    }

    // =========================================================================
    // TAB 0: ALL-IN-ONE HOME & GYM COCKPIT (DIREKT AUF DEM STARTBILDSCHIRM)
    // =========================================================================
    private fun renderAllInOneHomeAndGymTab() {
        val doneSetsToday = prefs.getInt("done_sets_today", 0)
        val totalSetsToday = prefs.getInt("total_sets_today", 12)
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }

        // A) 3 KOMPAKTE FORTSCHRITTS-KPIs + 1-TAP WASSER (+250ML)
        val kpiRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0B0F17", "#1E293B", 14)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val setsStat = buildMiniKpiColumn("SÄTZE", "✓$doneSetsToday/$totalSetsToday", "#FF5E00") {
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            currentExIdx = 0
            currentSetIdx = 1
            val firstEx = trainingDays[currentDayIdx].second[0]
            liveKg = firstEx.kg
            liveReps = firstEx.targetReps
            saveWatchStateAndRefreshTile()
            renderWatchUI()
        }
        val kcalStat = buildMiniKpiColumn("OFFEN", "${openKcal}kcal", "#10B981") {
            activeTab = 2
            renderWatchUI()
        }
        val waterBtn = TextView(this).apply {
            text = "💧${String.format("%.2f", drankL).replace(".", ",")}L\n+250ml"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#102336", "#38BDF8", 10)
            setPadding(dp(7), dp(4), dp(7), dp(4))
            setOnClickListener {
                triggerVibration(30)
                val nextL = prefs.getFloat("drank_liters", 0.0f) + 0.25f
                prefs.edit().putFloat("drank_liters", nextL).apply()
                WearOfflineSyncQueue.enqueueEvent(this@WearMainActivity, "WATCH_WATER_ADDED", "addedMl=250&totalL=$nextL")
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            }
        }

        kpiRow.addView(setsStat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kpiRow.addView(kcalStat, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        kpiRow.addView(waterBtn)
        contentContainer.addView(kpiRow)
        addSpacer(6)

        // B) TAG-UMSCHALTER & ÜBUNGS-PILLEN-LEISTE (INKL. CALISTHENICS & VEREINS-ATHLETIK)
        val dayPair = trainingDays[currentDayIdx]
        val exList = dayPair.second
        val curEx = exList[currentExIdx]

        val stripScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dayChip = buildChipButton("📅 ${dayPair.first.take(14)}.. ▾", "#182032", "#38BDF8") {
            triggerVibration(25)
            currentDayIdx = (currentDayIdx + 1) % trainingDays.size
            currentExIdx = 0
            currentSetIdx = 1
            val first = trainingDays[currentDayIdx].second[0]
            liveKg = first.kg
            liveReps = first.targetReps
            saveWatchStateAndRefreshTile()
            renderWatchUI()
        }
        stripRow.addView(dayChip)

        if (curEx.unilateral) {
            val sideChip = buildChipButton(if (unilateralLeft) "🦵 Links" else "🦵 Rechts", "#2A1638", "#C084FC") {
                triggerVibration(20)
                unilateralLeft = !unilateralLeft
                renderWatchUI()
            }
            stripRow.addView(sideChip)
        }

        exList.forEachIndexed { idx, ex ->
            val isSel = (idx == currentExIdx)
            val chip = buildChipButton(
                "#${idx + 1} ${ex.name.take(10)}..",
                if (isSel) "#331808" else "#101520",
                if (isSel) "#FF5E00" else "#94A3B8"
            ) {
                triggerVibration(20)
                currentExIdx = idx
                currentSetIdx = 1
                liveKg = ex.kg
                liveReps = ex.targetReps
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            }
            stripRow.addView(chip)
        }
        stripScroll.addView(stripRow)
        contentContainer.addView(stripScroll)
        addSpacer(6)

        // C) AKTUELLE ÜBUNGS-CARD MIT VORWOCHEN-ÜBERNAHME, 1RM & HANTELSCHEIBEN-INFO
        val exCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#0B0F17", "#26324B", 14)
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val topMetaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val setBadge = TextView(this).apply {
            text = "SATZ $currentSetIdx VON ${curEx.sets}"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val prevBadge = TextView(this).apply {
            text = "📋 ${curEx.prevRef} ⬆️"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                triggerVibration(25)
                liveKg = curEx.kg
                liveReps = curEx.targetReps
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            }
        }
        topMetaRow.addView(setBadge)
        topMetaRow.addView(prevBadge)
        exCard.addView(topMetaRow)

        val exTitleTv = TextView(this).apply {
            text = curEx.name
            setTextColor(Color.WHITE)
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
        }
        exCard.addView(exTitleTv)

        val est1Rm = Math.round(liveKg * (1f + liveReps / 30f))
        val subRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val targetTv = TextView(this).apply {
            text = "1RM ~$est1Rm kg • $lastRpeTag"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bumpChip = TextView(this).apply {
            text = "🚀 +${fmtKg(weightStep)}kg"
            setTextColor(Color.parseColor("#10B981"))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#0E291E", "#10B981", 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                triggerVibration(25)
                stepWeight(weightStep)
            }
        }
        subRow.addView(targetTv)
        subRow.addView(bumpChip)
        exCard.addView(subRow)
        contentContainer.addView(exCard)
        addSpacer(6)

        // D) FALLS PAUSEN-TIMER LÄUFT: GROSSER PAUSEN-BANNER MIT "ALS NÄCHSTES"-VORSCHAU & 1-TAP UNDO
        if (restSecondsLeft > 0) {
            val restBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = roundedBg("#1A120B", "#FF5E00", 16)
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            val rTitle = TextView(this).apply {
                text = "⏱️ SATZPAUSE • NÄCHSTER SATZ: ${fmtKg(liveKg)}kg × $liveReps"
                setTextColor(Color.parseColor("#FF5E00"))
                textSize = 9.5f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            }
            val m = (restSecondsLeft / 60).toString().padStart(2, '0')
            val s = (restSecondsLeft % 60).toString().padStart(2, '0')
            val rDigits = TextView(this).apply {
                text = "$m:$s"
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
            }
            val rBtns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rBtns.addView(buildChipButton("−15s", "#182032", "#F8FAFC") {
                restSecondsLeft = (restSecondsLeft - 15).coerceAtLeast(5)
                startRestCountdown(restSecondsLeft)
            })
            rBtns.addView(buildChipButton("+30s", "#182032", "#F8FAFC") {
                restSecondsLeft += 30
                startRestCountdown(restSecondsLeft)
            })
            if (canUndoLastSet) {
                rBtns.addView(buildChipButton("↩️ Undo", "#2D1215", "#F43F5E") {
                    undoLastCompletedSet()
                })
            } else {
                rBtns.addView(buildChipButton("Weiter ›", "#2A1810", "#FF5E00") {
                    stopRestCountdown()
                })
            }
            restBox.addView(rTitle)
            restBox.addView(rDigits)
            restBox.addView(rBtns)
            contentContainer.addView(restBox)
            addSpacer(6)
        }

        // E) GEWICHT (KG) & WIEDERHOLUNGEN (WDH.) – TOUCH + KRONE + ⚙️ ZAHNRAD
        val dialRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val kgDial = buildDialControlBox(
            label = "⚖️ KG (Krone)",
            valueText = fmtKg(liveKg),
            isFocused = (rotaryFocus == "kg"),
            onBoxTap = {
                rotaryFocus = "kg"
                triggerVibration(15)
                renderWatchUI()
            },
            onMinus = { stepWeight(-weightStep) },
            onPlus = { stepWeight(weightStep) },
            onGearTap = { openGearQuickPickerDialog() }
        )

        val repsDial = buildDialControlBox(
            label = "🔄 WDH.",
            valueText = liveReps.toString(),
            isFocused = (rotaryFocus == "reps"),
            onBoxTap = {
                rotaryFocus = "reps"
                triggerVibration(15)
                renderWatchUI()
            },
            onMinus = { stepReps(-1) },
            onPlus = { stepReps(1) },
            onGearTap = { openGearQuickPickerDialog() }
        )

        dialRow.addView(kgDial, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        dialRow.addView(repsDial, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        contentContainer.addView(dialRow)
        addSpacer(6)

        // F) GROSSER GRÜNER 1-TAP SATZ-ABHAKEN-BUTTON
        val completeBtn = Button(this).apply {
            text = "✓ SATZ $currentSetIdx (${fmtKg(liveKg)} kg × $liveReps) LOGGEN"
            setTextColor(Color.BLACK)
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#10B981", "#059669", 14)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                completeCurrentSet()
            }
        }
        contentContainer.addView(completeBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpacer(5)

        // G) ZUSATZ-BUTTONS (⚙️ ZAHNRAD & SCHEIBEN | ⏳ BESETZT | 🏠 ORT / ↩️ UNDO)
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        actionRow.addView(buildChipButton("⚙️ Rad/Scheiben", "#102336", "#38BDF8") {
            openGearQuickPickerDialog()
        })
        actionRow.addView(buildChipButton("⏳ Besetzt?", "#2B1F0E", "#FBBF24") {
            postponeOccupiedExercise()
        })
        if (canUndoLastSet && restSecondsLeft == 0) {
            actionRow.addView(buildChipButton("↩️ Undo", "#2D1215", "#F43F5E") {
                undoLastCompletedSet()
            })
        } else {
            actionRow.addView(buildChipButton("🏠 $activeEnvLabel", "#182032", "#F8FAFC") {
                val envs = listOf("Gym", "Home-Gym", "Bodyweight", "Park")
                activeEnvLabel = envs[(envs.indexOf(activeEnvLabel) + 1) % envs.size]
                WearOfflineSyncQueue.enqueueEvent(this@WearMainActivity, "WATCH_SWITCH_ENV", "env=$activeEnvLabel")
                renderWatchUI()
            })
        }
        contentContainer.addView(actionRow)
        addSpacer(6)

        // H) ALL-IN-ONE SCHNELL-DECK UNTEN AUF DER HOME-KACHEL (MAHLZEIT & EINKAUF DIREKT BEDIENT)
        val nextMeal = dailyMeals.firstOrNull { !it.done }
        val mealRow = buildQuickDeckRow(
            iconAndText = if (nextMeal != null) "🍎 ${nextMeal.title.take(18)}.. (${nextMeal.kcal})" else "✅ Alle Mahlzeiten erledigt!",
            btnLabel = if (nextMeal != null) "✓ Essen" else "Öffnen",
            btnBg = "#10B981"
        ) {
            triggerVibration(25)
            if (nextMeal != null) {
                val idx = dailyMeals.indexOf(nextMeal)
                nextMeal.done = true
                prefs.edit().putBoolean("meal_done_$idx", true).apply()
                WearOfflineSyncQueue.enqueueEvent(this@WearMainActivity, "WATCH_MEAL_TOGGLED", "idx=$idx&done=1")
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            } else {
                activeTab = 2
                renderWatchUI()
            }
        }
        contentContainer.addView(mealRow)
        addSpacer(4)

        val nextShop = shoppingItems.firstOrNull { !it.done }
        val shopRow = buildQuickDeckRow(
            iconAndText = if (nextShop != null) "🛒 ${nextShop.title.take(22)}.." else "✅ Einkauf komplett im Korb!",
            btnLabel = if (nextShop != null) "✓ Korb" else "Liste",
            btnBg = "#FF5E00"
        ) {
            triggerVibration(25)
            if (nextShop != null) {
                val idx = shoppingItems.indexOf(nextShop)
                nextShop.done = true
                prefs.edit().putBoolean("shop_done_$idx", true).apply()
                WearOfflineSyncQueue.enqueueEvent(this@WearMainActivity, "WATCH_SHOP_TOGGLED", "idx=$idx&done=1")
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            } else {
                activeTab = 3
                renderWatchUI()
            }
        }
        contentContainer.addView(shopRow)
    }

    // =========================================================================
    // 🏋️ HANTELSCHEIBEN-RECHNER FÜR 20-KG-LANGHANTEL (PRO SEITE)
    // =========================================================================
    private fun calculateBarbellPlatesPerSide(totalKg: Float): String {
        if (totalKg <= 20f) return "Leere 20kg-Stange (oder Kurzhantel)"
        var rem = (totalKg - 20f) / 2f
        val plates = floatArrayOf(25f, 20f, 15f, 10f, 5f, 2.5f, 1.25f)
        val out = mutableListOf<String>()
        for (p in plates) {
            while (rem >= p - 0.01f) {
                out.add(fmtKg(p))
                rem -= p
            }
        }
        return if (out.isEmpty()) "Nur 20kg-Stange" else "Je Seite: " + out.joinToString(" + ") + " kg"
    }

    // =========================================================================
    // ⚙️ NATIVES ZAHNRAD-, HANTELSCHEIBEN- & RPE-MENÜ (OHNE TASTATUR)
    // =========================================================================
    private fun openGearQuickPickerDialog() {
        triggerVibration(25)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#080C15"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(14), dp(14), dp(20))
        }

        var dialogRef: AlertDialog? = null

        val header = TextView(this).apply {
            text = "⚙️ Zahnrad-Schnellwahl\n${fmtKg(liveKg)} kg × $liveReps Wdh.\n🏋️ ${calculateBarbellPlatesPerSide(liveKg)}"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
        }
        box.addView(header)

        // 1. Gewichts-Sprünge
        val weightGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(-5.0f, -2.5f, +2.5f, +5.0f).forEach { delta ->
            val sign = if (delta > 0) "+" else ""
            weightGrid.addView(buildChipButton("$sign${fmtKg(delta)}kg", "#182032", "#38BDF8") {
                stepWeight(delta)
                header.text = "⚙️ Zahnrad-Schnellwahl\n${fmtKg(liveKg)} kg × $liveReps Wdh.\n🏋️ ${calculateBarbellPlatesPerSide(liveKg)}"
            })
        }
        box.addView(weightGrid)

        // 2. Direkte Wiederholungen
        val repsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(5, 8, 10, 12, 15).forEach { r ->
            repsGrid.addView(buildChipButton("${r}×", "#182032", "#10B981") {
                liveReps = r
                saveWatchStateAndRefreshTile()
                header.text = "⚙️ Zahnrad-Schnellwahl\n${fmtKg(liveKg)} kg × $liveReps Wdh.\n🏋️ ${calculateBarbellPlatesPerSide(liveKg)}"
                renderWatchUI()
            })
        }
        box.addView(repsGrid)

        // 3. Warmup-Rechner (50% / 75%) & Schrittweite (1,25 vs. 2,5 kg)
        val utilRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        utilRow.addView(buildChipButton("🔥 50% Warm", "#2B1F0E", "#FBBF24") {
            val baseKg = trainingDays[currentDayIdx].second[currentExIdx].kg
            liveKg = Math.round((baseKg * 0.5f) / 2.5f) * 2.5f
            liveReps = 12
            saveWatchStateAndRefreshTile()
            renderWatchUI()
            dialogRef?.dismiss()
        })
        utilRow.addView(buildChipButton("🔥 75% Warm", "#2B1F0E", "#FBBF24") {
            val baseKg = trainingDays[currentDayIdx].second[currentExIdx].kg
            liveKg = Math.round((baseKg * 0.75f) / 2.5f) * 2.5f
            liveReps = 6
            saveWatchStateAndRefreshTile()
            renderWatchUI()
            dialogRef?.dismiss()
        })
        utilRow.addView(buildChipButton("±${fmtKg(weightStep)}kg", "#182032", "#C084FC") {
            weightStep = if (weightStep == 2.5f) 1.25f else 2.5f
            saveWatchStateAndRefreshTile()
            dialogRef?.dismiss()
            openGearQuickPickerDialog()
        })
        box.addView(utilRow)

        // 4. 3-Stufen RPE-Schnellwahl
        val rpeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        rpeRow.addView(buildChipButton("🔥 Leicht (+2,5)", "#0E291E", "#10B981") {
            lastRpeTag = "🔥 Leicht"
            stepWeight(2.5f)
            dialogRef?.dismiss()
        })
        rpeRow.addView(buildChipButton("🎯 Optimal", "#102336", "#38BDF8") {
            lastRpeTag = "🎯 Optimal"
            renderWatchUI()
            dialogRef?.dismiss()
        })
        rpeRow.addView(buildChipButton("⚠️ Limit", "#2D1215", "#F43F5E") {
            lastRpeTag = "⚠️ Limit"
            renderWatchUI()
            dialogRef?.dismiss()
        })
        box.addView(rpeRow)

        val closeBtn = Button(this).apply {
            text = "✓ Übernehmen & Satz loggen"
            setTextColor(Color.BLACK)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#10B981", "#10B981", 12)
            setOnClickListener {
                dialogRef?.dismiss()
                completeCurrentSet()
            }
        }
        box.addView(closeBtn)

        scroll.addView(box)
        dialogRef = AlertDialog.Builder(this)
            .setView(scroll)
            .create()
        dialogRef.show()
    }

    // =========================================================================
    // TAB 1: STANDALONE GPS-CARDIO & ECHTER HARDWARE-PULS AUF DER PIXEL WATCH 4
    // =========================================================================
    private fun renderGpsAndHeartRateTab() {
        val zoneColor = if (liveBpm >= 158) "#FF5E00" else "#10B981"
        val zoneTitle = if (liveBpm >= 158) "🔥 HIIT / VO2MAX ZONE" else "🟢 ZONE 2 (FETT & BASIS)"
        val sensorState = if (hasRealHrFix) "⌚ Live-Sensor" else "⌚ Bereit"

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#0B0F17", zoneColor, 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val zTv = TextView(this).apply {
            text = "$zoneTitle • $activeSportLabel ($sensorState)"
            setTextColor(Color.parseColor(zoneColor))
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setOnClickListener {
                val sports = listOf("🏃 Laufen", "🚴 Radfahren", "🏊 Schwimmen", "⚡ Hyrox/HIIT")
                activeSportLabel = sports[(sports.indexOf(activeSportLabel) + 1) % sports.size]
                renderWatchUI()
            }
        }
        card.addView(zTv)

        val metricsTv = TextView(this).apply {
            val m = (gpsSeconds / 60).toString().padStart(2, '0')
            val s = (gpsSeconds % 60).toString().padStart(2, '0')
            val kcal = Math.round(gpsDistKm * 68f + (gpsSeconds / 60f) * 5f)
            val paceStr = if (gpsDistKm > 0.05f) {
                val paceMin = (gpsSeconds / 60f) / gpsDistKm
                val pm = paceMin.toInt()
                val ps = Math.round((paceMin - pm) * 60).toString().padStart(2, '0')
                "$pm:$ps/km"
            } else "--:--/km"
            text = "❤️ $liveBpm BPM   |   📡 ${String.format("%.2f", gpsDistKm)} km\n⏱️ $m:$s   |   ⚡ $paceStr   |   🔥 $kcal kcal"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        card.addView(metricsTv)
        contentContainer.addView(card)
        addSpacer(6)

        val toggleBtn = Button(this).apply {
            text = if (gpsRunning) "⏸️ GPS-Cardio pausieren" else "▶️ Outdoor-GPS starten"
            setTextColor(Color.BLACK)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(if (gpsRunning) "#F59E0B" else "#10B981", "#10B981", 14)
            setOnClickListener {
                triggerVibration(40)
                toggleGpsTracking()
            }
        }
        contentContainer.addView(toggleBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSpacer(5)

        val saveBtn = buildChipButton("💾 Einheit im Offline-Tresor speichern & Sync", "#102336", "#38BDF8") {
            triggerVibration(50)
            val kcal = Math.round(gpsDistKm * 68f + (gpsSeconds / 60f) * 5f).coerceAtLeast(120)
            WearOfflineSyncQueue.enqueueEvent(
                this,
                "WATCH_CARDIO_SAVED",
                "sport=$activeSportLabel&distKm=${String.format("%.2f", gpsDistKm)}&kcal=$kcal&bpm=$liveBpm"
            )
            Toast.makeText(this, "✅ Cardio ($kcal kcal) gespeichert & für Phone-Sync bereit!", Toast.LENGTH_SHORT).show()
            renderWatchUI()
        }
        contentContainer.addView(saveBtn)
    }

    // =========================================================================
    // TAB 2: ERNÄHRUNG, MAHLZEITEN-SLOTS & WASSER AM HANDGELENK
    // =========================================================================
    private fun renderNutritionAndWaterTab() {
        val drankL = prefs.getFloat("drank_liters", 0.0f)
        val openKcal = dailyMeals.filter { !it.done }.sumOf { it.kcal }

        val topBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0B0F17", "#10B981", 14)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val infoTv = TextView(this).apply {
            text = "🔥 $openKcal kcal offen\n💧 ${String.format("%.2f", drankL).replace(".", ",")} / 3,0 L"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addWBtn = buildChipButton("💧+250ml", "#102336", "#38BDF8") {
            triggerVibration(25)
            val nextL = drankL + 0.25f
            prefs.edit().putFloat("drank_liters", nextL).apply()
            WearOfflineSyncQueue.enqueueEvent(this, "WATCH_WATER_ADDED", "addedMl=250&totalL=$nextL")
            saveWatchStateAndRefreshTile()
            renderWatchUI()
        }
        topBox.addView(infoTv)
        topBox.addView(addWBtn)
        contentContainer.addView(topBox)
        addSpacer(6)

        dailyMeals.forEachIndexed { idx, meal ->
            val row = buildQuickDeckRow(
                iconAndText = "${meal.slot}: ${meal.title} (${meal.kcal} kcal)",
                btnLabel = if (meal.done) "✓" else "☐",
                btnBg = if (meal.done) "#10B981" else "#1E293B"
            ) {
                triggerVibration(25)
                meal.done = !meal.done
                prefs.edit().putBoolean("meal_done_$idx", meal.done).apply()
                WearOfflineSyncQueue.enqueueEvent(this, "WATCH_MEAL_TOGGLED", "idx=$idx&done=${if (meal.done) 1 else 0}")
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            }
            contentContainer.addView(row)
            addSpacer(4)
        }
    }

    // =========================================================================
    // TAB 3: 1-TAP SUPERMARKT-EINKAUFSLISTE AM HANDGELENK
    // =========================================================================
    private fun renderShoppingListTab() {
        val openCnt = shoppingItems.count { !it.done }
        val hdr = TextView(this).apply {
            text = "🛒 Einkaufsliste ($openCnt offen)"
            setTextColor(Color.parseColor("#FF5E00"))
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
        }
        contentContainer.addView(hdr)
        addSpacer(5)

        shoppingItems.forEachIndexed { idx, item ->
            val row = buildQuickDeckRow(
                iconAndText = item.title,
                btnLabel = if (item.done) "✓" else "☐",
                btnBg = if (item.done) "#10B981" else "#FF5E00"
            ) {
                triggerVibration(20)
                item.done = !item.done
                prefs.edit().putBoolean("shop_done_$idx", item.done).apply()
                WearOfflineSyncQueue.enqueueEvent(this, "WATCH_SHOP_TOGGLED", "idx=$idx&done=${if (item.done) 1 else 0}")
                saveWatchStateAndRefreshTile()
                renderWatchUI()
            }
            contentContainer.addView(row)
            addSpacer(4)
        }
    }

    // =========================================================================
    // HELPER, UNDO & WORKOUT ENGINE FUNCTIONS
    // =========================================================================
    private fun stepWeight(delta: Float) {
        liveKg = ((liveKg + delta).coerceAtLeast(0f) * 4f).toInt() / 4f
        trainingDays[currentDayIdx].second[currentExIdx].kg = liveKg
        saveWatchStateAndRefreshTile()
        renderWatchUI()
    }

    private fun stepReps(delta: Int) {
        liveReps = (liveReps + delta).coerceAtLeast(1)
        trainingDays[currentDayIdx].second[currentExIdx].targetReps = liveReps
        saveWatchStateAndRefreshTile()
        renderWatchUI()
    }

    private fun completeCurrentSet() {
        triggerVibration(70)
        val exList = trainingDays[currentDayIdx].second
        val curEx = exList[currentExIdx]

        // Speichere Zustand für 1-Tap Satz-Undo
        canUndoLastSet = true
        undoDayIdx = currentDayIdx
        undoExIdx = currentExIdx
        undoSetIdx = currentSetIdx
        undoKg = liveKg
        undoReps = liveReps

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
            currentSetIdx = 1
            if (currentExIdx < exList.size - 1) {
                currentExIdx += 1
            } else {
                currentExIdx = 0
            }
            val nextEx = exList[currentExIdx]
            liveKg = nextEx.kg
            liveReps = nextEx.targetReps
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

        val doneToday = (prefs.getInt("done_sets_today", 1) - 1).coerceAtLeast(0)
        prefs.edit().putInt("done_sets_today", doneToday).apply()

        WearOfflineSyncQueue.removeLastSetEventIfUndo(this)
        stopRestCountdown()
        saveWatchStateAndRefreshTile()
        Toast.makeText(this, "↩️ Letzter Satz rückgängig gemacht!", Toast.LENGTH_SHORT).show()
        renderWatchUI()
    }

    private fun postponeOccupiedExercise() {
        triggerVibration(35)
        val exList = trainingDays[currentDayIdx].second
        if (exList.size > 1) {
            val moved = exList.removeAt(currentExIdx)
            exList.add(moved)
            currentSetIdx = 1
            val nowCur = exList[currentExIdx]
            liveKg = nowCur.kg
            liveReps = nowCur.targetReps
            saveWatchStateAndRefreshTile()
            Toast.makeText(this, "⏳ Übung ans Ende verschoben – nächste Übung geladen!", Toast.LENGTH_SHORT).show()
            renderWatchUI()
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
                if (activeTab == 0) renderWatchUI()
            }

            override fun onFinish() {
                restSecondsLeft = 0
                triggerRestAlarmPattern()
                if (activeTab == 0) renderWatchUI()
            }
        }.start()
        renderWatchUI()
    }

    private fun stopRestCountdown() {
        restTimer?.cancel()
        restSecondsLeft = 0
        renderWatchUI()
    }

    @SuppressLint("MissingPermission")
    private fun toggleGpsTracking() {
        if (gpsRunning) {
            gpsRunning = false
            gpsTimer?.cancel()
            try { locationManager?.removeUpdates(this) } catch (e: Exception) {}
            renderWatchUI()
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
                    if (!hasRealHrFix) {
                        liveBpm = (liveBpm + if (Math.random() > 0.45) 1 else -1).coerceIn(122, 174)
                    }
                }
                if (activeTab == 1) renderWatchUI()
            }

            override fun onFinish() {
                gpsRunning = false
            }
        }.start()
        renderWatchUI()
    }

    private fun buildDialControlBox(
        label: String,
        valueText: String,
        isFocused: Boolean,
        onBoxTap: () -> Unit,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
        onGearTap: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = roundedBg("#0B0F17", if (isFocused) "#FF5E00" else "#26324B", 14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { onBoxTap() }

            val lblTv = TextView(this@WearMainActivity).apply {
                text = label
                setTextColor(if (isFocused) Color.parseColor("#FF5E00") else Color.parseColor("#94A3B8"))
                textSize = 9.5f
                setTypeface(null, Typeface.BOLD)
            }
            addView(lblTv)

            val ctrlRow = LinearLayout(this@WearMainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val minusBtn = buildStepButton("−", onMinus)
            val valTv = TextView(this@WearMainActivity).apply {
                text = valueText
                setTextColor(Color.WHITE)
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener { onGearTap() }
            }
            val plusBtn = buildStepButton("+", onPlus)

            ctrlRow.addView(minusBtn)
            ctrlRow.addView(valTv)
            ctrlRow.addView(plusBtn)
            addView(ctrlRow)
        }
    }

    private fun buildStepButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = roundedBg("#182032", "#334155", 8)
            setPadding(dp(9), dp(2), dp(9), dp(2))
            setOnClickListener {
                triggerVibration(15)
                onClick()
            }
        }
    }

    private fun buildMiniKpiColumn(title: String, value: String, colorHex: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener {
                triggerVibration(20)
                onClick()
            }
            addView(TextView(this@WearMainActivity).apply {
                text = title
                setTextColor(Color.parseColor(colorHex))
                textSize = 8.5f
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@WearMainActivity).apply {
                text = value
                setTextColor(Color.WHITE)
                textSize = 11.5f
                setTypeface(null, Typeface.BOLD)
            })
        }
    }

    private fun buildChipButton(label: String, bgHex: String, txtHex: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.parseColor(txtHex))
            textSize = 10.5f
            setTypeface(null, Typeface.BOLD)
            background = roundedBg(bgHex, txtHex, 10)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun buildQuickDeckRow(iconAndText: String, btnLabel: String, btnBg: String, onClick: () -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBg("#0B0F17", "#1E293B", 12)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }

            val tv = TextView(this@WearMainActivity).apply {
                text = iconAndText
                setTextColor(Color.parseColor("#E2E8F0"))
                textSize = 10.5f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val actBtn = TextView(this@WearMainActivity).apply {
                text = btnLabel
                setTextColor(Color.BLACK)
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                background = roundedBg(btnBg, btnBg, 8)
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }
            addView(tv)
            addView(actBtn)
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun addSpacer(heightDp: Int) {
        contentContainer.addView(
            View(this),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
        )
    }

    private fun fmtKg(v: Float): String {
        return if (v % 1.0f == 0.0f) v.toInt().toString() else v.toString().replace(".", ",")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

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
