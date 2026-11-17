#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i += 2) {
    const key = argv[i]?.replace(/^--/, "");
    const value = argv[i + 1];
    if (key && value !== undefined) args[key] = value;
  }
  return args;
}

function findAapt2(explicitPath) {
  if (explicitPath && fs.existsSync(explicitPath)) return explicitPath;
  const sdkRoot = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdkRoot) throw new Error("ANDROID_HOME or --aapt2 must be provided");
  const buildToolsRoot = path.join(sdkRoot, "build-tools");
  const versions = fs.readdirSync(buildToolsRoot).sort();
  if (versions.length === 0) throw new Error(`No build-tools found under ${buildToolsRoot}`);
  return path.join(buildToolsRoot, versions[versions.length - 1], "aapt2");
}

function extractSigningFingerprint(apkPath) {
  const out = execFileSync("keytool", ["-printcert", "-jarfile", apkPath], {
    encoding: "utf8",
  });
  const match = /SHA256:\s*([0-9A-Fa-f:]+)/.exec(out);
  if (!match) {
    throw new Error(`Could not read SHA256 certificate fingerprint from ${apkPath}`);
  }
  return match[1].replace(/:/g, "").toLowerCase();
}

function extractZipEntry(apkPath, entryPath, destPath) {
  const bytes = execFileSync("unzip", ["-p", apkPath, entryPath], {
    maxBuffer: 16 * 1024 * 1024,
  });
  fs.writeFileSync(destPath, bytes);
}

const args = parseArgs(process.argv);
const repoDir = path.resolve(args["repo-dir"] || "repo");
const apkDir = path.join(repoDir, "apk");
const iconDir = path.join(repoDir, "icon");
const inspectorPath = path.resolve(args["inspector-json"] || "output.json");
const repoName = args["repo-name"] || "idjo";
const shortName = args["short-name"] || "idjo";
const website = args["website"] || "https://github.com/idjoo/extensions-source";
const aapt2Bin = findAapt2(args["aapt2"] || args["aapt"]);

fs.mkdirSync(iconDir, { recursive: true });

const inspectorData = JSON.parse(fs.readFileSync(inspectorPath, "utf8"));
const apkFiles = fs
  .readdirSync(apkDir)
  .filter((f) => f.endsWith(".apk"))
  .sort();

if (apkFiles.length === 0) {
  throw new Error(`No APK files found in ${apkDir}`);
}

const indexData = [];
const indexMinData = [];
let repoFingerprint = args["fingerprint"] || null;

for (const apkName of apkFiles) {
  const apkPath = path.join(apkDir, apkName);
  const badging = execFileSync(aapt2Bin, ["dump", "badging", "--include-meta-data", apkPath], {
    encoding: "utf8",
  });

  const pkgLine = badging.split(/\r?\n/).find((l) => l.startsWith("package: "));
  if (!pkgLine) throw new Error(`Missing package line in aapt badging for ${apkName}`);

  const packageName = /package: name='([^']+)'/.exec(pkgLine)?.[1];
  const versionCode = Number(/versionCode='([^']+)'/.exec(pkgLine)?.[1]);
  const versionName = /versionName='([^']+)'/.exec(pkgLine)?.[1];
  const appLabel = /^application-label:'([^']+)'/m.exec(badging)?.[1];
  const nsfwValue = Number(/'tachiyomi\.animeextension\.nsfw' value='([^']+)'/.exec(badging)?.[1] ?? "0");
  const iconEntry =
    /^application-icon-640:'([^']+)'/m.exec(badging)?.[1] ||
    /^application-icon-480:'([^']+)'/m.exec(badging)?.[1] ||
    /^application-icon-320:'([^']+)'/m.exec(badging)?.[1] ||
    /^application-icon-240:'([^']+)'/m.exec(badging)?.[1] ||
    /^application-icon-160:'([^']+)'/m.exec(badging)?.[1];

  if (!packageName || !versionName || !appLabel || !iconEntry) {
    throw new Error(`Incomplete manifest metadata in ${apkName}`);
  }

  extractZipEntry(apkPath, iconEntry, path.join(iconDir, `${packageName}.png`));

  if (!repoFingerprint) {
    repoFingerprint = extractSigningFingerprint(apkPath);
  }

  let language = /aniyomi-([^.]+)/.exec(apkName)?.[1] || "all";
  const sources = inspectorData[packageName];
  if (!Array.isArray(sources) || sources.length === 0) {
    throw new Error(`No sources returned by Inspector for ${packageName}`);
  }

  if (sources.length === 1) {
    const srcLang = sources[0].lang;
    if (srcLang !== language && !["all", "other"].includes(srcLang) && !["all", "other"].includes(language)) {
      language = srcLang;
    }
  }

  const common = {
    name: appLabel,
    pkg: packageName,
    apk: apkName,
    lang: language,
    code: versionCode,
    version: versionName,
    nsfw: nsfwValue,
  };

  indexMinData.push({
    ...common,
    sources: sources.map((s) => ({
      name: s.name,
      lang: s.lang,
      id: String(s.id),
      baseUrl: s.baseUrl,
    })),
  });

  indexData.push({
    ...common,
    hasReadme: 0,
    hasChangelog: 0,
    sources,
  });
}

indexData.sort((a, b) => a.pkg.localeCompare(b.pkg));
indexMinData.sort((a, b) => a.pkg.localeCompare(b.pkg));

fs.writeFileSync(path.join(repoDir, "index.json"), JSON.stringify(indexData, null, 2) + "\n");
fs.writeFileSync(path.join(repoDir, "index.min.json"), JSON.stringify(indexMinData));
fs.writeFileSync(
  path.join(repoDir, "repo.json"),
  JSON.stringify(
    {
      meta: {
        name: repoName,
        shortName,
        website,
        signingKeyFingerprint: repoFingerprint,
      },
    },
    null,
    2,
  ) + "\n",
);

console.log(`Generated repository index for ${apkFiles.length} APK(s) (fingerprint: ${repoFingerprint})`);
