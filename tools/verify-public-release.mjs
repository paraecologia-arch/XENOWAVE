#!/usr/bin/env node
/**
 * Verifies a published XENOWAVE beta using only anonymous public requests and
 * the same discovery rules as the update client installed at versionCode 74.
 *
 * Usage:
 *   node tools/verify-public-release.mjs --version-code 75 \
 *     --version-name 0.10.3-beta.2 --apk-name XENOWAVE-0.10.3-beta.2-core.apk \
 *     --sha256 <hash> --size <bytes> --tag v0.10.3-beta.2 --commit <sha> \
 *     [--patch-name ... --patch-sha256 ... --patch-size ... --patch-base-sha256 ...] \
 *     [--out <json>]
 */
import { createHash } from "node:crypto";
import { writeFile } from "node:fs/promises";

const OWNER = "paraecologia-arch";
const REPO = "XENOWAVE";
const APPLICATION_ID = "com.dd3boh.xenowave";
const SDK_INT = 36;
const MIN_APK_BYTES = 1024 * 1024;
const MAX_APK_BYTES = 250 * 1024 * 1024;
const MIN_PATCH_BYTES = 4 * 1024;
const MAX_PATCH_BYTES = 100 * 1024 * 1024;
const MAX_METADATA_BYTES = 64 * 1024;
const VERSION_NAME = /^[0-9]{1,4}(\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.\-]{1,32})?$/;
const APK_NAME = /^[A-Za-z0-9._-]{1,120}\.apk$/;
const PATCH_NAME = /^[A-Za-z0-9._-]{1,120}\.(xnd|patch)$/;
const SHA256 = /^[0-9a-f]{64}$/;

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, "").replace(/-([a-z])/g, (_, c) => c.toUpperCase());
    args[key] = argv[i + 1];
  }
  return args;
}

const args = parseArgs(process.argv.slice(2));
const required = ["versionCode", "versionName", "apkName", "sha256", "size", "tag", "commit"];
for (const key of required) {
  if (!args[key]) throw new Error(`missing --${key}`);
}

const expected = {
  versionCode: Number(args.versionCode),
  versionName: args.versionName,
  apkName: args.apkName,
  sha256: args.sha256.toLowerCase(),
  size: Number(args.size),
  tag: args.tag,
  commit: args.commit.toLowerCase(),
  installedVersionCode: Number(args.installedVersionCode ?? 74),
};

const headers = {
  Accept: "application/vnd.github+json",
  "User-Agent": "XENOWAVE-public-verification",
  "X-GitHub-Api-Version": "2022-11-28",
};

async function get(url, options = {}) {
  const response = await fetch(url, { ...options, headers });
  if (!response.ok) throw new Error(`GET ${url} -> HTTP ${response.status}`);
  return response;
}

async function json(url) {
  return (await get(url)).json();
}

function validMetadata(raw) {
  if (raw.schemaVersion !== 1) return null;
  if (raw.applicationId !== APPLICATION_ID) return null;
  if (!Number.isInteger(raw.versionCode) || raw.versionCode <= 0) return null;
  if (typeof raw.versionName !== "string" || !VERSION_NAME.test(raw.versionName)) return null;
  if (raw.channel !== "beta" && raw.channel !== "stable") return null;
  if (!Number.isInteger(raw.minSdk) || raw.minSdk < 1 || raw.minSdk > 100) return null;
  if (typeof raw.apkName !== "string" || !APK_NAME.test(raw.apkName)) return null;
  if (!Number.isInteger(raw.sizeBytes) ||
      raw.sizeBytes < MIN_APK_BYTES || raw.sizeBytes > MAX_APK_BYTES) return null;
  if (typeof raw.sha256 !== "string" || !SHA256.test(raw.sha256)) return null;

  const deltas = [];
  if (Array.isArray(raw.deltas)) {
    for (const item of raw.deltas.slice(0, 4)) {
      if (!item || !Number.isInteger(item.fromVersionCode) ||
          item.fromVersionCode <= 0 || item.fromVersionCode >= raw.versionCode) continue;
      if (typeof item.fromApkSha256 !== "string" || !SHA256.test(item.fromApkSha256)) continue;
      if (typeof item.patchName !== "string" || !PATCH_NAME.test(item.patchName)) continue;
      if (!Number.isInteger(item.patchSizeBytes) ||
          item.patchSizeBytes < MIN_PATCH_BYTES || item.patchSizeBytes > MAX_PATCH_BYTES) continue;
      if (typeof item.patchSha256 !== "string" || !SHA256.test(item.patchSha256)) continue;
      if (item.format !== "xnd-cdc" || item.formatVersion !== 1) continue;
      deltas.push(item);
    }
  }
  return { ...raw, deltas };
}

async function hashResponse(response, maxBytes) {
  const hash = createHash("sha256");
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > maxBytes) throw new Error(`download exceeded ${maxBytes} bytes`);
    hash.update(chunk);
  }
  return { size, sha256: hash.digest("hex") };
}

async function main() {
  const api = `https://api.github.com/repos/${OWNER}/${REPO}`;
  const releaseUrl = `https://github.com/${OWNER}/${REPO}/releases/tag/${expected.tag}`;

  const tagRef = await json(`${api}/git/ref/tags/${encodeURIComponent(expected.tag)}`);
  let tagObject = tagRef.object;
  if (tagObject.type === "tag") {
    tagObject = (await json(`${api}/git/tags/${tagObject.sha}`)).object;
  }
  if (tagObject.type !== "commit" || tagObject.sha !== expected.commit) {
    throw new Error(`tag ${expected.tag} points to ${tagObject.sha}, expected ${expected.commit}`);
  }

  const candidates = [];
  const releasesScanned = [];
  for (let page = 1; page <= 3; page++) {
    const releases = await json(`${api}/releases?per_page=20&page=${page}`);
    releasesScanned.push(releases.map((r) => r.tag_name));
    for (const release of releases) {
      if (release.draft) continue;
      const metadataAsset = (release.assets || []).find((a) => a.name === "update.json");
      if (!metadataAsset) continue;
      const response = await get(metadataAsset.browser_download_url);
      const length = Number(response.headers.get("content-length") || 0);
      if (length > MAX_METADATA_BYTES) continue;
      const text = await response.text();
      if (Buffer.byteLength(text) > MAX_METADATA_BYTES) continue;
      let parsed;
      try {
        parsed = JSON.parse(text);
      } catch {
        continue;
      }
      const metadata = validMetadata(parsed);
      if (!metadata) continue;
      const apkAsset = (release.assets || []).find((a) => a.name === metadata.apkName);
      if (!apkAsset) continue;
      candidates.push({ release, metadata, apkAsset });
    }
    if (releases.length < 20) break;
  }

  const eligible = candidates
    .filter((c) => c.metadata.applicationId === APPLICATION_ID)
    .filter((c) => c.metadata.versionCode > expected.installedVersionCode)
    .filter((c) => c.metadata.minSdk <= SDK_INT)
    .sort((a, b) => b.metadata.versionCode - a.metadata.versionCode);

  const selected = eligible[0];
  if (!selected) throw new Error("installed client 74 would not discover any update");
  const { metadata, apkAsset, release } = selected;
  if (metadata.versionCode !== expected.versionCode ||
      metadata.versionName !== expected.versionName ||
      metadata.apkName !== expected.apkName ||
      metadata.sha256 !== expected.sha256 ||
      metadata.sizeBytes !== expected.size) {
    throw new Error("selected update does not match the expected artifact");
  }
  if (release.tag_name !== expected.tag) {
    throw new Error(`selected release is ${release.tag_name}, expected ${expected.tag}`);
  }

  const apk = await get(apkAsset.browser_download_url);
  const apkResult = await hashResponse(apk, MAX_APK_BYTES);
  if (apkResult.size !== expected.size || apkResult.sha256 !== expected.sha256) {
    throw new Error(`APK mismatch: ${apkResult.size} bytes / ${apkResult.sha256}`);
  }

  let patch = null;
  if (args.patchName) {
    const patchAsset = (release.assets || []).find((a) => a.name === args.patchName);
    if (!patchAsset) throw new Error("patch asset missing");
    const patchResult = await hashResponse(await get(patchAsset.browser_download_url), MAX_PATCH_BYTES);
    const declared = metadata.deltas.find((d) => d.patchName === args.patchName);
    if (!declared) throw new Error("patch not declared in update.json");
    if (patchResult.size !== Number(args.patchSize) || patchResult.sha256 !== args.patchSha256) {
      throw new Error("downloaded patch does not match declared size/hash");
    }
    if (declared.fromApkSha256 !== args.patchBaseSha256) {
      throw new Error("patch base hash is not the published APK 74");
    }
    patch = {
      name: args.patchName,
      sizeBytes: patchResult.size,
      sha256: patchResult.sha256,
      fromVersionCode: declared.fromVersionCode,
      fromApkSha256: declared.fromApkSha256,
      savingPercent: Number((100 * (1 - patchResult.size / expected.size)).toFixed(2)),
    };
  }

  const result = {
    status: "pass",
    verifiedAt: new Date().toISOString(),
    pageUrl: "https://paraecologia-arch.github.io/XENOWAVE/",
    releaseUrl,
    releaseId: release.id,
    prerelease: release.prerelease,
    draft: release.draft,
    tag: release.tag_name,
    commit: tagObject.sha,
    discovery: {
      simulatedClientVersionCode: expected.installedVersionCode,
      channel: "beta",
      releasesScanned: releasesScanned.flat(),
      eligibleVersionCodes: eligible.map((c) => c.metadata.versionCode),
      selectedVersionCode: metadata.versionCode,
    },
    apk: {
      name: apkAsset.name,
      url: apkAsset.browser_download_url,
      sizeBytes: apkResult.size,
      sha256: apkResult.sha256,
    },
    metadata: {
      url: release.assets.find((a) => a.name === "update.json").browser_download_url,
      versionName: metadata.versionName,
      versionCode: metadata.versionCode,
      applicationId: metadata.applicationId,
      minSdk: metadata.minSdk,
      channel: metadata.channel,
    },
    patch,
  };
  if (args.out) await writeFile(args.out, JSON.stringify(result, null, 2) + "\n", "utf8");
  console.log(JSON.stringify(result, null, 2));
}

main().catch((error) => {
  console.error(`FAILED: ${error.message}`);
  process.exit(1);
});
