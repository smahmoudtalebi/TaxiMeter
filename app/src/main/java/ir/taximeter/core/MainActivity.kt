package ir.taximeter.core

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** First integrated Persian driver screen. Native Android views are used to keep the prototype lightweight. */
class MainActivity : ComponentActivity() {
    private val db by lazy { TaxiDatabase.getInstance(this) }
    private val repository by lazy { TaxiRepository(db) }
    private lateinit var locationPermissions: LocationPermissionController
    private var currentSessionId: Long? = null
    private var pendingLocationAction: String? = null
    private var screen: LinearLayout? = null
    private var actionInProgress = false

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
        lifecycleScope.launch { refreshScreen() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { refreshScreen() }
    }

    private suspend fun refreshScreen() {
        val dao = db.taxiDao()
        val activeSession = dao.getActiveSession()
        val displaySession = activeSession ?: dao.getLatestSession()
        currentSessionId = activeSession?.id
        val fare = dao.getActiveFareConfig()
        val allTrips = displaySession?.let { dao.getTripsForSession(it.id) }.orEmpty()
        val activeTrips = allTrips.filter { it.status == "ACTIVE" }

        val scroll = ScrollView(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(0xFFF5F6F8.toInt())
        }
        scroll.addView(root)
        screen = root

        root.addView(text("تاکسی‌متر گردشی", 25f, true).apply { setPadding(0, 0, 0, dp(4)) })
        root.addView(text("مدیریت مستقل سفرهای حداکثر چهار مسافر", 14f, false, 0xFF5F6368.toInt()))
        root.addView(spacer(12))

        val shiftStatus = when {
            activeSession != null -> "وضعیت شیفت: فعال · شناسه ${activeSession.id}"
            displaySession != null -> "وضعیت شیفت: آخرین شیفت بسته‌شده · شناسه ${displaySession.id}"
            else -> "وضعیت شیفت: شروع نشده"
        }
        root.addView(card().apply {
            addView(text(shiftStatus, 16f, true))
            addView(text("تعرفه فعال: ${fare?.let { "نسخه ${it.version} · هر کیلومتر ${money(it.ratePerKmToman)} · حداقل ${money(it.minimumFareToman)}" } ?: "تعریف نشده"}", 13f, false))
            addView(text("کرایه پیش‌فرض فقط بر اساس مسافت معتبر است؛ زمان توقف به کرایه اضافه نمی‌شود.", 12f, false, 0xFF5F6368.toInt()))
        })

        val primaryActions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (activeSession == null) {
            primaryActions.addView(actionButton("شروع شیفت و ثبت GPS") { beginStartShiftFlow(fare) })
        } else {
            primaryActions.addView(actionButton("فعال‌سازی / ادامه ثبت GPS") { requestLocationAction(ACTION_RESUME_GPS) })
            primaryActions.addView(actionButton("پایان شیفت") { confirmCloseShift(activeSession.id, activeTrips.size) })
        }
        primaryActions.addView(actionButton("تعریف / تغییر تعرفه (برای سفرهای جدید)") { showFareConfigDialog(fare) })
        root.addView(primaryActions)
        root.addView(spacer(8))
        root.addView(text("وضعیت صندلی‌ها", 20f, true))
        root.addView(text("هر صندلی فقط یک سفر فعال دارد؛ با پیاده‌شدن، فقط همان سفر بسته می‌شود.", 13f, false, 0xFF5F6368.toInt()))
        root.addView(spacer(4))

        for (seat in 1..4) {
            val trip = activeTrips.firstOrNull { it.seatNumber == seat }
            val seatCard = card()
            seatCard.addView(text("صندلی $seat", 18f, true))
            if (trip == null) {
                seatCard.addView(text("آزاد", 15f, false, 0xFF287D46.toInt()))
                seatCard.addView(actionButton("سوار کردن مسافر") {
                    if (currentSessionId == null) toast("ابتدا شیفت را شروع کنید.")
                    else confirmBoardPassenger(currentSessionId!!, seat)
                }.apply { isEnabled = activeSession != null && !actionInProgress })
            } else {
                val distance = dao.getAllocatedDistanceMeters(trip.id)
                val stateText = if (trip.reviewStatus == "REQUIRED" || trip.hasUnresolvedGap) "نیازمند بررسی GPS" else "سفر فعال"
                seatCard.addView(text(stateText, 14f, true, if (trip.reviewStatus == "REQUIRED" || trip.hasUnresolvedGap) 0xFF9A5B00.toInt() else 0xFF2256A0.toInt()))
                seatCard.addView(text("شناسه: ${trip.id.take(8)}…", 12f, false, 0xFF5F6368.toInt()))
                seatCard.addView(text("مسافت معتبر ثبت‌شده: ${distance} متر", 14f, false))
                seatCard.addView(text("کرایه نهایی هنگام پیاده‌شدن و پس از اعتبارسنجی تعیین می‌شود.", 12f, false, 0xFF5F6368.toInt()))
                seatCard.addView(actionButton("ثبت پیاده‌شدن") { confirmAlightPassenger(trip) }.apply { isEnabled = !actionInProgress })
            }
            root.addView(seatCard)
            root.addView(spacer(8))
        }

        root.addView(text("سفرهای اخیر", 20f, true))
        val recentTrips = allTrips.sortedByDescending { it.boardedAtMs }.take(10)
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
                historyCard.addView(text("صندلی ${trip.seatNumber} · ${trip.id.take(8)}…", 14f, true))
                historyCard.addView(text("$status · مسافت ثبت‌شده ${trip.distanceMeters} متر", 12f, false))
                historyCard.addView(text("کرایه محاسبه‌شده: ${trip.calculatedFareToman?.let { money(it) } ?: "نامشخص"} · نهایی: ${trip.finalFareToman?.let { money(it) } ?: "در انتظار بررسی"}", 12f, false))
                if (trip.status == "CLOSED" && trip.calculatedFareToman != null && trip.fareStatus == "FINAL") {
                    historyCard.addView(actionButton("اصلاح کرایه با ثبت علت") { showFareAdjustmentDialog(trip) })
                }
                root.addView(historyCard)
                root.addView(spacer(6))
            }
        }
        root.addView(spacer(12))
        root.addView(text("نمونه اولیه است؛ پیش از استفاده واقعی باید روی گوشی و مسیرهای میدانی آزمون شود.", 12f, false, 0xFF8A3B30.toInt()))
        setContentView(scroll)
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
            .setMessage("سفر ${trip.id.take(8)}… بسته شود؟ اگر داده GPS ناقص باشد، کرایه قطعی اعلام نمی‌شود و سفر برای بررسی علامت می‌خورد.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ثبت پیاده‌شدن") { _, _ ->
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
                        val result = repository.alightPassenger(
                            tripId = trip.id,
                            nowMs = now,
                            latitude = latest?.takeIf { fresh }?.latitude,
                            longitude = latest?.takeIf { fresh }?.longitude,
                            gpsCoverageComplete = complete,
                            unresolvedGpsGap = gap
                        )
                        when (result) {
                            is AlightPassengerResult.Finalized -> toast("سفر بسته شد. مسافت ${result.distanceMeters} متر · کرایه ${money(result.fareToman)}")
                            is AlightPassengerResult.NeedsReview -> toast("سفر بسته شد؛ ${result.recordedDistanceMeters} متر ثبت شده اما کرایه نیازمند بررسی است.")
                            is AlightPassengerResult.Failure -> toast(result.message)
                        }
                    } catch (error: Exception) {
                        toast("ثبت پیاده‌شدن ناموفق بود: ${error.message ?: "خطای نامشخص"}")
                    }
                    refreshScreen()
                }
            }.show()
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
    }
}
