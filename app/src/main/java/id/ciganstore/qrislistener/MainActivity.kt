package id.ciganstore.qrislistener

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import id.ciganstore.qrislistener.data.PaymentEvent
import id.ciganstore.qrislistener.data.SqlitePaymentRepository
import id.ciganstore.qrislistener.notification.GopayNotificationListenerService
import id.ciganstore.qrislistener.notification.NotificationAccess
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var repository: SqlitePaymentRepository
    private lateinit var executor: ExecutorService
    private lateinit var accessStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var latestAmount: TextView
    private lateinit var latestMerchant: TextView
    private lateinit var latestTime: TextView
    private lateinit var historyContainer: LinearLayout
    private lateinit var emptyHistory: TextView
    private lateinit var diagnosticCounter: TextView
    private lateinit var storageError: TextView
    private lateinit var clearHistoryButton: Button
    private lateinit var openSettingsButton: Button
    private var receiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        configureEdgeToEdge()
        bindViews()
        applyWindowInsets()

        repository = SqlitePaymentRepository(applicationContext)
        executor = Executors.newSingleThreadExecutor()

        openSettingsButton.setOnClickListener { openNotificationAccessSettings() }
        clearHistoryButton.setOnClickListener { confirmClearHistory() }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GopayNotificationListenerService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        refreshScreen()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::executor.isInitialized && ::repository.isInitialized) {
            executor.execute { repository.close() }
            executor.shutdown()
        }
        super.onDestroy()
    }

    private fun bindViews() {
        accessStatus = findViewById(R.id.access_status)
        connectionStatus = findViewById(R.id.connection_status)
        latestAmount = findViewById(R.id.latest_amount)
        latestMerchant = findViewById(R.id.latest_merchant)
        latestTime = findViewById(R.id.latest_time)
        historyContainer = findViewById(R.id.history_container)
        emptyHistory = findViewById(R.id.empty_history)
        diagnosticCounter = findViewById(R.id.diagnostic_counter)
        storageError = findViewById(R.id.storage_error)
        clearHistoryButton = findViewById(R.id.clear_history_button)
        openSettingsButton = findViewById(R.id.open_settings_button)
    }

    private fun refreshScreen() {
        renderListenerState()
        val serviceReportedError = GopayNotificationListenerService.hasStorageError(this)
        diagnosticCounter.text = getString(
            R.string.diagnostic_count,
            GopayNotificationListenerService.ignoredPayloadCount(this),
        )

        executor.execute {
            val result = runCatching { repository.listNewest(HISTORY_LIMIT) }
            runOnUiThread {
                if (!isDestroyed) {
                    storageError.visibility = if (serviceReportedError || result.isFailure) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    result.getOrNull()?.let(::renderHistory)
                }
            }
        }
    }

    private fun renderListenerState() {
        val accessGranted = NotificationAccess.isGranted(this)
        val connected = accessGranted && GopayNotificationListenerService.isConnected(this)

        renderStatus(
            accessStatus,
            active = accessGranted,
            text = getString(if (accessGranted) R.string.status_active else R.string.status_inactive),
        )
        renderStatus(
            connectionStatus,
            active = connected,
            text = getString(
                when {
                    connected -> R.string.status_connected
                    accessGranted -> R.string.status_waiting_connection
                    else -> R.string.status_waiting_permission
                },
            ),
        )
        openSettingsButton.text = getString(
            if (accessGranted) R.string.review_notification_settings
            else R.string.open_notification_settings,
        )
    }

    private fun renderStatus(view: TextView, active: Boolean, text: String) {
        view.text = text
        view.setBackgroundResource(
            if (active) R.drawable.bg_status_active else R.drawable.bg_status_inactive,
        )
        view.setTextColor(
            getColor(if (active) R.color.status_active_text else R.color.status_inactive_text),
        )
    }

    private fun renderHistory(events: List<PaymentEvent>) {
        historyContainer.removeAllViews()
        emptyHistory.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
        clearHistoryButton.isEnabled = events.isNotEmpty()
        clearHistoryButton.alpha = if (events.isNotEmpty()) 1f else 0.45f

        val latest = events.firstOrNull()
        if (latest == null) {
            latestAmount.text = getString(R.string.no_payment_yet)
            latestMerchant.text = getString(
                if (NotificationAccess.isGranted(this)) R.string.test_payment_ready_hint
                else R.string.test_payment_hint,
            )
            latestTime.visibility = View.GONE
        } else {
            latestAmount.text = formatRupiah(latest.amount)
            latestMerchant.text = latest.merchant
            latestTime.text = formatDateTime(latest.receivedAt)
            latestTime.visibility = View.VISIBLE
        }

        events.forEachIndexed { index, event ->
            historyContainer.addView(createHistoryRow(event))
            if (index != events.lastIndex) historyContainer.addView(createDivider())
        }
    }

    private fun createHistoryRow(event: PaymentEvent): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(16), 0, dp(16))

        addView(TextView(context).apply {
            text = formatRupiah(event.amount)
            textSize = 20f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(typeface, Typeface.BOLD)
        })
        addView(TextView(context).apply {
            text = event.merchant
            textSize = 15f
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(5), 0, 0)
        })
        addView(TextView(context).apply {
            text = formatDateTime(event.receivedAt)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun createDivider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.outline))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_history_title)
            .setMessage(R.string.clear_history_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.confirm_clear) { _, _ -> clearHistory() }
            .show()
    }

    private fun clearHistory() {
        clearHistoryButton.isEnabled = false
        executor.execute {
            val result = runCatching {
                repository.clear()
                repository.listNewest(HISTORY_LIMIT)
            }
            runOnUiThread {
                if (!isDestroyed) {
                    storageError.visibility = if (result.isFailure) View.VISIBLE else View.GONE
                    result.getOrNull()?.let(::renderHistory)
                }
            }
        }
    }

    private fun openNotificationAccessSettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }

    private fun formatRupiah(amount: Long): String = NumberFormat
        .getCurrencyInstance(Locale.forLanguageTag("id-ID"))
        .apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 0
        }
        .format(amount)
        .replace('\u00A0', ' ')

    private fun formatDateTime(timestamp: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        Locale.getDefault(),
    ).format(Date(timestamp))

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        val isLight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK !=
            Configuration.UI_MODE_NIGHT_YES

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val lightAppearance = if (isLight) {
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            } else {
                0
            }
            window.insetsController?.setSystemBarsAppearance(
                lightAppearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    if (isLight) {
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    } else {
                        0
                    }
        }
    }

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.screen_root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val HISTORY_LIMIT = 100
    }
}
