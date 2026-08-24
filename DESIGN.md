---
name: QRIS Listener
description: A calm Android diagnostic surface for private local payment listening.
colors:
  action-teal: "#146B5D"
  on-action: "#FFFFFF"
  mineral-ground: "#F3F6F5"
  clean-surface: "#FFFFFF"
  payment-container: "#D5ECE5"
  ink: "#14201D"
  muted-ink: "#51635E"
  quiet-outline: "#BCC9C5"
  active-container: "#CDEBDD"
  active-ink: "#07583D"
  waiting-container: "#F6E5C8"
  waiting-ink: "#724A09"
  error-container: "#FFE0DE"
  error-ink: "#8B1D18"
  night-ground: "#101613"
  night-surface: "#19221F"
  night-payment-container: "#183D35"
  night-ink: "#E8F0ED"
  night-muted-ink: "#B6C5C0"
  night-action-teal: "#63D7BE"
  night-on-action: "#062C25"
typography:
  display:
    fontFamily: "sans-serif"
    fontSize: "30sp"
    fontWeight: 700
  headline:
    fontFamily: "sans-serif"
    fontSize: "20sp"
    fontWeight: 700
  body:
    fontFamily: "sans-serif"
    fontSize: "15sp"
    fontWeight: 400
  label:
    fontFamily: "sans-serif"
    fontSize: "14sp"
    fontWeight: 700
  support:
    fontFamily: "sans-serif"
    fontSize: "13sp"
    fontWeight: 400
rounded:
  alert: "12dp"
  panel: "16dp"
  badge: "999dp"
spacing:
  compact: "8dp"
  group: "16dp"
  content-edge: "20dp"
  section: "30dp"
components:
  button-primary:
    backgroundColor: "{colors.action-teal}"
    textColor: "{colors.on-action}"
    rounded: "{rounded.alert}"
    height: "48dp"
    padding: "12dp 16dp"
  status-active:
    backgroundColor: "{colors.active-container}"
    textColor: "{colors.active-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.badge}"
    padding: "7dp 12dp"
  status-waiting:
    backgroundColor: "{colors.waiting-container}"
    textColor: "{colors.waiting-ink}"
    typography: "{typography.label}"
    rounded: "{rounded.badge}"
    padding: "7dp 12dp"
  working-panel:
    backgroundColor: "{colors.clean-surface}"
    rounded: "{rounded.panel}"
    padding: "18dp"
  latest-payment-panel:
    backgroundColor: "{colors.payment-container}"
    rounded: "{rounded.panel}"
    padding: "20dp"
---

# Design System: QRIS Listener

## Overview

**Creative North Star: "Kartu Stok Digital"**

QRIS Listener feels like a trustworthy operational record kept beside a merchant's register. The interface is calm, direct, and easy to audit: readiness appears before history, accepted payment data receives the strongest surface, and recovery actions use plain Indonesian.

The visual system follows Android conventions instead of borrowing a web dashboard or iOS shell. It uses one teal action family, mineral neutral surfaces, restrained tonal depth, native text scaling, and semantic status labels that remain understandable without color.

**Key Characteristics:**

- Readiness before transaction detail.
- One action color reserved for controls and connected states.
- Tonal panels instead of decorative shadows or borders.
- Clear empty, active, waiting, disabled, and error states.
- Light and dark schemes with the same information hierarchy.

## Colors

The palette uses quiet green mineral neutrals with a single teal action family. Light and night tokens are paired by role so hierarchy does not change with system theme.

### Primary

- **Merchant Teal:** The `action-teal` and `night-action-teal` tokens mark the primary settings action and successful operational emphasis.

### Neutral

- **Mineral Ground:** The `mineral-ground` and `night-ground` tokens form the edge-to-edge application field.
- **Clean Surface:** The `clean-surface` and `night-surface` tokens contain readiness and empty history content.
- **Ledger Ink:** The `ink`, `muted-ink`, `night-ink`, and `night-muted-ink` tokens preserve readable hierarchy.
- **Quiet Outline:** The `quiet-outline` token is used only for a functional divider inside readiness.

### Named Rules

**The One Signal Rule.** Teal means an action can be taken or the listener is operational; it is not decorative color.

**The Text Plus Color Rule.** Active, waiting, and error states always carry explicit text. Color never communicates status alone.

## Typography

**Display Font:** Android sans-serif
**Body Font:** Android sans-serif

**Character:** The platform face keeps the utility native and legible. Hierarchy comes from Material-like role changes in size and weight, never from a novelty font.

### Hierarchy

- **Display** (700, 30sp): Application name and the largest empty or payment amount state.
- **Headline** (700, 20sp): Readiness, latest payment, and local history sections.
- **Body** (400, 15sp): Explanations, merchant identity, and empty-state guidance.
- **Label** (700, 14sp): Status badges and decisive control text.
- **Support** (400, 13sp): Timestamps, diagnostics, and local-storage notes.

### Named Rules

**The System Scale Rule.** All visible text uses `sp` and must remain readable at a 1.3 font scale without fixed-height clipping.

## Layout

The phone layout is a single edge-to-edge vertical ScrollView with 20dp content edges. Related items use 8-16dp spacing; major sections use 30dp separation. No screen-height assumption is allowed, so smaller devices and large fonts scroll naturally below the readiness and latest-payment regions.

System status, navigation, and display cutout insets are applied at runtime. The app ships one phone destination and therefore does not introduce bottom navigation, a drawer, or a floating action button.

## Elevation & Depth

Depth is primarily tonal: the white or deep-green readiness surface sits on the mineral ground, while the latest payment uses a stronger green container. Panels do not combine borders with decorative shadows. The primary Android button may use its platform state elevation solely as interaction feedback.

**The Tonal Ledger Rule.** A surface earns a distinct tone only when it separates readiness, the newest payment, an empty state, or an error.

## Shapes

Working panels use a consistent 16dp corner radius. The action button and error surface use a firmer 12dp radius. Compact semantic status labels are the only fully rounded elements; their pill shape signals a state, not decoration.

## Components

### Buttons

- **Shape:** Confident rounded rectangle (12dp) with a minimum 48dp touch height.
- **Primary:** Merchant Teal background with high-contrast on-action text and Android ripple feedback.
- **Secondary:** A borderless text action is used only for clearing local history and visibly disables when history is empty.

### Chips

- **Style:** Active and waiting status badges use semantic container and text pairs with 7dp vertical and 12dp horizontal padding.
- **State:** Status text changes with the color pair; badges are informational and never clickable.

### Cards / Containers

- **Corner Style:** Soft working panels (16dp).
- **Background:** Neutral surface for readiness and empty history; payment container for the newest parsed payment.
- **Shadow Strategy:** Tonal layering by default, with no card shadow.
- **Border:** None; a single internal 1dp divider separates the two readiness rows.
- **Internal Padding:** 18-20dp.

### Signature Component: Readiness Panel

The readiness panel groups Notification Access, listener connection, and the one recovery action in reading order. Both states must be visible before transaction content and must never be reduced to unlabeled dots.

## Do's and Don'ts

### Do:

- **Do** show access and connection as separate textual states.
- **Do** keep the settings action full width and at least 48dp tall.
- **Do** use parsed local fields for payment rows and preserve device-locale time formatting.
- **Do** test light mode, dark mode, and 1.3 font scale on an Android emulator or device.

### Don't:

- **Don't** add fintech gradients, decorative charts, or unrelated account metrics.
- **Don't** communicate payment success or listener readiness through color alone.
- **Don't** hide core content behind fixed viewport heights or system bars.
- **Don't** introduce web-style navigation or custom iOS controls into this Android utility.

