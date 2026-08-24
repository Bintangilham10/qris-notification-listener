---
version: 1
slug: "app-src-main-res-layout-activity-main-xml"
primary_target: "app/src/main/res/layout/activity_main.xml"
related_targets: ["app/src/main/java/id/ciganstore/qrislistener/MainActivity.kt"]
---

# QRIS Listener phone surface

- Scope and mode: one native Android phone screen in Operate mode.
- Audience and job: a merchant checks whether local notification listening is ready and whether a payment was parsed.
- Primary action: open Android Notification access settings when readiness is incomplete.
- Proof and content: explicit access and connection states, latest parsed event, persistent local history, empty/error diagnostics, and privacy copy.
- Constraints: no internet, no raw unrelated notification text, no customer storefront controls, scroll-safe at large font sizes, light and dark themes.
- Direction: Kartu Stok Digital. Calm Material-native ledger surfaces with readiness before transaction detail.
- Memorable moment: both readiness states turn textual green only when Android access and service connection are truly present.
- Unresolved: real GoPay package and notification text still require validation on the merchant's physical phone.
