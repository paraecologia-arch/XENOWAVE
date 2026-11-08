# XENOWAVE — Orbitron reference

This directory preserves the approved Orbitron file used by the XENOWAVE wordmark.

| Item | Value |
|---|---|
| File | `Orbitron-Medium.ttf` |
| Bytes | 17,800 |
| Family / style | Orbitron / Medium |
| Weight | 500 |
| Variable axis | No |
| SHA-256 | `e1dd2875a4cf1711615b7bc7eb2ae8d05bef3861f87a7fdd847a78199169b458` |
| License | SIL Open Font License 1.1 (`OFL-ORBITRON.txt`) |

## Android integration

- Bundled resource: `app/src/main/res/font/orbitron_medium.ttf` (same bytes and hash).
- License copy: `app/src/main/assets/licenses/OFL-ORBITRON.txt`.
- Compose family: `XenoWaveOrbitron` in `ui/theme/XenoWaveTypography.kt`.
- Wordmark style: `XenoWaveWordmarkStyle`, weight 500, 0.14 em tracking.
- The APK was verified to contain the font resource and the license asset.

## Usage rule

Use Orbitron only where the product name is the mark: the custom startup splash, About, the musical-age result card and the exported card. Do not use it for reading text, menus, controls, diagnostics or long-form copy. The Android launcher draws its own label below the icon; the app does not control that font.
