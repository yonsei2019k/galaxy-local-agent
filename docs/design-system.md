# ZipHap.Agent design system

## Design language 2.0 (2026-11-25)

Surfaces replace lines. The language is a pale grey page with white rounded cards, grouped by spacing rather than borders. All values come from `design/tokens.json` (CSS names shown; Compose names are the PascalCase equivalents in `ZipHapTokens`). Components adopt them in D3 (web) and D6 (Compose); until then the older component rules still apply.

- **Page and surfaces.** Page `--color-bg` #F3F4F8, cards `--color-surface` #FFFFFF, filled inputs and secondary buttons `--color-surface-sunken` #F1F3F7. Group related content in one card (`--radius-card` 20) and separate groups with `--space-section` (24) or 12-16 px. Page side margin is `--gutter` (16).
- **No hard lines.** Do not draw 1px dark borders. A card edge is the soft shadow `--shadow-card`. Inside one card, separate rows only when needed with `--color-divider` #EEF0F4, inset 16 from the sides. `--color-divider` is the only permitted hairline; `--color-line` is legacy and goes away as components migrate.
- **Elevation.** `--shadow-card` for cards, `--shadow-sheet` for bottom sheets (radius `--radius-sheet` 28), `--shadow-float` for floating buttons. Compose receives the same layers as `ZipHapTokens.ShadowCard/ShadowSheet/ShadowFloat` (`ZipShadow` offset, blur, ARGB color).
- **Text colors.** `--color-text` for primary, `--color-text-sub` #5B6178 for secondary (5.58:1 on #F3F4F8, 6.13:1 on #FFFFFF, 5.52:1 on #F1F3F7, AA). `--color-text-faint` #676D85 is for placeholders and disabled text (4.61:1 on #F1F3F7, 4.66:1 on #F3F4F8, 5.12:1 on #FFFFFF, AA; still keep information the user must read in `--color-text-sub` or darker). Role colors (cobalt, coral, mint, sun, lilac) keep their `-text` (AA text) and `-soft` (tile and chip background) variants.
- **Typography.** Families `--font-ui` (SUIT Variable stack) and `--font-display` (Jua, large greeting and empty-state titles only). Both are placeholders until D4 bundles the fonts; the stacks fall back to system fonts. Sizes: caption 12, small 13, body 15, subtitle 17, title 20, screen 24, hero 28. Weights regular 400, medium 500, bold 700. Line height `--lh-body` 1.6 for paragraphs and `--lh-tight` 1.3 for headings, with `--ls-tight` (-0.01em) on Korean headings.
- **Titles.** Screen title is 24/700 (`--fs-screen`, `--fw-bold`). Section title is small grey bold, 13/700 in `--color-text-sub`, placed above its card.
- **List rows.** Height at least `--row-min-height` 56, an icon tile on the left (`--icon-tile` 40, radius `--radius-tile` 12, role `-soft` background), a title with optional sub line, and a chevron on the right.
- **Buttons.** Primary: cobalt fill, white text, height `--control-height` 52, radius `--radius-control` 16. Secondary: `--color-surface-sunken` fill, no border. Every interactive target is at least `--touch-min` 44.
- **Inputs.** Sunken fill, no border, radius `--radius-control`; focus draws a 2px cobalt ring.
- **Chips.** Radius `--radius-chip` 12 or `--radius-pill`, `-soft` background with the matching `-text` color.
- **Icons and illustration.** Duotone icons: a pale color fill plus a rounded ink stroke of 1.8 with round caps. Large scenes (empty states, onboarding, connections) use spot illustrations in the same palette. Both come from `design/icons.json` (D2). No emoji characters in UI text.
- **Motion.** `--motion-*` and `--ease-*` tokens; the motion rules (sheet close delay, reduced motion) are implemented in D5.
- **Contrast of the new page color.** `--color-muted` #5E6480 measures 5.29:1 on #F3F4F8 and the `-text` role colors measure 4.72 to 5.97:1, so existing muted and role text still passes AA on the new page background.

Token source: `design/tokens.json`. `python scripts/gen-design-tokens.py` regenerates `web-ui/src/tokens.css` and `app/src/main/java/com/user/localagent/ui/ZipHapTokens.kt`; `--check` fails when they are stale, and unknown token groups are rejected. Do not edit generated files by hand.

## Previous theme notes (2026-11-24)

The current theme is "Sunlit Porcelain": one bright light theme (page #F7F8FC (now #F3F4F8, see 2.0 above), cards #FFFFFF, text #171B33) with cobalt, coral, mint, sun and lilac role colors. The navy palette below is the historical native palette and is superseded; see `docs/ziphap-design-system.ko.md` for the role-color table.

Design tokens (colors, spacing, font sizes, radii) have one source: `design/tokens.json`. Run `python scripts/gen-design-tokens.py` to regenerate `web-ui/src/tokens.css` (CSS variables such as `--color-cobalt`, `--space-1`, `--fs-body`, `--radius-card`) and `app/src/main/java/com/user/localagent/ui/ZipHapTokens.kt`; `--check` fails when the committed outputs are stale. Do not edit the generated files by hand. `styles.css` keeps its older alias variables pointing at the new tokens; Compose screens still use `ZipHapColors` until they adopt `ZipHapTokens`.

## Direction (historical)

ZipHap.Agent is a calm, private, on-device assistant. The interface uses a deep navy foundation, mint for the primary action and active state, and periwinkle for supporting highlights. Keep screens quiet and readable; show the next useful action without dashboard clutter.

## Color tokens

| Token | Hex | Use |
| --- | --- | --- |
| Deep navy | `#071522` | App background and foreground text on mint |
| Navy surface | `#102235` | Cards and grouped content |
| Elevated navy | `#193249` | Secondary surfaces, tracks, and chips |
| Mint | `#79E8C5` | Primary actions, active state, progress |
| Periwinkle | `#BFC7FF` | Secondary emphasis and supporting icons |
| Primary text | `#F3F7FC` | Headlines and body copy |
| Secondary text | `#C2D0DE` | Supporting copy and metadata |
| Error | `#FFB4AB` | Errors and destructive warnings |

Use `ZipHapTheme` and `ZipHapColors` from `com.user.localagent.ui` instead of defining screen-local palettes. Keep controls at Material 3 defaults unless a clear screen need calls for a change; preserve readable focus, disabled, and error states.

## Components

- `ZipHapHeader(title, status, busy)` establishes the brand, current screen, and a short status. Keep status text actionable and concise.
- `ZipHapCard` groups related content with a 22 dp radius and 18 dp internal spacing.
- `ZipHapChatBubble(text, fromUser)` distinguishes the user from the assistant with shape and color as well as alignment.
- `ZipHapEmptyState(icon, title, description, action)` explains what is missing and can provide one next step.

All components accept a `Modifier`. Use the default Android system sans font stack so Korean text uses the device's native fallback. No bundled font or network font is required.

## Motion and accessibility

Use motion only to show state changes. `ZipHapHeader` uses the Material progress indicator while busy; avoid decorative looping motion. Compose animation APIs honor the system animator duration scale. Keep status meaning in text rather than color alone, provide meaningful labels for interactive icons, and retain comfortable touch targets and body-sized Korean copy.

## Launcher mark

The vector mark combines a mint folded Z ribbon with a periwinkle H detail on navy. `ic_launcher.xml` and `ic_launcher_round.xml` provide Android 13+ adaptive icon layers; the package manifest can reference both when integrating the rebrand.
