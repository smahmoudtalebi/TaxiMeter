package ir.taximeter.core

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import ir.taximeter.core.data.FareConfigEntity
import ir.taximeter.core.data.PassengerTripEntity
import ir.taximeter.core.data.TaxiDatabase
import ir.taximeter.core.data.TaxiRepository
import ir.taximeter.core.data.BoardPassengerResult
import ir.taximeter.core.data.AlightPassengerResult
import ir.taximeter.core.data.FareAdjustmentResult
import ir.taximeter.core.data.SessionCloseResult
import ir.taximeter.core.location.LocationPermissionController
import ir.taximeter.core.location.LocationTrackingService
import ir.taximeter.core.payment.PosPaymentGateway
import ir.taximeter.core.payment.PosPaymentRequest
import ir.taximeter.core.payment.PosPaymentResult
import ir.taximeter.core.payment.UnconfiguredBluetoothPosGateway
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/** First integrated Persian driver screen. Native Android views are used to keep the prototype lightweight. */
class MainActivity : ComponentActivity() {
    private val db by lazy { TaxiDatabase.getInstance(this) }
    private val repository by lazy { TaxiRepository(db) }
    /** Replace with the vendor-specific adapter after the exact POS model/SDK is selected. */
    private val posPaymentGateway: PosPaymentGateway by lazy { UnconfiguredBluetoothPosGateway() }
    private lateinit var locationPermissions: LocationPermissionController
    private var currentSessionId: Long? = null
    private var pendingLocationAction: String? = null
    private var screen: LinearLayout? = null
    private var currentScrollView: ScrollView? = null
    private var actionInProgress = false
    private var selectedTab = TAB_CURRENT
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (activityVisible && selectedTab == TAB_CURRENT && !actionInProgress) {
                lifecycleScope.launch { refreshScreen() }
            }
            if (activityVisible) mainHandler.postDelayed(this, UI_REFRESH_INTERVAL_MS)
        }
    }
    private var activityVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "تاکسی‌متر گردشی"
        locationPermissions = LocationPermissionController(this) { granted ->
            val action = pendingLocationAction
            pendingLocationAction = null
            if (!granted) {
                toast("برای ثبت مسیر، مجوز موقعیت مکانی لازم است.")
                lifecycleScope.launch { refreshScreen() }
            } else {
                when (action) {
                    ACTION_START_SHIFT -> startShiftAndGps()
                    ACTION_RESUME_GPS -> resumeGps()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityVisible = true
        lifecycleScope.launch { refreshScreen() }
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, UI_REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        activityVisible = false
        mainHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    private suspend fun refreshScreen() {
        val previousScrollY = currentScrollView?.scrollY ?: 0
        val dao = db.taxiDao()
        val activeSession = dao.getActiveSession()
        val displaySession = activeSession ?: dao.getLatestSession()
        currentSessionId = activeSession?.id
        val fare = dao.getActiveFareConfig()
        val allTrips = displaySession?.let { dao.getTripsForSession(it.id) }.orEmpty()
        val activeTrips = allTrips.filter { it.status == "ACTIVE" }
        val latestPoint = displaySession?.let { dao.getLatestLocationPoint(it.id) }
        val latestValidPoint = displaySession?.let { dao.getLatestValidLocationPoint(it.id) }
        val now = System.currentTimeMillis()
        val gpsFresh = latestValidPoint != null && now >= latestValidPoint.recordedAtMs &&
            now - latestValidPoint.recordedAtMs <= GPS_FRESHNESS_MS
        val gpsStatus = when {
            activeSession == null -> "GPS: شیفت فعال نیست"
            latestPoint == null -> "GPS: هنوز نقطه‌ای ثبت نشده"
            !gpsFresh -> "GPS: موقعیت تازه نداریم؛ مسیر را بررسی کنید"
            latestPoint.validity != "VALID" -> "GPS: آخرین نقطه نامعتبر است"
            else -> "GPS: فعال · دقت آخرین نقطه ${latestValidPoint?.accuracyMeters?.let { "%.0f متر".format(Locale.US, it) } ?: "نامشخص"}"
        }

        val scroll = ScrollView(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(10), dp(12), dp(24))
            setBackgroundColor(0xFFF2F4F7.toInt())
        }
        scroll.addView(root)
        screen = root

        root.addView(text("تاکسی‌متر گردشی", 24f, true).apply { setPadding(0, 0, 0, dp(3)) })
        root.addView(text("مدیریت مستقل سفرهای حداکثر چهار مسافر", 13f, false, 0xFF5F6368.toInt()))
        root.addView(spacer(8))

        val shiftStatus = when {
            activeSession != null -> "شیفت فعال · شناسه ${activeSession.id}"
            displaySession != null -> "آخرین شیفت بسته‌شده · شناسه ${displaySession.id}"
            else -> "شیفت شروع نشده"
        }
        root.addView(card().apply {
            addView(text(shiftStatus, 15f, true))
            addView(text(gpsStatus, 13f, true, if (gpsFresh) 0xFF287D46.toInt() else 0xFF9A5B00.toInt()))
            addView(text("تعرفه فعال: ${fare?.let { "نسخه ${it.version} · هر کیلومتر ${money(it.ratePerKmToman)} · حداقل ${money(it.minimumFareToman)}" } ?: "تعریف نشده"}", 12f, false))
            addView(text("توقف به کرایه اضافه نمی‌شود. کرایه‌های روی کارت‌ها تخمینی‌اند؛ مبلغ قطعی فقط پس از اعتبارسنجی مسیر تعیین می‌شود.", 11f, false, 0xFF5F6368.toInt()))
        })
        root.addView(spacer(8))

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val currentTabButton = actionButton("سفر جاری") { selectedTab = TAB_CURRENT; lifecycleScope.launch { refreshScreen() } }
        val managementTabButton = actionButton("مدیریت شیفت و تعرفه") { selectedTab = TAB_MANAGEMENT; lifecycleScope.launch { refreshScreen() } }
        currentTabButton.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) }
        managementTabButton.layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) }
        currentTabButton.alpha = if (selectedTab == TAB_CURRENT) 1f else 0.65f
        managementTabButton.alpha = if (selectedTab == TAB_MANAGEMENT) 1f else 0.65f
        tabs.addView(currentTabButton)
        tabs.addView(managementTabButton)
        root.addView(tabs)
        root.addView(spacer(8))

        if (selectedTab == TAB_MANAGEMENT) {
            root.addView(text("مدیریت شیفت", 19f, true))
            root.addView(spacer(4))
            if (activeSession == null) {
                root.addView(actionButton("شروع شیفت و ثبت GPS") { beginStartShiftFlow(fare) })
            } else {
                root.addView(actionButton("فعال‌سازی / ادامه ثبت GPS") { requestLocationAction(ACTION_RESUME_GPS) })
                root.addView(actionButton("پایان شیفت") { confirmCloseShift(activeSession.id, activeTrips.size) })
                if (activeTrips.isNotEmpty()) {
                    root.addView(text("برای پایان شیفت، ابتدا ${activeTrips.size} سفر فعال را ببندید.", 13f, true, 0xFF9A5B00.toInt()))
                }
            }
            root.addView(spacer(10))
            root.addView(text("تعرفه", 19f, true))
            root.addView(text("هر تغییر، نسخه جدیدی از تعرفه می‌سازد و فقط برای سفرهای بعدی استفاده می‌شود.", 13f, false, 0xFF5F6368.toInt()))
            root.addView(actionButton("تعریف / تغییر تعرفه") { showFareConfigDialog(fare) })
            root.addView(spacer(10))
            root.addView(text("مصرف باتری", 17f, true))
            root.addView(text("صفحه فقط در حالت نمایش، هر ۵ ثانیه به‌روز می‌شود. ثبت GPS در سرویس جداگانه ادامه دارد؛ خاموش شدن صفحه، ثبت مسیر را متوقف نمی‌کند.", 13f, false))
            root.addView(spacer(10))
            root.addView(text("وضعیت GPS: ${latestPoint?.let { if (it.validity == "VALID") "آخرین نقطه معتبر" else "آخرین نقطه نامعتبر" } ?: "بدون داده"}", 13f, false))
            root.addView(text("اگر موقعیت تازه نیست یا GPS قطع شده، کرایه نهایی نباید بدون بررسی مسیر تأیید شود.", 12f, false, 0xFF8A3B30.toInt()))
        } else {
            root.addView(text("نمای کابین", 19f, true))
            root.addView(text("وضعیت صندلی‌ها و برآورد کرایه هر مسافر مستقل است.", 12f, false, 0xFF5F6368.toInt()))
            root.addView(spacer(5))

            // Front row: driver and front passenger seat 1.
            val frontRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER
            }
            val driverCard = card().apply {
                setBackgroundColor(0xFFE3EAF4.toInt())
                addView(text("راننده", 16f, true, 0xFF214A78.toInt()))
                addView(text(if (activeSession != null) "شیفت فعال" else "شیفت غیرفعال", 12f, false))
                addView(text(if (gpsFresh) "GPS تازه" else "GPS بررسی شود", 12f, true, if (gpsFresh) 0xFF287D46.toInt() else 0xFF9A5B00.toInt()))
                addView(text("${activeTrips.size}/4 مسافر", 12f, false))
            }
            driverCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            frontRow.addView(driverCard)
            val frontPassenger = createSeatCard(1, activeTrips.firstOrNull { it.seatNumber == 1 }, activeSession != null)
            frontPassenger.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) }
            frontRow.addView(frontPassenger)
            root.addView(frontRow)
            root.addView(spacer(6))

            // Rear bench: seats 2, 3 and 4.
            val rearRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                gravity = Gravity.CENTER
            }
            for (seat in 2..4) {
                val seatView = createSeatCard(seat, activeTrips.firstOrNull { it.seatNumber == seat }, activeSession != null)
                seatView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (seat != 2) marginStart = dp(3)
                    if (seat != 4) marginEnd = dp(3)
                }
                rearRow.addView(seatView)
            }
            root.addView(rearRow)
            root.addView(spacer(12))

            root.addView(text("سفرهای اخیر", 18f, true))
            val recentTrips = allTrips.sortedByDescending { it.boardedAtMs }.take(8)
            if (recentTrips.isEmpty()) {
                root.addView(text("هنوز سفری در این شیفت ثبت نشده است.", 13f, false, 0xFF5F6368.toInt()))
            } else {
                recentTrips.forEach { trip ->
                    val historyCard = card()
                    val status = when {
                        trip.status == "ACTIVE" -> "فعال"
                        trip.fareStatus == "NEEDS_REVIEW" || trip.reviewStatus == "REQUIRED" -> "بسته‌شده · نیازمند بررسی"
                        else -> "بسته‌شده"
                    }
                    historyCard.addView(text("صندلی ${trip.seatNumber} · ${trip.id.take(8)}…", 13f, true))
                    historyCard.addView(text("$status · مسافت ${trip.distanceMeters} متر", 12f, false))
                    historyCard.addView(text("برآورد ثبت‌شده: ${trip.calculatedFareToman?.let { money(it) } ?: "—"} · نهایی: ${trip.finalFareToman?.let { money(it) } ?: "در انتظار بررسی"}", 11f, false))
                    if (trip.status == "CLOSED" && trip.fareStatus == "NEEDS_REVIEW") {
                        historyCard.addView(actionButton("بررسی و تعیین کرایه") {
                            lifecycleScope.launch {
                                val config = db.taxiDao().getFareConfig(trip.fareConfigId)
                                if (config == null) toast("تعرفه سفر پیدا نشد.")
                                else showUncertainFareDialog(trip, trip.distanceMeters, config, null)
                            }
                        })
                    }
                    if (trip.status == "CLOSED" && trip.calculatedFareToman != null && trip.fareStatus == "FINAL") {
                        historyCard.addView(actionButton("اصلاح کرایه با ثبت علت") { showFareAdjustmentDialog(trip) })
                        historyCard.addView(actionButton("پرداخت / ارسال به پوز بلوتوثی") { showPaymentDialog(trip.finalFareToman ?: trip.calculatedFareToman ?: 0L, trip.id) })
                    }
                    root.addView(historyCard)
                    root.addView(spacer(5))
                }
            }
        }
        root.addView(spacer(10))
        root.addView(text("نمونه اولیه است؛ پیش از استفاده واقعی باید روی گوشی و مسیرهای میدانی آزمون شود.", 11f, false, 0xFF8A3B30.toInt()))
        currentScrollView = scroll
        setContentView(scroll)
        scroll.post { scroll.scrollTo(0, previousScrollY) }
    }

    private suspend fun createSeatCard(seat: Int, trip: PassengerTripEntity?, shiftActive: Boolean): LinearLayout {
        val seatCard = card().apply { setPadding(dp(8), dp(8), dp(8), dp(8)) }
        seatCard.addView(text("صندلی $seat", 14f, true))
        if (trip == null) {
            seatCard.setBackgroundColor(0xFFE8F5EC.toInt())
            seatCard.addView(text("آزاد", 13f, true, 0xFF287D46.toInt()))
            seatCard.addView(actionButton("سوار کردن") {
                val session = currentSessionId
                if (session == null) toast("ابتدا شیفت را شروع کنید.") else confirmBoardPassenger(session, seat)
            }.apply { isEnabled = shiftActive && !actionInProgress })
        } else {
            val dao = db.taxiDao()
            val distance = dao.getAllocatedDistanceMeters(trip.id)
            val fareConfig = dao.getFareConfig(trip.fareConfigId)
            val needsReview = trip.reviewStatus == "REQUIRED" || trip.hasUnresolvedGap || trip.distanceStatus == "GAP_DETECTED"
            seatCard.setBackgroundColor(if (needsReview) 0xFFFFF0D8.toInt() else 0xFFE8EEF9.toInt())
            seatCard.addView(text(if (needsReview) "نیازمند بررسی" else "مسافر سوار است", 12f, true,
                if (needsReview) 0xFF9A5B00.toInt() else 0xFF2256A0.toInt()))
            seatCard.addView(text("${distance} متر", 16f, true))
            val estimate = fareConfig?.let { estimateFare(distance, it) }
            seatCard.addView(text("برآورد: ${estimate?.let { money(it) } ?: "تعرفه نامشخص"}", 12f, true))
            seatCard.addView(text(if (needsReview) "مسافت ثبت‌شده؛ مسیر ناقص/نامطمئن" else "تعرفه روی مسافت GPS ثبت‌شده اعمال می‌شود", 10f, false, 0xFF5F6368.toInt()))
            seatCard.addView(actionButton("پیاده‌شدن") { confirmAlightPassenger(trip) }.apply { isEnabled = !actionInProgress })
        }
        return seatCard
    }

    private fun estimateFare(distanceMeters: Long, fare: FareConfigEntity): Long? {
        return try {
            val product = Math.multiplyExact(distanceMeters.coerceAtLeast(0L), fare.ratePerKmToman.coerceAtLeast(0L))
            val rounded = Math.addExact(product, 500L) / 1000L
            maxOf(fare.minimumFareToman.coerceAtLeast(0L), rounded)
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun beginStartShiftFlow(activeFare: FareConfigEntity?) {
        if (activeFare == null) {
            showFareConfigDialog(null, afterSaved = { requestLocationAction(ACTION_START_SHIFT) })
        } else {
            requestLocationAction(ACTION_START_SHIFT)
        }
    }

    private fun requestLocationAction(action: String) {
        pendingLocationAction = action
        locationPermissions.request()
    }

    private fun startShiftAndGps() {
        if (actionInProgress) return
        actionInProgress = true
        lifecycleScope.launch {
            try {
                val dao = db.taxiDao()
                var fare = dao.getActiveFareConfig()
                if (fare == null) {
                    toast("ابتدا تعرفه را تعریف کنید.")
                    return@launch
                }
                val existing = dao.getActiveSession()
                val sessionId = existing?.id ?: repository.startSession(System.currentTimeMillis())
                currentSessionId = sessionId
                LocationTrackingService.start(this@MainActivity, sessionId)
                toast(if (existing == null) "شیفت شروع شد و ثبت GPS فعال شد." else "ثبت GPS برای شیفت فعال شد.")
            } catch (error: Exception) {
                toast("شروع شیفت انجام نشد: ${error.message ?: "خطای نامشخص"}")
            } finally {
                actionInProgress = false
                refreshScreen()
            }
        }
    }

    private fun resumeGps() {
        if (actionInProgress) return
        actionInProgress = true
        lifecycleScope.launch {
            try {
                val session = db.taxiDao().getActiveSession()
                if (session == null) {
                    toast("شیفت فعالی وجود ندارد.")
                    return@launch
                }
                LocationTrackingService.start(this@MainActivity, session.id)
                toast("درخواست ثبت GPS ارسال شد.")
            } catch (error: Exception) {
                toast("فعال‌سازی GPS ناموفق بود: ${error.message ?: "خطای نامشخص"}")
            } finally {
                actionInProgress = false
                refreshScreen()
            }
        }
    }

    private fun showFareConfigDialog(current: FareConfigEntity?, afterSaved: (() -> Unit)? = null) {
        val rateInput = EditText(this).apply {
            hint = "نرخ هر کیلومتر به تومان"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current?.ratePerKmToman?.toString().orEmpty())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        val minimumInput = EditText(this).apply {
            hint = "حداقل کرایه به تومان"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current?.minimumFareToman?.toString().orEmpty())
            textAlignment = View.TEXT_ALIGNMENT_VIEW_END
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(rateInput)
            addView(minimumInput)
            addView(text("این تعرفه آزمایشی و قابل تنظیم است؛ رسمی بودن نرخ‌ها تأیید نشده است.", 12f, false, 0xFF8A3B30.toInt()))
        }
        AlertDialog.Builder(this)
            .setTitle(if (current == null) "تعریف تعرفه اولیه" else "ایجاد نسخه جدید تعرفه")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ذخیره تعرفه", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val rate = parseLong(rateInput.text.toString())
                        val minimum = parseLong(minimumInput.text.toString())
                        if (rate == null || minimum == null || rate < 0L || minimum < 0L) {
                            toast("نرخ و حداقل کرایه را به‌صورت عدد صحیح نامنفی وارد کنید.")
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            try {
                                val dao = db.taxiDao()
                                val version = dao.getMaxFareVersion() + 1
                                val now = System.currentTimeMillis()
                                dao.activateFareConfigAtomically(
                                    FareConfigEntity(
                                        version = version,
                                        ratePerKmToman = rate,
                                        minimumFareToman = minimum,
                                        createdAtMs = now,
                                        effectiveFromMs = now,
                                        isActive = true
                                    )
                                )
                                dialog.dismiss()
                                toast("نسخه تعرفه $version ذخیره شد؛ فقط سفرهای جدید از آن استفاده می‌کنند.")
                                refreshScreen()
                                afterSaved?.invoke()
                            } catch (error: Exception) {
                                toast("ذخیره تعرفه ناموفق بود: ${error.message ?: "خطای نامشخص"}")
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun confirmBoardPassenger(sessionId: Long, seat: Int) {
        lifecycleScope.launch {
            val point = db.taxiDao().getLatestValidLocationPoint(sessionId)
            val now = System.currentTimeMillis()
            val fresh = point != null && now >= point.recordedAtMs && now - point.recordedAtMs <= GPS_FRESHNESS_MS
            val message = if (fresh) "مسافر را برای صندلی $seat ثبت می‌کنید؟" else
                "موقعیت GPS تازه در دسترس نیست. سفر ثبت می‌شود، اما به‌دلیل نامشخص بودن بخشی از مسیر ممکن است کرایه نیازمند بررسی شود. ادامه می‌دهید؟"
            AlertDialog.Builder(this@MainActivity)
                .setTitle("سوار کردن مسافر")
                .setMessage(message)
                .setNegativeButton("انصراف", null)
                .setPositiveButton("ثبت سوارشدن") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            val result = repository.boardPassenger(
                                sessionId = sessionId,
                                seatNumber = seat,
                                nowMs = System.currentTimeMillis(),
                                latitude = point?.takeIf { fresh }?.latitude,
                                longitude = point?.takeIf { fresh }?.longitude,
                                gpsLocationFresh = fresh
                            )
                            when (result) {
                                is BoardPassengerResult.Success -> toast("سفر ثبت شد: ${result.tripId.take(8)}… · تعرفه نسخه ${result.fareVersion}")
                                is BoardPassengerResult.Failure -> toast(result.message)
                            }
                        } catch (error: Exception) {
                            toast("ثبت سوارشدن ناموفق بود: ${error.message ?: "خطای نامشخص"}")
                        }
                        refreshScreen()
                    }
                }.show()
        }
    }

    private fun confirmAlightPassenger(trip: PassengerTripEntity) {
        AlertDialog.Builder(this)
            .setTitle("ثبت پیاده‌شدن")
            .setMessage("سفر ${trip.id.take(8)}… بسته شود؟ در صورت نقص GPS، ابتدا پیشنهاد مبتنی بر مسیرهای مشابه (اگر موجود باشد) یا فرمول دستی نمایش داده می‌شود؛ مبلغ را می‌توانید تغییر دهید.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت پیاده‌شدن و محاسبه کرایه") { _, _ ->
                actionInProgress = true
                lifecycleScope.launch {
                    try {
                        val dao = db.taxiDao()
                        val now = System.currentTimeMillis()
                        val latest = dao.getLatestValidLocationPoint(trip.sessionId)
                        val fresh = latest != null && now >= latest.recordedAtMs && now - latest.recordedAtMs <= GPS_FRESHNESS_MS
                        val validSegments = dao.countValidSegmentsInRange(trip.sessionId, trip.boardedAtMs, now)
                        val invalidSegments = dao.countNonValidSegmentsInRange(trip.sessionId, trip.boardedAtMs, now)
                        val gap = trip.hasUnresolvedGap || trip.reviewStatus == "REQUIRED" || invalidSegments > 0 || !fresh
                        val complete = fresh && validSegments > 0 && invalidSegments == 0 && !gap
                        val config = dao.getFareConfig(trip.fareConfigId)
                        val endpointIsUsable = latest != null && now >= latest.recordedAtMs &&
                            now - latest.recordedAtMs <= HISTORICAL_ENDPOINT_MAX_AGE_MS &&
                            trip.boardingLat != null && trip.boardingLon != null &&
                            distanceBetweenMeters(trip.boardingLat, trip.boardingLon, latest.latitude, latest.longitude) >= 100.0
                        val historicalEstimate = if (!complete && endpointIsUsable && config != null) {
                            historicalRouteFareEstimate(
                                trip,
                                latest!!.latitude,
                                latest.longitude,
                                config
                            )
                        } else null
                        val result = repository.alightPassenger(
                            tripId = trip.id,
                            nowMs = now,
                            latitude = latest?.takeIf { fresh }?.latitude,
                            longitude = latest?.takeIf { fresh }?.longitude,
                            gpsCoverageComplete = complete,
                            unresolvedGpsGap = gap
                        )
                        when (result) {
                            is AlightPassengerResult.Finalized -> {
                                toast("کرایه برآورد و نهایی شد: ${money(result.fareToman)}")
                                showFareReadyDialog(result.fareToman, result.tripId)
                            }
                            is AlightPassengerResult.NeedsReview -> {
                                if (config == null) {
                                    toast("سفر بسته شد، اما تعرفه سفر پیدا نشد؛ اطلاعات باید بررسی شود.")
                                } else {
                                    showUncertainFareDialog(trip, result.recordedDistanceMeters, config, historicalEstimate)
                                }
                            }
                            is AlightPassengerResult.Failure -> toast(result.message)
                        }
                    } catch (error: Exception) {
                        toast("ثبت پیاده‌شدن ناموفق بود: ${error.message ?: "خطای نامشخص"}")
                    }
                    actionInProgress = false
                    refreshScreen()
                }
            }.show()
    }

    /** Looks for past trips with pickup and drop-off coordinates near this route. */
    private suspend fun historicalRouteFareEstimate(
        currentTrip: PassengerTripEntity,
        endLat: Double,
        endLon: Double,
        config: FareConfigEntity
    ): Long? {
        val startLat = currentTrip.boardingLat ?: return null
        val startLon = currentTrip.boardingLon ?: return null
        val candidates = db.taxiDao().getHistoricalTripsWithEndpoints()
            .asSequence()
            .filter { it.id != currentTrip.id && it.boardedAtMs < currentTrip.boardedAtMs && it.distanceMeters > 0L }
            .mapNotNull { old ->
                val oldStartLat = old.boardingLat ?: return@mapNotNull null
                val oldStartLon = old.boardingLon ?: return@mapNotNull null
                val oldEndLat = old.alightingLat ?: return@mapNotNull null
                val oldEndLon = old.alightingLon ?: return@mapNotNull null
                val startDelta = distanceBetweenMeters(startLat, startLon, oldStartLat, oldStartLon)
                val endDelta = distanceBetweenMeters(endLat, endLon, oldEndLat, oldEndLon)
                if (startDelta <= HISTORICAL_ENDPOINT_RADIUS_M && endDelta <= HISTORICAL_ENDPOINT_RADIUS_M) {
                    Triple(old.distanceMeters, startDelta + endDelta, old.boardedAtMs)
                } else null
            }
            .sortedWith(compareBy<Triple<Long, Double, Long>> { it.second }.thenByDescending { it.third })
            .take(3)
            .toList()
        if (candidates.isEmpty()) return null
        val averageDistance = candidates.map { it.first }.average().toLong().coerceAtLeast(0L)
        return estimateFare(averageDistance, config)
    }

    private fun distanceBetweenMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return earthRadius * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private fun showUncertainFareDialog(
        trip: PassengerTripEntity,
        recordedDistanceMeters: Long,
        config: FareConfigEntity,
        historicalEstimate: Long?
    ) {
        val initialEstimate = historicalEstimate ?: config.minimumFareToman
        val estimateInput = EditText(this).apply {
            hint = "برآورد اولیه (تومان)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialEstimate.toString())
        }
        val manualUnitsInput = EditText(this).apply {
            hint = "عدد دستی؛ هر واحد ۱۰۰۰ تومان"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val finalInput = EditText(this).apply {
            hint = "مبلغ نهایی برای دریافت (تومان)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initialEstimate.toString())
        }
        val reasonInput = EditText(this).apply {
            hint = "علت/توضیح راننده (الزامی)"
            setText(if (historicalEstimate != null) "تخمین از مسیر مشابه گذشته؛ بازبینی راننده" else "نقص GPS؛ تخمین دستی راننده")
        }
        var estimateMethod = if (historicalEstimate != null) "HISTORICAL_ROUTE" else "MANUAL_ESTIMATE"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(text("مسافت ثبت‌شده: $recordedDistanceMeters متر؛ به علت نقص GPS قطعی نیست.", 13f, true))
            addView(text(if (historicalEstimate != null) "پیشنهاد از میان حداکثر ۳ سفر گذشته با مبدأ و مقصد نزدیک محاسبه شده است؛ راننده باید تأیید یا اصلاح کند." else "مسیر مشابهی با مختصات کافی پیدا نشد. از فرمول دستی استفاده کنید یا مبلغ برآورد را مستقیماً وارد کنید.", 12f, false))
            addView(text("کرایه پایه مورد استفاده در فرمول دستی: ${money(config.minimumFareToman)}", 12f, false))
            addView(estimateInput)
            addView(manualUnitsInput)
            addView(actionButton("محاسبه دستی: کرایه پایه + عدد × ۱۰۰۰") {
                val units = parseLong(manualUnitsInput.text.toString())
                if (units == null || units < 0L) {
                    toast("عدد دستی باید صفر یا مثبت باشد.")
                } else {
                    try {
                        val increment = Math.multiplyExact(units, 1000L)
                        val total = Math.addExact(config.minimumFareToman, increment)
                        estimateInput.setText(total.toString())
                        finalInput.setText(total.toString())
                        estimateMethod = "MANUAL_UNITS_1000"
                    } catch (_: ArithmeticException) {
                        toast("عدد واردشده بیش از حد بزرگ است.")
                    }
                }
            })
            addView(finalInput)
            addView(reasonInput)
        }
        AlertDialog.Builder(this)
            .setTitle("تعیین کرایه با GPS نامطمئن")
            .setView(box)
            .setNegativeButton("بعداً بررسی می‌کنم", null)
            .setPositiveButton("ثبت مبلغ و ادامه پرداخت", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val estimate = parseLong(estimateInput.text.toString())
                        val finalAmount = parseLong(finalInput.text.toString())
                        val reason = reasonInput.text.toString().trim()
                        if (estimate == null || finalAmount == null || estimate < 0L || finalAmount < 0L || reason.isBlank()) {
                            toast("برآورد، مبلغ نهایی و علت معتبر را وارد کنید.")
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            when (val save = repository.resolveReviewedFare(
                                tripId = trip.id,
                                estimateToman = estimate,
                                finalFareToman = finalAmount,
                                method = estimateMethod,
                                reason = reason,
                                nowMs = System.currentTimeMillis()
                            )) {
                                is FareAdjustmentResult.Success -> {
                                    dialog.dismiss()
                                    toast("کرایه ثبت شد: ${money(finalAmount)}؛ برآورد اولیه ${money(save.originalCalculatedFareToman)} حفظ شد.")
                                    showFareReadyDialog(finalAmount, trip.id)
                                    refreshScreen()
                                }
                                is FareAdjustmentResult.Failure -> toast(save.message)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showFareReadyDialog(amountToman: Long, tripId: String? = null) {
        AlertDialog.Builder(this)
            .setTitle("مبلغ قابل دریافت")
            .setMessage("مبلغ کرایه: ${money(amountToman)} تومان\nاین مبلغ آماده پرداخت است. تأیید دریافت وجه فقط پس از پاسخ معتبر پوز ممکن خواهد بود.")
            .setNegativeButton("بستن", null)
            .setPositiveButton("پرداخت / ارسال به پوز") { _, _ -> showPaymentDialog(amountToman, tripId) }
            .show()
    }

    /**
     * Uses the vendor-neutral payment boundary. With the default adapter this safely reports
     * that no POS is configured; it never falsely marks the fare as paid.
     */
    private fun showPaymentDialog(amountToman: Long, tripId: String? = null) {
        if (amountToman <= 0L) {
            toast("مبلغ پرداخت باید بیشتر از صفر باشد.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("پرداخت با پوز بلوتوثی")
            .setMessage("مبلغ: ${money(amountToman)} تومان\nبا ادامه، برنامه تلاش می‌کند درخواست را از طریق درگاه پرداخت تنظیم‌شده ارسال کند. در نسخه فعلی، درگاه دستگاه هنوز پیکربندی نشده و پرداخت واقعی انجام نخواهد شد.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ارسال درخواست پرداخت") { _, _ ->
                lifecycleScope.launch {
                    val request = PosPaymentRequest(
                        requestId = UUID.randomUUID().toString(),
                        tripId = tripId,
                        amountToman = amountToman
                    )
                    when (val result = posPaymentGateway.charge(request)) {
                        is PosPaymentResult.Approved -> {
                            val reference = result.terminalReference?.let { "\nشماره پیگیری پوز: $it" }.orEmpty()
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("پرداخت تأیید شد")
                                .setMessage("پوز پرداخت را تأیید کرده است.$reference")
                                .setPositiveButton("تأیید", null)
                                .show()
                        }
                        is PosPaymentResult.Declined -> toast("پرداخت رد شد: ${result.message}")
                        is PosPaymentResult.Failed -> toast("خطا در پرداخت: ${result.message}")
                        is PosPaymentResult.NotConfigured -> AlertDialog.Builder(this@MainActivity)
                            .setTitle("پوز هنوز پیکربندی نشده است")
                            .setMessage(result.message + "\nبرای فعال‌سازی واقعی، مدل دقیق پوز و SDK/پروتکل رسمی آن لازم است.")
                            .setPositiveButton("متوجه شدم", null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showFareAdjustmentDialog(trip: PassengerTripEntity) {
        val amountInput = EditText(this).apply {
            hint = "کرایه نهایی جدید به تومان"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(trip.finalFareToman?.toString().orEmpty())
        }
        val reasonInput = EditText(this).apply {
            hint = "علت اصلاح (الزامی)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(text("کرایه محاسبه‌شده اولیه: ${trip.calculatedFareToman?.let { money(it) } ?: "نامشخص"}", 14f, true))
            addView(amountInput)
            addView(reasonInput)
        }
        AlertDialog.Builder(this)
            .setTitle("اصلاح کرایه")
            .setView(box)
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت اصلاح", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val amount = parseLong(amountInput.text.toString())
                        val reason = reasonInput.text.toString().trim()
                        if (amount == null || amount < 0L || reason.isBlank()) {
                            toast("مبلغ نامنفی و علت اصلاح الزامی است.")
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            val result = repository.adjustFare(trip.id, amount, reason, nowMs = System.currentTimeMillis())
                            when (result) {
                                is FareAdjustmentResult.Success -> {
                                    toast("اصلاح ثبت شد؛ مبلغ اولیه ${money(result.originalCalculatedFareToman)} حفظ شد.")
                                    dialog.dismiss()
                                    refreshScreen()
                                }
                                is FareAdjustmentResult.Failure -> toast(result.message)
                            }
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun confirmCloseShift(sessionId: Long, activeTripCount: Int) {
        if (activeTripCount > 0) {
            toast("برای پایان شیفت، ابتدا همه سفرهای فعال را ببندید.")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("پایان شیفت")
            .setMessage("شیفت بسته شود؟ پس از پایان، برای شروع شیفت بعدی باید شیفت جدید ایجاد شود.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("پایان شیفت") { _, _ ->
                lifecycleScope.launch {
                    when (val result = repository.closeSession(sessionId, System.currentTimeMillis())) {
                        SessionCloseResult.Success -> {
                            LocationTrackingService.stop(this@MainActivity)
                            toast("شیفت بسته شد.")
                        }
                        is SessionCloseResult.Failure -> toast(result.message)
                    }
                    refreshScreen()
                }
            }.show()
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setOnClickListener { onClick() }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundColor(0xFFFFFFFF.toInt())
        elevation = dp(1).toFloat()
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean, color: Int = 0xFF202124.toInt()): TextView = TextView(this).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun spacer(heightDp: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun money(value: Long): String {
        val formatted = NumberFormat.getIntegerInstance(Locale("fa", "IR")).format(value)
        return "$formatted تومان"
    }

    private fun parseLong(raw: String): Long? {
        val normalized = raw.trim()
            .replace(",", "").replace("٬", "").replace("٫", ".")
            .map { ch -> when (ch) {
                in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                else -> ch
            } }.joinToString("")
        return normalized.toLongOrNull()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val ACTION_START_SHIFT = "START_SHIFT"
        private const val ACTION_RESUME_GPS = "RESUME_GPS"
        private const val GPS_FRESHNESS_MS = 15_000L
        private const val HISTORICAL_ENDPOINT_MAX_AGE_MS = 5 * 60_000L
        private const val HISTORICAL_ENDPOINT_RADIUS_M = 300.0
        private const val UI_REFRESH_INTERVAL_MS = 1_000L
        private const val TAB_CURRENT = 0
        private const val TAB_MANAGEMENT = 1
    }
}
