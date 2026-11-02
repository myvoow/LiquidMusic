# Analyse : Apple Music 7.0.0-beta (Android)

- Fichier : `Apple Music 7.0.0-beta (120-640dpi) (Android 11+)`, bundle `.apkm` APKMirror (base + 11 splits)
- Paquet : `com.apple.android.music`, versionCode **1606**, minSdk **30**, targetSdk **36**
- Activité de lancement : `com.apple.android.music.common.MainActivity`
- 4 DEX, code obfusqué par R8 (sauf classes référencées dans les layouts et une partie des classes `com.apple.*`)

Reproduire :

```bash
python3 -I tools/apk_triage.py "Apple Music.apkm" --json triage.json
python3 -I tools/apk_res.py "Apple Music.apkm" layout res/layout/fragment_music_content.xml
python3 -I tools/dex_inspect.py "Apple Music.apkm" class 'Leightbitlab/com/blurview/Blur(View|Target);' --code
```

---

## 1. Verdict : vues classiques, avec du flou déjà en place

L'app est hybride (des `ComposeView` existent pour des dialogues et dégradés), mais **la barre d'onglets et le mini-player sont des `View` classiques**. Apple utilise même déjà la bibliothèque **BlurView v3** (Dimezis, paquet `eightbitlab.com.blurview`) pour flouter le contenu sous les deux pilules. Toute la plomberie de capture du fond existe donc déjà. Le mod n'a qu'à **remplacer l'effet appliqué** (flou simple → flou + réfraction + saturation + reflet) et **éclaircir la teinte** posée par-dessus.

---

## 2. Hiérarchie de l'écran principal (téléphone)

`res/layout/fragment_music_content.xml` (data binding `q8/R4` dans cette version) :

```
ConstraintLayout                     @id/content_container
├── BlurTarget                       @id/blur_target          ← source du flou (tout le contenu)
│   └── FrameLayout                  @id/tab_navigation_host  ← fragments des onglets
├── ComposeView                      @id/top_and_bottom_gradients
├── BlurView                         @id/mini_player_blur     ← pilule du mini-player (GONE au départ)
│     background=@drawable/bg_corner_radius_pill, height=@dimen/miniplayer_height (48dp)
├── CoordinatorLayout                @id/player_coordinator
│   └── FragmentContainerView        @id/player_sheet_container
│         PlayerMainFragment (BottomSheetBehavior, peekHeight=@dimen/player_peek_height)
│         └── layout/mini_player.xml : contenu du mini-player (titre, artiste, ▶, ⏭, …)
│               ConstraintLayout     @id/mini_player_content  background=@drawable/bg_apple_material
├── BlurView                         @id/bottom_nav_blur      ← calée sur bottom_nav_view
│     background=@drawable/bg_corner_radius_pill
└── BottomNavigationView             @id/bottom_nav_view      ← la barre d'onglets
      background=@drawable/bg_apple_material, height=@dimen/navigation_tabs_height (62dp)
      marginStart/End=@dimen/endMargin (16/20/34dp), itemActiveIndicatorStyle=@null
```

Variantes tablette (`layout-w600dp`, `layout-w675dp`, `layout-w735dp`) : même principe. D'autres `BlurView` existent : `top_nav_blur`, `floating_search_bar_blur` (barre de recherche flottante), `drawer_blur` (activité principale). Elles sont candidates au même traitement.

### Ce qui donne l'aspect « presque opaque » actuel

Ordre de dessin d'une pilule :

1. `BlurView` : flou du `BlurTarget`, **rayon 16 px** (constante passée à `setBlurRadius`, inlinée dans `oh.f.d()`). Pas de couleur de recouvrement dans le layout.
2. Par-dessus, la vue de contenu (`bottom_nav_view` / `mini_player_content`) a pour fond `bg_apple_material`, un `layer-list` :
   - `@id/artwork_color_scheme_layer` → `layer_artwork_color_scheme` (forme pilule, `apple_material_background`) ;
   - `@id/material_layer` → `layer_apple_material` (forme pilule, `apple_material_background`, contour `apple_material_stroke` de `apple_material_bg_stroke`).

| Couleur | Clair | Sombre |
|---|---|---|
| `apple_material_background` | `#8cf5f5f7` (55 %) | `#99262628` (60 %) |
| `apple_material_background_thick` | `#bff5f5f7` (75 %) | `#bf262628` (75 %) |
| `apple_material_stroke` | `#1a000000` | `#33ffffff` |

Avec deux couches à 60 % sur un flou faible, il ne reste que ~16 % de fond visible : d'où « un peu de translucidité ». `corner_radius_pill` = 80dp, donc forme capsule.

---

## 3. BlurView v3 dans cet APK (noms obfusqués de la 7.0.0-beta)

| Rôle (source BlurView v3) | Nom ici | Stable ? |
|---|---|---|
| `BlurView` | `eightbitlab.com.blurview.BlurView` | **Oui** (référencée par les layouts) |
| `BlurTarget` | `eightbitlab.com.blurview.BlurTarget` | **Oui** |
| `BlurTarget.renderNode` (`"BlurViewHost node"`) | champ `a : RenderNode` | Retrouvable **par type** |
| `BlurView.blurController` | champ `a : oh.a` (seul champ de type interface) | Retrouvable **par type** |
| `BlurView.overlayColor` | champ `b : int` | — |
| `BlurView.setupWith(BlurTarget)` | `b(BlurTarget) : oh.a` | Signature unique |
| `BlurView.draw(Canvas)` | `draw(Canvas)` | **Oui** (méthode du framework) |
| interface `BlurController` | `oh.a` : `g(Canvas)Z` (= draw), `f()V`, `destroy()`, `b(Z)`, `d()`, `e(Drawable)` | `draw` = seule méthode `(Canvas)boolean` |
| `RenderNodeBlurController` | `oh.f` (champ `e : RenderNode` `"BlurView node"`, `k : float` rayon, `j : int` overlay) | Par interface + champ `RenderNode` |
| `PreDrawBlurController` (API < 31) | `oh.d` + `oh.g` (RenderScript) | — |

Fonctionnement (`oh.f.g(Canvas)`, API 31+ et canvas matériel) :

1. le nœud `"BlurView node"` prend la taille du `BlurTarget`, translaté de −(position de la BlurView dans la cible) ;
2. il enregistre `frameClearDrawable` puis `drawRenderNode(BlurTarget.renderNode)` ;
3. il applique `RenderEffect.createBlurEffect(k, k, CLAMP)` ;
4. il dessine ce nœud, découpé au rectangle de la BlurView, puis le bruit (`oh.c`) et `drawColor(overlay)`.

`BlurTarget.dispatchDraw` réenregistre son contenu dans son `RenderNode` à chaque frame. Le défilement se propage donc tout seul, sans invalider la BlurView.

Appels côté Apple :

- `MainActivity.onCreate` : `binding.b0 (BlurView).setupWith(binding.Z (BlurTarget)).setBlurRadius(16).setFrameClearDrawable(decorView.background)` pour la `drawer_blur` ;
- lambda `Vb.x` (fragment de contenu) : même chaîne pour `bottom_nav_blur` / `mini_player_blur` avec `q8/R4.Z` (`blur_target`) ;
- `SearchLandingFragment.onViewCreated` : idem pour `floating_search_bar_blur`.

---

## 4. Sécurité et voie sans root

| Élément | Constat | Conséquence |
|---|---|---|
| Play Integrity | `com.apple.android.music.utils.AppIntegrityManager`, `prepareIntegrityToken`, jeton `appIntegrityToken` envoyé dans `WebViewModel.initiateRequest` (flux commerce / compte) et `externalAppIntegrity` dans la config « bag » du store | Un APK **re-signé** (LSPatch, patch statique) sera vu comme « app non reconnue » par Play Integrity : connexion ou abonnement probablement refusés. La voie **LSPosed (root, sans re-signature)** évite le problème |
| Signatures | `AMSStableIdentifier.verifyPackageSignature` (vérifie d'*autres* paquets Apple), `ke.k.c(PackageInfo)` (bibliothèque Google) | Pas de vérification de sa propre signature repérée côté Java |
| Root | chaîne `/system/xbin/su` présente (classes4) | Détection basique possible : à tester avec LSPosed + masquage si besoin |
| Natif | `libCoreFP.so`, `libCoreADI.so`, `libCoreLSKD.so`, `libFPDIFor3P.so` (FairPlay / ADI), `libmediaplatform.so`, `libAMSKit.so`… | DRM natif : le mod ne touche **rien** côté natif ni lecture |

---

## 5. Points d'accroche retenus pour le module

1. **Hook `BlurView.draw(Canvas)`** (nom stable). Pour les vues `bottom_nav_blur` et `mini_player_blur` (ids via `getIdentifier`, optionnellement `floating_search_bar_blur` et `top_nav_blur`) : remplacer, **une seule fois par instance**, le contrôleur (seul champ de type interface) par un **`java.lang.reflect.Proxy`** qui implémente cette interface :
   - méthode `(Canvas)boolean` → notre rendu Liquid Glass, renvoie `true` (BlurView dessine ensuite son fond et ses enfants) ;
   - toutes les autres méthodes → transmises au contrôleur d'origine (cycle de vie intact) ; si le délégué renvoie lui-même, renvoyer le proxy.
   - Si Apple rappelle `setupWith` (nouveau contrôleur), le hook le détecte au `draw` suivant et ré-enveloppe.
2. **Source du fond** : `BlurTarget` (id `blur_target`, ou parent `BlurTarget` le plus proche) → son champ `RenderNode`, retrouvé par type. Aucun enregistrement supplémentaire, aucun risque de récursion : la cible ne contient pas les pilules.
3. **Teinte** : sur `bottom_nav_view` et `mini_player_content`, le fond est un `LayerDrawable`. On baisse l'alpha des couches `material_layer` / `artwork_color_scheme_layer` via `findDrawableByLayerId(getIdentifier(...))`, ou on les remplace par la teinte Backdrop (`#121212` à 40 % en sombre, `#FAFAFA` à 40 % en clair). On garde le contour fin, qui fait office de liseré.
4. **Position** : un `OnPreDrawListener` par pilule invalide la BlurView quand sa position dans la cible change (animations du mini-player, apparition du clavier…).
5. **Repli** : API < 31 ou canvas logiciel → on n'installe pas le proxy (rendu Apple d'origine). API 31-32 → flou + saturation, sans réfraction AGSL.
