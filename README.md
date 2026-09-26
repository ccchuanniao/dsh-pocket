# DSH Pocket

An Android shell for a [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) host:
it shows the harness console full-screen, keeps you signed in, and can push a notification when a
task finishes so you do not have to sit in front of the machine.

It is the phone half of a pair. The host half is the **`dsh-pocket-pair`** harness plugin, which
mints a one-time pairing code, admits the device through a gate that supports per-device
revocation, and serves the APK you are about to install.

## What it is not

- Not a separate client. It hosts the harness's own web UI in a WebView; the harness does the work.
- No accounts, no analytics, no telemetry, no crash reporting. Nothing is sent anywhere except the
  harness you pair with.
- The only third-party service it can reach is Google's FCM, and only if you build in Firebase
  configuration. Leave those four values out and the app never contacts Google at all.

## What it stores on the device

Two secrets, and where they go:

| What | Where | Notes |
| --- | --- | --- |
| Harness password (only if the gate asks for one) | `EncryptedSharedPreferences`, key in the Android keystore | Never written in the clear. If the encrypted store cannot be opened the pair is **not persisted at all** and you are asked again — failing to encrypt degrades to "asked again", never to a plaintext file. |
| Device token (grants console access) | The WebView cookie jar, `HttpOnly; SameSite=Strict`, `Secure` on https | Handed to the WebView as a cookie rather than in the URL, so it does not reach a reverse proxy's access log |

`android:allowBackup="false"`. Android's automatic backup would upload both of the above to the
user's cloud account and restore them onto a new device. Re-pairing after a restore costs one QR
scan, so nothing is lost by refusing.

The harness password is only ever sent to the host you paired with: the WebView asks whatever raises
an authentication challenge, so the app checks the challenge's host against the paired address first
and cancels otherwise. It is also stored per host, so pointing the app at a different harness does
not offer the previous server's password to the new one.

## Building

Requirements: JDK 21 and an Android SDK with API 36.

```bash
cp dsh-pocket.properties.example dsh-pocket.properties   # optional; see Configuration
./gradlew :app:assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Without `keystore.properties` the build produces
an unsigned package; see [Signing](#signing).

## Compatibility

| | Version |
| --- | --- |
| Android | **8.0 (API 26) or newer.** `minSdk = 26` |
| Compile / target SDK | API 36 |
| JDK | 21 |
| Gradle | 8.14.3 (wrapper) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Host side | The `dsh-pocket-pair` harness plugin, on DeepSeek Harness `0.1.x` |

The app is only half of the system. It needs the **`dsh-pocket-pair`** plugin running on a harness
host to pair with: that plugin mints the pairing code, admits the device through a gate that supports
per-device revocation, and serves the APK the QR link points at. This repository does not contain it.

The app talks to the harness only through its web console and the plugin's gate, so it does not care
which harness version runs underneath beyond what the plugin itself requires. That requirement —
and the fact that the plugin reads harness internals rather than a frozen API — is documented in the
plugin's own README.

## Configuration

Everything that differs between deployments lives in `dsh-pocket.properties`, which is gitignored.
`dsh-pocket.properties.example` documents every key. Command-line `-Pkey=value` overrides the file.

Nothing in this repository contains anyone's domain, address, keystore, or Firebase project.

| Key | Effect when absent |
| --- | --- |
| `applicationId` | Defaults to `app.dshpocket` |
| `appLinkHost` | Defaults to a placeholder that never verifies; links open in the browser |
| `firebaseProjectId` / `firebaseAppId` / `firebaseApiKey` / `firebaseSenderId` | Push is not enabled |
| `pairBase` / `pairKey` | The app asks the user to pair by hand instead of pairing itself |

**Keep `applicationId` stable.** It is the app's identity on the device and the identity your
Firebase Android app is registered under. Changing it installs a second app that receives no push.

## Signing

`app/build.gradle.kts` reads `keystore.properties` from the project root:

```properties
storeFile=.signing/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Both that file and `.signing/` are gitignored on purpose. **Never regenerate a keystore for a build
people have already installed** — the signature is how Android decides an update is really yours,
and a new key means every existing device has to uninstall first.

## One-scan install and pair (optional)

The pairing QR code is an `https` link to the APK download with the harness address and pairing code
in the URL fragment (fragments are not sent to servers, so the code stays out of download logs).

For an installed app to intercept that link instead of the browser, Android must verify that you
control the host. Set `appLinkHost`, then serve the fingerprint:

```bash
# SHA-256 of your signing certificate
keytool -list -v -keystore .signing/release.keystore -alias <alias> | grep SHA256
```

Serve it at `https://<appLinkHost>/.well-known/assetlinks.json`:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "app.dshpocket",
    "sha256_cert_fingerprints": ["AA:BB:..."]
  }
}]
```

`package_name` must match your `applicationId`. Without this step the app still works — the link
just opens in the browser, which is also how a phone without the app installs it.

## Push notifications (optional)

Fill in the four Firebase client values and the app will register for push on first launch. They are
public values present in any Firebase Android app; the service account that actually *sends* push
lives on the harness host and never enters this project.

The `dsh-pocket-pair` plugin sends one notification per finished task. Its body can be the tail of
the last assistant message or a single line written by the harness's own model.

The app asks for notification permission on Android 13 and later. If you deny it, everything else
keeps working.

## Package layout

```
app/src/main/java/app/dshpocket/
  MainActivity.kt        WebView host, pairing entry points, push registration
  Pairing.kt             Redemption against the harness plugin
  PairingPrefs.kt        Persisted harness address and device token
  DeviceIdentity.kt      Stable per-installation device name
  StoredCredentials.kt   Encrypted storage for the WebView's session cookie
  PushService.kt         FirebaseMessagingService, notification channel
  PushRegistration.kt    Token registration with retry
  DshGestures.kt         Gesture handling
app/src/main/assets/peek-v7.html    Animated splash
brand/                              Icon and splash source art
```

## License

MIT — see [LICENSE](LICENSE). Use it commercially, modify it, ship it; keep the copyright notice.

**The MIT licence covers this project's source code.** Third-party assets keep their own terms:

- **Splash font** — ZCOOL KuaiLe (站酷快乐体), SIL Open Font License 1.1. The licence text ships in
  [`brand/fonts/OFL-ZCOOL-KuaiLe.txt`](brand/fonts/OFL-ZCOOL-KuaiLe.txt), as the OFL requires. OFL
  permits commercial use, embedding and redistribution; it only forbids selling the font by itself.
  The `@font-face` in the splash is a ~2 KB subset, regenerated with
  [`brand/fonts/embed-splash-font.py`](brand/fonts/embed-splash-font.py).
- **App icon and splash artwork** — generated with ChatGPT, so no copyright is claimed on them and
  no separate licence applies. Reuse them freely.
