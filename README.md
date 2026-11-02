# LiquidMusic Patches

Patches for [Morphe](https://morphe.software) that give Apple Music on Android a real Liquid Glass effect: refracted edges, blur, vibrancy and a specular highlight. The effect covers the floating tab bar, the mini player and, optionally, the search bar.

The rendering is ported from [Backdrop / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) by Kyant.

## Usage

Add this repository as a patch source in Morphe: tap the button below, or add `https://github.com/myvoow/LiquidMusic` by hand in Morphe Manager.

<a href="https://morphe.software/add-source?github=myvoow/LiquidMusic">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

1. Add the source (button above). For test builds (the `dev` branch), enable pre-releases for this source in Morphe.
2. Pick Apple Music, provide the APKM (tested with 7.0.0-beta) and leave the Liquid Glass patch selected.
3. Adjust the options if you want: blur, refraction width and strength, vibrancy, opacity of Apple's tint, highlight, chromatic aberration, search bar.
4. Uninstall the official Apple Music (its signature is different), then install the patched app.

| Android | Result |
|---|---|
| 13+ | Full Liquid Glass (AGSL refraction, blur, vibrancy, highlight) |
| 12 | Blur, vibrancy and highlight, without refraction |
| 11 | Apple's original look (the patch does nothing) |

> [!WARNING]
> Morphe re-signs the app, and Apple Music sends a Play Integrity token when you sign in and when you pay (see [the analysis](docs/apple-music-7.0.0-beta.md#4-security-and-the-non-root-path)). Apple may therefore refuse a re-signed copy at sign-in or when you subscribe. This has not been tested yet: try it before you delete your downloads.

## Patches

<!-- PATCHES_START EXPANDED -->
> **[v1.0.1](https://github.com/myvoow/LiquidMusic/releases/tag/v1.0.1)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
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
| [Liquid Glass](#liquid-glass) | Replaces the blur behind the floating tab bar and the mini player with Liquid Glass: refracted edges, blur, vibrancy and a specular rim. Refraction requires Android 13, blur and vibrancy Android 12. | • Blur radius<br>• Refraction width<br>• Refraction strength<br>• Vibrancy<br>• Tint opacity<br>• Edge highlight<br>• Chromatic aberration<br>• Search bar |

</details>

<!-- PATCHES_END -->

## How it works

Apple Music 7.0 already blurs the content under the tab bar and the mini player with the BlurView v3 library. A `BlurTarget` records the screen into a `RenderNode`, and pill-shaped `BlurView`s (`bottom_nav_blur`, `mini_player_blur`) blur it.

The patch:

1. injects a call at the start of `BlurView.draw(Canvas)`. The class and the method keep their names through obfuscation, which makes the patch robust to app updates;
2. bundles an extension (`extensions/liquidglass`) that draws the glass instead of the blur. It reuses the `RenderNode` of the `BlurTarget`, applies Backdrop's chain to it (saturation → blur → AGSL lens), then draws a highlight along the edge;
3. fades Apple's `bg_apple_material` tint (55 to 60% opaque) so the glass shows through.

If anything goes wrong (unknown view, Android too old, an error), the extension hands drawing back to the original blur, so the app never crashes because of the patch.

Details: [PLAN.md](PLAN.md) and [docs/apple-music-7.0.0-beta.md](docs/apple-music-7.0.0-beta.md).

## Development

The project follows the official [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template):

```
patches/      # Morphe patches (Kotlin) → patches-<version>.mpp
extensions/   # Android code bundled into the patched app (Java) → liquidglass.mpe
tools/        # APK analysis with no dependencies (Python stdlib)
docs/         # analysis of each Apple Music version
```

### Building locally

Run `./gradlew buildAndroid`. You need an Android SDK and a GitHub token with `read:packages` in `~/.gradle/gradle.properties` (`gpr.user`, `gpr.key`) to download the Morphe plugin. The output is `patches/build/libs/patches-*.mpp`, which you can test with [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).

### Publishing

The repository must be public, because Morphe Manager reads `patches-bundle.json` without authentication. Every push to `dev` (pre-release) or `main` (stable) runs `release.yml`, which builds the `.mpp`, creates the GitHub Release and updates `patches-bundle.json`, the file Morphe Manager reads. Create `dev` before the first release on `main`. Use semantic commit messages (`feat:`, `fix:`, `chore:`), and do not edit `patches-bundle.json`, `patches-list.json` or `CHANGELOG.md` by hand.

### Analysing a new Apple Music version

```bash
python3 -I tools/apk_triage.py "Apple Music.apkm" --json triage.json
python3 -I tools/apk_res.py "Apple Music.apkm" layout res/layout/fragment_music_content.xml
python3 -I tools/dex_inspect.py "Apple Music.apkm" class 'Leightbitlab/com/blurview/BlurView;' --code
```

## License

GPLv3 ([LICENSE](LICENSE)). The shaders ported from Backdrop stay under Apache-2.0, and the Morphe template adds a naming condition (see [NOTICE](NOTICE)). This is an independent project, compatible with Morphe and not affiliated with Morphe or Apple.
