# QRIS Notification Listener Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the unofficial Node.js GoPay gateway with a private Android APK that locally detects, parses, deduplicates, stores, and displays static QRIS payment notifications from the official GoPay Merchant app.

**Architecture:** A system-bound `NotificationListenerService` rejects every package except `com.gojek.gopaymerchant` before reading notification text. Pure Kotlin parsing and event-ID components feed an app-private SQLite repository on a single worker thread; a single diagnostic activity shows permission state, connection state, latest payment, and local history.

**Tech Stack:** Kotlin, Android framework APIs, Android Gradle Plugin 9.2.1 with built-in Kotlin, Gradle 9.4.1, compile/target SDK 36, min SDK 26, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-08-24-qris-notification-listener-design.md`

## Global Constraints

- The application ID is `id.ciganstore.qrislistener`; the visible name is `QRIS Listener`.
- The only accepted source package is `com.gojek.gopaymerchant`.
- The accepted initial title is `Pembayaran QRIS statis diterima`.
- The proof-of-concept has no `INTERNET`, SMS, contacts, storage, Accessibility, device-admin, VPN, or root permission.
- Preserve `.git` and both `docs/superpowers/specs` and `docs/superpowers/plans` during cleanup.
- Delete only explicitly inventoried legacy children inside the verified workspace root.
- Do not commit, push, publish, or deploy; the user retains those actions.
- Do not run `login.js`, `sessionManager.js`, or any GoPay login/session code.

## Target file structure

```text
.
├── .gitignore
├── README.md
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/gradle-wrapper.jar
├── gradle/wrapper/gradle-wrapper.properties
├── settings.gradle.kts
├── docs/superpowers/specs/2026-08-24-qris-notification-listener-design.md
├── docs/superpowers/plans/2026-08-24-qris-notification-listener.md
└── app
    ├── build.gradle.kts
    └── src
        ├── main
        │   ├── AndroidManifest.xml
        │   ├── java/id/ciganstore/qrislistener
        │   │   ├── MainActivity.kt
        │   │   ├── data/PaymentEvent.kt
        │   │   ├── data/PaymentRepository.kt
        │   │   ├── data/SqlitePaymentRepository.kt
        │   │   └── notification
        │   │       ├── GopayNotificationListenerService.kt
        │   │       ├── NotificationAccess.kt
        │   │       ├── NotificationPayload.kt
        │   │       ├── PaymentEventId.kt
        │   │       ├── PaymentNotificationParser.kt
        │   │       └── PaymentNotificationProcessor.kt
        │   └── res
        │       ├── drawable/bg_status_active.xml
        │       ├── drawable/bg_status_inactive.xml
        │       ├── layout/activity_main.xml
        │       └── values/{colors.xml,strings.xml,themes.xml}
        └── test/java/id/ciganstore/qrislistener/notification
            ├── PaymentEventIdTest.kt
            ├── PaymentNotificationParserTest.kt
            └── PaymentNotificationProcessorTest.kt
```

The files are separated so parsing, event identity, persistence, Android notification integration, access detection, and UI each have one responsibility.

---

### Task 1: Remove the legacy gateway and scaffold a buildable Android project

**Files:**
- Delete: `.env`
- Delete: `.env.example`
- Delete: `docker-compose.yml`
- Delete: `Dockerfile`
- Delete: `login.js`
- Delete: `package-lock.json`
- Delete: `package.json`
- Delete: `server.js`
- Delete: `sessionManager.js`
- Delete: `setup.sh`
- Delete directory: `node_modules/`
- Replace: `.gitignore`
- Replace: `README.md`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/java/id/ciganstore/qrislistener/MainActivity.kt`
- Generate: Gradle wrapper files

**Interfaces:**
- Produces: a clean Android application that builds as `app-debug.apk` and contains no legacy Node runtime or secret files.

- [ ] **Step 1: Re-verify the destructive boundary and inventory**

Run:

```powershell
$workspace = (Resolve-Path -LiteralPath '.').Path
$gitRoot = (git rev-parse --show-toplevel).Replace('/', '\\')
if ($workspace -ne $gitRoot) { throw "Workspace and Git root differ" }
if (-not (Test-Path -LiteralPath (Join-Path $workspace '.git'))) { throw '.git missing' }
Get-ChildItem -Force | Select-Object Name, FullName
```

Expected: both paths equal `C:\Users\binta\OneDrive\Documents\Coding\gateaway-gopay`, and every deletion target is a direct child of it except `node_modules`, which resolves underneath it.

- [ ] **Step 2: Delete only the approved legacy targets**

Use native PowerShell with literal paths. Validate `node_modules` resolves under the workspace before its recursive deletion. Never remove the workspace root, parent, `.git`, or `docs`.

```powershell
$legacyFiles = @(
  '.env', '.env.example', 'docker-compose.yml', 'Dockerfile', 'login.js',
  'package-lock.json', 'package.json', 'server.js', 'sessionManager.js', 'setup.sh'
)
foreach ($relative in $legacyFiles) {
  $target = Join-Path $workspace $relative
  if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Force }
}
$modules = (Resolve-Path -LiteralPath (Join-Path $workspace 'node_modules')).Path
if (-not $modules.StartsWith($workspace + [IO.Path]::DirectorySeparatorChar)) {
  throw 'node_modules escaped workspace'
}
Remove-Item -LiteralPath $modules -Recurse -Force
```

Expected: `.git` and `docs` remain; every listed Node target is absent.

- [ ] **Step 3: Create Gradle settings and root build configuration**

Use AGP 9.2.1 and built-in Kotlin:

```kotlin
// settings.gradle.kts
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "QrisListener"
include(":app")
```

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application") version "9.2.1" apply false
}
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=false
android.nonTransitiveRClass=true
```

- [ ] **Step 4: Create the application module**

Use `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`, Java 17 bytecode, and JUnit 4.13.2. Do not add any network, HTTP, analytics, Firebase, or third-party runtime dependency.

```kotlin
plugins { id("com.android.application") }

android {
    namespace = "id.ciganstore.qrislistener"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.ciganstore.qrislistener"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
```

- [ ] **Step 5: Add a minimal activity, manifest, resources, README, and ignore rules**

The manifest initially declares only the launcher activity and no permissions. `MainActivity` displays `QRIS Listener` so the first build proves the toolchain before listener logic is introduced.

The Android `.gitignore` must include:

```gitignore
.gradle/
.idea/
local.properties
**/build/
captures/
*.iml
*.jks
*.keystore
```

- [ ] **Step 6: Generate the wrapper and build the scaffold**

Use Android Studio's JBR and Gradle 9.4.1:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
gradle wrapper --gradle-version 9.4.1 --distribution-type bin
Set-Content -LiteralPath 'local.properties' -Value 'sdk.dir=C\:\\Users\\binta\\AppData\\Local\\Android\\Sdk'
.\gradlew.bat assembleDebug
```

If `gradle` is not on PATH, invoke the cached Gradle 9.x executable found under `%USERPROFILE%\.gradle\wrapper\dists` to generate the wrapper.

Expected: `app/build/outputs/apk/debug/app-debug.apk` exists.

---

### Task 2: Implement the strict QRIS notification parser with tests

**Files:**
- Create: `app/src/main/java/id/ciganstore/qrislistener/notification/NotificationPayload.kt`
- Create: `app/src/main/java/id/ciganstore/qrislistener/data/PaymentEvent.kt`
- Create: `app/src/main/java/id/ciganstore/qrislistener/notification/PaymentNotificationParser.kt`
- Test: `app/src/test/java/id/ciganstore/qrislistener/notification/PaymentNotificationParserTest.kt`

**Interfaces:**
- Produces: `NotificationPayload(packageName, notificationKey, postedAt, title, body)`.
- Produces: `PaymentEvent(eventId, type, amount, merchant, receivedAt, sourcePackage)`.
- Produces: `PaymentNotificationParser.parse(payload): ParsedPayment?`, where `ParsedPayment(amount, merchant)` contains no Android types.

- [ ] **Step 1: Write failing parser tests**

Include exact assertions for `Rp 1`, `Rp 1.000`, `Rp 25.000`, merchant punctuation, optional final period, case-insensitive title, wrong package, wrong title, empty merchant, zero, negative, decimal, and malformed amounts.

```kotlin
@Test fun parsesThousandsSeparator() {
    val result = parser.parse(payload(body = "Rp 25.000 di CiganStore, Digital & Kreatif."))
    assertEquals(25000L, result?.amount)
    assertEquals("CiganStore, Digital & Kreatif", result?.merchant)
}

@Test fun rejectsOtherPackages() {
    assertNull(parser.parse(payload(packageName = "com.example.fake")))
}

@Test fun rejectsDecimalAmount() {
    assertNull(parser.parse(payload(body = "Rp 1,50 di CiganStore.")))
}
```

- [ ] **Step 2: Run the parser tests and confirm failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "*PaymentNotificationParserTest"`

Expected: compilation fails because the parser and models do not exist.

- [ ] **Step 3: Implement the minimal strict parser**

Use constants and an anchored regex:

```kotlin
object PaymentNotificationParser {
    const val GOPAY_MERCHANT_PACKAGE = "com.gojek.gopaymerchant"
    const val STATIC_QRIS_TITLE = "Pembayaran QRIS statis diterima"
    private val bodyPattern = Regex("^Rp\\s+([0-9]{1,3}(?:\\.[0-9]{3})*|[0-9]+)\\s+di\\s+(.+?)\\.?$")

    fun parse(payload: NotificationPayload): ParsedPayment? {
        if (payload.packageName != GOPAY_MERCHANT_PACKAGE) return null
        if (!payload.title.trim().equals(STATIC_QRIS_TITLE, ignoreCase = true)) return null
        val match = bodyPattern.matchEntire(payload.body.trim()) ?: return null
        val amount = match.groupValues[1].replace(".", "").toLongOrNull() ?: return null
        val merchant = match.groupValues[2].trim().removeSuffix(".").trim()
        if (amount <= 0L || merchant.isEmpty()) return null
        return ParsedPayment(amount, merchant)
    }
}
```

- [ ] **Step 4: Run parser tests**

Run: `.\gradlew.bat testDebugUnitTest --tests "*PaymentNotificationParserTest"`

Expected: all parser tests pass.

---

### Task 3: Implement deterministic IDs, processing, and duplicate protection

**Files:**
- Create: `app/src/main/java/id/ciganstore/qrislistener/notification/PaymentEventId.kt`
- Create: `app/src/main/java/id/ciganstore/qrislistener/data/PaymentRepository.kt`
- Create: `app/src/main/java/id/ciganstore/qrislistener/notification/PaymentNotificationProcessor.kt`
- Test: `app/src/test/java/id/ciganstore/qrislistener/notification/PaymentEventIdTest.kt`
- Test: `app/src/test/java/id/ciganstore/qrislistener/notification/PaymentNotificationProcessorTest.kt`

**Interfaces:**
- Consumes: `PaymentNotificationParser.parse(NotificationPayload)`.
- Produces: `PaymentEventId.from(payload): String` as lowercase SHA-256.
- Produces: `PaymentRepository.insertIfNew(event): Boolean`, `listNewest(limit): List<PaymentEvent>`, `clear()`.
- Produces: `PaymentNotificationProcessor.process(payload): ProcessResult` with `Accepted`, `Duplicate`, or `Ignored`.

- [ ] **Step 1: Write failing event-ID and processor tests**

Use an in-memory fake repository inside the test. Assert the same payload produces the same 64-character lowercase ID, changing `postedAt` changes it, the first processing result is `Accepted`, the second is `Duplicate`, and an invalid notification is `Ignored` without repository insertion.

```kotlin
@Test fun duplicateNotificationIsInsertedOnlyOnce() {
    val repository = FakePaymentRepository()
    val processor = PaymentNotificationProcessor(repository)
    assertTrue(processor.process(validPayload) is ProcessResult.Accepted)
    assertEquals(ProcessResult.Duplicate, processor.process(validPayload))
    assertEquals(1, repository.events.size)
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run: `.\gradlew.bat testDebugUnitTest --tests "*PaymentEventIdTest" --tests "*PaymentNotificationProcessorTest"`

Expected: compilation fails because ID, repository, and processor contracts do not exist.

- [ ] **Step 3: Implement SHA-256 identity and processor**

Build the digest input with unambiguous newline separators in this order: package name, notification key, decimal post time, title, body. The processor creates a `PaymentEvent` only after strict parsing succeeds and returns the repository's duplicate outcome.

```kotlin
sealed interface ProcessResult {
    data class Accepted(val event: PaymentEvent) : ProcessResult
    data object Duplicate : ProcessResult
    data object Ignored : ProcessResult
}
```

- [ ] **Step 4: Run all unit tests**

Run: `.\gradlew.bat testDebugUnitTest`

Expected: parser, event-ID, and processor tests all pass.

---

### Task 4: Add private persistence and the Android notification listener service

**Files:**
- Create: `app/src/main/java/id/ciganstore/qrislistener/data/SqlitePaymentRepository.kt`
- Create: `app/src/main/java/id/ciganstore/qrislistener/notification/GopayNotificationListenerService.kt`
- Create: `app/src/main/java/id/ciganstore/qrislistener/notification/NotificationAccess.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `PaymentNotificationProcessor`, `PaymentRepository`, and pure models from Tasks 2-3.
- Produces: app-private SQLite table `payment_events` with a primary-key `event_id`.
- Produces: explicit app-scoped broadcast action `id.ciganstore.qrislistener.PAYMENT_HISTORY_CHANGED` after an accepted event.
- Produces: `NotificationAccess.isGranted(context): Boolean`.

- [ ] **Step 1: Implement the SQLite repository**

Create schema version 1:

```sql
CREATE TABLE payment_events (
  event_id TEXT PRIMARY KEY NOT NULL,
  type TEXT NOT NULL,
  amount INTEGER NOT NULL CHECK(amount > 0),
  merchant TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  source_package TEXT NOT NULL
)
```

Use `insertWithOnConflict(..., CONFLICT_IGNORE)` for idempotency, `ORDER BY received_at DESC`, a caller-supplied bounded limit, and a transaction for `clear()`.

- [ ] **Step 2: Implement notification-access detection**

For API 27+, call `NotificationManager.isNotificationListenerAccessGranted(ComponentName)`. On API 26, parse `Settings.Secure.getString(contentResolver, "enabled_notification_listeners")` into flattened `ComponentName` values and compare exactly with the listener component.

- [ ] **Step 3: Implement the listener service**

The callback must check `sbn.packageName` before reading extras:

```kotlin
override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName != PaymentNotificationParser.GOPAY_MERCHANT_PACKAGE) return
    val extras = sbn.notification.extras
    val payload = NotificationPayload(
        packageName = sbn.packageName,
        notificationKey = sbn.key,
        postedAt = sbn.postTime,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
        body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
    )
    executor.execute { processAndNotifyUi(payload) }
}
```

Use one `ExecutorService`; close it in `onDestroy`. Connection state is stored in private preferences. `onListenerDisconnected` records disconnected and calls `requestRebind(ComponentName(...))` without starting a foreground service.

- [ ] **Step 4: Register the service with only the system binding permission**

```xml
<service
    android:name=".notification.GopayNotificationListenerService"
    android:exported="true"
    android:label="@string/listener_service_name"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
```

Verify the manifest contains no `<uses-permission>` entry, especially no `android.permission.INTERNET`.

- [ ] **Step 5: Run tests, lint, and debug build**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Expected: all unit tests pass, lint has no errors, and the debug APK builds.

---

### Task 5: Build the diagnostic UI and local-history controls

**Files:**
- Modify: `app/src/main/java/id/ciganstore/qrislistener/MainActivity.kt`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/drawable/bg_status_active.xml`
- Create: `app/src/main/res/drawable/bg_status_inactive.xml`
- Create: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/themes.xml`

**Interfaces:**
- Consumes: `NotificationAccess.isGranted`, `PaymentRepository.listNewest`, `PaymentRepository.clear`, and the history-changed broadcast.
- Produces: one responsive diagnostic screen with no web/server controls.

- [ ] **Step 1: Create the responsive XML layout**

Use a `ScrollView` root with a vertical container so content remains usable on small screens and large fonts. Include IDs for access status, connection status, settings button, latest-payment card, history container, empty state, diagnostic counter, and clear-history button. Avoid fixed screen heights.

- [ ] **Step 2: Implement activity state and settings navigation**

On `onResume`, refresh notification access, connection state, and history on a worker executor. Open `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` from the permission button. Register an app-scoped runtime receiver while started and unregister it while stopped.

- [ ] **Step 3: Render payment history safely**

Format rupiah with `NumberFormat.getCurrencyInstance(Locale("id", "ID"))`, format time in the device timezone, and construct text from parsed database fields only. Never display raw unrelated notification text.

- [ ] **Step 4: Add confirmed local deletion**

The clear button opens an `AlertDialog`. Only its positive action calls `repository.clear()` on the executor, refreshes the screen, and leaves GoPay Merchant data untouched.

- [ ] **Step 5: Run automated verification**

Run:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Expected: tests pass, lint has no errors, and the APK exists.

---

### Task 6: Document, install, and verify on the merchant phone

**Files:**
- Modify: `README.md`
- Verify artifact: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Produces: a copy-ready installation and test guide plus a device-verified debug APK.

- [ ] **Step 1: Write README setup and privacy documentation**

Document:

- The app reads only GoPay Merchant package `com.gojek.gopaymerchant` after Notification Access is granted.
- It has no internet permission and does not read OTP, SMS, PIN, contacts, or files.
- How to enable Notification Access.
- How to set both GoPay Merchant and QRIS Listener battery usage to unrestricted if the phone vendor kills background processes.
- How to make a Rp1 test payment and what parsed output should appear.
- Local history is removed by Clear History or uninstalling the APK; GoPay transaction history is unaffected.
- Web/server delivery is not part of this version.

- [ ] **Step 2: Check manifest permissions from the built APK**

Run Android build-tools `aapt2 dump permissions` or `apkanalyzer manifest permissions` against the APK.

Expected: no `android.permission.INTERNET`, SMS, contacts, storage/media, Accessibility, device-admin, or VPN permission; the service binding permission appears only on the service declaration.

- [ ] **Step 3: Detect a connected device**

Run:

```powershell
& 'C:\Users\binta\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
```

If no authorized physical device is listed, stop device verification and give the APK path plus USB-debugging instructions. Do not claim installation or runtime verification.

- [ ] **Step 4: Install the APK without erasing app data**

Run:

```powershell
& 'C:\Users\binta\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

Expected: `Success`. Leave the app installed and open.

- [ ] **Step 5: Verify permission and package filtering**

Open the app, grant Notification Access manually, return to the app, and confirm status becomes active. Generate unrelated notifications and verify none appear in QRIS Listener history.

- [ ] **Step 6: Verify a real QRIS payment notification**

With the activity closed, make one real static QRIS payment. Confirm exactly one event appears with the correct amount and merchant after reopening the listener. Reopen once more and verify no duplicate appears.

- [ ] **Step 7: Final verification snapshot**

Run:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
git status --short --untracked-files=all
```

Expected: clean build/tests/lint pass; status contains only the intentional Android replacement and design/plan documents. Do not commit or push.

