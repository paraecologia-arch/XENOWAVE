# XENOWAVE Implementation Inventory

## Current official artwork (2026-11-06)

The brand owner confirmed `docs/brand/references/xenowave_launcher_master.png` as the official logo and alien symbol. The previous composite reference panel was retired and removed at the owner's request. Existing app artwork is preserved. Earlier entries below describe the historical implementation and do not reinstate that panel.

## Protected baseline

- Branch: `feat/xenowave-brand`
- Base commit: `cf602708dbaeab2b7c87fde2217bae05f8c263da`
- The hash supplied as `cf602708dbaeab2b7c87fde221074988d4d9e03a3` did not match HEAD. Per approval, implementation used the audited HEAD hash recorded above.
- `Codex_GLM.bat` remains untracked and is excluded only through `.git/info/exclude`.
- Project `.gitignore` was not modified.

## Baseline validation

The commands below were run before implementation. Each used the local JDK 17 at `C:\Users\rafael.de.souza\Documents\Codex\tools\jdk\jdk-17.0.20.1+1` because `JAVA_HOME` was not configured globally.

| Command | Result | Duration | Pre-existing issue |
|---|---|---:|---|
| `.:app:assembleCoreDebug` | Failed during Gradle configuration | 69 s | Dependencies could not be downloaded from Google/Maven due SSL `certificate_unknown` / PKIX path building failure |
| `.:app:testCoreDebugUnitTest` | Failed during Gradle configuration | 6 s | Same dependency/TLS failure |
| `.:app:lintCoreDebug` | Failed during Gradle configuration | 12 s | Same dependency/TLS failure |

The initial invocation without a configured JDK also failed in 0 s with `JAVA_HOME is not set`. The failures did not reach Android compilation, so they were not caused by uninitialized `ffMetadataEx` or `taglib` submodules. No submodule update was performed and no submodule pointer was changed.

## Implemented

- Canonical brand documentation.
- Official Master Visual Reference verification and registration.
- Centralized XML palette tokens, including secondary surface and primary light text.
- Centralized Compose semantic tokens, metrics, and shapes.
- XENOWAVE Material 3 light/dark schemes.
- XENOWAVE as the default app theme.
- Dynamic wallpaper/artwork coloring disabled by default and retained as an optional user setting.
- Visible app name and debug launcher label.
- Careful visible string migration, preserving technical references and old URLs where required.
- Fastlane store-description brand names.
- Removal of the old OuterTune logo from the About and OOBE screens; only the approved text name is shown while assets are pending.
- Approved typography and verbal-identity decisions registered; font embedding remains pending.

## Pending official assets

- Isolated official files for the approved dark-background, light-background, monochrome, and isolated-symbol variations. Until those isolated files are released, the launcher foreground, monochrome layer and splash icon are scaled derivatives of the approved `xenowave_launcher_master.png` (see below).
- Orbitron integration for the XENOWAVE name was approved on 2026-11-06 and implemented in phase 5 on 2026-11-07 (weight 500, 0.14 em tracking). See the phase 5 section below. The launcher label remains system-controlled; no wordmark is drawn inside the launcher artwork. Inter interface-font integration remains pending.
- Faithful vector conversion of the functional icons whose current master still aliases a different approved glyph (list under "Held" below).
- Fastlane store screenshots and README captures of the current XENOWAVE interface. Previous captures of the inherited interface were removed from the active documentation and store listing and must be recaptured on a physical device.

## Icon system, launcher and splash (continuation session)

This section records the work completed after the interrupted icon-reconstruction session.

### Delivered

- Functional icon system: 129 VectorDrawable resources at `app/src/main/res/drawable/xeno_icon_*.xml`, mirrored by 129 SVG masters at `docs/brand/iconography/svg/`, and registered in `docs/brand/iconography/manifest.json`.
- Runtime layer: `XenoIcon` (`ui/icons/XenoIcon.kt`) resolves tint, selected/accent state, cyan signal glow, RTL mirroring, 24 dp visual bounds and 48 dp minimum interactive bounds; the icon groups live in `XenoActionIcons`, `XenoLibraryIcons`, `XenoNavigationIcons`, `XenoPlayerIcons`, `XenoSystemIcons` and `XenoIcons.AutoMirrored`.
- Material Icons are gone from the visible UI: no `androidx.compose.material.icons` import and no `ImageVector` remains in `app/src/main/java`, and every icon parameter in the app's own components is now a `@DrawableRes Int`.
- Legacy non-Compose drawables used by notifications, shortcuts and widgets (`play`, `pause`, `small_icon`, `shortcut_*`, `repeat_*`, `shuffle_*`, `skip_*`, `placeholder_icon`, ...) were migrated to the same XENOWAVE structure and no longer carry the OuterTune red `#ED5564`.
- Adaptive launcher icon: `mipmap-anydpi-v26/xeno_launcher.xml` and `xeno_launcher_round.xml` combine `@color/xenowave_space_background`, `@mipmap/xeno_launcher_foreground` and `@mipmap/xeno_launcher_monochrome`. The manifest declares `@mipmap/xeno_launcher` and `@mipmap/xeno_launcher_round`.
- Pre-API-26 launcher icons: density-specific `mipmap-{m,h,xh,xxh,xxxh}dpi/xeno_launcher.png` (rounded square) and `xeno_launcher_round.png` (circle) on `#0B1020`. This also removes the previous state in which `@mipmap/ic_launcher` existed only for `anydpi-v26` while `minSdk` is 24.
- Themed icon: `xeno_launcher_monochrome.png` per density, an alpha-only mark derived from the bright structure of the official master so the system tint stays legible.
- Splash (API 31+): `drawable-{m,h,xh,xxh,xxxh}dpi/xenowave_splash_icon.png` on a 288 dp canvas with the artwork inside the central 192 dp, referenced by `Theme.XenoWave.Splash` as `android:windowSplashScreenAnimatedIcon`.
- Store assets: `app/src/main/ic_launcher-playstore.png` and `fastlane/metadata/android/en-US/images/icon.png` are 512 x 512 composites of the official artwork on the official `#0B1020` background.

### Derivative provenance

Every launcher, monochrome and splash raster is produced from the approved `xenowave_launcher_master.png` only by cropping to the artwork bounds, scaling the artwork to the required safe zone (66/108 for the adaptive foreground and monochrome layer, 192/288 for the splash), compositing on `#0B1020` where a filled surface is required, and lossless palette quantisation. No shape, glyph or logo was redrawn, and no new artwork was authored. At the time of that implementation, the SHA-256 values of all seven reference boards matched the manifest. On 2026-11-06, the retired composite panel entry was removed; the manifest retains the official launcher master and five functional icon boards.

### Repaired

The interrupted session's Kotlin generator emitted the replacement call without its opening parenthesis, once per converted call site. The working tree therefore contained 82 files with unbalanced parentheses and could not have compiled.

- 285 call sites across 81 files had the dropped `(` restored, and one call whose first argument had been glued to the function name was rebuilt in full.
- Result: parenthesis, brace and bracket balance is now exact in every file under `app/src/main/java`.
- 288 `XenoIcon` call sites were verified against the composable's signature: only declared parameters are passed, every call supplies `contentDescription`, and no legacy `imageVector` / `painter` / `contentScale` / `colorFilter` argument survives.
- Every `Xeno*Icons` member reference resolves to a declared member; every `R.drawable` reference (149 distinct) resolves to an existing resource; every XML file under `app/src/main/res` parses.

### Converted from the approved boards in this pass

The following masters aliased a different approved glyph and were re-derived from the board that actually defines them:

| Master | Board source |
|---|---|
| `add_circle_outline` | `xenowave_icons_02_navigation.png`, row 4 col 1 (circle plus) |
| `remove_circle_outline` | `xenowave_icons_02_navigation.png`, row 3 col 5 (circle minus) |
| `indeterminate_check_box` | `xenowave_icons_03_library_files.png`, row 3 col 5 (frame with dash) |
| `content_cut` | `xenowave_icons_03_library_files.png`, row 4 col 3 (scissors) |
| `edit_off` | `xenowave_icons_03_library_files.png`, row 4 col 2 (pencil with slash) |
| `playlist_add`, `library_add` | `xenowave_icons_03_library_files.png`, row 3 col 1 (list with circle plus) |
| `playlist_remove` | `xenowave_icons_03_library_files.png`, row 3 col 2 (list with circle minus) |
| `library_add_check` | `xenowave_icons_03_library_files.png`, row 2 col 5 (spines with check badge) |
| `queue_music` | `xenowave_icons_03_library_files.png`, row 5 col 4 (list with music note) |
| `volume_up` | `xenowave_icons_05_player_interactions.png`, row 2 col 1 (speaker with waves) |
| `confirmation_number` | `xenowave_icons_05_player_interactions.png`, row 5 col 1 (ticket) |

### Held semantic aliases (14)

These 14 requested symbols temporarily use existing approved XENOWAVE geometry. They were left untouched on purpose: a faithful conversion needs the isolated official vectors, and approximating the board artwork was explicitly out of bounds.

- `downloading` -> `autorenew` -> the approved board indicates a distinct transfer arrow, but no isolated official vector is available.
- `explicit` -> `trending_up` -> no approved board cell or isolated official vector defines the explicit-content badge.
- `casino` -> `coronavirus` -> no isolated official casino vector is available.
- `bolt` -> `speed` -> no isolated official bolt vector is available.
- `backup` -> `restore` -> no isolated official backup vector is available.
- `arrow_outward` -> `logout` -> no isolated official outward-arrow vector is available.
- `developer_mode` -> `devices` -> no isolated official developer-mode vector is available.
- `grid_view` -> `interests` -> no isolated official grid-view vector is available.
- `swipe` -> `touch_app` -> no isolated official swipe vector is available.
- `text_fields` -> `text_rotation_angledown` -> no isolated official text-fields vector is available.
- `check_box` -> `select_all` -> no isolated official checked-box vector is available.
- `check_box_outline_blank` -> `deselect` -> no isolated official empty-checkbox vector is available.
- `sync_problem` -> `warning_amber` -> no isolated official sync-problem vector is available.
- `play_arrow` -> `slow_motion_video` -> no isolated official standalone play-arrow vector is available.

Accepted intentional aliases, kept for state stability: `album`/`outlined_album`, `error`/`error_outline`/`outlined_error`, `info`/`outlined_info`, `chevron_left`/`navigate_before`, `bedtime`/`dark_mode`, `lock`/`sync_lock`, `music_note`/`library_music`, `folder`/`folder_copy`, `history`/`more_time`/`timer`, `blur_on`/`equalizer`/`graphic_eq`, `filter_alt`/`sort`/`tune`, `clear_all`/`drag_handle`/`reorder`, `list`/`queue`, `search`/`manage_search`, `block`/`no_cell`.

## Phase 5 visual implementation (2026-11-07)

The local visual integration authorized by the owner was implemented without changing the musical-age engine, database schema, application ID, signing identity or version.

### Wordmark and font

- Orbitron Medium (weight 500) bundled at `app/src/main/res/font/orbitron_medium.ttf`, 17,800 bytes, SHA-256 `e1dd2875a4cf1711615b7bc7eb2ae8d05bef3861f87a7fdd847a78199169b458`.
- License at `app/src/main/assets/licenses/OFL-ORBITRON.txt`; provenance at `docs/brand/fonts/README.md`.
- The About screen shows “Orbitron Medium · The Orbitron Project Authors · SIL Open Font License 1.1”.
- `XenoWaveOrbitron` / `XenoWaveWordmarkStyle` in `ui/theme/XenoWaveTypography.kt`; `XenoBrandWordmark` in `ui/component/XenoBrandWordmark.kt`.
- Applied to the custom startup splash, About screen, musical-age card and exported card. Functional reading text, menus, controls and diagnostics keep the existing Material typography.
- Launcher artwork remains the official ET-only mark. The `XENOWAVE` label below it is drawn by the Android launcher and cannot be re-fonted by the app. The API 31+ splash now uses the official ET foreground on `#0B1020` instead of a blank light splash.

### Result presentation

- Reused the approved `et-jovial-v1.png`, `et-maduro-v1.png` and `et-veterano-v1.png` as `drawable-nodpi/xenowave_et_*.png`; no artwork was regenerated.
- Mapping is centralized in `MusicalAgeVisualIdentity.kt`: Alpha/Z → jovial, Millennial/X → mature, Boomer/Silent/Classics → veteran. The mapping is exhaustive over `MusicalGeneration`.
- `READY` uses only `ReadyResult` fields already calculated by the engine. `MIXED_ERAS` shows the official ET and the neutral message; `NO_HISTORY`, `INSUFFICIENT_HISTORY` and `INSUFFICIENT_METADATA` keep honest messages with no fictional age or generation.
- The ~3.4 s reveal is implemented locally with Compose Canvas (signal pulse, elongated star travel, deceleration, card). Skip is available from the start; system animation reduction/disable leads directly to the static card; the reveal is cancelled on background/navigation and does not loop on rotation.

### Export, share and download

- `MusicalAgeCardRenderer` draws the approved 1080×1920 and 1080×1080 compositions directly to a bitmap; it is not a screen capture. The PNG includes the ET, wordmark, age, generation, era, listening period, download CTA, QR and official URL.
- `MusicalAgeCardFiles` writes under `cache/musical-age-cards` only, with a 24 h TTL. `provider_paths.xml` exposes only `playlist-exports` and `musical-age-cards`; no root, files, databases, backups or whole cache.
- Sharing uses `ACTION_SEND`, MIME `image/png`, `ClipData`, `EXTRA_TEXT` with the official link and `FLAG_GRANT_READ_URI_PERMISSION`. Save to gallery is offered only on Android 10+ through MediaStore with no broad storage permission.
- `OfficialAppLinkProvider.DOWNLOAD_URL` remains the only download URL. QR is the approved deterministic matrix (41 modules, quiet zone 4, version 4/M) and was decoded from both exported formats.
- Export is revalidated against the current result revision immediately before writing and again before opening the chooser; stale artifacts are deleted and not shared.

### Local validation

- `testCoreDebugUnitTest`: 454 tests / 68 suites / 0 failures / 0 errors / 0 skipped (JVM/Robolectric; not an Android device run).
- `lintCoreDebug`: 0 errors / 205 warnings / 2 hints.
- `assembleCoreDebug`, `assembleCoreDebugAndroidTest`, `compileCoreReleaseKotlin` and `processCoreReleaseManifest` completed.
- New debug APK: 45,873,398 bytes, SHA-256 `6f0e1329c85a8343111d6dc0a64526fcf6bf765384f06610c45faa20b3e944c7`.
- New androidTest APK: 1,102,287 bytes, SHA-256 `27f27cef303ce31d69c5ecb4accd8f81a545bd12f830ea7775d3cba7905f72d2`.
- Synthetic previews and QR validation are in `../../../IDADE-MUSICAL-XENOWAVE-2026-11-06/40-RELATORIO-ETAPA-5-VISUAIS/` (relative to the repository).

Android validation of phases 4 and 5, including the launcher/splash on a device, share-sheet grants, gallery insertion and the Compose reveal, remains pending for the joint run after the 2026-11-09 reboot. No publication, commit or push was performed.

## Final validation

The final commands used the same local JDK 17 and a temporary corporate truststore outside the repository. The truststore resolved the initial Java certificate mismatch, but the network proxy then returned HTTP `503 Service Unavailable` for Google Maven and Maven Central artifacts. All three commands therefore failed during Gradle configuration before Android/Kotlin compilation.

| Command | Result | Duration | Blocking issue |
|---|---|---:|---|
| `.:app:assembleCoreDebug` | Failed during Gradle configuration | 34 s | Proxy HTTP 503 while downloading build-classpath artifacts |
| `.:app:testCoreDebugUnitTest` | Failed during Gradle configuration | 66 s | Proxy HTTP 503 while downloading build-classpath artifacts |
| `.:app:lintCoreDebug` | Failed during Gradle configuration | 35 s | Proxy HTTP 503 while downloading build-classpath artifacts |

Static validation completed successfully:

- 121 Android XML files parsed successfully.
- `git diff --check` passed.
- No Android `<string>` node still contains `OuterTune` in any letter case.
- The Master Visual Reference retains its approved dimensions and SHA-256.
- The application ID and namespace were migrated to `com.dd3boh.xenowave` in the technical identity migration round (see below).
- No diff exists in InnerTubeX/streaming, download, or database implementation paths.

### Continuation session validation (2026-09-17)

`gradlew.bat` was run with the local JDK 17 and the already-cached Gradle 9.3.1 distribution.

| Command | Result | Blocking issue |
|---|---|---|
| `.\gradlew.bat assembleCoreDebug` | Failed during dependency resolution | `Got SSL handshake exception during request` for `dl.google.com` (AGP, aaptcompiler) and `repo.maven.apache.org` (Kotlin plugins) |
| `.\gradlew.bat testCoreDebugUnitTest` | Failed during dependency resolution | same |
| `.\gradlew.bat lintCoreDebug` | Failed during dependency resolution | same |
| `.\gradlew.bat testDebugUnitTest` | Failed during dependency resolution | same; the unflavoured task name is never reached |
| `.\gradlew.bat lintDebug` | Failed during dependency resolution | same |
| `.\gradlew.bat assembleDebug` | Failed during dependency resolution | same |

Windows HTTPS validation succeeds for Google Maven and Maven Central, and using the JDK's `Windows-ROOT` trust store for one non-persistent Gradle invocation eliminates the earlier PKIX error. Dependency resolution is currently blocked by HTTP `503 Service Unavailable` responses from the proxy/network for both repositories. Independently, this environment has no Android SDK: `local.properties` is absent, `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, and no `aapt2`, `platform-tools` or `build-tools` exists under the user profile. No Android/Kotlin compilation, lint, unit test or local APK generation has completed, and no TLS or build configuration was persisted.

Equivalent static validation was performed instead, and all of it passes:

- `git diff --check`: clean.
- Kotlin parenthesis, brace and bracket balance: exact for every file under `app/src/main/java`.
- 288 `XenoIcon` call sites conform to the composable signature, all pass `contentDescription`, none pass a legacy icon argument.
- All `Xeno*Icons` member references resolve; all 149 distinct `R.drawable` references resolve; no `androidx.compose.material.icons` import or `ImageVector` reference remains.
- Every XML file under `app/src/main/res` parses, and every `@drawable`, `@mipmap`, `@color`, `@style`, `@string` and `@xml` reference in the resource tree and the manifest resolves.
- 129 VectorDrawable masters map one-to-one to 129 SVG masters, and every VectorDrawable was rendered to raster previews and reviewed; none uses an unsupported node type.
- The seven approved reference assets still match the SHA-256 values recorded in `docs/brand/iconography/manifest.json`.

### Final XML validation (2026-09-18)

All 252 XML files under `app/src/main/res` were loaded individually with .NET `XmlReader` and `XmlDocument`. DTD processing was prohibited and external resolution was disabled. The parser reported zero invalid files.

The Gradle failures did not identify `ffMetadataEx` or `taglib` as their cause. Because configuration never reached compilation, submodule initialization was neither required by the observed failure nor performed.

## Preserved technical areas

- InnerTubeX engine and resolver.
- `ResolvedStream`, headers, and `RangePolicy`.
- `StreamDataSpec` and Media3 integration.
- Download rules and download manager behavior.
- Database schema and migrations.
- Login, library, navigation, playback, and persistence behavior.
- Preference keys and persisted identifiers (database and DataStore names are brand-neutral).

## Technical identity migration (2026-10-18)

The application stopped being an OuterTune-branded build and became an independent XENOWAVE package.

- Namespace and base application ID: `com.dd3boh.xenowave`; debug application ID: `com.dd3boh.xenowave.debug`.
- Kotlin and Java sources moved from `com/dd3boh/outertune` to `com/dd3boh/xenowave` in `main` and `test` source sets.
- Room schema directory renamed to `app/schemas/com.dd3boh.xenowave.db.InternalDatabase`; schema content and database version (20) unchanged.
- Launcher shortcuts, intent actions, FileProvider authority, ProGuard rules and workflow artifacts now derive from the new package.
- Compose theme function `OuterTuneTheme` renamed to `XenoWaveTheme`; XML style `Theme.OuterTune` renamed to `Theme.XenoWave`.
- Debug launcher label is `XENOWAVE`; the automatic red debug overlay on the home screen was removed entirely.
- Fork-specific string files renamed from `strings-ot.xml` to `strings-xenowave.xml` in every locale (resource names and translations unchanged).
- Legacy helper classes with the `Ot` abbreviation were renamed (`XenoOffloadSupportProvider`, `XenoDownloadManager`, `XenoDownloadDirectoryManager`, `TreeDocumentFileXeno`, `LegacyStreamResolver`).
- Workflow `Build XENOWAVE debug APK`, artifact `XENOWAVE-core-debug`.
- Because the application ID changed, Android treats this build as a separate application: it installs side by side with the old package and does not inherit its private data. No cross-package migration is implemented.
- The GitHub repository was renamed from the previous OuterTune repository name to XENOWAVE; every project-controlled link now points to the new repository.
- Copyright headers, GPL licensing, upstream attribution, submodule URLs and historical references were intentionally preserved.
