# Releasing (fork, GitHub-only)

This fork ships signed APKs as **GitHub Releases** only — no Play Store, no F-Droid.
Releases are built by `.github/workflows/android-build.yml`, which triggers on any tag
matching `v0.*`, signs the APKs from repository secrets, and creates a **draft pre-release**
with the universal APKs attached.

The app keeps the upstream package id `com.brouken.player`, so a build from this fork
**cannot be installed alongside** the Play Store / F-Droid version (same package).

## One-time setup

### 1. Generate a signing keystore

```sh
keytool -genkeypair -v \
  -keystore keystore.jks \
  -alias key \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "<YOUR_PASSWORD>" -keypass "<YOUR_PASSWORD>"
```

Notes:
- The alias **must** be `key` (the workflow passes `-Pandroid.injected.signing.key.alias=key`).
- Use the **same** value for the store password and key password — the workflow passes one
  secret (`KEYSTORE_PASSWORD`) for both.
- Keep `keystore.jks` private. **Do not commit it.** Back it up somewhere safe; losing it
  means you can't ship updates that upgrade in place.

### 2. Add the repository secrets

GitHub → your fork → **Settings → Secrets and variables → Actions → New repository secret**:

- `KEYSTORE_BASE64` — the keystore, base64-encoded:
  ```sh
  base64 -w0 keystore.jks   # Linux; on macOS use: base64 keystore.jks | tr -d '\n'
  ```
  Paste the output as the secret value.
- `KEYSTORE_PASSWORD` — the password you used above.

## Cutting a release

The version is derived from `versionCode` in `app/build.gradle`
(`versionName "0.${versionCode}"`). Bump `versionCode`, commit, then tag:

```sh
git tag v0.213          # match the new versionCode
git push origin v0.213
```

The workflow runs, signs `assembleRelease`, and creates a **draft** pre-release.
Open the repo's **Releases** page, review it, and click **Publish** (it's a draft so nothing
goes out until you do).

Artifacts attached:
- `app/build/outputs/apk/latestUniversal/release/*.apk` — for modern Android (use this on the
  Xiaomi Pad 6).
- `app/build/outputs/apk/legacyUniversal/release/*.apk` — `targetSdk 29` build for older devices.

## CI note for the native (DV7→8.1) build

Once the Dolby Vision 7→8.1 feature lands, `./gradlew` triggers `externalNativeBuild` (the JNI
bridge). Because `libdovi` is committed prebuilt (per `app/src/main/cpp/`), CI needs **only the
NDK** — which AGP provisions automatically from the `ndkVersion` in `app/build.gradle`. **No Rust
toolchain is required in CI.** If a runner ever fails to auto-install the NDK, add an explicit
NDK setup step (e.g. `sdkmanager "ndk;<version>"`) before the Gradle step.
