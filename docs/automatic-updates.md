# XENOWAVE automatic updates

This document describes how the in-app updater works, how to publish a new
official version and how to test the first update safely.

## How it works

- The app reads **only** the official GitHub releases of
  `paraecologia-arch/XENOWAVE`, over HTTPS, without any embedded GitHub token.
- A bounded release list (3 pages × 20 releases) is inspected. Drafts are
  ignored. The winner is chosen by the numeric `versionCode` from its
  `update.json` asset, never by tag text or release date.
- The default channel is **Beta**, because the public distribution is a beta.
  Beta accepts prereleases and final releases. Stable ignores prereleases and
  beta-only metadata. The channel is stored as a preference and can be changed
  in **Settings → Updates**.
- Detection is automatic: a light foreground check runs when the app becomes
  visible (15 minute cooldown; **Check now** bypasses it), and WorkManager
  keeps one unique periodic discovery job about every 12 hours (`CONNECTED`,
  battery not low). There is no continuous polling.
- Detection never starts a download. The app shows **Update** and **Not now**
  in the foreground. Only an explicit **Update** tap persists consent for that
  exact `versionCode` + SHA-256 and starts the heavy work.
- Consent is target-bound. Accepting version A does not authorise version B, a
  different SHA-256, or a different application id. Every install path (worker,
  manual, retry, restart, stored APK) validates the same record.
- Downloads go to the app's private storage (`files/updates`), use a `.part`
  file and are committed atomically after the size and SHA-256 match the
  metadata.
- Before installation the APK is checked again: size, SHA-256, package name,
  increasing `versionCode`, minimum SDK and signing certificate compatibility
  (including Android key-rotation history).
- Installation uses `PackageInstaller` with `MODE_FULL_INSTALL`. The app never
  uninstalls the previous version, so app data is preserved.
- Automatic installation waits while music is playing. It never stops or
  restarts the player to force an update.

## Release metadata contract (`update.json`)

The asset must describe the **final signed APK**, never the unsigned CI output:

```json
{
  "schemaVersion": 1,
  "applicationId": "com.dd3boh.xenowave",
  "versionCode": 72,
  "versionName": "0.10.2-beta.1",
  "channel": "beta",
  "minSdk": 24,
  "apkName": "XENOWAVE-0.10.2-beta.1-core.apk",
  "sizeBytes": 14430527,
  "sha256": "0c32a7be7b240e22999b3f17696dca5272f0792df6c85b74b1f15270a1a35d4a"
}
```

Rules enforced by the app:

- `schemaVersion` must be `1`;
- `applicationId` must equal the installed application id;
- `versionCode` must be positive and greater than the installed one;
- `versionName` must look like `0.10.2` or `0.10.2-beta.1`;
- `channel` must be `beta` or `stable`;
- `minSdk` must be compatible with the phone;
- `apkName` must be a plain `.apk` file name (no folders);
- `sizeBytes` must be between 1 MiB and 250 MiB;
- `sha256` must be 64 lowercase hex characters.

## Publishing a new version

1. Check that no newer release was published in the meantime.
2. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Open the **Build public XENOWAVE APK** workflow
   (`workflow_dispatch`). It builds, lints, runs the unit tests, zipaligns and
   uploads the **unsigned** APK using the version read from Gradle.
4. Download the unsigned artifact and sign it locally with the existing
   release key:

   ```powershell
   .\sign-public-apk.ps1 -UnsignedApk .\release-download\XENOWAVE-0.10.2-beta.1-core-unsigned.apk `
       -VersionName 0.10.2-beta.1 -Channel beta
   ```

   The script uses the existing keystore through Windows DPAPI, verifies the
   signature and writes `SHA256SUMS.txt` plus `update.json` for the signed APK.
   It refuses to overwrite an existing published APK.
5. Create the GitHub release (for example `v0.10.2-beta.1`) as a **prerelease**
   for beta versions and upload:
   - `XENOWAVE-0.10.2-beta.1-core.apk`
   - `SHA256SUMS.txt`
   - `update.json`
6. Keep old release assets untouched. Never re-upload a different APK under the
   same version.

The helper `tools/generate-update-metadata.ps1` can also be run on an already
signed APK:

```powershell
.\tools\generate-update-metadata.ps1 -ApkPath ..\release\XENOWAVE-0.10.2-beta.1-core.apk `
    -VersionCode 72 -VersionName 0.10.2-beta.1 -Channel beta -OutDir ..\release\v0.10.2-beta.1
```

## First update on a phone

1. Install the beta that already contains the updater (this version). This
   first installation is always manual because previous public versions do not
   have updater code.
2. Open **Settings → Updates**. Grant the Android permission to install unknown
   apps for XENOWAVE if Android asks. The app cannot grant this permission by
   itself.
3. Publish the next version (higher `versionCode`, same `applicationId` and
   same signing key) as described above.
4. Reopen XENOWAVE. The app checks the official releases and shows
   **New XENOWAVE version available** with **Update** and **Not now**. On the
   first run after this change, you can also use
   **Settings → Updates → Check now**.
5. Tap **Update**. Only then does the APK download, get verified and reach
   `PackageInstaller`. **Not now** creates no download and no install session.
6. Android may show its own confirmation screen. Confirm it; if the app is in
   the background, use the notification.
7. Verify that app data (library, playlists, queue) is preserved. If Android
   reports a signature mismatch, the APK was signed with a different key —
   discard it and rebuild with the existing release key.

## Limitations (real behaviour, no marketing)

- The very first installation of a version with updater code is manual.
- Android 12+ can update an app without user action only when the system grants
  `UPDATE_PACKAGES_WITHOUT_USER_ACTION`; otherwise the user confirmation is
  mandatory. Many manufacturers always ask.
- If notifications are disabled, Android's confirmation can still be opened from
  the in-app update dialog while XENOWAVE is in the foreground. There is no
  background confirmation without a notification channel, so keep the app open
  or enable notifications.
- Debug builds (`applicationId` ending in `.debug`) never auto-update; the
  production package has a different signature/identity.
- Only the public `core` flavor is self-updatable. The `full` flavor is not
  offered updates, because replacing it with a core APK would remove codecs.
- The device speech/recognition service and the update download both need
  network in some situations. Nothing here promises permanent offline use.
- GitHub may rate-limit anonymous requests. The app backs off and retries later;
  it does not bypass limits.

## Security notes

- No GitHub token, password or keystore is shipped in the APK or committed.
- Downloads are limited to `https` and to GitHub distribution hosts
  (`api.github.com`, `github.com`, `objects.githubusercontent.com`,
  `release-assets.githubusercontent.com`, `github-releases.githubusercontent.com`).
- Redirects are followed manually, at most five hops, validating each host.
- Invalid downloads are deleted. Signature, package and version checks are
  mandatory before a session is created.
- The install receiver is non-exported and only accepts the explicit action
  sent by the app itself. It never accepts APKs or commands from other apps.
