# Build & Patch

BiliDetox is an Xposed module — it cannot run on its own. It must be embedded into a Bilibili APK using NPatch.

## Prerequisites

- JDK 17+ (MSJDK 21 verified)
- Android SDK (set via `local.properties` or `ANDROID_HOME`)
- ADB (for on-device testing)
- An unmodified Bilibili APK (project developed & verified against 9.6.0)

The NPatch JAR is already included at `tools/npatch.jar` (v1.0.6-698). To upgrade it, replace that file.

## Where files go

```
BiliDetox/
  original/                         <- put the unmodified Bilibili APK here (git-ignored)
    iBiliPlayer-bili.apk
  tools/
    npatch.jar                      <- NPatch CLI (checked in)
    NPatchLauncher.java             <- BKS provider fix, compiled on demand
    build/                          <- compiled launcher (git-ignored)
  output/                           <- patched APKs land here (git-ignored)
  app/                              <- module source
```

## One-command build & patch

From the repo root:

```cmd
patch.bat
```

Or point it at a specific APK:

```cmd
patch.bat original\iBiliPlayer-bili.apk
```

This does four things:

1. `gradlew :app:assembleDebug` — builds the module APK
2. Compiles `tools/NPatchLauncher.java` (no external deps beyond NPatch itself)
3. Invokes NPatch with `-l 0` to embed the module
4. Writes the result to `output\`

Install:

```cmd
adb install -r output\iBiliPlayer-bili-698-npatched.apk
```

Since NPatch re-signs the APK, any official Bilibili install must be uninstalled first.

## Key flag: `-l 0`

NPatch defaults to `-l 1` (signature bypass via nested-zip linking). On a PC build environment that path has a bug: failed nested links silently drop all `classes*.dex`, and the output APK lacks `classes.dex`, causing:

```
INSTALL_FAILED_INVALID_APK: Scanning Failed.: ... code is missing
```

`-l 0` uses plain entry copying and produces a complete APK. Bilibili does not perform runtime self-signature verification, so disabling signature bypass does not affect functionality.

## Why NPatchLauncher exists

Running `java -jar tools\npatch.jar ...` on a desktop JDK fails with:

```
java.security.KeyStoreException: BKS not found
```

NPatch calls `KeyStore.getInstance("BKS")` unconditionally. BKS is a BouncyCastle-specific format provided by the Android runtime but not desktop JDKs. BouncyCastle is already bundled inside the NPatch JAR (~4900 classes) — it simply needs to be registered as a security provider before NPatch runs.

`tools/NPatchLauncher.java` (~45 lines, no external dependencies) registers the provider reflectively, then calls `top.nkbe.npatch.patch.NPatch.main`. `patch.bat` compiles it on demand into `tools/build/`.

I also attempted `-Djava.security.properties=...` to add the provider; that does not work because that mechanism resolves provider class names with the bootstrap classloader at JVM init, before BouncyCastle on the application classpath is visible.

## Iterating on the module

After changing code under `app/`:

```cmd
patch.bat
adb install -r output\iBiliPlayer-bili-698-npatched.apk
adb shell monkey -p tv.danmaku.bili -c android.intent.category.LAUNCHER 1
adb logcat -s BiliDetox:V
```
