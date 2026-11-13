import { formatBytes, formatDate, ManifestError, validateManifest } from "./manifest.js";

// Public fallback, kept only for a failed manifest request. It is a real,
// verified release, not a placeholder or a floating "latest" link.
const FALLBACK = {
  versionName: "0.10.3-beta.7",
  versionCode: 80,
  minSdk: 24,
  sizeBytes: 19782308,
  sha256: "a2bd3caf60dfae93ae3dbba324fb82815d97836872ca8276b6092be723c5f386",
  url: "https://github.com/paraecologia-arch/XENOWAVE/releases/download/v0.10.3-beta.7/XENOWAVE-0.10.3-beta.7-core.apk",
  publishedAt: "2026-11-13T18:22:12Z",
};

const button = document.querySelector("#download");
const status = document.querySelector("#status");
const release = document.querySelector("#release");
const verifyVersion = document.querySelector("#verify-version");
const verifyCode = document.querySelector("#verify-code");
const verifySha = document.querySelector("#verify-sha");

function platformLabel(minSdk) {
  if (minSdk <= 24) return "Android 7.0 ou superior";
  return `Android (API ${minSdk}) ou superior`;
}

function showReady(data, usedFallback) {
  button.href = data.url;
  button.removeAttribute("aria-disabled");
  button.textContent = "Baixar para Android";
  status.dataset.state = "ready";
  status.innerHTML = "";
  if (usedFallback) {
    status.append(document.createTextNode(
      "Não foi possível carregar a versão mais recente. O botão baixa a versão " +
      `${data.versionName}, já verificada. `,
    ));
    const retry = document.createElement("button");
    retry.type = "button";
    retry.className = "retry";
    retry.textContent = "Tentar novamente";
    retry.addEventListener("click", load);
    status.append(retry);
  } else {
    status.textContent = "Versão disponível. Toque no botão para baixar.";
  }
  const date = formatDate(data.publishedAt);
  release.hidden = false;
  release.innerHTML = "";
  const parts = [
    `Versão ${data.versionName}`,
    formatBytes(data.sizeBytes),
    platformLabel(data.minSdk),
  ];
  if (date) parts.push(`publicada em ${date}`);
  release.append(document.createTextNode(parts.join(" · ")));
  verifyVersion.textContent = data.versionName;
  verifyCode.textContent = String(data.versionCode);
  verifySha.textContent = data.sha256;
}

function showRetry(message) {
  button.href = "#";
  button.setAttribute("aria-disabled", "true");
  button.textContent = "Baixar para Android";
  status.dataset.state = "error";
  status.innerHTML = "";
  status.append(document.createTextNode(message + " "));
  const retry = document.createElement("button");
  retry.type = "button";
  retry.className = "retry";
  retry.textContent = "Tentar novamente";
  retry.addEventListener("click", load);
  status.append(retry);
}

async function load() {
  status.dataset.state = "";
  status.textContent = "Carregando informações da versão…";
  try {
    const response = await fetch(new URL("download.json", document.baseURI), {
      cache: "no-store",
      credentials: "omit",
      redirect: "error",
    });
    if (!response.ok) throw new ManifestError(`HTTP ${response.status}`);
    const data = validateManifest(await response.json());
    showReady(data, false);
  } catch (error) {
    console.error("Falha ao carregar download.json", error);
    showRetry("Não foi possível carregar a versão mais recente.");
    const fallback = validateManifest({
      schemaVersion: 1,
      applicationId: "com.dd3boh.xenowave",
      channel: "beta",
      ...FALLBACK,
    });
    showReady(fallback, true);
    status.dataset.state = "error";
  }
}

load();
