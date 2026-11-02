# LiquidMusic : du vrai Liquid Glass dans Apple Music Android

Objectif : remplacer le fond de la **barre d'onglets flottante** (Accueil / Nouveautés / Radio / Bibliothèque / Recherche) et du **mini-player flottant** d'Apple Music Android par un vrai effet Liquid Glass : flou, réfraction sur les bords, saturation, reflet, comme sur iOS 26/27. Le rendu est porté de [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (bibliothèque *Backdrop*, Apache-2.0).

Analyse détaillée de l'APK : [`docs/apple-music-7.0.0-beta.md`](docs/apple-music-7.0.0-beta.md).

---

## 1. Verdict : **faisable, et plus simple que prévu**

L'APKM **Apple Music 7.0.0-beta** (versionCode 1606, minSdk 30, targetSdk 36) a été analysé.

- La barre (`bottom_nav_view`, une `BottomNavigationView`) et le mini-player (`mini_player_content`) sont des **vues Android classiques**, pas du Compose.
- Apple utilise déjà **BlurView v3** : un `BlurTarget` (`blur_target`) enregistre tout le contenu dans un `RenderNode`, et deux `BlurView` en forme de pilule (`bottom_nav_blur`, `mini_player_blur`) le floutent sous la barre et le mini-player.
- L'aspect « à peine translucide » vient d'un **flou de 16 px seulement**, recouvert de **deux couches de teinte à 55-60 %** (`bg_apple_material`).

On garde donc toute l'infrastructure d'Apple. Il suffit de :

1. **remplacer l'effet** des deux BlurView (flou → saturation + flou + réfraction AGSL + reflet) ;
2. **éclaircir la teinte** posée par-dessus.

| | Avant analyse | Après analyse |
|---|---|---|
| Cas | A (Views) ou B (Compose) ? | **A confirmé**, infrastructure de flou déjà présente |
| Effort | 1 à 2 semaines (A) / 3 à 6 (B) | **~1 semaine** pour une première version |
| Robustesse | Moyenne | Bonne : hooks sur noms stables (`BlurView.draw`, ids de ressources) et champs retrouvés par type |

Selon la version d'Android :

- **Android 13+ (API 33)** : rendu complet, avec la réfraction (shaders AGSL) ;
- **Android 12 (API 31-32)** : flou et saturation, sans réfraction ;
- **Android 11 (API 30, minimum de l'app)** : rendu Apple d'origine.

### Point d'attention : Play Integrity

Apple Music envoie un jeton **Play Integrity** (`AppIntegrityManager`, `appIntegrityToken`) dans ses flux compte et commerce. Un APK **re-signé** sera vu comme « non reconnu », ce qui fera probablement échouer la connexion ou l'abonnement. Il faut donc le **module LSPosed avec root**, qui ne modifie pas l'APK. La voie sans root (LSPatch) reste à tester, mais elle a peu de chances de marcher.

---

## 2. Ce qu'on reprend d'AndroidLiquidGlass

Repo analysé : `Kyant0/AndroidLiquidGlass` @ `65ab177`, artefact `io.github.kyant0:backdrop:2.0.1`.

La bibliothèque est **Compose uniquement** (`Modifier.layerBackdrop` / `Modifier.drawBackdrop`), donc inutilisable telle quelle dans les vues d'Apple. On **porte ses algorithmes** vers l'API `android.graphics` :

| Backdrop | Portage |
|---|---|
| `vibrancy()` (matrice de saturation ×1,5) | `RenderEffect.createColorFilterEffect(ColorMatrixColorFilter)` |
| `blur(r)` | `RenderEffect.createBlurEffect(r, r, CLAMP)` |
| `lens(height, amount, chromaticAberration)` : `RoundedRectRefractionShaderString` / `…WithDispersion` (`internal/Shaders.kt`) | `RuntimeShader` AGSL + `RenderEffect.createRuntimeShaderEffect(shader, "content")` ; uniforms `size`, `offset`, `cornerRadii`, `refractionHeight`, `refractionAmount` (négatif), `depthEffect` |
| `Highlight` (`DefaultHighlightShaderString`, `AmbientHighlightShaderString`) | shader AGSL dessiné avec un `Paint` sur le contour de la pilule |
| `Shadow` / `InnerShadow` | `Paint` + `BlurMaskFilter`, ou ombre d'élévation existante |
| Exemple `LiquidBottomTabs` : `vibrancy → blur(8dp) → lens(24dp, 24dp)`, teinte `#121212` à 40 % (sombre) / `#FAFAFA` à 40 % (clair) | Valeurs par défaut du mod, réglables |
| `DampedDragAnimation`, `InteractiveHighlight` (goutte de sélection, reflet sous le doigt) | Phase 3 (optionnelle) |

Il faut conserver la mention de copyright de Kyant et un fichier `NOTICE` (Apache-2.0).

---

## 3. Architecture

```
LiquidMusic/
├── glass/            # Bibliothèque Android (Views, sans Compose) : moteur Liquid Glass
│   ├── GlassShaders.kt          # AGSL portés de Backdrop (réfraction, dispersion, highlight)
│   ├── GlassEffect.kt           # construit la chaîne RenderEffect (API 31 / 33)
│   └── LiquidGlassRenderer.kt   # dessine une pilule de verre à partir d'un RenderNode source
├── sample/           # App de démo : RecyclerView de pochettes + BlurTarget/pilules (phase 1)
├── xposed/           # Module LSPosed, scope com.apple.android.music
│   ├── Entry.kt                 # IXposedHookLoadPackage
│   ├── BlurViewHook.kt          # hook BlurView.draw + proxy du contrôleur
│   ├── TintHook.kt              # éclaircit bg_apple_material
│   └── settings/                # réglages (intensité, flou, teinte, pilules concernées)
├── tools/            # Analyse d'APK sans dépendance (Python stdlib)
│   ├── apk_triage.py            # vue d'ensemble : SDK, Compose, widgets, sécurité, natif
│   ├── apk_res.py               # resources.arsc + layouts compilés en clair
│   └── dex_inspect.py           # classes / champs / méthodes / appels dans les DEX
└── docs/apple-music-<version>.md   # rapport d'analyse par version
```

### Fonctionnement du hook (détails et noms dans `docs/`)

```kotlin
// Dans le process com.apple.android.music (LSPosed, API Xposed 82)
val blurView = XposedHelpers.findClass("eightbitlab.com.blurview.BlurView", cl)
val blurTarget = XposedHelpers.findClass("eightbitlab.com.blurview.BlurTarget", cl)
val controllerField = blurView.declaredFields.single { it.type.isInterface }.apply { isAccessible = true }
val targetNodeField = blurTarget.declaredFields.single { it.type == RenderNode::class.java }.apply { isAccessible = true }
val drawMethod = controllerField.type.methods.single {
    it.returnType == Boolean::class.javaPrimitiveType && it.parameterTypes.contentEquals(arrayOf(Canvas::class.java))
}

XposedHelpers.findAndHookMethod(blurView, "draw", Canvas::class.java, object : XC_MethodHook() {
    override fun beforeHookedMethod(p: MethodHookParam) {
        val view = p.thisObject as View
        if (view.id !in targetIds || Build.VERSION.SDK_INT < 31) return
        val current = controllerField.get(view)
        if (!Proxy.isProxyClass(current.javaClass)) {
            controllerField.set(view, glassProxy(view, delegate = current))   // ré-enveloppe après chaque setupWith
        }
    }
})

fun glassProxy(view: View, delegate: Any): Any =
    Proxy.newProxyInstance(cl, arrayOf(controllerField.type)) { proxy, m, args ->
        if (m == drawMethod) {
            val canvas = args[0] as Canvas
            if (canvas.isHardwareAccelerated) renderers.getOrPut(view) { LiquidGlassRenderer(view) }
                .draw(canvas, source = findBlurTarget(view), sourceNode = targetNodeField.get(target) as RenderNode)
            true                                             // BlurView dessine ensuite son fond et ses enfants
        } else {
            val r = m.invoke(delegate, *(args ?: emptyArray()))
            if (r === delegate) proxy else r                 // API fluide de BlurView
        }
    }
```

Côté rendu, `LiquidGlassRenderer.draw` :

1. **Nœud de capture** : un `RenderNode` de la taille de la pilule + marge (rayon de flou). Il enregistre `translate(-(pos pilule − pos cible) + marge)` puis `drawRenderNode(sourceNode)`. C'est un seul appel : le contenu de la cible est déjà enregistré par Apple à chaque frame.
2. **Chaîne d'effets** : `createChainEffect(lens, createChainEffect(blur, saturation))`, comme dans Backdrop. Le shader AGSL reçoit `size` = taille de la pilule, `offset` = −marge et `cornerRadii` = min(h/2, 80dp).
3. **Dessin** : on dessine le nœud découpé au `Path` capsule, puis le reflet (highlight AGSL) et la teinte réglable.
4. **Mise à jour** : un `OnPreDrawListener` invalide la BlurView si sa position change (animation du mini-player, clavier, rotation).

**Teinte** : `bottom_nav_view.background` et `mini_player_content.background` sont des `LayerDrawable`. On récupère `material_layer` et `artwork_color_scheme_layer` (ids via `getIdentifier`) et on baisse leur alpha, par exemple à 40 %, ou on les remplace par la teinte Backdrop. On le vérifie à chaque attachement, car Apple peut reconstruire le fond quand la pochette change.

---

## 4. Plan de travail

### ✅ Phase 0 : analyse (faite)

- [x] Triage de l'APKM 7.0.0-beta (`tools/apk_triage.py`).
- [x] Hiérarchie de `fragment_music_content.xml` et `mini_player.xml` (`tools/apk_res.py`).
- [x] Identification de BlurView v3 et de ses noms obfusqués (`tools/dex_inspect.py`).
- [x] Repérage de Play Integrity et des bibliothèques natives (DRM).
- [ ] Sur un appareil : confirmer avec `adb shell dumpsys activity top` (ou Developer Assistant) que les ids `bottom_nav_blur` / `mini_player_blur` / `mini_player_content` sont bien affichés à l'écran d'accueil.

### Phase 1 : moteur de verre + démo (2 à 3 jours)

1. `glass/` : porter les shaders (`Shaders.kt` de Backdrop), la chaîne d'effets et le reflet.
2. `sample/` : reproduire la structure d'Apple, avec un `BlurTarget`-like (FrameLayout qui enregistre ses enfants dans un `RenderNode`), un `RecyclerView` de pochettes colorées, une pilule « barre » de 62dp et une pilule « mini-player » de 48dp.
3. Valider :
   - même rendu que l'APK catalogue de Backdrop ;
   - thème clair et sombre ;
   - fluidité au défilement (`dumpsys gfxinfo … framestats`) ;
   - replis API 31-32 et 30.

### Phase 2 : module LSPosed (2 à 3 jours)

1. Squelette du module : `compileOnly("de.robv.android.xposed:api:82")`, `assets/xposed_init`, scope `com.apple.android.music`. Il embarque `glass/` (pas de Compose, module léger).
2. `BlurViewHook` : proxy du contrôleur sur `bottom_nav_blur` et `mini_player_blur` (voir §3).
3. `TintHook` : éclaircit `bg_apple_material` sur `bottom_nav_view` et `mini_player_content`.
4. Tests :
   - défilement de chaque onglet ;
   - ouverture et fermeture du lecteur plein écran (bottom sheet) ;
   - mini-player caché quand rien ne joue ;
   - changement de pochette (couche `artwork_color_scheme`) ;
   - mode sombre ou clair, rotation, tablette (`layout-w600dp`) ;
   - stabilité sur longue durée (fuites de `RenderNode`).
5. Sécurité : si un id ou un champ est introuvable, on n'installe rien et on écrit un log. Le pire cas est le rendu Apple d'origine, jamais un crash.

### Phase 3 : finitions « comme iOS » (optionnel, 3 à 5 jours)

- Étendre le verre à `floating_search_bar_blur` (barre de recherche) et `top_nav_blur`.
- **Goutte de sélection** liquide sous l'onglet actif : port de `LiquidBottomTabs`, `DampedDragAnimation` et `InteractiveHighlight`. On suit l'onglet sélectionné via `View.isSelected()` des items de `bottom_nav_view` (API du framework, donc pas de nom obfusqué).
- Reflet qui suit le doigt, léger grossissement à l'appui.
- Écran de réglages : intensité de la réfraction, rayon de flou, opacité de la teinte, aberration chromatique, pilules concernées.

### Phase 4 : distribution et maintenance

- GitHub Actions : compiler l'APK du module et le publier en Release.
- `README` : installation via LSPosed et tableau de compatibilité par version d'Apple Music.
- À chaque version d'Apple Music, relancer les trois scripts de `tools/`. Les points d'accroche sont choisis pour survivre à l'obfuscation (`BlurView.draw`, ids de ressources, champs trouvés par type), donc en général rien à changer.
- **Ne jamais publier d'APK Apple Music**, modifié ou non : uniquement le module.

---

## 5. Risques

| Risque | Impact | Parade |
|---|---|---|
| Play Integrity sur APK re-signé | Pas de version sans root fiable | Support officiel : LSPosed (root) uniquement ; LSPatch « non supporté » |
| Détection de root (`/system/xbin/su`) ou d'intégrité de l'appareil | L'app peut limiter des fonctions | Masquage côté LSPosed (Shamiko / HMA), à tester |
| Apple change de bibliothèque de flou ou de layout (la 7.0 est en bêta) | Le mod ne s'applique plus, sans crash | Repli silencieux ; réanalyse avec `tools/` ; garder un plan B qui pose notre propre vue de verre au même endroit |
| Coût GPU (saturation + flou + AGSL à chaque frame) | Saccades sur petits appareils | Nœud limité à la pilule + marge, réglages, désactivation par pilule |
| Android 11-12 | Pas de réfraction | Rendu Apple (11) ou flou seul (12) |
| Juridique (CGU Apple, droit d'auteur) | Retrait du projet | Usage personnel, module uniquement, aucun binaire Apple redistribué |

---

## 6. Prochaine étape

Phase 1 : écrire `glass/` et l'app `sample/`. Pour ça, il faut un projet Gradle Android dans ce repo (AGP, Kotlin, minSdk 30, compileSdk 36).
