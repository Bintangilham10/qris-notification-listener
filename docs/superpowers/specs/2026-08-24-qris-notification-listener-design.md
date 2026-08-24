# QRIS Notification Listener - Design

## Objective

Replace the current unofficial Node.js GoPay gateway with a private Android proof-of-concept application that detects successful static QRIS payment notifications posted by the official GoPay Merchant application.

The first version is deliberately local-only. It does not log in to GoPay, request an OTP, access SMS, contact a server, or update an order automatically. Its purpose is to prove that notifications can be filtered and parsed accurately before any web integration is introduced.

## Workspace transition

The existing Node.js gateway is no longer part of the target architecture. During implementation:

- Preserve the `.git` directory so all tracked history remains recoverable.
- Remove the current Node.js source, Docker files, dependency tree, `.env`, and obsolete session/cache artifacts.
- Create a standard single-module Android project in the workspace root.
- Replace the README and `.gitignore` with Android-specific documentation and ignore rules.
- Do not create commits or push changes automatically.

The exact workspace root is `C:\Users\binta\OneDrive\Documents\Coding\gateaway-gopay`. Destructive cleanup must be limited to explicitly inventoried children of this directory and must never target the workspace root itself, its parent, or `.git`.

## Platform and application

- Native Android application written in Kotlin.
- Private sideloaded APK; Google Play publication is outside scope.
- One application module named `app`.
- Application name: `QRIS Listener`.
- Application ID: `id.ciganstore.qrislistener`.
- Minimum Android API: 26.
- Compile and target SDK will use a compatible installed SDK, currently available through API 36.
- The Android Studio bundled JDK and the installed Android SDK will be used for local builds.

## Notification source and privacy boundary

The listener uses Android's `NotificationListenerService`, declared with `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`. The user must explicitly grant Notification Access through Android system settings.

Although Android delivers notification metadata from all applications to an enabled listener, the implementation must check `StatusBarNotification.packageName` first and immediately return unless it is exactly:

```text
com.gojek.gopaymerchant
```

The application must not request or use:

- SMS permission
- Contacts permission
- Storage or media permission
- Accessibility Service
- Device administrator access
- VPN access
- Root access
- Internet permission in the local proof-of-concept

No unrelated notification title or body may be persisted or displayed.

## Accepted notification format

The initial confirmed GoPay Merchant notification is:

```text
Title: Pembayaran QRIS statis diterima
Body:  Rp 1 di CiganStore, Digital & Kreatif.
```

An event is accepted only when all of these conditions hold:

1. Package name is `com.gojek.gopaymerchant`.
2. The normalized title equals `Pembayaran QRIS statis diterima`, ignoring leading/trailing whitespace and letter case.
3. The body matches the structure `Rp <amount> di <merchant>`.
4. The amount can be parsed as a positive whole number of rupiah.
5. The merchant text is not empty.

Indonesian thousands separators are supported. Examples:

```text
Rp 1 di CiganStore, Digital & Kreatif.       -> 1
Rp 1.000 di CiganStore, Digital & Kreatif.   -> 1000
Rp 25.000 di CiganStore, Digital & Kreatif.  -> 25000
```

Unexpected formats are ignored and counted only as a local parser diagnostic without storing their body. This prevents an unrecognized or changed GoPay notification from being misclassified as a payment.

## Parsed payment event

Each accepted notification produces this local model:

```json
{
  "event_id": "deterministic hash",
  "type": "QRIS_STATIC_PAYMENT",
  "amount": 1,
  "merchant": "CiganStore, Digital & Kreatif",
  "received_at": "Android notification post time",
  "source_package": "com.gojek.gopaymerchant"
}
```

`event_id` is derived from the source package, Android notification key, post time, title, and body. It is unique in local storage so notification updates or listener reconnections cannot insert the same event twice.

## Local persistence

Accepted events are stored in a private app database that other normal applications cannot access. The database contains only parsed payment fields and the event ID; it does not store the original notification object, OTPs, tokens, cookies, or unrelated notification content.

The database supports:

- Insert-if-new by event ID.
- Newest-first payment history.
- Count of accepted payments.
- Explicit local history deletion initiated by the user.

Uninstalling the APK deletes this local history. GoPay's own transaction data remains unaffected.

## User interface

The first version has one small status screen:

- Notification Access status: active or inactive.
- Button to open Android Notification Access settings.
- Listener connection status.
- Latest parsed payment with amount, merchant, and time.
- Newest-first local payment history.
- Empty state explaining how to make a test payment.
- Clear local history action with a confirmation dialog.

The screen is diagnostic, not a customer-facing store or payment dashboard.

## Reliability behavior

- The listener must remain functional while the activity is closed; Android binds the notification listener service.
- `onListenerConnected` and `onListenerDisconnected` update visible connection state.
- The app may request a listener rebind after a disconnection where supported.
- Parsing and database work must not block the notification callback's main thread.
- Reboot persistence relies on Android restoring the granted notification listener; no continuous foreground service is introduced in the proof-of-concept.
- Device-specific battery restrictions may still delay or stop delivery. The README will document how to set the listener and GoPay Merchant battery mode to unrestricted when necessary.

## Error handling

- Wrong package: ignore immediately.
- Wrong title: ignore.
- Missing or malformed amount/merchant: ignore and increment a non-sensitive diagnostic counter.
- Duplicate event: ignore without error.
- Database failure: show a local error state without crashing the listener process.
- Notification access revoked: show inactive status and a direct settings button.

The app must never mark a malformed notification as a successful payment.

## Verification

Automated parser tests cover:

- `Rp 1`, `Rp 1.000`, and `Rp 25.000`.
- Merchant names containing commas, ampersands, and spaces.
- Optional final period.
- Wrong source package.
- Wrong notification title.
- Empty merchant.
- Zero, negative, decimal, and malformed amounts.
- Duplicate event IDs.

Build verification produces a debug APK. Device verification then checks:

1. The APK installs and opens.
2. Notification Access can be granted.
3. Unrelated notifications do not appear in history.
4. A real GoPay Merchant QRIS payment notification is parsed once.
5. Closing the activity does not stop detection.
6. Reopening the activity retains local history.
7. Revoking Notification Access changes the visible state.

No server communication is considered or claimed as complete in this phase.

## Deferred work

The following are explicitly deferred until local detection is verified:

- HTTPS delivery to the Node or replacement backend.
- HMAC request signing, nonces, and replay protection.
- Web dashboard.
- Product, stock, cart, checkout, voucher, or customer session features.
- Automatic order matching and `PAID` status updates.
- Dynamic QRIS notification formats.
- Publication to Google Play.

