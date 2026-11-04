# XENOWAVE delta updates (XND1)

## Status

Delta transport is implemented from **0.10.3-beta.1 / versionCode 74**.
Clients 0.10.2-beta.1 (72) and 0.10.2-beta.2 (73) do not contain the applier
and must receive 74 through the existing full-APK path. A real on-device delta
test requires a future version published after 74.

## Format

- Magic `XND1`, format `xnd-cdc`, version `1`.
- Content-defined chunking over both APKs, followed by a Deflate-compressed
  instruction stream.
- `COPY` instructions reference exact byte ranges in the installed base APK.
- `LITERAL` instructions carry changed bytes.
- The header binds the base size/SHA-256 and target size/SHA-256.
- The applier verifies the base hash, writes to a separate private file,
  checks the exact target size and SHA-256, and then runs the normal
  `ApkVerifier` (package, version, signature/key rotation).

The implementation is pure Java using `java.io` only, so it runs on the
project minSdk 24 without JNI, native ABIs or `java.nio.file` desugaring.

## Safety limits

- Base/target APK: at most 250 MiB.
- Patch file: at most 100 MiB.
- Decompressed payload: bounded.
- Instructions: at most 1,000,000.
- One literal instruction: at most 2 MiB.
- Copy ranges are validated against the base; output is capped at the exact
  target size.
- No in-place write to `ApplicationInfo.sourceDir`.
- Split installs skip delta and keep the full path.
- A failed patch for the same base/patch/target is remembered so it is not
  retried forever. A new target or patch gets a new identity.

## Selection policy

A delta is offered only when:

- `fromVersionCode` matches the installed version;
- `fromApkSha256` matches the installed APK bytes;
- the format/version is supported;
- the patch is at least 4 KiB;
- the patch is at least 20% smaller than the full APK.

Otherwise the existing full APK is used exactly once. A patch download/apply
failure is followed by one full download, respecting cancellation and network
constraints. Cancellation never starts the full download as a side effect.

## Measured offline results

Generated from the public signed APKs with `tools/delta/DeltaTool.java`:

| Base | Target | Full APK | Patch | Saving | Generate | Apply |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 0.10.1-beta.1 (71) | 0.10.2-beta.1 (72) | 14,430,527 | 4,776,447 | 67.13% | 459 ms | 108 ms |
| 0.10.2-beta.1 (72) | 0.10.3-beta.1 (74) | 14,533,074 | 4,745,405 | 67.53% | 515 ms | 104 ms |

Both reconstructions matched the target SHA-256 byte for byte:

- 71→72: `e953791474225d90e85d1644dd99a12947d7f1268af5e8b1530a2c199f311ac3`
- 72→74: `c2f0d8256909380b2967510ab8a2b2106f8297a04b92b5f93ace7837dfbf0c92`

The 72→74 patch is `XENOWAVE-0.10.2-beta.1-to-0.10.3-beta.1.xnd`, SHA-256
`bf679a37525488345da66f07dfa230c20b8a736cae3722a4143f6451f163600a`.

## Generating a patch

The APK target **must already be signed** before the patch is generated. Never
re-sign, recompress or edit the target after generating the patch.

```powershell
$javac = "$env:JAVA_HOME\bin\javac.exe"
$java = "$env:JAVA_HOME\bin\java.exe"
$toolOut = "C:\temp\xenowave-delta-tool"
New-Item -ItemType Directory -Force -Path $toolOut | Out-Null

& $javac -d $toolOut `
    app\src\main\java\com\dd3boh\xenowave\update\delta\DeltaCodec.java `
    tools\delta\DeltaTool.java

& $java -cp $toolOut DeltaTool create `
    --base ..\release\v0.10.2-beta.1\XENOWAVE-0.10.2-beta.1-core.apk `
    --target ..\release\v0.10.3-beta.1\XENOWAVE-0.10.3-beta.1-core.apk `
    --patch ..\release\v0.10.3-beta.1\XENOWAVE-0.10.2-beta.1-to-0.10.3-beta.1.xnd
```

The tool applies the patch to a temporary file and fails if the reconstruction
is not byte-identical to the signed target.

## Metadata

`update.json` keeps all schema-1 full-APK fields. `deltas` is optional:

```json
{
  "schemaVersion": 1,
  "applicationId": "com.dd3boh.xenowave",
  "versionCode": 74,
  "versionName": "0.10.3-beta.1",
  "channel": "beta",
  "minSdk": 24,
  "apkName": "XENOWAVE-0.10.3-beta.1-core.apk",
  "sizeBytes": 14614994,
  "sha256": "c2f0d8256909380b2967510ab8a2b2106f8297a04b92b5f93ace7837dfbf0c92",
  "deltas": [
    {
      "fromVersionCode": 72,
      "fromApkSha256": "e953791474225d90e85d1644dd99a12947d7f1268af5e8b1530a2c199f311ac3",
      "patchName": "XENOWAVE-0.10.2-beta.1-to-0.10.3-beta.1.xnd",
      "patchSizeBytes": 4745405,
      "patchSha256": "bf679a37525488345da66f07dfa230c20b8a736cae3722a4143f6451f163600a",
      "format": "xnd-cdc",
      "formatVersion": 1
    }
  ]
}
```

The old client 72 ignores `deltas` and continues using the full APK. A missing
patch asset, unknown format or malformed delta entry is ignored for that entry;
the root APK metadata remains usable.

## Publishing checklist

1. Build, test and lint the release variant.
2. Zipalign and sign the final APK with the stable key.
3. Generate the patch from the **signed** base and **signed** target.
4. Verify the patch reconstructs the exact target SHA-256.
5. Generate `update.json` and `SHA256SUMS.txt` with the APK and every useful
   patch entry.
6. Upload the full APK, `update.json`, `SHA256SUMS.txt` and the patch as assets
   of the same release.
7. Keep old releases and their assets untouched.
