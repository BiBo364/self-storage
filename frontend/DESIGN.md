---
name: Kho Mộc Frontend
description: A concrete-and-signal interface for finding, renting, and operating self-storage.
colors:
  concrete: "#E9EAE6"
  concrete-deep: "#DFE1DC"
  surface: "#FFFFFF"
  surface-muted: "#F3F4F1"
  ink: "#101413"
  ink-2: "#333B38"
  steel: "#5A625F"
  rule: "#C9CCC6"
  rule-strong: "#AEB3AC"
  signal: "#F2C300"
  signal-strong: "#D9AE00"
  signal-ink: "#101413"
  link: "#1F5A66"
  success: "#2F6B4F"
  success-soft: "#E4ECE5"
  warning: "#7A5A00"
  warning-soft: "#F7E9B0"
  danger: "#B3261E"
  danger-soft: "#F7E3E0"
  info: "#1F5A66"
  info-soft: "#E3EDEE"
  sidebar: "#101413"
  bar-dark: "#070A07"
  concrete-dark: "#0E1211"
  surface-dark: "#1A201E"
  ink-dark: "#EEF0EC"
  steel-dark: "#9AA19C"
  rule-dark: "#2B3330"
  signal-dark: "#FFD400"
  link-dark: "#8FC4CC"
typography:
  display:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "clamp(2.75rem, 6.4vw, 4.875rem)"
    fontWeight: 700
    lineHeight: 0.95
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.8125rem"
    fontWeight: 700
    lineHeight: 1.05
    letterSpacing: "-0.01em"
  section:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.125rem"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "0"
  lead:
    fontFamily: "Source Sans 3, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1rem"
    fontWeight: 400
    lineHeight: 1.55
  body:
    fontFamily: "Source Sans 3, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.9375rem"
    fontWeight: 400
    lineHeight: 1.55
  small:
    fontFamily: "Source Sans 3, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.8125rem"
    fontWeight: 400
    lineHeight: 1.45
  button:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "0.01em"
  label:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.75rem"
    fontWeight: 600
    lineHeight: 1.4
    letterSpacing: "0.01em"
  data:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "0.875rem"
    fontWeight: 500
    lineHeight: 1.4
    fontFeature: "tnum"
  stat:
    fontFamily: "Saira Semi Condensed, ui-sans-serif, system-ui, sans-serif"
    fontSize: "1.375rem"
    fontWeight: 500
    lineHeight: 1.2
    fontFeature: "tnum"
rounded:
  none: "0px"
  field: "2px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "16px"
  lg: "24px"
  xl: "32px"
  2xl: "48px"
  3xl: "64px"
components:
  button-primary:
    backgroundColor: "{colors.signal}"
    textColor: "{colors.signal-ink}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "12px 16px"
    height: "42px"
  button-primary-hover:
    backgroundColor: "{colors.signal-strong}"
    textColor: "{colors.signal-ink}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "12px 16px"
    height: "42px"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "12px 16px"
    height: "42px"
  button-small:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "0 12px"
    height: "32px"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.small}"
    rounded: "{rounded.field}"
    padding: "0 12px"
    height: "42px"
  tag:
    backgroundColor: "{colors.surface-muted}"
    textColor: "{colors.steel}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "4px 8px"
  list-item:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.none}"
    padding: "16px"
  nav-active:
    backgroundColor: "{colors.signal}"
    textColor: "{colors.signal-ink}"
    typography: "{typography.button}"
    rounded: "{rounded.none}"
    padding: "0 10px"
    height: "37px"
  table-header:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.concrete}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "10px 14px"
---

<!-- STATUS: Target direction approved. The React app uses the Bãi kho token set. -->

# Design System: Kho Mộc Frontend

## Overview

**Creative North Star: "The Yard Board"**

Kho Mộc looks like a working storage yard, not a design studio. The surface is cool concrete, the type is signage, and the only saturated colour is the yellow used to mark a bay, a lane, or the next action. The interface should feel measured and physical: a visitor can read availability, price, status and the next step without decoding a visual metaphor.

The system is light-first. Daytime browsing, comparing and queue work happen on concrete and white surfaces; the dark sidebar anchors the brand without turning the whole product into a dashboard. Dark theme is a full token swap, not a filter: it keeps the same yellow signal, but container and control borders become translucent light instead of pure white.

The core layout rule is structural: **a list is a stack of blocks, not a stack of rules**. Independent items get a surface, 16px padding and an 8px gap. Horizontal rules belong to data tables, where cells must align. Section separation comes from surface tone and spacing, not from a divider under every heading.

**Key Characteristics:**

- Light concrete ground, white item surfaces, near-black ink.
- One saturated signal: hi-vis yellow (`#F2C300`), used for action and active state only.
- Saira Semi Condensed for display and interface labels; Source Sans 3 for reading text.
- 0px corners on buttons, tags, panels and list items; form fields use a 2px radius.
- Block lists with gaps; ruled tables only where cells must line up.
- Flat surfaces; depth is tonal, not shadowed.
- Vietnamese-first copy with real diacritics; no tiny tracked-out labels.

## Colors

The palette is cool concrete and steel with one hi-vis marking colour. It is deliberately not warm cream, beige or terracotta.

### Primary

- **Signal Yellow** (`#F2C300`): the primary action, active navigation, focus halo and small attention markers. It is the only saturated colour in the system.
- **Signal Strong** (`#D9AE00`): hover/pressed state for the primary action. Never a background for a whole section.
- **Signal Ink** (`#101413`): text on signal yellow. Yellow never carries light text.

### Secondary

- **Steel Teal** (`#1F5A66`): links, secondary emphasis, selected labels and informational focus. It replaces the old lime/teal accent as the "read more" colour, not the action colour.

### Neutral

- **Concrete** (`#E9EAE6`): page ground.
- **Surface** (`#FFFFFF`): list items, panels, table rows.
- **Surface Muted** (`#F3F4F1`): nested panels, tasks, empty states.
- **Ink** (`#101413`): primary text, sidebar, table headers, CTA band.
- **Steel** (`#5A625F`): secondary text, metadata, table labels.
- **Rule** (`#C9CCC6`): the only divider colour, and only inside data tables.

### Status

- **Success** (`#2F6B4F` on `#E4ECE5`): active, approved, available.
- **Warning** (`#7A5A00` on `#F7E9B0`): pending, expiring, needs attention.
- **Danger** (`#B3261E` on `#F7E3E0`): overdue, rejected, destructive.
- **Info** (`#1F5A66` on `#E3EDEE`): neutral system messages.

### Dark Theme

Dark is an alternate theme, not the default. Concrete becomes `#0E1211`, surface `#1A201E`, ink `#EEF0EC`, steel `#9AA19C`, rule `#2B3330`, signal `#FFD400`, link `#8FC4CC`. Container and control borders use translucent light (`rgba(238,240,236,.22–.28)`); they never become solid white.

### Named Rules

**The One Signal Rule.** Signal yellow covers at most about 10% of a screen. It marks the primary action, the active navigation item, the focus halo and a small attention marker. It is never a page or section background.

**The No Cream Rule.** No warm cream, beige, terracotta or rusty-orange accent. Concrete is a cool grey; the warmth of the brand comes from the name and the physical warehouse, not the surface tint.

**The Muted Border Rule.** In dark theme, container and control borders are translucent light, never `#FFFFFF`. A token that flips `--ink` to light must not flip every border to white.

## Typography

**Display Font:** Saira Semi Condensed (with system sans fallback)<br />
**Body Font:** Source Sans 3 (with system sans fallback)<br />
**Data Font:** Saira Semi Condensed with tabular figures; monospace is allowed only inline for code paths.

**Character:** Saira's condensed signage forms make headings and controls read like wayfinding. Source Sans 3 carries Vietnamese paragraphs at 15px without the tight, tracked-out rhythm that made the previous system feel machine-set.

### Hierarchy

- **Display** (700, `clamp(2.75rem, 6.4vw, 4.875rem)`, `0.95`, `-0.02em`): one landing statement per viewport.
- **Title** (700, `1.8125rem`, `1.05`, `-0.01em`): page titles in the app shell and section titles on public pages.
- **Section** (600, `1.125rem`, `1.25`): panel headings, list group headings, card titles.
- **Lead** (400, `1rem`, `1.55`): landing lede and short intros.
- **Body** (400, `0.9375rem`, `1.55`): explanations and customer-facing copy; keep prose at 55–70ch.
- **Small** (400, `0.8125rem`, `1.45`): table body, metadata and secondary copy.
- **Button** (600, `0.875rem`, `1.2`): buttons and tab labels.
- **Label** (600, `0.75rem`, `1.4`): table headers, field labels and metadata. Sentence case; no wide tracking.
- **Data** (500, `0.875rem`, `1.4`, tabular): prices, codes, dates, counts.
- **Stat** (500, `1.375rem`, `1.2`, tabular): stat strip and price emphasis.

### Named Rules

**The No Mono Decoration Rule.** There are no monospace labels or 10px tracked-out uppercase kickers. Numbers use tabular figures in Saira; only actual code paths may use a system monospace inline.

**The No Kicker Rule.** A section title stands on its own. Do not stack a small uppercase label, a slogan and a subtitle above one heading.

## Layout

The public page uses a 1200px content container with 24px desktop gutters and 16px mobile gutters (the app keeps `PAGE_CONTAINER`). The application shell uses a 236–268px ink sidebar, a 64px topbar and a content column capped at 1320px. The first viewport of the public landing is a two-column composition: a short statement on the left, a finder block on the right, then a full-width facility block list.

Lists are stacks of blocks: a surface, 16px padding and an 8px gap between items. A list item never uses a bottom border. Tables are the exception: a header block and row blocks keep columns aligned, and 1px rules are allowed only when a dense table genuinely needs them. Sections are separated by surface tone and vertical spacing rather than a rule under every heading.

The app shell keeps the same information architecture as the current product: public header, customer sidebar, FM sidebar and shared topbar. Customer labels stay task-oriented; operations pages may run denser. One primary action per page.

Responsive behaviour is structural: at 1100px the sidebar narrows, at 900px it becomes an off-canvas panel and tables scroll horizontally, at 760px public compositions stack and the finder becomes full width.

**The Block List Rule.** A horizontal rule never separates independent list items. If items are independent, each gets its own surface and an 8px gap; rules belong to aligned table cells.

## Elevation & Depth

The system is flat by default. Cards, list items, panels and table rows carry no shadow. Depth comes from three surface tones (concrete, surface, surface-muted) and a 1px or 2px border. The only shadow is an overlay shadow for dialogs and popovers: `0 24px 60px rgba(16,20,19,.18)`.

**The Flat-By-Default Rule.** If a surface needs to read as raised, change its tone or border first. Do not add a diffuse shadow to a card at rest.

## Shapes

Buttons, tags, panels, list items and table rows are square (0px radius). Form fields use a 2px radius so they read as inputs, not buttons. Control borders are 2px; table rules are 1px. Pills are reserved for nothing: buttons, tags, navigation and fields are never pill-shaped. Only avatars and status dots are circular.

The hazard stripe (alternating ink and signal yellow) is a single signature device. Use it once per view, on the finder or a CTA edge; do not turn it into a repeated decorative texture.

**The Square Control Rule.** A rounded rectangle is not the default container. If a block needs distinction, use a surface tone and spacing before reaching for a radius.

## Components

### Buttons

- **Shape:** square (0px radius), 2px border. Default height 42px with 14px text.
- **Small action:** row actions use 32px height and 12px label text; never shrink a primary action to this size.
- **Primary:** signal yellow fill, ink text. Hover uses signal-strong. One primary per decision area.
- **Secondary:** transparent fill, ink border and ink text. Light theme hover inverts to ink fill with concrete text; dark theme hover uses a translucent light wash and a slightly brighter border, never a white border.
- **Ghost:** text only; hover uses signal-soft. **Danger:** danger border and text; hover uses danger-soft.

### Fields

- **Style:** 42px height, 13px text, surface background, 2px radius, 1px rule-strong border.
- **Focus:** signal border plus a 3px signal-soft halo. **Error:** danger border and an inline error message; never colour alone.
- **Labels:** sentence case, above the field, linked with `htmlFor`.

### Tags

- **Style:** solid soft status background, no border, 0px radius, 12px Saira 600, sentence case.
- **Signal:** a 5px dot in the status colour; text always present.

### List Items

- **Style:** surface block, 16px padding, 8px gap between items, no divider.
- **Structure:** index or code, primary label, secondary metadata, trailing action. Codes use 16px Saira 500; metadata uses 12px. Hover changes the block to surface-muted; it does not add a border.

### Tables

- **Style:** ink header block with concrete text; surface row blocks with 6px gaps. Columns align across the header and rows. If a future table needs rules, use 1px rule lines inside the table only.
- **Density:** 12–13px body text, 44px minimum row height. Horizontal scroll below 900px.

### Navigation

- **Public header:** surface bar, no bottom rule; the hero below provides the tonal change.
- **App sidebar:** ink background, concrete text. The active item is a solid signal block with ink text, not a left-rule tab.
- **Topbar:** surface background, no rule; the content uses concrete, so the tonal change separates them.

### Panels and Notices

- **Panels:** surface-muted background, no border, 18px padding. Headings carry an 8px signal square.
- **Empty / loading / error:** keep the block rhythm. Loading uses skeleton rows shaped like the real list; empty states offer one concrete next action; errors sit inline near the failed control. No spinner in the middle of content.
- **Login:** surface card with a 1px container border, signal square beside the heading, one primary action. No coloured top bar.

### Dark Theme Components

The dark theme swaps paper, surface, ink, steel, rule, signal and link tokens. Borders on the finder, login card, size visuals and secondary buttons use `--container-border` / `--control-border`, not `--ink`. The yellow signal remains the same role and never becomes a background for a large region.

## Do's and Don'ts

### Do

- **Do** use signal yellow for the primary action, active navigation and focus attention; keep it rare.
- **Do** make each list item a surface block with 16px padding and an 8px gap.
- **Do** use rules only inside a data table where cells align.
- **Do** use Saira Semi Condensed for display and UI labels, Source Sans 3 for body copy, tabular figures for data.
- **Do** use translucent light borders in dark theme for containers and controls.
- **Do** keep Vietnamese copy, real diacritics, `1.500.000 đ`, `dd/MM/yyyy`, visible focus and three states per screen.
- **Do** let content decide section count; four process blocks are fine, three equal feature cards are not a default.

### Don't

- **Don't** use warm cream, beige, terracotta or rusty-orange accents.
- **Don't** put a bottom border on every list item, row or section.
- **Don't** use 10px tracked-out uppercase labels or monospace as decoration.
- **Don't** use pill buttons, pill tags or large-radius cards.
- **Don't** use gradients, glass, glow, decorative blur or drop shadows on resting cards.
- **Don't** turn dark theme borders into solid white; use the muted border tokens.
- **Don't** add three equal feature cards with icons as a default section shape.
- **Don't** hard-code provisional API contracts, permissions or business rules as settled UI truth.
