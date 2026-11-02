# LiquidMusic : du vrai Liquid Glass dans Apple Music Android

Objectif : remplacer le fond de la **barre d'onglets flottante** et du **mini-player flottant** d'Apple Music Android par un vrai effet Liquid Glass (flou, réfraction sur les bords, saturation, reflet), comme sur iOS 26/27. Le rendu est porté de [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (*Backdrop*, Apache-2.0).

**Choix de distribution : un patch [Morphe](https://morphe.software)**, publié par ce dépôt. Les utilisateurs ajoutent `myvoow/LiquidMusic` comme source dans Morphe Manager et appliquent le patch **Liquid Glass** à leur APKM Apple Music.

Analyse détaillée de l'APK : [`docs/apple-music-7.0.0-beta.md`](docs/apple-music-7.0.0-beta.md).

---

## 1. Verdict

**Faisable, et c'est implémenté.**

L'APKM Apple Music 7.0.0-beta (versionCode 1606, minSdk 30) utilise des vues classiques pour la barre d'onglets (`bottom_nav_view`) et le mini-player (`mini_player_content`). Il les floute déjà avec **BlurView v3** :

- un `BlurTarget` (`blur_target`) enregistre le contenu dans un `RenderNode` ;
- deux `BlurView` en pilule (`bottom_nav_blur`, `mini_player_blur`) le floutent ;
- l'aspect presque opaque vient d'un flou de 16 px recouvert de deux couches de teinte à 55-60 % (`bg_apple_material`).

Le patch garde cette infrastructure. Il remplace seulement le **dessin** des BlurView par du Liquid Glass et éclaircit la teinte.

Selon la version d'Android :

- **13+** : rendu complet ;
- **12** : flou, vibrance et reflet, sans réfraction ;
- **11** : rendu Apple d'origine.

### ⚠️ Risque principal : Play Integrity

Morphe **re-signe** l'APK. Or Apple Music envoie un jeton Play Integrity (`AppIntegrityManager`, `appIntegrityToken`) dans ses flux compte et commerce. Apple peut donc refuser la connexion ou l'abonnement d'une copie re-signée. **Ce n'est pas encore testé** : c'est la première chose à vérifier sur un appareil (§4). Si ça bloque, le même code d'extension peut être livré en module LSPosed, qui ne re-signe pas l'APK (§5).

---

## 2. Comment fonctionne Morphe (analysé sur les dépôts MorpheApp, novembre 2026)

| Élément | Rôle |
|---|---|
| [morphe-patcher](https://github.com/MorpheApp/morphe-patcher) 2.1.0 | Bibliothèque qui applique les patches : bytecode (smali/dexlib2), ressources, fichiers bruts. Hard fork de ReVanced Patcher, paquet `app.morphe.*` |
| [morphe-patches-gradle-plugin](https://github.com/MorpheApp/morphe-patches-gradle-plugin) `app.morphe.patches` 2.1.0 | Configure le projet : `patches/` (Kotlin, compilé en `.mpp` = jar + dex via D8) et `extensions/*` (modules Android compilés en dex `.mpe`, embarqués dans le `.mpp`). Hébergé sur GitHub Packages, donc il faut un jeton `read:packages` (dans Actions, `GITHUB_TOKEN` suffit) |
| [morphe-patches-template](https://github.com/MorpheApp/morphe-patches-template) | Point de départ d'un dépôt de patches tiers, dont ce dépôt reprend la structure |
| `release.yml` + `.releaserc` (semantic-release) | À chaque push sur `main` (stable) ou `dev` (pré-version), avec des commits `feat:` / `fix:` : construit `patches-<v>.mpp`, crée la Release GitHub, met à jour `patches-bundle.json`, `patches-list.json`, `CHANGELOG.md` et la liste du README |
| [Morphe Manager](https://github.com/MorpheApp/morphe-manager) | Pour une source `owner/repo`, lit `https://raw.githubusercontent.com/owner/repo/main/patches-bundle.json` (ou `/dev/` si les pré-versions sont activées). Ce fichier contient `version`, `download_url` (le `.mpp` de la Release), `created_at`, `description` et en option `public_key`. Lien d'ajout : `https://morphe.software/add-source?github=owner/repo` |
| Signature (optionnelle) | `./gradlew generateSigningKey`, puis le secret `MORPHE_SIGNING_KEY` dans les Actions. Le Manager mémorise la clé et refuse ensuite une Release modifiée |

Notions clés du patcher :

- **Patch** : `bytecodePatch(name, description, default) { compatibleWith(...); extendWith("x.mpe"); execute { ... } }`.
- **Options** : `intOption`, `booleanOption`… réglables dans le Manager.
- **`Compatibility`** : paquet, nom, type de fichier attendu (`APKM`…), couleur d'icône, versions cibles (`AppTarget`, dont `version = null` = « expérimental, toutes versions »).
- **`Fingerprint`** : description partielle d'une méthode (classe, nom, types, filtres d'instructions) pour la retrouver malgré l'obfuscation.
- **Extension** : dex précompilé fusionné dans l'app, appelé depuis le smali injecté (`addInstructions`, `addInstructionsWithLabels`, `returnEarly`…).
- **Licence** : GPLv3, avec interdiction d'utiliser le nom « Morphe » pour un dérivé. Ce dépôt s'appelle donc **LiquidMusic Patches**, « compatible avec Morphe ».

---

## 3. Ce qui est implémenté

```
patches/src/main/kotlin/app/liquidmusic/patches/applemusic/
├── shared/Constants.kt                 # Compatibility Apple Music (APKM, 7.0.0-beta + expérimental)
└── layout/liquidglass/
    ├── Fingerprints.kt                 # BlurViewDrawFingerprint : BlurView.draw(Canvas) + appel super.draw
    └── LiquidGlassPatch.kt             # patch "Liquid Glass" + 8 options
extensions/liquidglass/src/main/java/app/liquidmusic/extension/
├── LiquidGlassPatch.java               # point d'entrée drawBlurView(View, Canvas) + getters d'options
├── GlassRenderer.java                  # rendu du verre (RenderNode, RenderEffect, AGSL), teinte, suivi de position
├── BlurViewAccess.java                 # accès par type aux champs obfusqués de BlurView v3
└── GlassShaders.java                   # shaders AGSL portés de Backdrop (réfraction, dispersion, reflet)
```

### Le patch (bytecode)

`BlurView.draw(Canvas)` garde son nom : la classe est référencée par les layouts et la méthode surcharge `View.draw`. Elle devient :

```java
public final void draw(Canvas canvas) {
    if (LiquidGlassPatch.drawBlurView(this, canvas)) {   // injecté
        super.draw(canvas);                              // fond + enfants
        return;
    }
    ...code d'origine (flou Apple)
}
```

Les options du Manager sont injectées dans l'extension en remplaçant le corps de ses getters par `returnEarly(valeur)`.

### Options

| Option | Défaut | Rôle |
|---|---|---|
| Blur radius | 8 dp | Flou du contenu derrière le verre |
| Refraction width | 24 dp | Largeur de la bande courbée sur les bords (0 = pas de réfraction) |
| Refraction strength | 24 dp | Déplacement du contenu au bord |
| Vibrancy | 150 % | Saturation du contenu |
| Tint opacity | 40 % | Part conservée de la teinte `bg_apple_material` d'Apple |
| Edge highlight | 100 % | Intensité du liseré lumineux |
| Chromatic aberration | non | Dispersion prismatique des bords (Android 13+) |
| Search bar | oui | Applique aussi le verre à `floating_search_bar_blur` et `top_nav_blur` (tablette) |

### L'extension (exécutée dans Apple Music)

1. **Cible** : nom de ressource de la BlurView. Toujours `bottom_nav_blur` et `mini_player_blur` ; en option `floating_search_bar_blur` et `top_nav_blur`. Les autres BlurView, comme `drawer_blur`, gardent le flou d'Apple.
2. **Source** : le `BlurTarget` configuré par Apple. On le lit dans le contrôleur de la BlurView, retrouvé comme seul champ de type interface, puis à défaut en cherchant un `BlurTarget` voisin. Ensuite on prend son champ `RenderNode`, trouvé par type.
3. **Rendu** :
   - un `RenderNode` de la taille de la pilule + un rayon de flou, qui référence le nœud du BlurTarget au bon décalage ;
   - la chaîne `saturation → createBlurEffect → createRuntimeShaderEffect(réfraction)` ;
   - un découpage à la forme (rayon lu sur le fond `GradientDrawable`, sinon capsule) ;
   - le reflet de Backdrop : contour de 0,5 dp, shader AGSL, `BlendMode.PLUS`.
4. **Teinte** : les couches `material_layer` et `artwork_color_scheme_layer` du fond de `bottom_nav_view` / `mini_player_content` passent à l'opacité choisie.
5. **Mouvements** : un `OnPreDrawListener` redessine la pilule quand sa position change (animation du mini-player). Le défilement du contenu, lui, se propage tout seul par le `RenderNode`.
6. **Sécurité** : Android < 12, canvas logiciel, vue inconnue ou exception → `false`, et BlurView dessine son flou d'origine. Aucun crash possible du fait du patch.

---

## 4. Reste à faire

1. **Compiler** : la CI (`release.yml`, étape « Verify project compiles ») tourne à chaque push. On ne peut pas compiler dans l'environnement de développement actuel, faute de SDK Android et de jeton GitHub Packages.
2. **Première Release** : fusionner cette branche dans `dev` (pré-version `1.0.0-dev.1`) ou `main` (`1.0.0`). Le workflow publie le `.mpp` et remplit `patches-bundle.json`. Réglages GitHub nécessaires :
   - Settings › Actions › General › Workflow permissions : « Read and write » ;
   - cocher « Allow GitHub Actions to create and approve pull requests » (pour `open_pull_request.yml`).
3. **Test sur appareil** (Android 13+), dans cet ordre :
   - connexion Apple ID et lecture sur l'APK patché (Play Integrity) ;
   - rendu de la barre et du mini-player (défilement, ouverture et fermeture du lecteur, changement de pochette, clair et sombre) ;
   - performances (`adb shell dumpsys gfxinfo com.apple.android.music framestats`) ;
   - Android 12 (sans réfraction).
4. **Réglages fins** des valeurs par défaut après comparaison avec iOS 26.
5. **Optionnel** :
   - signer les Releases (`generateSigningKey`) ;
   - ajouter une icône `patches-bundle.png` ;
   - ajouter la goutte de sélection liquide sous l'onglet actif (port de `LiquidBottomTabs`).

---

## 5. Plan B si Play Integrity bloque : module LSPosed (root)

Sans re-signature, Apple Music garde sa signature d'origine et passe Play Integrity (si l'appareil le passe). Il suffit de :

- hooker `eightbitlab.com.blurview.BlurView.draw(Canvas)` avec Xposed ;
- appeler le même `LiquidGlassPatch.drawBlurView(view, canvas)` ;
- si le résultat est `true`, appeler `View.draw` d'origine et sauter le code d'origine.

`extensions/liquidglass` est réutilisable tel quel. Les options passeraient par des préférences du module au lieu de `returnEarly`.

---

## 6. Risques

| Risque | Impact | Parade |
|---|---|---|
| Play Integrity sur APK re-signé | Connexion ou abonnement refusés | Tester en premier ; sinon plan B LSPosed |
| Achats Google Play sur APK re-signé | Paiement in-app indisponible | S'abonner avant, ou depuis un autre appareil |
| Apple change de bibliothèque de flou ou de layout (la 7.0 est en bêta) | Le patch échoue (empreinte) ou ne fait rien | Réanalyse avec `tools/` ; empreinte sur des noms stables |
| Coût GPU | Saccades sur petits appareils | Nœud limité à la pilule + marge ; options pour réduire flou ou réfraction |
| Juridique | Retrait | Ni APK ni code Apple dans le dépôt ; l'utilisateur patche sa propre copie |
