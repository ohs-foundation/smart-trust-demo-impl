import { pathToFileURL } from "node:url";

const CLINICAL = /"resourceType"\s*:\s*"(Composition|Immunization|Condition|MedicationStatement|MedicationRequest|AllergyIntolerance)"/;
const DOCUMENT_BUNDLE = /"type"\s*:\s*"document"/;
const HASH = /\$argon2[a-z]*\$/;
const PASSCODE = /pass[-_ ]?(code|hash)/i;
// A compact JWE, the form a VHL's encrypted summary travels in.
const JWE = /eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]*\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}/;
const RULES = [
  ["clinical content of the summary", CLINICAL],
  ["a document Bundle", DOCUMENT_BUNDLE],
  ["a passcode hash", HASH],
  ["a passcode", PASSCODE],
  ["an encrypted summary (JWE)", JWE],
];

export function problemsIn(where, text) {
  return RULES.filter(([, rule]) => rule.test(text)).map(([what]) => `${where} holds ${what}`);
}

// Runs in the page, so it reads the origin's own storage; the browser holds the values and none leave it except as a match.
async function read() {
  const dump = (store) => Object.fromEntries(Object.keys(store).map((key) => [key, store.getItem(key)]));
  const databases = (await indexedDB.databases?.()) ?? [];
  const records = {};
  for (const { name } of databases) {
    const db = await new Promise((resolve, reject) => {
      const open = indexedDB.open(name);
      open.onsuccess = () => resolve(open.result);
      open.onerror = () => reject(open.error);
    });
    for (const store of db.objectStoreNames) {
      records[`${name}/${store}`] = await new Promise((resolve) => {
        const all = db.transaction(store).objectStore(store).getAll();
        all.onsuccess = () => resolve(JSON.stringify(all.result));
        all.onerror = () => resolve("");
      });
    }
    db.close();
  }
  const cacheUrls = {};
  for (const name of window.caches ? await window.caches.keys() : []) {
    const cache = await window.caches.open(name);
    cacheUrls[name] = (await cache.keys()).map((request) => request.url);
  }
  return { local: dump(localStorage), session: dump(sessionStorage), databases: databases.map((d) => d.name), records, caches: cacheUrls };
}

/** Reads the origin's localStorage, sessionStorage, IndexedDB and Cache Storage through any open page on it, or opens one. */
export async function scanBrowserStorage(context, origin) {
  const existing = context.pages().find((page) => page.url().startsWith(origin));
  const page = existing ?? (await context.newPage());
  try {
    if (!existing) await page.goto(`${origin}/`, { waitUntil: "domcontentloaded" });
    if (new URL(page.url()).origin !== origin) throw new Error(`the page moved to ${new URL(page.url()).origin}; open ${origin} first`);
    const found = await page.evaluate(read);
    const problems = [];
    for (const [area, entries] of [["localStorage", found.local], ["sessionStorage", found.session]]) {
      for (const [key, value] of Object.entries(entries)) problems.push(...problemsIn(`${area}[${key}]`, `${key}\n${value}`));
    }
    for (const name of found.databases) problems.push(...problemsIn(`IndexedDB name ${name}`, name));
    for (const [store, text] of Object.entries(found.records)) problems.push(...problemsIn(`IndexedDB ${store}`, text));
    for (const [name, urls] of Object.entries(found.caches)) {
      problems.push(...problemsIn(`Cache Storage ${name}`, `${name}\n${urls.join("\n")}`));
    }
    const counts = {
      localStorage: Object.keys(found.local).length,
      sessionStorage: Object.keys(found.session).length,
      indexedDB: found.databases.length,
      cacheStorage: Object.keys(found.caches).length,
    };
    return { ok: problems.length === 0, problems, counts };
  } finally {
    if (!existing) await page.close();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [cdp, origin] = process.argv.slice(2);
  if (!cdp || !origin) {
    console.error("usage: node tools/qa/browser_scan.mjs <cdp-endpoint, e.g. http://127.0.0.1:9222> <origin, e.g. https://client.kesaro.<STACK_HOST>:8443>");
    process.exit(2);
  }
  const { chromium } = await import("playwright-core");
  const browser = await chromium.connectOverCDP(cdp);
  const results = await Promise.all(browser.contexts().map((context) => scanBrowserStorage(context, origin)));
  const problems = results.flatMap((result) => result.problems);
  const counts = JSON.stringify(results.map((result) => result.counts));
  console.log(problems.length ? `FAIL  6 browser storage of ${origin}: ${problems.length} problem(s)\n      ${problems.join("\n      ")}` : `OK    6 browser storage of ${origin}: no passcode and no summary content (${counts})`);
  await browser.close();
  process.exit(problems.length ? 1 : 0);
}
