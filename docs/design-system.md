# ZipHap.Agent design system

## Current theme and token source (2026-11-24)

The current theme is "Sunlit Porcelain": one bright light theme (page #F7F8FC, cards #FFFFFF, text #171B33) with cobalt, coral, mint, sun and lilac role colors. The navy palette below is the historical native palette and is superseded; see `docs/ziphap-design-system.ko.md` for the role-color table.

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
