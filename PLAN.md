# LiquidMusic: real Liquid Glass in Apple Music for Android

Goal: replace the background of the floating tab bar and the floating mini player in Apple Music for Android with a real Liquid Glass effect (clear glass, refraction on the edges, saturation, highlight), as on iOS 26/27. The rendering is ported from [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (*Backdrop*, Apache-2.0).

Distribution: a [Morphe](https://morphe.software) patch published by this repository. Users add `myvoow/LiquidMusic` as a source in Morphe Manager and apply the Liquid Glass patch to their Apple Music APKM.

Detailed APK analysis: [`docs/apple-music-7.0.0-beta.md`](docs/apple-music-7.0.0-beta.md).

---

## 1. Verdict

Feasible, and implemented.

The Apple Music 7.0.0-beta APKM (versionCode 1606, minSdk 30) uses classic Views for the tab bar (`bottom_nav_view`) and the mini player (`mini_player_content`), and already blurs them with BlurView v3:

- a `BlurTarget` (`blur_target`) records the content into a `RenderNode`;
- two pill-shaped `BlurView`s (`bottom_nav_blur`, `mini_player_blur`) blur it;
- the near-opaque look comes from a 64 px blur (radius 16 × scale factor 4) of the window background and the content, covered by two tint layers at 55-60% (`bg_apple_material`).

The patch keeps this setup. It only replaces how the BlurViews draw, with Liquid Glass, and lightens the tint.

By Android version:

- 13+: full effect;
- 12: vibrancy and highlight, without refraction;
- 11: Apple's original look.

### Main risk: Play Integrity

Morphe re-signs the APK, and Apple Music sends a Play Integrity token (`AppIntegrityManager`, `appIntegrityToken`) in its account and commerce flows. Apple may therefore refuse sign-in or the subscription for a re-signed copy. On the first device test (1.0.1), sign-in worked: the Home page showed the account's own recommendations. Subscribing and paying are untested (§4). If it blocks, the same extension code can ship as an LSPosed module, which does not re-sign the APK (§5).

---

## 2. How Morphe works (from the MorpheApp repositories, November 2026)

| Part | Role |
|---|---|
| [morphe-patcher](https://github.com/MorpheApp/morphe-patcher) 2.1.0 | Library that applies patches: bytecode (smali/dexlib2), resources, raw files. Hard fork of ReVanced Patcher, package `app.morphe.*` |
| [morphe-patches-gradle-plugin](https://github.com/MorpheApp/morphe-patches-gradle-plugin) `app.morphe.patches` 2.1.0 | Sets up the project: `patches/` (Kotlin, built into a `.mpp` = jar + dex via D8) and `extensions/*` (Android modules built into `.mpe` dex files, bundled inside the `.mpp`). Hosted on GitHub Packages, so it needs a `read:packages` token (in Actions, `GITHUB_TOKEN` is enough) |
| [morphe-patches-template](https://github.com/MorpheApp/morphe-patches-template) | Starting point for a third-party patch repository; this repository uses its layout |
| `release.yml` + `.releaserc` (semantic-release) | On every push to `main` (stable) or `dev` (pre-release) with `feat:` / `fix:` commits: builds `patches-<v>.mpp`, creates the GitHub Release, updates `patches-bundle.json`, `patches-list.json`, `CHANGELOG.md` and the patch list in the README |
| [Morphe Manager](https://github.com/MorpheApp/morphe-manager) | For an `owner/repo` source, reads `https://raw.githubusercontent.com/owner/repo/main/patches-bundle.json` (or `/dev/` when pre-releases are enabled). The file holds `version`, `download_url` (the `.mpp` of the Release), `created_at`, `description` and, optionally, `public_key`. Link to add a source: `https://morphe.software/add-source?github=owner/repo` |
| Signing (optional) | `./gradlew generateSigningKey`, then the `MORPHE_SIGNING_KEY` secret in Actions. The Manager remembers the key and then refuses a modified Release |

Patcher concepts:

- Patch: `bytecodePatch(name, description, default) { compatibleWith(...); extendWith("x.mpe"); execute { ... } }`.
- Options: `intOption`, `booleanOption`… adjustable in the Manager.
- `Compatibility`: package, name, expected file type (`APKM`…), icon color, target versions (`AppTarget`; `version = null` means "experimental, any version").
- `Fingerprint`: a partial description of a method (class, name, types, instruction filters), used to find it despite obfuscation.
- Extension: precompiled dex merged into the app and called from injected smali (`addInstructions`, `addInstructionsWithLabels`, `returnEarly`…).
- License: GPLv3, and a derivative may not use the name "Morphe". This repository is therefore called LiquidMusic Patches, "compatible with Morphe".

---

## 3. What is implemented

```
patches/src/main/kotlin/app/liquidmusic/patches/applemusic/
├── shared/Constants.kt                 # Apple Music Compatibility (APKM, 7.0.0-beta + experimental)
└── layout/liquidglass/
    ├── Fingerprints.kt                 # BlurViewDrawFingerprint: BlurView.draw(Canvas) + super.draw call
    └── LiquidGlassPatch.kt             # "Liquid Glass" patch + 8 options
extensions/liquidglass/src/main/java/app/liquidmusic/extension/
├── LiquidGlassPatch.java               # entry point drawBlurView(View, Canvas) + option getters
├── GlassRenderer.java                  # glass rendering (RenderNode, RenderEffect, AGSL), tint, position tracking
├── GlassRim.java                       # specular rim, drawn in the overlay of the view over the glass
├── BlurViewAccess.java                 # finds the obfuscated fields of BlurView v3 by type
└── GlassShaders.java                   # AGSL shaders ported from Backdrop (refraction, dispersion, highlight)
tools/glass-preview/                    # renders the glass on the JVM (Robolectric + hwui), with stand-ins for Apple's views
```

### The patch (bytecode)

`BlurView.draw(Canvas)` keeps its name: layouts reference the class, and the method overrides `View.draw`. It becomes:

```java
public final void draw(Canvas canvas) {
    if (LiquidGlassPatch.drawBlurView(this, canvas)) {   // injected
        super.draw(canvas);                              // background + children
        return;
    }
    ...original code (Apple's blur)
}
```

The Manager options reach the extension: the patch replaces the bodies of its getters with `returnEarly(value)`.

### Options

| Option | Default | Effect |
|---|---|---|
| Blur radius | 0 (Off) | Blur of the content behind the glass. Off since 1.1.0: Liquid Glass stays clear, as on iOS 26 |
| Refraction width | 24 dp | Width of the bent band along the edges (0 = no refraction) |
| Refraction strength | 24 dp | How far the content is displaced at the edge |
| Vibrancy | 150% | Saturation of the content |
| Tint opacity | 40% | Share of Apple's `bg_apple_material` tint that is kept |
| Edge highlight | 100% | Intensity of the rim light |
| Chromatic aberration | off | Prism-like dispersion of the edges (Android 13+) |
| Search bar | on | Also applies the glass to `floating_search_bar_blur` and `top_nav_blur` (tablet) |

### The extension (runs inside Apple Music)

1. Target: the resource name of the BlurView. Always `bottom_nav_blur` and `mini_player_blur`; optionally `floating_search_bar_blur` and `top_nav_blur`. Other BlurViews, such as `drawer_blur`, keep Apple's blur.
2. Source: the `BlurTarget` that Apple set up. The extension reads it from the controller of the BlurView (the only field with an interface type), or else looks for a neighbouring `BlurTarget`. It then takes the `RenderNode` field of the target, found by type.
3. Rendering:
   - a `RenderNode` the size of the pill plus one blur radius, which first draws the window background (as Apple's "frame clear" drawable does), then references the node of the BlurTarget at the right offset;
   - the chain `saturation → createBlurEffect → createRuntimeShaderEffect(refraction)`, the blur only when its option is on (off by default);
   - clipping to the shape (radius read from the `GradientDrawable` background, otherwise a capsule);
   - Backdrop's highlight: a 0.5 dp stroke, AGSL shader, `BlendMode.PLUS`, in the overlay of `bottom_nav_view` / `mini_player_content`, so Apple's tint does not dim it.
4. Tint: the `material_layer` and `artwork_color_scheme_layer` layers of the `bottom_nav_view` / `mini_player_content` background go to the chosen opacity, checked before every frame.
5. Movement: an `OnPreDrawListener` redraws the pill when its position changes (mini player animation). Scrolling content needs nothing extra, since it propagates through the `RenderNode`.
6. Safety: on Android < 12, on a software canvas, for an unknown view or after an exception, the extension returns `false` and BlurView draws its original blur. The patch cannot crash the app.

---

## 4. Remaining work

1. Build: done. CI (`release.yml`, step "Verify project compiles") passes. Run #1 ended with `BUILD SUCCESSFUL`: Java extension compiled and dexed, Kotlin patch compiled against Morphe 2.1.0, `.mpp` produced.
2. Make the repository public (Settings › General › Danger Zone › Change visibility). Morphe Manager reads `raw.githubusercontent.com/myvoow/LiquidMusic/<branch>/patches-bundle.json` and downloads the `.mpp` from Releases without authentication, so it cannot see a private repository.
3. Actions settings (Settings › Actions › General › Workflow permissions):
   - "Read and write permissions";
   - tick "Allow GitHub Actions to create and approve pull requests" (for `open_pull_request.yml`).
4. First release, in this order:
   - create the `dev` branch from this branch. CI then publishes the pre-release `v1.0.0-dev.1`, fills in `patches-bundle.json` on `dev` and opens a `dev → main` PR;
   - merge that PR (a plain merge, not a squash). CI publishes `v1.0.0` on `main`.

   `dev` must exist before any release of `main`: the backmerge plugin runs `checkout dev` after every stable release.
5. Device test (Android 13+), in this order:
   - Apple ID sign-in and playback on the patched APK (Play Integrity). With 1.0.1, the patched app installed and signed in; playback is still to confirm;
   - rendering of the tab bar and the mini player (scrolling, opening and closing the player, artwork change, light and dark);
   - performance (`adb shell dumpsys gfxinfo com.apple.android.music framestats`);
   - Android 12 (no refraction).

   What the 1.0.1 screenshot showed, and how 1.0.2 fixes it:
   - the content under the tab bar was sharp. The glass was drawn, but transparent: BlurTarget only records its children, and Apple's blur gets its opaque base from the window background (`setFrameClearDrawable(decorView.background)`). The glass now draws that background first;
   - Apple's tint was back at full opacity. Apple replaces `material_layer` and resets the alpha of `artwork_color_scheme_layer` whenever the colors of the player change (`Aa.a0.c`), after the extension had faded them once. The fade is now checked before every frame;
   - the rim was barely visible, since it was drawn under that tint. It now sits in the overlay of the view drawn over the glass.

   `tools/glass-preview` reproduces both rendering bugs with the 1.0.1 code, and its test fails on them.
6. Tune the default values after comparing with iOS 26.
7. Optional:
   - sign the Releases (`generateSigningKey`);
   - add a `patches-bundle.png` icon;
   - add the liquid selection drop under the active tab (port of `LiquidBottomTabs`).

---

## 5. Plan B if Play Integrity blocks: LSPosed module (root)

Without re-signing, Apple Music keeps its original signature and passes Play Integrity (if the device passes it). The module only has to:

- hook `eightbitlab.com.blurview.BlurView.draw(Canvas)` with Xposed;
- call the same `LiquidGlassPatch.drawBlurView(view, canvas)`;
- if the result is `true`, call the original `View.draw` and skip the original code.

`extensions/liquidglass` can be reused as is. The options would come from the preferences of the module instead of `returnEarly`.

---

## 6. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Play Integrity on a re-signed APK | Sign-in or subscription refused | Test it first; otherwise plan B (LSPosed) |
| Google Play purchases on a re-signed APK | In-app payment unavailable | Subscribe beforehand, or from another device |
| Apple changes its blur library or layout (7.0 is a beta) | The patch fails (fingerprint) or does nothing | Re-analyse with `tools/`; fingerprints on stable names |
| GPU cost | Stutter on low-end devices | Node limited to the pill plus a margin; options to reduce blur or refraction |
| Legal | Takedown | No APK and no Apple code in the repository; users patch their own copy |
