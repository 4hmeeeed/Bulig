# Handoff: BULIG — offline-first emergency reporting (mobile)

## Overview

BULIG (Waray-Waray for "help") is an offline-first emergency reporting app for a barangay in
Tacloban City, Philippines. A resident files an emergency report with **no internet and no cell
signal** — during a typhoon, flood, or earthquake. The report is written to local storage, then
relayed phone-to-phone over Bluetooth until some phone in the mesh finds signal and uploads it to
the barangay command center.

This bundle covers 12 mobile artboards (390 × 844) — 9 resident screens, 3 responder screens — plus
a component sheet.

### The one non-negotiable product rule

> **The app must never lie about delivery.**

"Saved on your phone", "Passed to 3 nearby phones", and "Delivered to the command center" are three
**different states** and must never look alike. Delivery is opportunistic and genuinely not
guaranteed. Concretely, in this design:

- **Grey** = saved on this phone only. Nobody has seen it.
- **Blue** = relayed / in motion. Copies are travelling. Every relayed surface also carries the
  words "not yet delivered".
- **Green** = confirmed receipt by the command center. **Green is reserved.** Do not use green for
  optimistic states, for "sent", for "queued", or for local success toasts.

If you implement only one thing faithfully, implement this. A submit confirmation that renders
green while offline is a **bug**, not a styling choice.

## About the design files

`BULIG Mobile UI.dc.html` in this folder is a **design reference created in HTML** — a prototype
showing intended look, copy, and states. It is **not production code to copy directly**. It renders
all 12 artboards side by side on one canvas, each artboard a fixed 390 × 844 frame with a fake
status bar and bottom nav.

`support.js` is only the runtime that makes the reference file open in a browser. It is not part of
the design and should not be ported.

Your task is to **recreate these designs in the target codebase's existing environment** — React
Native, Flutter, Kotlin/Compose, SwiftUI, or a web stack — using its established components,
navigation, and theming. If no environment exists yet, choose the framework that fits the
constraint set below and implement there.

Two practical notes on the prototype's HTML, so you don't inherit its shortcuts:

- All styling is inline, single-use, and repeated per artboard. Do **not** mirror that. Build real
  tokens and real components from the "Design tokens" and "Component inventory" sections here.
- Icons are Material Symbols Outlined glyphs, referenced by ligature name (e.g. `flood`, `hub`,
  `signal_cellular_off`). Substitute your platform's icon set; the **shape distinctions matter**
  (see the priority-chip rule).

## Fidelity

**High-fidelity.** Colors, type sizes, weights, spacing, radii, copy (English and Waray-Waray), and
state logic are all final and intentional. Recreate faithfully, using the codebase's existing
primitives where they exist. Where this document and the HTML disagree, **this document wins** —
the HTML has hand-tuned per-artboard padding to fit an 844 px frame that a real scrolling app does
not need.

## Target user and constraints

The user is a frightened person, possibly at night, possibly in rain, with wet hands, on a cheap
Android phone, one-handed, likely holding a child or supporting an injured relative. Design and
implementation consequences, all of which are load-bearing:

- **Minimum body text 16sp.** No 12sp body copy anywhere. Small type in this design is confined to
  metadata, chip labels, and eyebrow labels (11–13px), never to instructions or state.
- **Minimum touch target 48dp.** Steppers are 52dp squares, primary buttons 60dp, responder action
  buttons 64dp, the emergency button ~212dp tall.
- **No gestures for anything important.** No swipe-to-confirm, no long-press, no hold-to-send —
  gloves, rain, and wet screens defeat them. Every action is a plain tap.
- **Nothing requires a network to be understood.** No spinners waiting on a server to render state,
  no empty states that read as errors, no "check your connection" language. Offline is the normal
  case, not the failure case.
- **Strong contrast, readable in bright sun and in the dark.**
- **Few choices per screen.** Never a wall of form fields.
- **Bilingual labels throughout:** English primary, Waray-Waray beneath in a smaller, muted weight.
- The **connectivity banner is persistent** — always visible, never collapsed, never behind a menu.

## Design tokens

Colors are authored in `oklch()`. Hex equivalents are provided for platforms without oklch support;
prefer oklch where available (the ramp is perceptually even and greys stay neutral).

### Surfaces

| Token | oklch | ~hex | Use |
|---|---|---|---|
| `canvas` | `oklch(0.98 0.004 250)` | `#F8F9FB` | Screen background |
| `surface` | `oklch(1 0 0)` | `#FFFFFF` | Cards, bars, sheets |
| `border` | `oklch(0.90 0.008 250)` | `#DFE2E8` | Hairlines, card borders |

### Text

| Token | oklch | ~hex | Use |
|---|---|---|---|
| `ink` | `oklch(0.22 0.015 260)` | `#25272E` | Primary text |
| `ink-muted` | `oklch(0.50 0.014 260)` | `#6E717A` | Secondary text, Waray subtitles |
| `ink-subtle` | `oklch(0.64 0.012 260)` | `#94969E` | Metadata, timestamps, disabled |

### Brand

| Token | oklch | ~hex | Use |
|---|---|---|---|
| `brand` | `oklch(0.52 0.16 250)` | `#3B6FD4` | Borders on selected/active, icon accents |
| `brand-strong` | `oklch(0.44 0.17 252)` | `#2A5AC4` | Primary button fill, active nav, links |
| `brand-soft` | `oklch(0.95 0.03 250)` | `#E8EFFD` | Selected tile fill, mesh callout fill |

### Priority

| Token | oklch | ~hex | Icon shape | On-color text |
|---|---|---|---|---|
| `CRITICAL` | `oklch(0.55 0.22 25)` | `#D2352A` | octagon (`error`) | white |
| `HIGH` | `oklch(0.65 0.19 55)` | `#E8761F` | triangle (`change_history`) | `oklch(0.20 0.04 50)` |
| `MODERATE` | `oklch(0.75 0.15 90)` | `#D89B1C` | square (`square`) | `oklch(0.24 0.05 85)` |
| `LOW` | `oklch(0.65 0.10 200)` | `#3E9FB4` | circle (`circle`) | white |

**HARD RULE: priority is never color alone.** Every priority indicator carries color **+ text label
+ a distinct icon shape**, so it survives color blindness and greyscale printing (barangay offices
print duty rosters on mono laser printers). The four shapes are deliberately different silhouettes —
octagon, triangle, square, circle — not four colors of the same dot.

### State

| Token | oklch | ~hex | Meaning |
|---|---|---|---|
| `online` | `oklch(0.62 0.16 150)` | `#2E9E63` | Connected / delivered / resolved — **reserved for confirmed truth** |
| `offline` | `oklch(0.70 0.17 65)` | `#E28A1B` | No signal / pending delivery |
| `syncing` | `oklch(0.60 0.15 250)` | `#4A7FDD` | Uploading / relaying / in motion |
| `danger` | `oklch(0.55 0.22 25)` | `#D2352A` | Life-threatening, destructive actions |

### Type

**Inter** throughout (400 / 500 / 600 / 700 / 800). **IBM Plex Mono** (400 / 500 / 600) for machine
values only: emergency codes, coordinates, device ids, hop logs, timestamps in list metadata,
artboard numbering.

| Role | Size / line-height / weight | Notes |
|---|---|---|
| Emergency button label | 33 / 1.05 / 800 | Two lines, centered, letter-spacing −0.01em |
| Screen title | 18 / 1.1 / 700 | Header bar |
| Screen subtitle (Waray) | 13 / 1 / 500 | `ink-muted` |
| Section heading | 17–18 / 1.2 / 700 | Card headings, list row titles on responder rows |
| Body | 16 / 1.4 / 400–500 | **Floor for instructional text** |
| Body small | 13.5–14 / 1.4 / 400 | Supporting sentences inside cards |
| List row title | 15–16 / 1.2 / 700 | |
| Bilingual subtitle | 12–12.5 / 1.2 / 500 | `ink-muted`, sits under its English label |
| Eyebrow / section label | 12 / 1 / 700, letter-spacing 0.09–0.10em, uppercase | `ink-muted` |
| Banner state label | 11.5–12 / 1 / 800, letter-spacing 0.10em, uppercase | Tinted to the state color, darkened for contrast |
| Chip label | 10.5–11 / 1 / 700–800, letter-spacing 0.03–0.06em, uppercase | |
| Mono metadata | 12 / 1 / 400–500 | `ink-subtle` |
| Emergency code display | 30 / 1.1 / 600 mono | Large enough to read aloud over radio |
| Stepper value | 22 / 1 / 700 | |

### Radius, spacing, elevation

- **Panel radius `0.75rem` (12px)** — cards, buttons, tiles, phone frames, sheets.
- Inner/secondary radius `0.6rem` (≈10px) — stepper buttons, map overlays, inline status pills.
- Chip radius `0.4–0.5rem` (6–8px). Toggles and pulse rings are fully round (`999px`).
- Screen padding 16px. Card padding 12–16px. Gaps 8–14px, most commonly 10px.
- Priority is expressed on list cards as a **5px left rule** in the priority color, in addition to
  the chip.
- Banners carry a **4px left rule** in the state color, over an 8–12% tint of the same color.
- Only two shadows exist: the emergency button
  (`0 4px 14px oklch(0.55 0.22 25 / 0.32)`) and the submit button
  (`0 3px 10px oklch(0.55 0.22 25 / 0.3)`). Everything else is flat with a 1px border. Do not add
  elevation for decoration.

## The connectivity banner — the most important element

Persistent and always visible at the top of the content area, directly beneath the status bar. Never
collapsed, never dismissible, never behind a menu, never animated away. Six states. Each changes
**icon, left-rule color, tint, eyebrow label, and sentence together** — no state is a recolor of
another, so the set is still distinguishable in greyscale and by shape alone.

Anatomy: 4px left rule · 8–12% tint of the state color · 34–38px white icon tile with a 1px
state-colored border and a 20–22px glyph · eyebrow label (11.5–12px / 800 / 0.10em, uppercase, state
color darkened for AA contrast) · sentence (13.5–14px / 500 / `ink`).

| State | Color | Icon | Eyebrow | Sentence |
|---|---|---|---|---|
| ONLINE | `online` | `cloud_done` | ONLINE | Connected to command center |
| OFFLINE | `offline` | `signal_cellular_off` | OFFLINE (or "OFFLINE — NO SIGNAL" on Home) | Reports are saved and will be relayed to nearby phones |
| SYNCING | `syncing` | `progress_activity`, rotating 1.6s linear infinite | SYNCING | Uploading 3 pending reports… |
| PENDING | `offline` | `hourglass_top` | PENDING | {n} reports waiting to be delivered |
| RELAYED | `syncing` | `hub` | RELAYED — NOT DELIVERED | Passed to {n} nearby phones |
| SYNCED | `online` | `check_circle` | SYNCED | Delivered to command center |

Notes that matter:

- PENDING and OFFLINE share the `offline` hue, so they are additionally separated by **icon**
  (`hourglass_top` vs `signal_cellular_off`), by eyebrow text, and — in the component sheet — by a
  **dashed** outline on PENDING. If your platform can't do the dashed border, keep the icon and text
  differences; do not let the two states collapse into one look.
- RELAYED and SYNCING share the `syncing` hue and are separated the same way (`hub` vs the spinner).
- The RELAYED eyebrow **must** include the negation. "RELAYED" alone reads as success.
- Counts in SYNCING / PENDING / RELAYED are live values; pluralize properly ("1 report waiting").
- The banner is a single reusable component with a `state` enum and a count. Do not fork it per
  screen.

## Screens

Every artboard is 390 × 844, background `canvas`, with:

- **Status bar**, 44px, `surface`: time at 13/600, then right-aligned icons at 15px. Resident
  screens show `signal_cellular_off` in `offline`, `wifi_off`, `bluetooth` in `brand`, battery.
  Responder screens show `signal_cellular_alt_2_bar` in `online`, `bluetooth`, battery. The status
  bar is a prototype prop — use the real system status bar.
- **Bottom nav**, 74px, `surface`, 1px top border, three equal tabs — Home (`home`), My reports
  (`description`), Mesh (`hub`). Icon 23px, label 11px; active tab is `brand-strong` at 700 with a
  2px top border in `brand-strong`; inactive is `ink-muted` at 500. Present on Home, My reports, and
  Mesh status only. Flow screens (02–06), report detail, and all responder screens use a back
  header instead.
- **Back header**, 60px, `surface`, 1px bottom border: 48px back target (`arrow_back`, 26px) ·
  title block (title 17–18/700 + Waray or mono subtitle) · right slot holding either flow position
  (`2 / 4`, mono 12/600, `ink-subtle`) or a priority chip.

### 01 — HOME (resident)

**Purpose:** get a frightened person from launch to "I have reported this" in the fewest taps, and
tell them the truth about connectivity before they act.

Layout, top to bottom: status bar · **connectivity banner (OFFLINE)** · **mesh strip** · content
(16px padding, 14px gaps) · bottom nav.

- **Mesh strip**, 46px, `surface`, 1px bottom border: `hub` icon 19px in `brand` · "4 Bulig phones
  nearby" (14/600 `ink`) · spacer · "Mesh ›" affordance (13/600 `brand-strong`). Taps to artboard 09.
- **REPORT EMERGENCY button** — 212px tall, full width, `danger` fill, radius 12, the one shadow.
  Contents centered: `sos` glyph 44px white · "REPORT / EMERGENCY" 33/1.05/800 white on two lines ·
  "Isumat an Emerhensya" 16/500 at 88% white · a pill (`white / 0.18`, radius 999) with `wifi_off`
  15px + "Works without signal" 12/600. Placed in the lower-middle of the screen so it is reachable
  one-handed with a thumb. **One tap, straight to artboard 02** — no confirmation dialog, no hold.
- **"MY REPORTS" section label** row: eyebrow 12/700/0.10em `ink-muted` · hairline rule · count
  `ink-subtle`.
- **Recent reports card** — `surface`, 1px border, radius 12, rows split by hairlines. Each row:
  34px type-icon tile (radius 8, `canvas` fill, 1px border, 20px glyph `ink-muted`) · title 15/700 +
  mono code · timestamp · **delivery chip** (RELAYED blue, DELIVERED green). Two rows shown.
- **Prototype disclaimer** pinned to the bottom of the content area: `surface` card, 1px border,
  `warning` glyph 17px in `danger`, text 11.5/1.45 `ink-muted` — *"Capstone prototype — not a
  replacement for official emergency services. Call 911 when you have signal."* This note is
  required on the first artboard.

### 02 — EMERGENCY TYPE PICKER

**Purpose:** name the emergency in one tap.

Back header ("What is happening?" / "Ano an nahitatabo? Tap one." / `1 / 4`) · **condensed offline
strip** (`offline` tint, 4px left rule, `signal_cellular_off` 17px, "OFFLINE — this report will be
saved on your phone" 12.5/600) · **2 × 5 grid**, 10px gap, 14/16px padding, rows sized by `1fr` so
all ten tiles fit with **no scrolling and no search**.

Tile: `surface`, 1.5px `border`, radius 12, centered stack — 32px glyph in `brand-strong` · English
15/1.1/700 `ink` · Waray 12/1.15/500 `ink-muted`. **Selected** tile: `brand-soft` fill, 2px
`brand-strong` border, Waray text switches to `brand-strong`. (Flood is shown selected.)

The ten types, in grid order, with icons:

| # | English | Waray-Waray | Icon |
|---|---|---|---|
| 1 | Medical | Emerhensya Medikal | `medical_services` |
| 2 | Fire | Sunog | `local_fire_department` |
| 3 | Flood | Baha | `flood` |
| 4 | Landslide | Pagdahili sang tuna | `landslide` |
| 5 | Earthquake | Linog | `earthquake` |
| 6 | Rescue needed | Kinahanglan bulig | `hail` |
| 7 | Missing person | Nawawara nga tawo | `person_search` |
| 8 | Trapped person | Nakukulong nga tawo | `emergency_home` |
| 9 | Infrastructure | Nadaot nga pasilidad | `construction` |
| 10 | Other | Iba pa | `more_horiz` (`ink-muted`, not brand) |

Have the Waray strings reviewed by a native speaker before shipping; treat them as reviewed
placeholders.

### 03 — REPORT DETAILS

**Purpose:** capture who is affected without a keyboard marathon. **Everything on this screen is
optional except the type already chosen.**

Back header shows the chosen type with its icon + `2 / 4`.

- **Description** — eyebrow "WHAT IS HAPPENING? — OPTIONAL" · 88px `surface` box, 1.5px border,
  radius 12, text 15/1.45 · then a **48px "Record voice note instead"** button (`surface`, 1.5px
  `brand` border, `mic` 21px + label 15/700 in `brand-strong`). Typing in a flood is a bad
  assumption; the voice path is a peer, not a fallback.
- **Four steppers** in one card, hairline-split rows. Each row: English 16/1.2/700 + Waray
  12.5/1.2/500 on the left; on the right a 52px minus (radius 10, `canvas` fill, 1.5px `border`,
  `remove` 26px), a 44px-wide value at 22/700, and a 52px plus (`brand-soft` fill, 1.5px `brand`
  border, `add` 26px in `brand-strong`). 4px between controls.
  1. People affected / Pira ka tawo — shown 5
  2. Children / Kabataan — shown 2
  3. Elderly / Lagas — shown 1
  4. Cannot walk alone / Diri makalakat nga usa — shown 1
- **Life-threatening toggle** — the one loud control. `danger` tint at 7%, **2px `danger` border**,
  radius 12: `warning` 26px `danger` · "Life-threatening" 16/1.15/800 in `oklch(0.42 0.18 27)` ·
  "Delikado an kinabuhi — someone may die without help now" 12.5/1.3 · 58 × 34 switch, `danger`
  fill when on, 28px white knob.
- **Footer** — `surface`, 1px top border: 60px `brand-strong` "CONTINUE" with `arrow_forward`, then
  reassurance 11.5/1.4 `ink-subtle`: *"Nothing is sent yet. Next: confirm where you are."*

### 04 — LOCATION CONFIRM

**Purpose:** confirm position honestly. **A pin is an estimate, not an address.**

Back header ("Where are you?" / "Hain ka yana?" / `3 / 4`) · **296px map preview** · content.

Map preview (in the prototype: a CSS grid-line background, two rotated white road bands, a river
band at the bottom with a `CADACAN RIVER` mono label; in production: cached offline tiles):

- **GPS accuracy ring** — 136px circle, `brand / 0.14` fill, 1.5px **dashed** `brand / 0.6` border,
  drawn **to true scale for the reported accuracy**, with the 22px pin (`brand-strong`, 3px white
  ring) at its center. A bad fix must *look* bad. Do not draw a fixed-size decorative ring.
- **"OFFLINE MAP" chip**, top-left: white 95%, 1px border, radius 10, `cloud_off` 15px in `offline`
  + "Cached tiles · saved 3 days ago".
- **Recenter button**, top-right: 48px, white 95%, `my_location` 24px `brand-strong`.

Content:

- **Accuracy explainer card** — `gps_not_fixed` 22px in `offline` · "Accuracy ±38 m" 15/700 · body
  13/1.4 `ink-muted`: *"The pin is an estimate, not an address. Responders will search the whole
  circle. Move to open sky for a tighter fix."*
- **"CONFIRM YOUR AREA"** — 56px select, `brand-soft` fill, 2px `brand` border: `place` 22px ·
  "Purok 4, Barangay 88" 16/700 · `expand_more`. Beneath, mono 12 `ink-subtle`:
  `11.24186, 125.00417 · fix at 9:42`. **No reverse-geocoded street address is ever invented** —
  only the purok the resident confirms, plus raw coordinates for responders.
- **"Adjust pin by hand"** — 52px secondary, 1.5px `brand` border, `edit_location_alt` +
  15/700 `brand-strong`. Always available; GPS is not authoritative.
- **GPS-failure note** — `canvas` fill, 1px **dashed** border, `info` 17px `ink-subtle`, text
  11.5/1.45: *"If GPS fails entirely you can still submit with only the purok. A report without
  coordinates is better than no report."*
- **Footer** — 60px `brand-strong` "USE THIS LOCATION".

### 05 — REVIEW & SUBMIT

**Purpose:** last look, computed priority **with its reasons**, one confirm action.

Back header ("Check before sending" / "Usisaha anay" / `4 / 4`).

- **Priority card** — `surface`, 1px border, **5px `danger` left rule**, radius 12. Header row:
  CRITICAL chip (filled, `error` octagon + label) + "Highest priority" 13/500 `ink-muted`. Then
  eyebrow "WHY THIS PRIORITY", then reason rows — `check` 18px in the priority color + text 14/1.35
  `ink`:
  - You marked this life-threatening
  - 2 children and 1 elderly person affected
  - 1 person cannot walk without help
  - Flooding, rising water reported

  Priority is **shown with its reasoning, never asserted**. The rules that produce it are listed
  under "Priority computation" below.
- **Summary card** — hairline-split rows, each with a 21px `ink-muted` glyph, content, and a
  "Change" link (13/600 `brand-strong`) that returns to the owning step: type (`flood`) · affected
  (`groups`, "5 people affected" + "2 children · 1 elderly · 1 cannot walk alone") · location
  (`place`, purok + mono coords + `±38 m`) · description (`notes`, verbatim resident text, no
  "Change" link — edit via step 2).
- **Offline warning** — `offline` tint 10%, 1px `offline / 0.45` border, `smartphone` 21px: "You
  have no signal right now" 14/700 + *"This report will be saved on your phone and passed to nearby
  phones. It is not delivered until the command center confirms it."*
- **Footer** — 68px `danger` button, two lines: "SAVE & SEND REPORT" 19/800 + "Tipigan ngan
  ipadara" 13/500, with the submit shadow. Beneath: *"Saved instantly. Relaying starts on its own —
  you can close the app."* The label says what actually happens: it **saves**.

### 06 — SUBMITTED

**Purpose:** confirm the save, hand over the code, and set honest expectations. **No green tick, no
"sent".**

- **Banner: SAVED ON THIS PHONE** — and this is the point: the banner is **grey**
  (`ink-muted` rule over an 8% grey tint, `smartphone` icon), because grey is the truth. Sentence:
  "Waiting for a nearby phone to carry it."
- **Emergency code card** — centered: eyebrow "YOUR EMERGENCY CODE" · code at **30px mono/600**
  (`BLG-2026-0417`) · explanation 13/1.4 centered, max 280px: *"Write this down or say it on the
  radio. It works even if your phone dies."* · two 44px secondary buttons, `content_copy` "Copy"
  and `volume_up` "Read aloud".
- **"WHAT HAPPENS NEXT"** — four numbered steps, 26px round markers; step 1 filled `ink-muted` with
  white numeral (the only completed step), steps 2–4 `canvas` fill with 1.5px border and
  `ink-muted` numeral. Body 14/1.4; step 1 is `ink`, steps 2–4 are `ink-muted`.
  1. **Now —** your report sits on this phone, encrypted.
  2. When a Bulig phone comes within ~80 m, it takes a copy and carries it.
  3. The first phone that finds signal uploads it to the command center.
  4. You get a delivery confirmation only when they truly have it.
- **"Keep Bluetooth on" advice** — `brand-soft` fill, 1px `brand / 0.35` border,
  `bluetooth_searching` 21px: *"Staying near other people — an evacuation center, a street with
  neighbours — makes delivery much more likely."* Actionable advice that improves real delivery odds.
- **Actions** — 56px `brand-strong` "Track this report" (→ 08) and 52px secondary "Back to home".

### 07 — MY REPORTS

**Purpose:** every report's delivery state at a glance.

Header (no back arrow): "My reports" / "An akon mga report" + "4 total" · **PENDING banner** · list.

Each card: `surface`, 1px border, **5px priority left rule**, radius 12, 13/14px padding, 9px gaps:

1. Row: 22px type glyph `ink-muted` · title 16/1.2/700 · **priority chip** (filled, icon + label).
2. Row: **delivery chip** (bordered, tinted, icon + label) + mono timestamp.
3. **Plain-language delivery sentence** 13/1.35 `ink-muted` — the chip is never the only
   explanation.
4. Mono emergency code, `ink-subtle`.

The four cards shown, which together demonstrate the full delivery vocabulary:

| Type | Priority | Delivery chip | Sentence |
|---|---|---|---|
| Flood / Baha | CRITICAL | RELAYED · 3 PHONES (blue) | Copies are travelling. Not yet delivered to the command center. |
| Infrastructure damage | HIGH | SAVED ON THIS PHONE (grey) | No phone has taken a copy yet. It stays here until one does. |
| Missing person | MODERATE | DELIVERED (green) | Command center has it. Responder Tanod R. Cinco assigned. |
| Medical | LOW | RESOLVED (green, `task_alt`) | — (card at 78% opacity; closed items recede but stay legible) |

**Sort order: newest first, and undelivered never sinks below delivered.** A relayed or locally-held
report always outranks a resolved one regardless of age.

### 08 — REPORT DETAIL

**Purpose:** the delivery timeline, in full, with hop-level evidence.

Back header (type + mono code/timestamp + CRITICAL chip) · **RELAYED banner** ("RELAYED — NOT YET
DELIVERED" / "Passed to 3 nearby phones") · timeline card.

Timeline: five steps, each a 28px round marker in a left rail with a 2px connector, and a text
block. **Completed steps are solid** with a white glyph; the **current step is ringed** (3px
translucent halo in its color); **future steps are hollow with a 2px dashed border** and
`ink-subtle` glyph, and are **explicitly labelled "Not yet"** — never pre-filled with optimistic
ticks or greyed checkmarks.

| Step | Marker | Title | Detail |
|---|---|---|---|
| 1 | solid `ink-muted`, `smartphone` | Created on your phone | Saved offline, encrypted · `15:02 · Aug 27` |
| 2 | **ringed** `syncing`, `hub` | Relayed via 3 phones | "Each hop is a phone that took a copy onward" + hop log |
| 3 | dashed hollow, `cloud_upload` | Delivered to command center | Not yet — waiting for a phone with signal |
| 4 | dashed hollow, `badge` | Responder assigned | Not yet |
| 5 | dashed hollow, `task_alt` | Resolved | Not yet |

The **hop log** under step 2 is a `canvas` inset box, 1px border, radius 8, mono 12.5/500 lines:
`hop 1 · phone-7C4A · 15:04` / `hop 2 · phone-B119 · 15:06` / `hop 3 · phone-2E80 · 15:07`. Device
ids are random rotating pseudonyms — never resident names.

Below: an affected-summary strip (`groups` 20px + "5 affected · 2 children · 1 elderly · 1 cannot
walk alone · marked life-threatening") and a 52px secondary "Check for updates" (`refresh`).

### 09 — MESH STATUS  ← the emotional core

**Purpose:** make the invisible visible, and show a resident that **they are helping neighbours**.
Give this screen real care; it is what makes the mesh trustworthy rather than magic.

Header: "Bulig mesh" / "An mesh han Bulig" + an ACTIVE chip (`bluetooth_connected`, `brand` tint).

- **Radar hero** — 158px, `brand-strong` fill, radius 12. Two 118px concentric rings pulse outward
  (`scale(0.55) opacity .45` → `scale(1.3) opacity 0`, 3.4s ease-out infinite, second ring offset
  1.7s), four small white dots for nearby devices (one at 55% opacity = fading), and a centered
  translucent panel: **count at 46/800 white**, "BULIG PHONES NEARBY" 12/700/0.10em, "within about
  80 metres" 12.5/500. Honest about range; no fake map of neighbours' positions.
- **The headline callout** — `brand-soft` fill, 1px `brand / 0.35` border, `volunteer_activism`
  26px: **"You are carrying 2 reports for other people"** at 17/1.25/800, then *"Your phone is
  holding your neighbours' emergencies and will hand them on the moment it can. You do not have to
  do anything."* This sentence — not a network graph — is the point of the screen. Keep it plain,
  keep it first, do not turn it into a stat tile.
- **Two contribution tiles** — "7 / Reports passed on today" and "2 / Delivered because of you",
  value 24/700, label 12/1.25 `ink-muted`.
- **"NEARBY DEVICES"** card — three rows, 9/12px padding: `smartphone` glyph tinted by role · mono
  device id 14/600 · link quality + hop distance 12/400 `ink-muted` · role chip.
  - `phone-7C4A` — strong link · 1 hop from signal — **CAN UPLOAD** (green tint)
  - `phone-B119` — strong link · 2 hops from signal — **RELAYING** (blue tint)
  - `phone-2E80` — weak link · hops unknown — **RELAYING** (blue tint)
- **Privacy + churn footnote** 11/1.4 `ink-subtle`: *"phone-F03D dropped out of range 40 s ago.
  Device names are random and change daily — no resident names, numbers or locations are ever
  shown."* Device churn is stated as normal, not as an error.

### 10 — MY ASSIGNMENTS (responder)

**Purpose:** answer, while walking, the four questions a responder asks: what, how bad, how far,
how old.

Header: 40px `RC` avatar (`brand-soft`, 1px `brand / 0.3`, 14/700 `brand-strong`) · "My assignments"
+ "Tanod R. Cinco · Zone 2" · 48px `filter_list`. Then the **SYNCING banner** ("Uploading 3 pending
reports…", spinner).

Cards, **strict priority order, never user-reorderable**. Top card is expanded with its action:

- Row 1: priority chip · spacer · age ("10 min ago").
- Row 2: 24px type glyph `ink` · type 18/1.2/700.
- Row 3: affected summary 14/1.4 `ink-muted` ("5 affected · 2 children · 1 elderly ·
  life-threatening").
- Row 4: `near_me` + "420 m · Purok 4" 13.5/600, and `hub` + "via 3 hops" in `brand`.
- Row 5 (top card only): 50px `brand-strong` "Open assignment".

Second card additionally carries a status chip (EN ROUTE) beside its priority chip. Third card is
MODERATE with no injuries. Footnote 11.5/1.45 `ink-subtle`: *"Reports may arrive out of order over
the mesh. Age is measured from when the resident filed it, not when you received it."* Age is
**filing time**, not receipt time — a mesh-delayed CRITICAL must not look fresh.

### 11 — ASSIGNMENT DETAIL (responder)

Back header (type + mono `code · filed 15:02` + CRITICAL chip) · **172px map** · content.

Map: same construction as artboard 04, but the accuracy ring and pin are in `danger` (this is an
incident, not the user's own position), plus a small `brand-strong` dot labelled **YOU** and a
top-right readout card: "420 m" 14/700 + "±38 m accuracy" 11/400.

- **"RESIDENT'S WORDS"** card — the description **verbatim**, 15/1.45, in quotes, with a footnote
  *"Waray-Waray · not translated by the app"*. Never machine-translate an emergency; nuance loss
  can cost lives.
- **Vulnerability breakdown** — 2 × 2 grid of tiles, value 20/700 + label 12.5/1.2. "5 affected" is
  neutral (`surface`, 1px border); **children, elderly, and cannot-walk are tinted `danger` at 6%
  with a `danger / 0.3` border** because they change what a responder brings. Above the fold,
  deliberately.
- **"WHY CRITICAL"** — same reason-row pattern as artboard 05: resident marked life-threatening ·
  3 of 5 affected are vulnerable · rising water, people on a roof. Reasoning is shown so a responder
  can **override it with judgement**.
- **Mesh-latency note** — `syncing` tint 8%, 1px `syncing / 0.3` border, `hub` 18px, 12/1.45:
  *"Reached the command center after 3 mesh hops, 5 min behind filing. The situation may have
  changed."*
- **Footer** — 64px `online` "ACCEPT" (`check_circle` 25px + 19/800) and 52px secondary
  "Decline — cannot reach".

### 12 — ACTION BAR STATES (responder)

Not a screen — the six footer configurations of artboard 11, stacked as a spec sheet. **One decision
per state.** Primary buttons are 64dp, full width; the next action is always the **only filled
button on screen**. No swipe-to-confirm anywhere.

Each state pairs a **status pill** (9/12px padding, radius 10, tinted, 13/700) with its action:

1. **ASSIGNED** — `online` "ACCEPT" (`check_circle`) + secondary "Decline — cannot reach".
2. **ACCEPTED** — pill "ACCEPTED 15:14 · resident notified when signal allows" (green) →
   `brand-strong` "EN ROUTE" (`directions_walk`). The pill is explicit that resident notification is
   **conditional on signal**.
3. **EN ROUTE** — pill "EN ROUTE · 6 min · 420 m to go" (blue) → `brand-strong` "ON SITE"
   (`location_on`).
4. **ON SITE** — pill "ON SITE since 15:26" (blue) → `online` "RESOLVED" (`task_alt`) + a 52px
   secondary with a `HIGH`-orange border: "Needs more help — escalate".
5. **RESOLVED & QUEUED OFFLINE** — pill "RESOLVED on this phone · status not yet uploaded"
   (`offline`, `hourglass_top`) → a **disabled-looking** 64px "CLOSED" (`canvas` fill, 1.5px border,
   `ink-subtle` text). The responder's own status changes obey the same honesty rule as residents'
   reports: locally resolved ≠ synced.
6. **DECLINE confirmation sheet** — "Decline this assignment?" 16/1.25/700 · *"It returns to the
   command center queue immediately, and to other responders when signal allows. Tell them why:"* ·
   three reason chips (Road impassable · Already on another call · Too dangerous alone) · 56px
   `danger` "DECLINE" · 50px secondary "Keep it".

## Priority computation

Computed **on the device**, offline, deterministically — never server-side, never a spinner. Shown
with its reasons on artboards 05 and 11.

Inputs: emergency type · life-threatening flag · people affected · children · elderly · people who
cannot walk alone.

Rules as implemented in the design:

- **CRITICAL** — life-threatening flag set, **or** a type that implies immediate threat to life
  (trapped person, rescue needed) with any vulnerable person affected.
- **HIGH** — entrapment or injury implied by type, or vulnerable people affected without the
  life-threatening flag.
- **MODERATE** — needs a response today; no injury reported (e.g. infrastructure damage, missing
  person with no immediate danger).
- **LOW** — report only, no injury, no vulnerability.

Reason strings are generated from whichever inputs fired, in this order: the life-threatening flag
first, then vulnerability counts, then type-specific context. Show every reason that contributed;
show nothing that didn't. If your rules differ from a barangay's SOP, make the rule table
configurable — but keep the **reasons visible**.

## Interactions & behavior

Navigation: Home → type picker → details → location → review → submitted → (track) report detail.
Bottom nav switches Home / My reports / Mesh. Responder: assignments → assignment detail → action
bar progression.

- **Nothing blocks on the network.** Submit writes to local storage and returns immediately; the
  submitted screen is reachable with airplane mode on and the radios off.
- **Relaying is autonomous.** The resident is told "you can close the app". Background BLE
  advertise/scan carries reports onward; the UI never asks a resident to keep a screen open.
- **The banner reflects reality, not intent.** It reads from actual radio + sync state. It must
  never optimistically advance (no "SYNCED" on request dispatch — only on acknowledged receipt).
- **State can only move forward on evidence.** Local save → relayed (a peer acknowledged taking a
  copy, with hop count) → delivered (command center acknowledged) → assigned → resolved. Never
  infer a later state from an earlier one.
- **Reports may arrive out of order and late.** Sort and age everything by **filing timestamp**.
- Animations, total: the two mesh pulse rings (3.4s ease-out infinite, offset 1.7s) and the sync
  spinner (1.6s linear infinite). Nothing else moves. No decorative motion, no confetti, no success
  animation — a delivered emergency is not a celebration.
- Touch feedback: a brief opacity/scale press state is fine. Avoid ripples that imply Material
  default.
- Steppers: minus is **disabled at zero, not hidden**, so the control never changes shape or
  reflows under a thumb. Long-press to repeat is a nice-to-have; tap must always work.
- Copy / Read aloud on the emergency code: clipboard, and TTS reading the code character by
  character ("B-L-G, two zero two six, zero four one seven") for radio use.
- **No destructive action without confirmation**, and decline always asks for a reason.

## State model

Per report: `id` · `code` (`BLG-YYYY-NNNN`) · `type` · `description` · `voiceNote?` ·
`affected {total, children, elderly, mobilityLimited}` · `lifeThreatening` ·
`location {lat, lng, accuracyM, purok, pinAdjusted}` · `createdAt` · `priority` ·
`priorityReasons[]` · `deliveryState` · `hops[] {peerId, at}` · `assignment? {responder, status,
timestamps}`.

`deliveryState` enum — **the type that carries the product's core promise**:
`SAVED_LOCAL | RELAYED | DELIVERED | ASSIGNED | EN_ROUTE | ON_SITE | RESOLVED`. Give it one
formatter that returns chip color, icon, label, and the plain-language sentence together, so no
screen can accidentally render a partial or optimistic version.

Device/mesh state: `connectivity` enum (`ONLINE | OFFLINE | SYNCING | PENDING | RELAYED | SYNCED`)
· `nearbyPeers[] {id, linkQuality, hopsFromSignal, lastSeen}` · `carryingForOthers` ·
`relayedTodayCount` · `deliveredBecauseOfYouCount` · `pendingUploadCount`.

Persistence: local-first (SQLite/Room or equivalent), encrypted at rest, survives force-quit and
battery death. Sync is an opportunistic background job, never a user-visible request. Peer ids are
rotating pseudonyms regenerated daily; never transmit or display resident identity in the mesh
layer.

## Assets

- **Fonts:** Inter (400/500/600/700/800), IBM Plex Mono (400/500/600) — both open-licensed; bundle
  them, do not fetch at runtime (the app must work with no network).
- **Icons:** Material Symbols Outlined in the prototype, by ligature name. Swap for your platform's
  set; preserve the four distinct priority silhouettes (octagon / triangle / square / circle) and
  the state icons (`cloud_done`, `signal_cellular_off`, `progress_activity`, `hourglass_top`, `hub`,
  `check_circle`).
- **Maps:** the prototype's maps are CSS placeholders. Production needs **pre-cached offline tiles**
  for the barangay (bundled at install, refreshed when online) — the map must render with no
  network. The accuracy ring must be drawn from the real reported accuracy at true scale.
- No illustrations, no photography, no decorative imagery. There is none in the design and none
  should be added.

## Tone

Calm, competent, institutional — a public safety tool, not a consumer app. Trustworthy rather than
flashy. No playful illustration, no gradients for decoration, no marketing polish. Avoid the generic
Material default look, avoid dashboard-style data density on resident screens, avoid tiny text.
Every pixel should feel like something a barangay official would stake their reputation on.

**The prototype disclaimer on artboard 01 is required and must survive into the build:** this is a
capstone prototype and does not replace official emergency services.

## Files

- `BULIG Mobile UI.dc.html` — all 12 artboards + the component sheet. Open directly in a browser;
  pan and zoom the canvas. The three intro cards at the top state the delivery-state rule.
- `support.js` — runtime for the reference file only. Not part of the design; do not port.

## Component inventory

Build these once, use everywhere:

1. `ConnectivityBanner(state, count)` — six states, persistent, never collapsible.
2. `PriorityChip(level)` — filled; color + label + distinct icon shape.
3. `DeliveryChip(state, hopCount?)` — bordered + tinted; grey / blue / green per the rule.
4. `StatusChip(status)` — responder lifecycle (assigned, en route, on site, resolved).
5. `Stepper(label, labelWaray, value, onChange)` — 52dp targets, minus disabled at zero.
6. `PrimaryButton` (60dp) · `EmergencyButton` (212dp) · `SecondaryButton` (52dp) ·
   `DestructiveButton` · `ResponderActionButton` (64dp) · disabled variant.
7. `ReportListRow` (resident: delivery-led) and `AssignmentListRow` (responder: priority-led) —
   both with the 5px priority left rule.
8. `BilingualLabel(en, war)` — the English-over-Waray pairing used across tiles, steppers, and
   headers.
9. `ReasonList(reasons, color)` — the "why this priority" rows.
10. `DeliveryTimeline(steps)` — solid / ringed / dashed-hollow markers with "Not yet" labels.
11. `MapPreview(center, accuracyM, mode: 'self' | 'incident')` — offline tiles + true-scale
    accuracy ring + adjust-pin affordance.
12. `SectionLabel(text)` — the uppercase eyebrow + hairline rule.
