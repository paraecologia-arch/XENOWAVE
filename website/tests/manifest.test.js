import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { formatBytes, validateManifest } from "../manifest.js";

const valid = {
  schemaVersion: 1,
  applicationId: "com.dd3boh.xenowave",
  channel: "beta",
  versionName: "0.10.3-beta.2",
  versionCode: 75,
  minSdk: 24,
  apkName: "XENOWAVE-0.10.3-beta.2-core.apk",
  sizeBytes: 14614994,
  sha256: "c2f0d8256909380b2967510ab8a2b2106f8297a04b92b5f93ace7837dfbf0c92",
  url: "https://github.com/paraecologia-arch/XENOWAVE/releases/download/v0.10.3-beta.2/XENOWAVE-0.10.3-beta.2-core.apk",
  publishedAt: "2026-11-05T12:00:00Z",
};

function rejects(patch, label) {
  test(`rejects ${label}`, () => {
    assert.throws(() => validateManifest({ ...valid, ...patch }));
  });
}

test("accepts the published manifest shape", () => {
  const result = validateManifest(valid);
  assert.equal(result.versionCode, 75);
  assert.equal(result.url, valid.url);
});

test("the checked-in manifest is valid", async () => {
  const raw = await readFile(new URL("../download.json", import.meta.url), "utf8");
  const result = validateManifest(JSON.parse(raw));
  assert.match(result.apkName ?? "", /-core\.apk$/);
});

rejects({ url: "http://github.com/paraecologia-arch/XENOWAVE/releases/download/v/XENOWAVE-0.10.3-beta.2-core.apk" }, "plain HTTP");
rejects({ url: "https://evil.example/paraecologia-arch/XENOWAVE/releases/download/v/XENOWAVE-0.10.3-beta.2-core.apk" }, "foreign host");
rejects({ url: "https://github.com/paraecologia-arch/XENOWAVE/releases/download/../x/XENOWAVE-0.10.3-beta.2-core.apk" }, "path traversal");
rejects({ url: "https://github.com/paraecologia-arch/XENOWAVE/releases/download/v/XENOWAVE-0.10.3-beta.2-core.apk?redirect=https://evil.example" }, "query string");
rejects({ url: "https://github.com/paraecologia-arch/XENOWAVE/releases/download/v/XENOWAVE-0.10.3-beta.2-core.apk#x" }, "fragment");
rejects({ url: "https://github.com/paraecologia-arch/OTHER/releases/download/v/XENOWAVE-0.10.3-beta.2-core.apk" }, "foreign repository");
rejects({ url: "https://github.com/paraecologia-arch/XENOWAVE/releases/download/v/patch.xnd" }, "patch file");
rejects({ apkName: "XENOWAVE-0.10.3-beta.2-debug.apk" }, "debug APK name");
rejects({ apkName: "XENOWAVE-0.10.3-beta.2-full.apk" }, "full APK name");
rejects({ versionCode: 0 }, "zero versionCode");
rejects({ versionCode: "75" }, "text versionCode");
rejects({ versionName: "latest" }, "text version name");
rejects({ sizeBytes: 100 }, "tiny size");
rejects({ sizeBytes: 300 * 1024 * 1024 }, "oversized APK");
rejects({ sha256: "abc" }, "short hash");
rejects({ applicationId: "com.example.app" }, "foreign application id");
rejects({ schemaVersion: 2 }, "unknown schema");
rejects({ minSdk: 0 }, "invalid minSdk");

test("formats sizes for the page", () => {
  assert.equal(formatBytes(14614994), "13,9 MB");
});
