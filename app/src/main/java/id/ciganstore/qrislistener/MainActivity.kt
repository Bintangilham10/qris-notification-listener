package id.ciganstore.qrislistener

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import id.ciganstore.qrislistener.data.PaymentRecord
import id.ciganstore.qrislistener.data.SqlitePaymentRepository
import id.ciganstore.qrislistener.data.UploadCounts
import id.ciganstore.qrislistener.data.UploadState
import id.ciganstore.qrislistener.data.UploadStatus
import id.ciganstore.qrislistener.notification.GopayNotificationListenerService
import id.ciganstore.qrislistener.notification.NotificationAccess
import id.ciganstore.qrislistener.upload.ListenerProtocol
import id.ciganstore.qrislistener.upload.ListenerSettingsStore
import id.ciganstore.qrislistener.upload.ListenerSettingsValidator
import id.ciganstore.qrislistener.upload.ListenerSync
import id.ciganstore.qrislistener.upload.ListenerUploader
import id.ciganstore.qrislistener.upload.PrefsUploadStatusStore
import id.ciganstore.qrislistener.upload.UploadProblem
import id.ciganstore.qrislistener.upload.UploadProblemKind
import id.ciganstore.qrislistener.upload.UploadScheduler
import id.ciganstore.qrislistener.upload.UploadStatusSnapshot
import id.ciganstore.qrislistener.upload.UploadStatusText
import java.security.GeneralSecurityException
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var repository: SqlitePaymentRepository
    private lateinit var settingsStore: ListenerSettingsStore
    private lateinit var statusStore: PrefsUploadStatusStore
    private lateinit var executor: ExecutorService

    /** Network and Keystore actions; kept off [executor] so they never delay screen refreshes. */
    private lateinit var actionExecutor: ExecutorService
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
    private lateinit var uploadStatus: TextView
    private lateinit var uploadTarget: TextView
    private lateinit var uploadLastDelivered: TextView
    private lateinit var uploadCounts: TextView
    private lateinit var uploadLastHeartbeat: TextView
    private lateinit var uploadError: TextView
    private lateinit var uploadClockWarning: TextView
    private lateinit var retryUploadButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var uploadActionResult: TextView
    private lateinit var toggleSettingsButton: Button
    private lateinit var settingsForm: View
    private lateinit var serverUrlInput: EditText
    private lateinit var deviceIdInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var secretState: TextView
    private lateinit var settingsDebugNote: TextView
    private lateinit var saveSettingsButton: Button
    private lateinit var settingsResult: TextView
    private var receiverRegistered = false
    private var settingsExpanded = false
    private var actionRunning = false
    private var uploaderConfigured = false

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
        settingsStore = ListenerSettingsStore(applicationContext, allowDebugCleartext = BuildConfig.DEBUG)
        statusStore = PrefsUploadStatusStore(applicationContext)
        executor = Executors.newSingleThreadExecutor()
        actionExecutor = Executors.newSingleThreadExecutor()

        openSettingsButton.setOnClickListener { openNotificationAccessSettings() }
        clearHistoryButton.setOnClickListener { confirmClearHistory() }
        retryUploadButton.setOnClickListener { retryUploadsNow() }
        testConnectionButton.setOnClickListener { testConnection() }
        toggleSettingsButton.setOnClickListener { setSettingsExpanded(!settingsExpanded) }
        saveSettingsButton.setOnClickListener { saveUploaderSettings() }
        settingsDebugNote.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE

        val summary = settingsStore.summary()
        fillSettingsForm(summary)
        setSettingsExpanded(savedInstanceState?.getBoolean(STATE_SETTINGS_EXPANDED) ?: !summary.isComplete)

        // Keeps the 15-minute heartbeat alive even before notification access is granted.
        runCatching { UploadScheduler.ensureScheduled(applicationContext) }
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
        if (
            NotificationAccess.isGranted(this) &&
            !GopayNotificationListenerService.isConnected()
        ) {
            runCatching { GopayNotificationListenerService.requestConnection(this) }
        }
        refreshScreen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SETTINGS_EXPANDED, settingsExpanded)
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (::actionExecutor.isInitialized) actionExecutor.shutdown()
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
        uploadStatus = findViewById(R.id.upload_status)
        uploadTarget = findViewById(R.id.upload_target)
        uploadLastDelivered = findViewById(R.id.upload_last_delivered)
        uploadCounts = findViewById(R.id.upload_counts)
        uploadLastHeartbeat = findViewById(R.id.upload_last_heartbeat)
        uploadError = findViewById(R.id.upload_error)
        uploadClockWarning = findViewById(R.id.upload_clock_warning)
        retryUploadButton = findViewById(R.id.retry_upload_button)
        testConnectionButton = findViewById(R.id.test_connection_button)
        uploadActionResult = findViewById(R.id.upload_action_result)
        toggleSettingsButton = findViewById(R.id.toggle_settings_button)
        settingsForm = findViewById(R.id.settings_form)
        serverUrlInput = findViewById(R.id.server_url_input)
        deviceIdInput = findViewById(R.id.device_id_input)
        secretInput = findViewById(R.id.secret_input)
        secretState = findViewById(R.id.secret_state)
        settingsDebugNote = findViewById(R.id.settings_debug_note)
        saveSettingsButton = findViewById(R.id.save_settings_button)
        settingsResult = findViewById(R.id.settings_result)
    }

    private fun refreshScreen() {
        renderListenerState()
        val serviceReportedError = GopayNotificationListenerService.hasStorageError(this)
        diagnosticCounter.text = getString(
            R.string.diagnostic_count,
            GopayNotificationListenerService.ignoredPayloadCount(this),
        )
        val summary = settingsStore.summary()
        val snapshot = statusStore.snapshot()
        renderSecretState(summary)

        executor.execute {
            val history = runCatching { repository.listHistory(HISTORY_LIMIT) }
            val counts = runCatching { repository.uploadCounts() }
            runOnUiThread {
                if (!isDestroyed) {
                    storageError.visibility = if (serviceReportedError || history.isFailure || counts.isFailure) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    history.getOrNull()?.let { records -> renderHistory(records, counts.getOrNull()) }
                    renderUpload(summary, snapshot, counts.getOrNull())
                }
            }
        }
    }

    private fun renderListenerState() {
        val accessGranted = NotificationAccess.isGranted(this)
        val connected = accessGranted && GopayNotificationListenerService.isConnected()

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
                    accessGranted -> R.string.status_reconnecting
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

    private fun renderUpload(
        summary: ListenerSettingsStore.Summary,
        snapshot: UploadStatusSnapshot,
        counts: UploadCounts?,
    ) {
        uploaderConfigured = summary.isComplete
        val health = UploadStatusText.health(summary.isComplete, snapshot.configurationBlock, snapshot.lastProblem)
        renderStatus(
            uploadStatus,
            active = health == UploadStatusText.Health.ACTIVE,
            text = getString(UploadStatusText.healthLabel(health)),
        )

        uploadTarget.text = if (summary.isComplete) {
            getString(R.string.upload_target, summary.baseUrl, summary.deviceId)
        } else {
            getString(R.string.upload_target_missing)
        }
        uploadLastDelivered.text = snapshot.lastDeliveredAt
            ?.let { getString(R.string.upload_last_delivered, formatDateTime(it)) }
            ?: getString(R.string.upload_never_delivered)
        uploadLastHeartbeat.text = snapshot.lastHeartbeatAt
            ?.let { getString(R.string.upload_last_heartbeat, formatDateTime(it)) }
            ?: getString(R.string.upload_no_heartbeat)
        uploadCounts.text = if (counts != null) {
            getString(R.string.upload_counts, counts.pending + counts.blockedConfig, counts.failedPermanent)
        } else {
            getString(R.string.upload_counts_unknown)
        }

        // A configuration block outranks a later temporary problem: it is what stops delivery.
        val problem = snapshot.configurationBlock ?: snapshot.lastProblem
        val problemAt = if (snapshot.configurationBlock != null) snapshot.configurationBlockAt else snapshot.lastProblemAt
        if (summary.isComplete && problem != null) {
            val description = describeProblem(problem)
            uploadError.text = if (problemAt != null) {
                getString(R.string.upload_last_problem, formatDateTime(problemAt), description)
            } else {
                description
            }
            uploadError.visibility = View.VISIBLE
        } else {
            uploadError.visibility = View.GONE
        }

        val clockAlreadyExplained = problem?.kind == UploadProblemKind.CLOCK_REJECTED ||
            problem?.kind == UploadProblemKind.CLOCK_INVALID
        val skew = snapshot.clockSkewMillis
        if (summary.isComplete && !clockAlreadyExplained && skew != null && UploadStatusText.shouldWarnClock(skew)) {
            uploadClockWarning.text = getString(R.string.upload_clock_warning, formatSkew(skew))
            uploadClockWarning.visibility = View.VISIBLE
        } else {
            uploadClockWarning.visibility = View.GONE
        }
        renderActionButtons()
    }

    private fun renderActionButtons() {
        val enabled = uploaderConfigured && !actionRunning
        listOf(retryUploadButton, testConnectionButton).forEach { button ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else DISABLED_ALPHA
        }
        saveSettingsButton.isEnabled = !actionRunning
        saveSettingsButton.alpha = if (actionRunning) DISABLED_ALPHA else 1f
    }

    private fun describeProblem(problem: UploadProblem): String {
        val message = UploadStatusText.problemMessage(problem.kind)
        return when (UploadStatusText.problemArgument(problem.kind)) {
            UploadStatusText.ProblemArgument.NONE -> getString(message)
            UploadStatusText.ProblemArgument.TECHNICAL_DETAIL -> getString(message, problem.describe())
            UploadStatusText.ProblemArgument.REJECTION_REASON ->
                getString(message, getString(UploadStatusText.rejectionReason(problem.detail)))
        }
    }

    private fun formatSkew(skewMillis: Long): String {
        val seconds = (abs(skewMillis) / 1_000L).toInt()
        return if (seconds >= 90) {
            val minutes = (seconds + 30) / 60
            resources.getQuantityString(R.plurals.duration_minutes, minutes, minutes)
        } else {
            resources.getQuantityString(R.plurals.duration_seconds, seconds, seconds)
        }
    }

    private fun renderHistory(records: List<PaymentRecord>, counts: UploadCounts?) {
        historyContainer.removeAllViews()
        emptyHistory.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        // Only delivered and rejected events can be cleared; waiting ones are always kept.
        val clearable = counts?.let { it.uploaded + it.failedPermanent > 0 } ?: records.any {
            it.upload.status == UploadStatus.UPLOADED || it.upload.status == UploadStatus.FAILED_PERMANENT
        }
        clearHistoryButton.isEnabled = clearable
        clearHistoryButton.alpha = if (clearable) 1f else DISABLED_ALPHA

        val latest = records.firstOrNull()?.event
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
            latestTime.text = formatDateTime(latest.postedAt)
            latestTime.visibility = View.VISIBLE
        }

        records.forEachIndexed { index, record ->
            historyContainer.addView(createHistoryRow(record))
            if (index != records.lastIndex) historyContainer.addView(createDivider())
        }
    }

    private fun createHistoryRow(record: PaymentRecord): View = LinearLayout(this).apply {
        val event = record.event
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
            text = formatDateTime(event.postedAt)
            textSize = 13f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, dp(5), 0, 0)
        })
        addView(TextView(context).apply {
            val rejected = record.upload.status == UploadStatus.FAILED_PERMANENT
            text = historyStatusText(record.upload)
            textSize = 13f
            setTextColor(getColor(if (rejected) R.color.error_text else R.color.text_secondary))
            if (rejected) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(5), 0, 0)
        })
    }

    private fun historyStatusText(state: UploadState): String {
        val message = UploadStatusText.historyStatus(state)
        return when (state.status) {
            UploadStatus.PENDING -> if (state.attempts > 0) {
                resources.getQuantityString(message, state.attempts, state.attempts)
            } else {
                getString(message)
            }
            UploadStatus.FAILED_PERMANENT ->
                getString(message, getString(UploadStatusText.rejectionReason(state.lastError)))
            UploadStatus.UPLOADED, UploadStatus.BLOCKED_CONFIG -> getString(message)
        }
    }

    private fun createDivider(): View = View(this).apply {
        setBackgroundColor(getColor(R.color.outline))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )
    }

    private fun setSettingsExpanded(expanded: Boolean) {
        settingsExpanded = expanded
        settingsForm.visibility = if (expanded) View.VISIBLE else View.GONE
        toggleSettingsButton.text = getString(
            if (expanded) R.string.hide_server_settings else R.string.edit_server_settings,
        )
    }

    /** Fills the editable fields from storage. The secret is never read back, only its presence. */
    private fun fillSettingsForm(summary: ListenerSettingsStore.Summary) {
        serverUrlInput.setText(summary.baseUrl ?: ListenerProtocol.DEFAULT_BASE_URL)
        deviceIdInput.setText(summary.deviceId)
        secretInput.text.clear()
        listOf(serverUrlInput, deviceIdInput, secretInput).forEach { it.error = null }
        renderSecretState(summary)
    }

    private fun renderSecretState(summary: ListenerSettingsStore.Summary) {
        secretState.text = getString(
            if (summary.hasSecret) R.string.secret_state_saved else R.string.secret_state_missing,
        )
    }

    private fun saveUploaderSettings() {
        if (actionRunning) return
        listOf(serverUrlInput, deviceIdInput, secretInput).forEach { it.error = null }
        val form = ListenerSettingsValidator.validateForm(
            baseUrl = serverUrlInput.text.toString(),
            deviceId = deviceIdInput.text.toString(),
            secretInput = secretInput.text.toString(),
            hasStoredSecret = settingsStore.summary().hasSecret,
            allowDebugCleartext = BuildConfig.DEBUG,
        )
        when (form) {
            is ListenerSettingsValidator.FormResult.Invalid -> showFormErrors(form)
            is ListenerSettingsValidator.FormResult.Valid -> {
                // The typed secret is not kept on screen; the decoded copy is wiped after sealing.
                secretInput.text.clear()
                setActionRunning(true)
                showSettingsResult(getString(R.string.settings_saving))
                actionExecutor.execute {
                    val saved = try {
                        ListenerSync.saveSettings(applicationContext, form.baseUrl, form.deviceId, form.secretKey)
                    } catch (error: GeneralSecurityException) {
                        false
                    } catch (error: RuntimeException) {
                        // Keystore failures can also surface as ProviderException.
                        false
                    } finally {
                        form.secretKey?.fill(0)
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setActionRunning(false)
                        if (saved) {
                            fillSettingsForm(settingsStore.summary())
                            settingsResult.visibility = View.GONE
                            setSettingsExpanded(false)
                            showActionResult(getString(R.string.settings_saved))
                        } else {
                            showSettingsResult(getString(R.string.settings_save_failed))
                        }
                        refreshScreen()
                    }
                }
            }
        }
    }

    private fun showFormErrors(form: ListenerSettingsValidator.FormResult.Invalid) {
        val urlError = when (form.baseUrlProblem) {
            null -> null
            ListenerSettingsValidator.BaseUrlProblem.EMPTY -> R.string.error_url_empty
            ListenerSettingsValidator.BaseUrlProblem.MALFORMED -> R.string.error_url_malformed
            ListenerSettingsValidator.BaseUrlProblem.NOT_HTTPS -> R.string.error_url_not_https
            ListenerSettingsValidator.BaseUrlProblem.HAS_CREDENTIALS -> R.string.error_url_credentials
            ListenerSettingsValidator.BaseUrlProblem.HAS_PATH -> R.string.error_url_path
        }
        val secretError = when (form.secretProblem) {
            null -> null
            ListenerSettingsValidator.SecretProblem.REQUIRED -> R.string.error_secret_required
            ListenerSettingsValidator.SecretProblem.INVALID -> R.string.error_secret_invalid
        }
        urlError?.let { serverUrlInput.error = getString(it) }
        if (form.deviceIdInvalid) deviceIdInput.error = getString(R.string.error_device_id)
        secretError?.let { secretInput.error = getString(it) }
        when {
            urlError != null -> serverUrlInput.requestFocus()
            form.deviceIdInvalid -> deviceIdInput.requestFocus()
            secretError != null -> secretInput.requestFocus()
        }
        settingsResult.visibility = View.GONE
    }

    private fun testConnection() {
        if (actionRunning) return
        setActionRunning(true)
        showActionResult(getString(R.string.test_connection_running))
        actionExecutor.execute {
            val result = runCatching { ListenerSync.testConnection(applicationContext) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setActionRunning(false)
                showActionResult(describeTestResult(result))
                refreshScreen()
            }
        }
    }

    private fun describeTestResult(result: ListenerSync.SyncResult?): String = when (result) {
        ListenerSync.SyncResult.NotConfigured -> getString(R.string.test_connection_not_configured)
        ListenerSync.SyncResult.SecretUnavailable -> getString(R.string.upload_problem_secret_unavailable)
        ListenerSync.SyncResult.Busy -> getString(R.string.test_connection_busy)
        is ListenerSync.SyncResult.Heartbeat -> when (val heartbeat = result.result) {
            is ListenerUploader.HeartbeatResult.Success -> {
                val skew = heartbeat.clockSkewMillis
                if (skew != null && UploadStatusText.shouldWarnClock(skew)) {
                    getString(R.string.test_connection_ok_clock, formatSkew(skew))
                } else {
                    getString(R.string.test_connection_ok)
                }
            }
            is ListenerUploader.HeartbeatResult.Failure ->
                getString(R.string.test_connection_failed, describeProblem(heartbeat.problem))
        }
        null, is ListenerSync.SyncResult.Uploaded, is ListenerSync.SyncResult.Periodic ->
            getString(R.string.test_connection_error)
    }

    private fun retryUploadsNow() {
        if (actionRunning) return
        setActionRunning(true)
        actionExecutor.execute {
            val scheduled = runCatching { ListenerSync.retryNow(applicationContext) }.isSuccess
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                setActionRunning(false)
                showActionResult(
                    getString(
                        when {
                            !scheduled -> R.string.upload_retry_failed
                            !isOnline() -> R.string.upload_retry_offline
                            else -> R.string.upload_retry_started
                        },
                    ),
                )
                refreshScreen()
            }
        }
    }

    private fun setActionRunning(running: Boolean) {
        actionRunning = running
        renderActionButtons()
    }

    private fun showActionResult(message: String) {
        uploadActionResult.text = message
        uploadActionResult.visibility = View.VISIBLE
    }

    private fun showSettingsResult(message: String) {
        settingsResult.text = message
        settingsResult.visibility = View.VISIBLE
    }

    private fun isOnline(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
            val result = runCatching { repository.clear() }
            runOnUiThread {
                if (!isDestroyed) {
                    if (result.isFailure) storageError.visibility = View.VISIBLE
                    refreshScreen()
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
        .replace(' ', ' ')

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
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
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
        const val DISABLED_ALPHA = 0.45f
        const val STATE_SETTINGS_EXPANDED = "settings_expanded"
    }
}
