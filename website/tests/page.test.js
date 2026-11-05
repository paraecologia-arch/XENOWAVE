import test from "node:test";
import assert from "node:assert/strict";
import { readFile, stat } from "node:fs/promises";

const root = new URL("../", import.meta.url);
const html = await readFile(new URL("index.html", root), "utf8");

test("uses only local assets and relative paths", () => {
  assert.doesNotMatch(html, /src="https?:/);
  assert.doesNotMatch(html, /href="https?:[^"]*\.css/);
  assert.match(html, /href="styles\.css"/);
  assert.match(html, /src="app\.js"/);
});

test("contains the consent button and accessibility hooks", () => {
  assert.match(html, /id="download"/);
  assert.match(html, /Deseja baixar o XENOWAVE\?/);
  assert.match(html, /aria-live="polite"/);
  assert.match(html, /<noscript>/);
  assert.match(html, /permissão é diferente/);
});

test("does not auto-download or use inline event handlers", () => {
  assert.doesNotMatch(html, /on(click|load|error)=/i);
  assert.doesNotMatch(html, /<meta[^>]+refresh/i);
  assert.doesNotMatch(html, /download\s*=/);
});

test("local brand asset exists", async () => {
  const asset = await stat(new URL("assets/xeno_launcher.png", root));
  assert.ok(asset.size > 1024);
});
