# XENOWAVE Brand Guidelines

## Status

This document is the canonical brand specification for XENOWAVE. The identity decisions recorded here are frozen and may not be reinterpretated, redesigned, or replaced during implementation.

## Official identity

- **Name:** XENOWAVE
- **Concept:** extraterrestrial technology, music, waves, signal, mystery, and space
- **Official symbol:** a stylized alien head wearing headphones
- **Central element:** a waveform/signal on the alien's forehead
- **Lighting:** controlled blue and cyan glow
- **Primary background:** very dark spatial blue
- **Visual language:** futuristic, technological, spatial, premium, and clean
- **Forbidden character:** childish, caricature-like, generic, or excessively gamer-like treatments

The official logo and alien symbol source is the Master Visual Reference described below. It must be used exactly as supplied. On 2026-11-06, the brand owner confirmed this artwork as official and instructed removal of the previous composite reference panel. That panel is retired and must not guide new work or replace the implemented interface.

## Master Visual Reference

- Path: `docs/brand/references/xenowave_launcher_master.png`
- Format: PNG
- Dimensions: 1254 x 1254
- SHA-256: `9a53596d285ebc048b945eee1559bdcad198860686e3c4211cf3206f0f91dc7b`

The file must not be modified, overwritten, redrawn, reinterpreted, vectorized, color-graded, cropped, or used to generate another visual reference. Derivative launcher, monochrome, splash, and store assets require explicit approval after the master reference has been examined.

`docs/brand/assets/` is reserved for approved brand assets. No graphical placeholder may be created there.

On 2026-11-06, the brand owner explicitly approved the youthful, mature and veteran ET derivatives and the portrait/square musical-age card compositions in `../../../IDADE-MUSICAL-XENOWAVE-2026-11-06/ARTES-ETAPA-5-PREVIAS/`. Reuse those v1 assets as the visual baseline for phase 5, after phase 4 security review. This approval permits those separate character derivatives for the musical-age presentation/cards; the official master remains preserved. The historical PREVIAS folder name does not mean visual approval is still pending. Example ages are illustrative, and this is not publication authorization or approval of an unimplemented animation.

## Canonical palette

| Role | Token | Value |
|---|---|---|
| Spatial background | `xenowave_space_background` | `#0B1020` |
| Primary blue | `xenowave_primary_blue` | `#2563EB` |
| Luminous cyan | `xenowave_luminous_cyan` | `#00D4FF` |
| Support purple | `xenowave_support_purple` | `#8B5CF6` |
| Secondary surface | `xenowave_secondary_surface` | `#1E293B` |
| Primary light text | `xenowave_primary_text` | `#E2E8F0` |

The raw hexadecimal values are centralized in `app/src/main/res/values/xenowave_colors.xml`. Compose screens must consume semantic brand tokens or Material 3 roles rather than duplicating these values.

### Semantic roles

- `background` and `surface` provide the app canvas.
- `surfaceContainer`, `surfaceContainerHigh`, and `surfaceContainerHighest` provide elevation without neon excess.
- `textPrimary`, `textSecondary`, and `textDisabled` preserve hierarchy.
- `primary` is the principal blue interaction color.
- `signal` is the controlled cyan energy/highlight color.
- `support` is the limited purple support color.
- `selectedContainer` and `onSelectedContainer` identify selection.
- `focusBorder` must remain clearly visible.
- `border` is subtle and must not compete with content.
- `signalGradient` is reserved for controlled emphasis, not whole-screen neon treatment.
- `glow` is a low-opacity spatial effect and must never reduce readability.

## Color behavior

- Dark mode uses `#0B1020` as its base.
- Dark mode uses `#1E293B` for the high surface container and `#E2E8F0` for primary text.
- Light mode derives accessible surfaces from the same palette while retaining XENOWAVE hierarchy.
- Blue is the primary interaction color.
- Cyan represents signal and controlled emphasis.
- Purple is support only and must not compete with blue or cyan.
- The default experience is XENOWAVE. Wallpaper-driven Material You coloring and media artwork color extraction are optional user preferences, not default behavior.
- `pureBlack` remains an explicit accessibility/user preference; it is not the default XENOWAVE dark background.
- Interactive text and essential icons must meet applicable Material accessibility contrast guidance.
- Focus and selection states may not rely on color alone.

## Typography

The approved families are:

- **Orbitron:** titles, brand communication, and emphasis elements.
- **Inter:** interface text, controls, and functional reading.

On 2026-11-06, the brand owner approved Orbitron as shown in the XENOWAVE name preview (weight 500, letter spacing 0.14 em). Apply this decision to the brand name wherever used in the app, icon artwork and sharing card during phase 5, after the mandatory phase 4 security review. The approved alien master must not be redrawn. This supersedes the earlier prohibition on composing the name in Orbitron.

Technical font integration remains pending until phase 5; keep the current functional Material typography during phase 3. Check the actual font file and license before bundling. Do not replace interface reading/control typography indiscriminately. The Android launcher controls the font of its own label below the icon; distinguish that system label from lettering in icon artwork.

### Phase 5 typography implementation (2026-11-07)

- Orbitron Medium (weight 500, no variable axis) is bundled at `app/src/main/res/font/orbitron_medium.ttf`. The approved file is 17,800 bytes, SHA-256 `e1dd2875a4cf1711615b7bc7eb2ae8d05bef3861f87a7fdd847a78199169b458`.
- The OFL text is kept at `app/src/main/assets/licenses/OFL-ORBITRON.txt` and `docs/brand/fonts/OFL-ORBITRON.txt`. Provenance is recorded in `docs/brand/fonts/README.md`.
- The About screen shows the attribution “Orbitron Medium · The Orbitron Project Authors · SIL Open Font License 1.1”.
- The wordmark uses `XenoWaveWordmarkStyle`: Orbitron Medium, weight 500, 0.14 em tracking. It is applied to the custom startup splash, the About screen, the musical-age result card and the exported PNG card.
- Functional reading text, menus, controls and diagnostics keep the existing Material typography. Orbitron is not applied to those surfaces.
- The launcher artwork remains the official ET-only mark and contains no drawn product name. Android draws the label `XENOWAVE` below the icon with its own launcher font; the app cannot change that label font. The API 31+ system splash now uses the official ET foreground on `#0B1020`; it does not attempt to add a wordmark inside the masked splash icon.
- The approved master remains unchanged at `docs/brand/references/xenowave_launcher_master.png` (SHA-256 `9a53596d285ebc048b945eee1559bdcad198860686e3c4211cf3206f0f91dc7b`).

## Verbal identity

- Primary slogan: `Signal received.`
- Signature: `Explore • Listen • Go further`
- Approved Portuguese message: `Música sem fronteiras • Além do conhecido • Sempre com você`
- Secondary atmospheric phrase: `Some songs come from further away.`

## Shape and spacing

- XENOWAVE uses calm, modern Material 3 radii through the centralized shape tokens.
- Brand spacing is expressed through the centralized metric tokens.
- Sharp science-fiction effects, excessive chrome, and decorative gradients are not permitted.

## Logo and icon rules

### Permitted

- Use only official files supplied by the brand owner.
- Preserve official geometry, colors, composition, and glow.
- Use the existing launcher temporarily while official derivatives are pending.
- Display the text `XENOWAVE` where a textual identity is required.

### Forbidden

- Recreating or approximating the alien-head logo.
- Generating icons with AI.
- Converting the raster master into an approximate vector drawable.
- Automatically removing its background.
- Rebuilding highlights, lines, headphones, or waveform.
- Using generic alien, music, headset, space, or signal icons as a substitute mark.
- Applying the complete master panel directly as a launcher icon.

### Pending assets

Derivative definitions and exact target files will be decided only after examination and explicit approval of the master reference. Pending asset families include adaptive launcher foreground, monochrome/themed icon, splash artwork, Play Store icon, and Fastlane icon.

Approved visual variations, still pending isolated official files:

- Primary logo on dark background.
- Light-background version.
- Monochrome version.
- Isolated symbol.

## Splash and launcher

- No definitive XENOWAVE launcher or splash asset may be generated before approval.
- The launcher label is `XENOWAVE` in every build type, including debug and userdebug.
- Splash treatment must preserve contrast, avoid flashing, and use only official artwork.
- System bars retain legible icons and must follow the selected light/dark mode.

## Application references

### Definitive visible identity

- App name, launcher label, About title, OOBE welcome/complete messages, visible user-facing messages, and institutional brand presentation use `XENOWAVE`.

### Pending official destination

- External repository paths that still contain the upstream project name may remain until the GitHub repository is renamed. They must not be presented as XENOWAVE-owned channels that do not exist yet.

### Technical compatibility references that remain

- Application ID `com.dd3boh.xenowave` (debug: `com.dd3boh.xenowave.debug`)
- Kotlin packages under `com.dd3boh.xenowave`
- Existing actions, preference keys, routes, database entities, persisted identifiers, backup identifiers, and technical component names
- Historical attribution and licensing references

Blind global replacement of `OuterTune` remains forbidden for licenses, copyright headers, contributor attribution, external dependency names and historical references.

## New screen checklist

1. Use semantic XENOWAVE or Material 3 tokens; do not add local hexadecimal values.
2. Start from the correct light/dark background and surface hierarchy.
3. Reserve cyan for signal, selection, focus, or a single controlled emphasis.
4. Use purple only as restrained support.
5. Preserve text contrast and non-color state indicators.
6. Orbitron is approved for the XENOWAVE name; implementation is scheduled for phase 5. Inter remains registered for interface typography, with integration pending. Continue using the current Material implementation during phase 3.
7. Do not invent logo artwork or substitute an icon.
8. Keep streaming, download, library, login, navigation, and persistence behavior unchanged.

## Non-reinterpretation rule

XENOWAVE and its official symbol are frozen. Any change to the name, symbol, construction, palette roles, logo treatment, or definitive asset family requires a new explicit brand decision before implementation.
