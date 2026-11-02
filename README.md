<div align="center">

<img src=".github/assets/banner.svg" alt="LiquidMusic: real Liquid Glass for Apple Music on Android" width="100%">

<br>
<br>

**Real Liquid Glass for Apple Music on Android.**<br>
Clear glass with refracted edges, vibrancy and a specular rim on the floating tab bar and the mini player,<br>
packaged as a [Morphe](https://morphe.software) patch.

<br>

[![Release](https://img.shields.io/github/v/release/myvoow/LiquidMusic?style=for-the-badge&label=release&color=fa2d48)](https://github.com/myvoow/LiquidMusic/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/myvoow/LiquidMusic/release.yml?branch=main&style=for-the-badge&label=build)](https://github.com/myvoow/LiquidMusic/actions/workflows/release.yml)
[![Morphe](https://img.shields.io/badge/Morphe-compatible-0a84ff?style=for-the-badge)](https://morphe.software)
[![Android](https://img.shields.io/badge/Android-12%2B-30d158?style=for-the-badge&logo=android&logoColor=white)](#what-you-get)
[![License](https://img.shields.io/github/license/myvoow/LiquidMusic?style=for-the-badge&color=8e8e93)](LICENSE)

<br>

<a href="https://morphe.software/add-source?github=myvoow/LiquidMusic">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="56">
</a>

</div>

<br>

## Add the source

On your phone, tap **Add to Morphe** above. You can also paste this address in Morphe Manager when you add a patch source:

```text
https://github.com/myvoow/LiquidMusic
```

## Install

1. Add the source (see above).
2. In Morphe, choose **Apple Music** and give it the APKM. The patch was built on **7.0.0-beta**; Morphe shows later versions as experimental.
3. Keep **Liquid Glass** selected, and change its [options](#options) if you like.
4. Uninstall the official Apple Music, since the patched app has a different signature, then install the patched one.

> [!WARNING]
> Morphe re-signs the app, and Apple Music sends a Play Integrity token when you sign in and when you pay ([details](docs/apple-music-7.0.0-beta.md#4-security-and-the-non-root-path)). Apple may refuse a re-signed copy at sign-in or when you subscribe. On the first device test, sign-in worked; subscribing and paying are untested. Sign in and play a song before you delete the downloads of the official app.

## What you get

<p align="center">
  <img src=".github/assets/preview.png" alt="Apple's blur next to Liquid Glass on the mini player and the tab bar" width="100%">
  <br>
  <sub>Rendered by <a href="tools/glass-preview">tools/glass-preview</a> with Android's own renderer, on a stand-in for Apple Music's layout.</sub>
</p>

| | Apple Music 7.0 | With Liquid Glass |
|---|---|---|
| Content behind the bar | 64 px blur, almost unreadable | clear, no blur, colors boosted to 150% |
| Edges | flat | bent like a lens (Android 13+) |
| Tint on top | two layers at 60% | 40% of Apple's tint, also after the player colors change |
| Rim | thin stroke | specular highlight, drawn over the tint |
| Surfaces | tab bar, mini player | tab bar, mini player, search bar |

| Android | Result |
|:---:|---|
| 13+ | Full effect: refraction, vibrancy, highlight |
| 12 | Vibrancy and highlight, without refraction |
| 11 | Apple's original look; the patch stays inactive |

## Options

Every option can be changed in Morphe before patching.

| Option | Default | What it does |
|---|:---:|---|
| Blur radius | Off | Blur of the content behind the glass (Light 4 dp, Frosted 12 dp). Off keeps the glass clear |
| Refraction width | 24 dp | Width of the bent band along the edges (Off = no refraction) |
| Refraction strength | 24 dp | How far the content moves at the very edge |
| Vibrancy | 150% | Saturation of the content behind the glass |
| Tint opacity | 40% | How much of Apple's tint is kept (100% = Apple's look) |
| Edge highlight | 100% | Brightness of the rim light |
| Chromatic aberration | off | Splits the colors of the edges like a prism (Android 13+) |
| Search bar | on | Also covers the floating search bar and the tablet top bar |

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.1.0](https://github.com/myvoow/LiquidMusic/releases/tag/v1.1.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 Apple Music&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**Package name:** `com.apple.android.music`

**🎯 Supported versions:**

| 7.0.0-beta |
| :---: |
| Floating tab bar and mini player redesign |  |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Liquid Glass](#liquid-glass) | Replaces the blur behind the floating tab bar and the mini player with clear Liquid Glass: refracted edges, vibrancy and a specular rim, without blur. Refraction requires Android 13, vibrancy Android 12. | • Blur radius<br>• Refraction width<br>• Refraction strength<br>• Vibrancy<br>• Tint opacity<br>• Edge highlight<br>• Chromatic aberration<br>• Search bar |

</details>

<!-- PATCHES_END -->

## How it works

Apple Music 7.0 already blurs what is under the tab bar and the mini player with the BlurView library: a `BlurTarget` records the screen into a `RenderNode`, and pill-shaped `BlurView`s draw it blurred. The patch adds one call at the start of `BlurView.draw(Canvas)`, a method that keeps its name through obfuscation, and the bundled extension draws the glass instead:

```mermaid
flowchart LR
    A["Window background<br/>+ screen content<br/>(BlurTarget RenderNode)"] --> B["Vibrancy<br/>saturation"]
    B --> C["Blur<br/>(off by default)"]
    C --> D["Lens<br/>AGSL refraction"]
    D --> E["Clip to the pill"]
    E --> F["Apple's tint, faded<br/>+ icons and text"]
    F --> G["Edge highlight"]
```

The window background goes first, as in Apple's blur, so the glass is opaque: what is right behind its edges does not show through, only the content the lens bends there. Apple's translucent material is faded on every frame, because Apple sets it again whenever the colors of the player change, and the rim is drawn over it. If anything goes wrong (an unknown view, Android 11, an error), the extension hands drawing back to Apple's blur, so the app never crashes because of the patch.

Full write-up: [PLAN.md](PLAN.md) and [the Apple Music 7.0.0-beta analysis](docs/apple-music-7.0.0-beta.md).

## FAQ

<details>
<summary><b>Nothing changed after patching</b></summary>
<br>

The glass needs Android 12 (vibrancy and highlight) or 13 (refraction); on Android 11 the patch stays inactive. Check that Liquid Glass was selected and that the APK is Apple Music 7.x. If it still looks the same, the extension logs the reason under the `LiquidGlass` tag:

```bash
adb logcat -s LiquidGlass
```

Open an [issue](https://github.com/myvoow/LiquidMusic/issues) with that output.
</details>

<details>
<summary><b>I can't sign in to my Apple account</b></summary>
<br>

That is the Play Integrity risk described above. Reinstall the official app from the Play Store. [PLAN.md](PLAN.md#5-plan-b-if-play-integrity-blocks-lsposed-module-root) describes a plan B that does not re-sign the app: an LSPosed module, which needs root.
</details>

<details>
<summary><b>Does it work with other Apple Music versions?</b></summary>
<br>

It was built and checked against 7.0.0-beta. It only relies on names that survive obfuscation (`BlurView.draw` and resource ids), so later 7.x versions are expected to work. Morphe shows them as experimental.
</details>

<details>
<summary><b>Scrolling stutters</b></summary>
<br>

The extra work is limited to the area of the pills, and the default glass has no blur. If your phone struggles, patch again with Refraction width set to Off, and Blur radius left Off.
</details>

## Credits

- [Backdrop (AndroidLiquidGlass)](https://github.com/Kyant0/AndroidLiquidGlass) by Kyant: the AGSL shaders and the parameters of the effect.
- [Morphe](https://morphe.software): the patcher, the manager and the patch template.
- [BlurView](https://github.com/Dimezis/BlurView) by Dimezis: the blur library inside Apple Music that the patch builds on.

<details>
<summary><b>Development</b></summary>
<br>

The project follows the official [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template):

```
patches/              # Morphe patches (Kotlin) → patches-<version>.mpp
extensions/           # Android code bundled into the patched app (Java) → liquidglass.mpe
tools/*.py            # APK analysis with no dependencies (Python stdlib)
tools/glass-preview/  # renders the glass on the JVM, without a phone
docs/                 # analysis of each Apple Music version
```

**Previewing the glass.** `./gradlew -p tools/glass-preview testDebugUnitTest` draws the tab bar and the mini player over album covers, with Apple's blur and with the glass, and writes the images to `tools/glass-preview/build/preview/`. Robolectric runs Android's real renderer (hwui), so RenderNode, RenderEffect and the AGSL shaders really execute. The views stand in for Apple Music's: same class names, same fields, same resources. The test also fails if what is right behind the glass shows through it, or if Apple's tint is not faded after a color change. Both bugs reached a phone in 1.0.1. It needs an Android SDK, and nothing from Apple.

**Building locally.** Run `./gradlew buildAndroid`. You need an Android SDK and a GitHub token with `read:packages` in `~/.gradle/gradle.properties` (`gpr.user`, `gpr.key`) to download the Morphe plugin. The output is `patches/build/libs/patches-*.mpp`, which you can test with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).

**Publishing.** The repository must stay public, because Morphe Manager reads `patches-bundle.json` without authentication. Every push to `dev` (pre-release) or `main` (stable) runs `release.yml`, which builds the `.mpp`, creates the GitHub Release and updates `patches-bundle.json`. Use semantic commit messages (`feat:`, `fix:`, `chore:`), and do not edit `patches-bundle.json`, `patches-list.json` or `CHANGELOG.md` by hand.

**Analysing a new Apple Music version.**

```bash
python3 -I tools/apk_triage.py "Apple Music.apkm" --json triage.json
python3 -I tools/apk_res.py "Apple Music.apkm" layout res/layout/fragment_music_content.xml
python3 -I tools/dex_inspect.py "Apple Music.apkm" class 'Leightbitlab/com/blurview/BlurView;' --code
```
</details>

## License

GPLv3, see [LICENSE](LICENSE). The shaders ported from Backdrop stay under Apache-2.0, and the Morphe template adds a naming condition ([NOTICE](NOTICE)).

LiquidMusic is an independent project, compatible with Morphe and not affiliated with Morphe or Apple. Apple Music is a trademark of Apple Inc. This repository contains no part of the Apple Music app: you patch your own copy.
