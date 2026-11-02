# LiquidMusic : du vrai Liquid Glass dans Apple Music Android

Objectif : remplacer le fond de la **barre d'onglets flottante** (Accueil / Nouveautés / Radio / Bibliothèque / Recherche) et du **mini-player flottant** (« dans paris j'fume av… — spookyghost333 & GENSEKI ») d'Apple Music Android par un vrai effet Liquid Glass : flou, réfraction sur les bords, saturation, reflet et ombre, comme sur iOS 26/27. Le rendu vient de [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (bibliothèque *Backdrop*).

---

## 1. Verdict

**Oui, c'est possible.** La difficulté dépend d'une seule question, qu'on tranche en phase 0 : **la barre et le mini-player sont-ils des `View` Android classiques ou du Jetpack Compose ?**

| Cas | Faisabilité | Effort | Robustesse aux mises à jour |
|---|---|---|---|
| **A. Views** (layout XML, ids de ressources) | Élevée | ~1 à 2 semaines | Bonne : on cible des ids de ressources |
| **B. Compose** (barre dessinée dans un `ComposeView`) | Moyenne | ~3 à 6 semaines | Faible : il faut des signatures sur du code obfusqué par R8 |

Quel que soit le cas :

- **Android 13+ (API 33)** : rendu complet, avec la réfraction (shaders AGSL `RuntimeShader`).
- **Android 12 (API 31-32)** : flou et teinte seulement, sans réfraction.
- **Android ≤ 11** : on garde le rendu d'origine.
- **Le mod ne se distribue pas sous forme d'APK Apple Music modifié.** On publie un **module LSPosed** (notre code, plus le code Apache-2.0 de Backdrop). Chacun l'applique à sa propre copie de l'app.

### Ce qu'on sait déjà d'Apple Music

- La refonte « Liquid Glass » d'Apple Music **7.0** (bêta, octobre 2026) a déjà une barre d'onglets flottante en pilule et un mini-player flottant détaché, avec **« un peu de translucidité »**. Il n'y a ni flou ni réfraction ([9to5Google](https://9to5google.com/2026/09/30/apple-music-7-0-redesign-liquid-glass/), [iPhoneSoft](https://iphonesoft.fr/2026/10/03/apple-music-android-se-liquid-glass), [Android Headlines](https://www.androidheadlines.com/2026/10/apple-music-gets-liquid-glass-design-on-android.html)).
- C'est une bonne nouvelle pour le mod. **La mise en page existe déjà** : deux pilules qui flottent au-dessus du contenu qui défile. On ne change que *la façon dont leur fond est dessiné*, pas la structure de l'écran.
- La version est encore en bêta, donc les ids et la structure peuvent changer avant la version stable. Il faut cibler la 7.0 stable et faire des hooks tolérants.

---

## 2. Ce que fait (et ne fait pas) AndroidLiquidGlass

Repo analysé : `Kyant0/AndroidLiquidGlass` @ `65ab177`, artefact `io.github.kyant0:backdrop:2.0.1`, licence **Apache-2.0**.

### Comment ça marche

1. **Capture du fond** : `Modifier.layerBackdrop(backdrop)` enregistre le contenu qui se trouve *derrière* la vitre dans un `GraphicsLayer` (un `RenderNode` sous le capot).
2. **Dessin de la vitre** : `Modifier.drawBackdrop(backdrop, shape, effects, highlight, shadow, innerShadow)` relit ce calque, le décale à la position de la vitre (`LayerBackdrop.drawBackdrop`), le découpe à la forme (`Capsule()`) et applique une chaîne de `RenderEffect` :
   - `vibrancy()` : matrice de couleur, saturation ×1,5 (API 31+) ;
   - `blur(r)` : `RenderEffect.createBlurEffect` (API 31+) ;
   - `lens(height, amount, chromaticAberration)` : shader AGSL `RoundedRectRefractionShaderString` / `…WithDispersion` dans `internal/Shaders.kt` (API 33+). Il calcule un SDF de rectangle arrondi et décale les pixels près du bord pour imiter une lentille.
3. **Finitions** : `Highlight` (liseré lumineux, shaders `DefaultHighlightShaderString` / `AmbientHighlightShaderString`), `Shadow`, `InnerShadow`, puis une teinte de surface (`onDrawSurface { drawRect(containerColor) }`).

Paramètres de l'exemple `LiquidBottomTabs` (`app/.../components/LiquidBottomTabs.kt`), proches de ce qu'on veut :

```kotlin
effects = {
    vibrancy()
    blur(8.dp.toPx())
    lens(24.dp.toPx(), 24.dp.toPx())
}
onDrawSurface = { drawRect(Color(0xFF121212).copy(alpha = 0.4f)) } // thème sombre
// + une « goutte » qui suit l'onglet sélectionné : lens(10dp, 14dp, chromaticAberration = true)
//   avec DampedDragAnimation + InteractiveHighlight pour l'effet de pression/glisser
```

### Limites importantes pour un mod

- **La bibliothèque ne marche qu'en Compose.** La source (`layerBackdrop`) et la vitre (`drawBackdrop`) doivent toutes deux être des nœuds Compose *de la même copie de Compose*. Dans un mod, on n'a pas le code source d'Apple Music. Le Compose d'Apple est obfusqué par R8 et chargé par son propre classloader, donc **on ne peut pas brancher la bibliothèque telle quelle sur l'UI d'Apple**.
- On en reprend donc **les algorithmes** : shaders AGSL, ordre des effets, paramètres, animations. On les **porte vers l'API `android.graphics` pure** (`RenderNode`, `RenderEffect`, `RuntimeShader`), qui marche avec n'importe quelle UI. La licence Apache-2.0 le permet, à condition de garder la mention de copyright et le fichier `NOTICE`.
- La bibliothèque ne fournit aucun composant prêt à l'emploi : `LiquidBottomTabs` n'est qu'un exemple de l'app catalogue.

---

## 3. Architecture retenue

```
LiquidMusic/
├── glass/            # Bibliothèque Android (Views, sans Compose) : moteur Liquid Glass
│   ├── LiquidGlassRenderer.kt   # capture RenderNode + chaîne RenderEffect
│   ├── GlassShaders.kt          # AGSL portés de Backdrop (réfraction, dispersion, highlight)
│   └── LiquidGlassDrawable.kt   # Drawable qu'on pose en fond d'une View existante
├── sample/           # App de démo (phase 1) : liste de pochettes + 2 pilules flottantes
├── xposed/           # Module LSPosed qui cible com.apple.android.music
│   ├── Entry.kt                 # IXposedHookLoadPackage
│   ├── AppleMusicTargets.kt     # ids / signatures par version d'Apple Music
│   └── settings/                # écran de réglages (intensité, flou, teinte)
└── tools/
    └── apk_triage.py            # analyse de l'APK/APKM (phase 0), déjà écrit
```

### Livraison : module LSPosed plutôt que APK patché

| Méthode | Root ? | Re-signature ? | Mises à jour Apple Music | Recommandation |
|---|---|---|---|---|
| **LSPosed + module** | oui | non | Play Store normal, le module s'adapte | **Recommandé** |
| **LSPatch** (ou fork maintenu) + même module | non | oui (contournement de signature intégré) | réinstaller et repatcher à chaque version | Possible, à valider en phase 0 |
| Patch statique (apktool + smali + DEX injecté) | non | oui | repatcher à chaque version | À éviter, sauf plus tard en patch style ReVanced |

Le même module sert pour les deux premières lignes. Le patch statique ne fait que rajouter du travail.

### Le moteur « glass » côté Views (cœur du portage)

Principe, celui de BlurView v3 appliqué aux shaders de Backdrop :

```kotlin
@RequiresApi(31)
class LiquidGlassRenderer(private val host: View, private val source: View) {
    private val node = RenderNode("LiquidGlass")
    var capturing = false; private set     // garde-fou anti-récursion

    fun draw(canvas: Canvas, shape: Path, padding: Float) {
        if (!canvas.isHardwareAccelerated) return            // fallback : fond d'origine
        val h = IntArray(2).also(host::getLocationInWindow)
        val s = IntArray(2).also(source::getLocationInWindow)

        node.setPosition(0, 0, host.width + 2 * padding.toInt(), host.height + 2 * padding.toInt())
        val rc = node.beginRecording()
        rc.translate(s[0] - h[0] + padding, s[1] - h[1] + padding)
        capturing = true
        source.draw(rc)          // ré-enregistre la display list : peu coûteux, réutilise les RenderNode enfants
        capturing = false
        node.endRecording()
        node.setRenderEffect(effectChain)   // lens(blur(vibrancy(content))), même ordre que Backdrop

        canvas.save(); canvas.clipPath(shape)
        canvas.translate(-padding, -padding); canvas.drawRenderNode(node)
        canvas.restore()
        // puis : teinte (#121212 @ 40 % en sombre), highlight (AGSL), ombre
    }
}
```

- `effectChain` reproduit `vibrancy() → blur(8dp) → lens(24dp, 24dp)` :
  - `RenderEffect.createColorFilterEffect(saturation 1.5)` ;
  - `createBlurEffect(r, r, CLAMP)` ;
  - `createRuntimeShaderEffect(refraction, "content")`, avec les uniforms `size`, `offset = -padding`, `cornerRadii = hauteur/2` sur les 4 coins (capsule), `refractionHeight`, `refractionAmount` (négatif, comme dans `Lens.kt`) et `depthEffect`.
- **Anti-récursion** : si la vitre se trouve *dans* la vue capturée, son `draw()` ne fait rien tant que `capturing == true`.
- **Mise à jour à chaque frame** : un `ViewTreeObserver.OnPreDrawListener` appelle `host.invalidate()` quand le contenu défile ou s'anime.
- **Marge de capture** : on capture `padding = rayon de flou` en plus autour de la pilule pour éviter les bords « clampés », comme le fait Backdrop.

---

## 4. Plan de travail

### Phase 0 : analyse (1 à 2 jours), **point de décision A/B**

1. **Triage statique** de l'APKM :
   ```bash
   python3 -I tools/apk_triage.py "Apple Music_7.x.apkm" --json triage.json
   ```
   Le script donne :
   - la version, `minSdk`/`targetSdk` et l'activité principale ;
   - la présence de Compose. On la détecte par les sections de trace `Compose:recompose` / `Compose:applyChanges`, qui **survivent à R8**. Testé sur l'APK catalogue de Backdrop, minifié : détection OK alors qu'il ne reste qu'un seul nom de classe `androidx.compose` ;
   - les layouts qui contiennent `ComposeView`, `BottomNavigationView`, `CoordinatorLayout`… ;
   - les noms de ressources du type `tab_bar`, `mini_player`, `now_playing`… ;
   - les bibliothèques natives ;
   - les marqueurs de sécurité : Play Integrity, `signingInfo`, détection de root ou Xposed.
2. **Vérification sur l'appareil** (avis final sur A ou B), avec l'app ouverte sur Accueil :
   ```bash
   adb shell dumpsys activity top | grep -iE "tab|nav|mini|player|ComposeView" -n
   ```
   - Si on voit des `View` avec des ids `…:id/xxx_tab_bar` et `…:id/mini_player…` → **cas A**.
   - Si on voit seulement un `AndroidComposeView` qui couvre tout l'écran → **cas B**.

   Le Layout Inspector d'Android Studio, ou `uiautomator dump`, peuvent confirmer.
3. **Décompilation ciblée** (jadx-gui) de l'activité principale, de la classe de la barre et de celle du mini-player :
   - comment la sélection d'onglet est notifiée ;
   - comment le mini-player apparaît, disparaît et s'ouvre en plein écran ;
   - quelles couleurs de fond sont appliquées, et d'où elles viennent (ressource, attribut de thème, constante).
4. **Test de re-signature** (pour le chemin sans root) :
   - re-signer `base.apk` et les splits avec la même clé (`apksigner`), puis `adb install-multiple` ;
   - vérifier connexion Apple ID, streaming, téléchargements et paroles.

   Si quelque chose casse, on ne prend en charge **que LSPosed (root)**.

**Livrable** : `docs/apple-music-<version>.md`, avec la hiérarchie des vues, les ids et classes ciblés, et le résultat du test de re-signature.

### Phase 1 : prototype du verre hors d'Apple Music (3 à 4 jours)

1. Module `glass/` : porter de Backdrop
   - `Shaders.kt` (réfraction, dispersion, highlight) ;
   - `vibrancy`, `blur` et `lens` vers `android.graphics.RenderEffect` ;
   - `Highlight` et `Shadow` vers des `Paint` ou des shaders sur `Canvas`.
2. App `sample/` qui imite l'écran de la capture : un `RecyclerView` de pochettes colorées, une pilule « barre d'onglets » et une pilule « mini-player » posées par-dessus.
3. Valider :
   - même rendu que l'APK catalogue de Backdrop, côte à côte ;
   - thème clair et sombre ;
   - 60/120 Hz tenus au défilement (`adb shell dumpsys gfxinfo <pkg> framestats`) ;
   - repli propre sur API 31-32 (sans `lens`) et ≤ 30 (sans rien).

### Phase 2 : module LSPosed, barre et mini-player (cas A : 4 à 6 jours)

1. Squelette du module :
   - API Xposed legacy 82 (`compileOnly`), compatible LSPosed et LSPatch ;
   - `assets/xposed_init` et `scope = com.apple.android.music` ;
   - le module embarque `glass/` (ni Compose ni AndroidX lourd → module léger).
2. Point d'entrée : hook `Activity.onCreate`/`onPostCreate` sur l'activité principale, puis `decorView.post { … }` pour trouver les vues via `resources.getIdentifier("<nom>", "id", "com.apple.android.music")`. Comme les noms de ressources survivent en général à l'obfuscation, c'est stable d'une version à l'autre.
3. Pour chaque pilule (barre, puis mini-player) :
   - neutraliser le fond d'origine (`background = null`, ou hook de la couleur appliquée) ;
   - poser `LiquidGlassDrawable` comme fond, avec la `source` = le conteneur de fragments ou la liste qui défile derrière ;
   - garder les icônes, le texte, la pochette, les boutons ▶ et ⏭ **tels quels**, par-dessus la vitre.
4. Cas à gérer :
   - changement d'onglet et de fragment (la `source` change) ;
   - ouverture et fermeture du lecteur plein écran (animation du mini-player) ;
   - mini-player caché quand rien ne joue ;
   - insets et barre de gestes ;
   - rotation et tablette ;
   - mode sombre ou clair ;
   - `Reduce transparency` et économie d'énergie (désactivation possible).
5. **Cas B (Compose)**, à la place des points 2 et 3 (plus long). On ne peut pas ajouter une `View` *entre* le contenu et les icônes, puisque tout est dans un seul `AndroidComposeView`. Deux pistes :
   - **B1 (recommandée)** :
     - repérer avec jadx la fonction composable obfusquée qui dessine la pilule (via ses chaînes, ses `R.string.tab_*`, ses descriptions d'accessibilité « Accueil », « Radio »…) ;
     - la hooker pour remplacer son paramètre `Modifier` par `modifier.then(glass)`. Le `glass` est un `DrawModifier` créé avec `java.lang.reflect.Proxy`, parce que l'interface est obfusquée ;
     - dans `draw()`, on récupère le `android.graphics.Canvas` natif (`drawContext.canvas.nativeCanvas`) et on appelle `LiquidGlassRenderer`. La source est le `RenderNode` du calque du contenu défilant, obtenu par réflexion, ou à défaut l'image de la frame précédente.

     Il faut refaire les signatures (style DexKit) à chaque version d'Apple Music.
   - **B2 (repli « faux verre »)** : seulement rendre la teinte d'origine plus transparente et ajouter le liseré lumineux et l'ombre, sans flou réel. C'est simple, mais beaucoup moins joli.

### Phase 3 : finitions « comme iOS » (3 à 5 jours, optionnel)

- **Goutte de sélection** liquide sous l'onglet actif : port de `LiquidBottomTabs`, de `DampedDragAnimation` (ressort, effet d'échelle 78/56 à l'appui) et de `InteractiveHighlight` (reflet qui suit le doigt). On se branche sur l'écouteur de sélection de la barre d'Apple.
- **Glisser d'un onglet à l'autre** sur la barre, comme sur iOS.
- **Rétrécissement au défilement** de la barre : Apple ne le fait pas sur Android, mais c'est faisable avec un `OnScrollListener` sur la liste source.
- **Écran de réglages** dans l'app du module : intensité de la réfraction, rayon de flou, opacité de la teinte, aberration chromatique, et activation séparée pour la barre et le mini-player. Lu via `XSharedPreferences` ou les préférences distantes de libxposed.

### Phase 4 : distribution et maintenance

- GitHub Actions : compiler l'APK du module à chaque tag et publier une Release.
- `README` : installation via LSPosed (root) et LSPatch (sans root), avec le tableau de compatibilité par version d'Apple Music.
- À chaque nouvelle version d'Apple Music : relancer `tools/apk_triage.py`, `dumpsys activity top` et vérifier les ids (ou signatures en cas B).
- Garder `LICENSE` (Apache-2.0) et un `NOTICE` qui crédite **Kyant** pour les shaders et la logique portés.
- **Ne jamais publier l'APK Apple Music modifié** : uniquement le module.

---

## 5. Risques

| Risque | Impact | Parade |
|---|---|---|
| UI en Compose (cas B) | Effort ×3, hooks fragiles | Décision en phase 0 ; B1 avec signatures DexKit, sinon B2 |
| Re-signature refusée (Play Integrity, vérification native) | Pas de version sans root | Limiter le support à LSPosed (root) |
| Détection de root/Xposed | L'app refuse de se lancer | Masquage LSPosed (Shamiko, HMA) ; marqueurs signalés par le triage |
| Perf. (capture + flou + AGSL à chaque frame) | Saccades sur petits appareils | Capturer seulement la zone des pilules, flou réduit, désactivable dans les réglages |
| Changement d'ids à chaque mise à jour (surtout 7.0 bêta → stable) | Le mod ne s'applique plus, sans crash | Recherche tolérante (plusieurs noms candidats + heuristique taille/position) ; si rien ne correspond, on ne fait rien |
| Android < 13 | Pas de réfraction | Flou seul sur 12, rien en dessous |
| Juridique (CGU Apple, droit d'auteur) | Retrait du projet | Usage personnel, aucun binaire Apple redistribué, module seulement |

---

## 6. Prochaine étape concrète

Lancer la **phase 0** sur l'APKM fourni :

```bash
python3 -I tools/apk_triage.py "chemin/vers/Apple Music.apkm" --json triage.json
adb shell dumpsys activity top > hierarchy.txt   # app ouverte sur l'onglet Accueil
```

Ensuite, déposer `triage.json` et `hierarchy.txt` dans `docs/`. Ces deux fichiers suffisent pour choisir A ou B et figer la liste des hooks.
