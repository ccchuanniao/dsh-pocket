# DSH Pocket — operations guide for AI agents

You are an AI coding agent operating a DeepSeek Harness host on behalf of a human. This document
tells you how to set up, verify, and hand over the DSH Pocket system, and what to say to the human
while you do it.

It is written for you, not for them. Sections marked **Say to the human** contain text you can pass
along nearly verbatim. Everything else is for your own execution.

Read the whole document before starting. Several steps fail in ways that look like success.

---

## 1. What this system is

Two halves that only work together:

| Half | What it is | Where it runs |
| --- | --- | --- |
| **`dsh-pocket-pair`** | A DeepSeek Harness plugin | The harness host |
| **DSH Pocket** | An Android app | The human's phone |

The plugin does three separable jobs. Decide which ones the human actually wants before you
configure anything — each adds setup work and failure modes.

1. **Pairing** — mint a one-time code, admit the phone, revoke it later. This is the core.
2. **Distribution** — serve the APK, and optionally build it, from the plugin page.
3. **Notifications** — push a message to the phone when a task finishes.

Pairing alone needs no third-party services. Notifications need Firebase and a network path to
Google. Do not configure notifications unless asked; it is the part most likely to fail on a host
with restricted egress.

---

## 2. Before you change anything

Ask the human these questions. The answers determine the configuration, and getting them wrong is
expensive to undo.

1. **What address will the phone use to reach this host?** Options, in order of reliability:
   - A public HTTPS hostname that reaches the harness (reverse proxy, tunnel, DNS).
   - A LAN address and port, if the phone is on the same network.
   - `127.0.0.1` plus `adb reverse`, only for an emulator or a tethered device.

   This becomes `pairBase`. **Pairing cannot work without it**, and a wrong value produces a QR code
   that fails on the phone, where you cannot debug it.

2. **Does the phone need to receive notifications when a task finishes?** If yes, they need a
   Firebase project they control. If they do not have one, stop and say so — do not improvise.

3. **Is this host reachable from the phone at all right now?** Test before configuring. Many
   failures reported as "the plugin is broken" are a firewall, a proxy, or a certificate.

---

## 3. Installing the plugin

```bash
dsh plugin --profile <profile> add dsh-pocket-pair
```

The plugin's host half is read at boot, so **restart the harness afterward**:

```bash
systemctl restart dsh-web.service      # or however the harness is supervised
```

> **If you are running inside the harness you are restarting**, the restart kills your own session.
> Schedule it instead of running it synchronously:
> `systemd-run --on-active=120 --unit=dsh-restart systemctl restart dsh-web.service`

Client-side changes (the settings page) additionally need the browser to reload. The bundle URL
carries a content hash that the harness recomputes when the file changes, so **an ordinary reload is
enough** — a hard refresh is not required.

---

## 4. Configuration

The plugin's defaults are deliberately empty or off. A typical deployment sets only a few keys.

Minimal, in the profile's `cordis.patch.yml`:

```yaml
- id: pocket-pair
  config:
    pairBase: 'https://harness.example.com'
    lanPort: 8081
```

Full key list:

| Key | Default | Meaning |
| --- | --- | --- |
| `pairBase` | `''` | Address the phone connects to, with scheme. **Empty means pairing cannot work.** |
| `lanPort` | `8081` | Port for the gate. Must not collide with the harness or anything else. |
| `lanEnabled` | `true` | Set false to disable the gate entirely. |
| `lanAdvertise` | `''` | LAN address to advertise; auto-detected when empty. |
| `apkUrl` | `''` | APK download URL. Empty derives `<pairBase>/apk/<apkName>`. |
| `apkName` | `dsh-pocket.apk` | File name of the distributed APK. |
| `apkDir` | `<DSH_HOME>/dsh-pocket-pair/apk` | Where distributed APKs live. |
| `codeTtlSeconds` | `3600` | Pairing code lifetime. |
| `redeemPerMinute` | `10` | Rate limit on the public redemption route. |
| `buildEnabled` | **`false`** | Let the settings page run Gradle. See the security note below. |
| `buildProjectDir` | `''` | Android project to build. Build stays off while empty. |
| `buildOutputApk` | `app/build/outputs/apk/release/app-release.apk` | Build output, relative to the project. |
| `pushEnabled` | `true` | Master switch for sending push. |
| `pushTitle` | `DSH 任务完成` | Notification title. |
| `pushAiSummary` | **`false`** | Let the model write the notification body. |
| `pushAiTimeoutMs` | `15000` | Timeout for that generation; falls back to plain text. |
| `fcmServiceAccountFile` | `''` | Path to the Firebase service account JSON. **A real secret.** Empty disables sending. |
| `fcmProxy` | `''` | HTTP proxy for reaching Google, if the host needs one. |
| `firebaseProjectId` / `firebaseAppId` / `firebaseApiKey` / `firebaseSenderId` | `''` | Public client values, baked into the APK. |
| `notifyTopic` | `''` | Legacy; prefer per-device tokens. |

`pairBase`, `apkUrl` and the Firebase values can also be set from the settings page, which stores
them in `<DSH_HOME>/dsh-pocket-pair/settings.json` (mode 0600). Page values override configuration.

> **Security:** `buildEnabled: true` means an authenticated HTTP request runs Gradle in a directory
> of your choosing — that is arbitrary code execution for anyone who has the browser session. Turn it
> on only on a host the human fully controls and trusts.

---

## 5. Verify before you involve the human

**Do not hand a QR code to a human until you have verified the host side yourself.** A code that
fails on their phone costs a round trip and looks like your mistake, because it is.

Authenticated routes are under `/api/...` and require the browser session cookie. Get one by opening
the harness's launch-token URL, which the harness prints once at startup:

```bash
# The harness prints: dsh web: http://127.0.0.1:<port>/?token=<token>
curl -s -c /tmp/jar -o /dev/null "http://127.0.0.1:8080/?token=<token>"
curl -s -b /tmp/jar http://127.0.0.1:8080/api/pocket-pair/state
```

Check, in this order:

1. **The plugin loaded.**
   `state` returns JSON containing `"pairBase"` and `"pushTrace"`.
   If it 404s, the plugin is not registered — check the profile bundle list and restart.

2. **`pairBase` is what the phone will use.**
   The response's `pairBase` must be an address the phone can actually reach. Verify from the
   phone's network perspective, not the host's: `127.0.0.1` is never right for a phone.

3. **The gate is listening.**
   ```bash
   ss -lntp | grep <lanPort>
   ```
   It binds `::` (dual-stack), which is normal — the harness itself cannot do that.

4. **The public download route answers.**
   ```bash
   curl -sI "http://127.0.0.1:<lanPort>/apk/<apkName>"
   ```
   `200` means an APK is present; `404` means `apkDir` is empty. A phone cannot install what is not
   there.

5. **If notifications are wanted**, verify the send path before promising anything:
   ```bash
   curl -s -b /tmp/jar -X POST http://127.0.0.1:8080/api/pocket-pair/summarize \
     -H 'content-type: application/json' \
     -d '{"text":"Fixed the pairing retry. Tests pass."}'
   ```
   A `200` with a generated line proves the model path. It does **not** prove FCM works; only a real
   notification does. Read `push.log` after the first completed task (section 8).

---

## 6. Handing over to the human

Once the checks pass, mint a code and give the human the link. The settings page does this with a
button; the API does it too:

```bash
curl -s -b /tmp/jar -X POST http://127.0.0.1:8080/api/pocket-pair/mint
```

The response contains the pairing code, the full link, and a QR image.

**Say to the human** (adjust the address):

> 1. Install the app: open `<pairBase>/apk/dsh-pocket.apk` on the phone and install it. Android will
>    warn about installing from outside the store — that is expected for a self-hosted app.
> 2. Open the app. If it asks for an address, enter `<pairBase>`.
> 3. On the host, open **Settings → 手机配对** and click **生成配对码**.
> 4. Scan the QR code with the phone, or type the code shown under it.
> 5. The code works **once** and expires in an hour. If it fails, generate a new one.

Two things to tell them explicitly, because they cause most confusion:

- **The pairing window closes itself** after one device pairs. That is by design, not a bug.
- **Revoking a device is immediate.** Settings → 手机配对 → the device → 吊销. The phone's next
  request gets `401`.

---

## 7. Building and distributing the APK

If `buildEnabled` is on and `buildProjectDir` points at an Android project, the settings page's
**构建并发布** button runs Gradle and publishes the result. The build also opens a pairing window and
bakes the address plus a single-use enrollment key into the package, so a fresh install pairs with
nothing to type. The key is spent on first redemption, so a publicly downloadable APK cannot be
reused by a second person.

Builds take minutes. The API returns as soon as the build starts; poll `state` for progress:

```bash
curl -s -b /tmp/jar http://127.0.0.1:8080/api/pocket-pair/state   # see .build
```

Check the version from Gradle's own metadata rather than guessing:

```bash
cat <buildProjectDir>/app/build/outputs/apk/release/output-metadata.json
```

**Never publish an APK that embeds a value you would not share.** A baked `pairBase` is the human's
domain; a baked `pairKey` is a credential, even if single-use.

---

## 8. Diagnostics

Two instruments exist. Use them before theorising.

**`pushTrace`** — in the `state` response, under that key. Contains:

| Field | Meaning |
| --- | --- |
| `sessionEvents` | Count of every session event the plugin has seen, by type. If `assistant/message` is absent, events are not reaching the plugin at all. |
| `agentStatus` | `running` / `idle` transition counts. A healthy task produces exactly one of each. |
| `lastSessionId` / `lastAgentId` | The ids from the two events. They must be equal. |
| `lastAssistantText` | The last assistant text the plugin extracted. **Empty here means the body pipeline is broken at extraction.** |
| `lastSaidKeys` / `turnKeys` | Keys of the in-memory maps. These must contain `lastAgentId`. |
| `pushes` | The last 20 push attempts, with the body and its source. |
| `skipped` | Why the last push was not sent, when it was not. |

**`<DSH_HOME>/dsh-pocket-pair/push.log`** — one line per push, capped at 64 KB (keeps the last 100
lines). The `来源` field is the one that matters:

- `来源=AI` — the body came from the model.
- `来源=最后一条回复` — the body is the tail of the last assistant message.
- `来源=兜底` — **neither source produced text.** Something upstream is broken; see the catalogue.

> **`ctx.logger.info` does not reach systemd's journal.** Only the harness's own startup lines
> appear there. Do not try to debug this plugin from `journalctl`; use the two instruments above.

---

## 9. Failure catalogue

| Symptom | Likely cause | What to check |
| --- | --- | --- |
| Pairing QR fails on the phone; host looks fine | `pairBase` unreachable from the phone's network, or the phone routes that domain through a proxy/VPN | Test the address from the phone's browser first |
| QR link opens a browser but the app never intercepts it | App Links verification is not set up | `/.well-known/assetlinks.json` on `appLinkHost`, and its fingerprint matching the signing certificate |
| Notification body is always the same sentence | The body pipeline extracted no text | `pushTrace.lastAssistantText` — if empty, extraction is broken |
| No notification at all | No registered push token, or sending failed | `pushTrace.skipped`, `pushTrace.sent`, and `push.log` |
| Push registration returns `401` | The phone's HTTP client does not share the WebView cookie jar | The app must send `X-Dsh-Device-Token`; the gate accepts that header |
| Console shows "Reconnecting" forever | The gate forwards WebSocket upgrades with a mismatched `Origin` | The gate must rewrite `Origin` as well as `Host`, and keep `Connection`/`Upgrade` |
| Model call returns nothing, no error | Adapter failures arrive as a terminal *chunk*, not an exception | Check `assembler.finish`; a missing API key looks like "no text" otherwise |
| Settings page renders blank | `jsx(type, props, key)` — children passed as the third argument render nothing | Children belong in `props.children` |

---

## 10. Traps worth knowing

These cost real time. They are recorded here so they cost you none.

- **`SessionEvent` is an envelope.** The payload is at `event.data`, not `event`. Reading
  `event.message` silently yields `undefined`, which turns into a permanently empty notification
  body rather than an error.
- **A harness plugin must declare `inject` before reading `ctx.<service>`.** Accessing an undeclared
  service *throws*; it does not return `undefined`. But adding an optional service to the plugin's
  required `inject` array stops the whole plugin loading on a host that lacks it. Use
  `ctx.inject(deps, callback)` for optional capabilities.
- **Do not set a small `maxTokens` for a reasoning model.** Thinking counts against the output
  budget, so a limit sized for the answer truncates it to nothing. Clamp the result instead.
- **`reasoningEffort` collapses the stream to empty unless the model declares support.** Query
  `resolveModelInfo` first; an unsupported explicit value fails without an error.
- **Do not let the model infer the output language** when the system prompt is in another language.
  Decide it in code and state it explicitly.
- **`cache-control: immutable` on plugin bundles is not a trap.** The URL carries a content hash
  that the harness recomputes on change, so a normal reload picks up new client code.

---

## 11. Never publish or commit

- `fcmServiceAccountFile` — a real Google credential. It stays on the host, never in an APK, never
  in a repository.
- The Android signing keystore and its `keystore.properties`. Anyone holding it can publish updates
  that devices accept as the original author's.
- Anyone's `pairBase`, LAN addresses, proxy addresses, or Firebase project values.
- `settings.json`, `pairing.json`, `push.log` — they contain device tokens and work content.

---

## 12. Reference

**Host routes** (`/api` routes require the browser session cookie):

| Route | Auth | Purpose |
| --- | --- | --- |
| `GET /api/pocket-pair/state` | session | Codes, devices, settings, build progress, `pushTrace` |
| `POST /api/pocket-pair/mint` | session | Mint a pairing code |
| `POST /api/pocket-pair/settings` | session | Save `pairBase`, `apkUrl`, Firebase values, `pushAiSummary` |
| `POST /api/pocket-pair/revoke` | session | Revoke a device by name |
| `POST /api/pocket-pair/close` | session | Close the enrollment window |
| `POST /api/pocket-pair/build` | session | Start a Gradle build and publish |
| `POST /api/pocket-pair/summarize` | session | Generate one notification body from sample text |

**Gate routes** (the plugin's own listener on `lanPort`):

| Route | Auth | Purpose |
| --- | --- | --- |
| `POST /dsh-pocket-pair/redeem` | **none** | Spend a pairing code for a device token |
| `GET /apk/<name>.apk` | **none** | Download the APK |
| `POST /dsh-pocket-pair/push` | device token | Register an FCM token |
| everything else | device token | Proxied to the harness |

The two unauthenticated routes are unavoidable: the caller is a phone that has no credentials yet.

**Files written to disk**, all under `<DSH_HOME>/dsh-pocket-pair/`, mode 0600:

| File | Contents |
| --- | --- |
| `settings.json` | Values entered on the settings page |
| `pairing.json` | Unused codes, paired device tokens, last-seen times |
| `push.log` | Recent push records |
| `apk/` | Published APKs |
