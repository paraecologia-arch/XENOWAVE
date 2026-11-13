const SHA256 = /^[0-9a-f]{64}$/;
const VERSION_NAME = /^[0-9]{1,4}(\.[0-9]{1,4}){1,3}([-+][0-9A-Za-z.\-]{1,32})?$/;
const APK_NAME = /^[A-Za-z0-9._-]{1,120}\.apk$/;
const MIN_APK_BYTES = 1024 * 1024;
const MAX_APK_BYTES = 250 * 1024 * 1024;
const OFFICIAL_HOST = "github.com";
const OFFICIAL_PREFIX = "/paraecologia-arch/XENOWAVE/releases/download/";

export class ManifestError extends Error {}

export function validateManifest(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new ManifestError("manifesto inválido");
  }

  if (value.schemaVersion !== 1) throw new ManifestError("esquema desconhecido");
  if (value.applicationId !== "com.dd3boh.xenowave") throw new ManifestError("aplicativo inesperado");
  if (!Number.isInteger(value.versionCode) || value.versionCode <= 0) {
    throw new ManifestError("código de versão inválido");
  }
  if (typeof value.versionName !== "string" || !VERSION_NAME.test(value.versionName)) {
    throw new ManifestError("nome de versão inválido");
  }
  if (!Number.isInteger(value.minSdk) || value.minSdk < 1 || value.minSdk > 100) {
    throw new ManifestError("Android mínimo inválido");
  }
  if (typeof value.apkName !== "string" || !APK_NAME.test(value.apkName) ||
      !value.apkName.endsWith("-core.apk")) {
    throw new ManifestError("nome de APK inválido");
  }
  if (!Number.isInteger(value.sizeBytes) ||
      value.sizeBytes < MIN_APK_BYTES || value.sizeBytes > MAX_APK_BYTES) {
    throw new ManifestError("tamanho inválido");
  }
  if (typeof value.sha256 !== "string" || !SHA256.test(value.sha256)) {
    throw new ManifestError("hash inválido");
  }

  let url;
  try {
    url = new URL(value.url);
  } catch {
    throw new ManifestError("link inválido");
  }
  if (url.protocol !== "https:" || url.hostname !== OFFICIAL_HOST) {
    throw new ManifestError("link fora da distribuição oficial");
  }
  if (url.port !== "" || url.search !== "" || url.hash !== "") {
    throw new ManifestError("link com parâmetros inesperados");
  }
  if (!url.pathname.startsWith(OFFICIAL_PREFIX) || url.pathname.includes("..")) {
    throw new ManifestError("caminho fora da distribuição oficial");
  }
  const fileName = decodeURIComponent(url.pathname.split("/").pop() || "");
  if (fileName !== value.apkName) {
    throw new ManifestError("o link não aponta para o APK declarado");
  }

  return {
    versionName: value.versionName,
    versionCode: value.versionCode,
    apkName: value.apkName,
    minSdk: value.minSdk,
    sizeBytes: value.sizeBytes,
    sha256: value.sha256,
    url: url.toString(),
    publishedAt: typeof value.publishedAt === "string" ? value.publishedAt : "",
  };
}

export function formatBytes(bytes) {
  const mb = bytes / (1024 * 1024);
  return `${mb.toFixed(1).replace(".", ",")} MB`;
}

export function formatDate(isoDate) {
  if (!isoDate) return "";
  const date = new Date(isoDate);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "long",
    year: "numeric",
  }).format(date);
}
