# 🫧 LiquidMusic Patches

Patches compatibles avec [Morphe](https://morphe.software) qui ajoutent un vrai effet **Liquid Glass** à **Apple Music** sur Android : réfraction sur les bords, flou, vibrance et reflet. L'effet s'applique à la barre d'onglets flottante, au mini-player et (en option) à la barre de recherche.

Le rendu est porté de [Backdrop / AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) de Kyant.

## 📲 Utilisation

Ajoute ce dépôt comme source de patches dans Morphe : appuie sur le bouton ci-dessous, ou ajoute l'URL `https://github.com/myvoow/LiquidMusic` à la main dans Morphe Manager.

<a href="https://morphe.software/add-source?github=myvoow/LiquidMusic">
  <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
</a>

1. Ajoute la source (bouton ci-dessus). Pour les versions de test (branche `dev`), active les pré-versions de cette source dans Morphe.
2. Choisis **Apple Music**, fournis l'APKM (testé avec **7.0.0-beta**), et laisse le patch **Liquid Glass** coché.
3. Si tu veux, règle les options : flou, largeur et force de la réfraction, vibrance, opacité de la teinte Apple, reflet, aberration chromatique, barre de recherche.
4. Désinstalle l'Apple Music officiel (sa signature est différente), puis installe l'app patchée.

| Android | Rendu |
|---|---|
| 13+ | Liquid Glass complet (réfraction AGSL, flou, vibrance, reflet) |
| 12 | Flou + vibrance + reflet, sans réfraction |
| 11 | Rendu Apple d'origine (le patch ne fait rien) |

> [!WARNING]
> Morphe re-signe l'application. Or Apple Music envoie un jeton **Play Integrity** pendant la connexion et les achats (voir [l'analyse](docs/apple-music-7.0.0-beta.md#4-sécurité-et-voie-sans-root)). Une copie re-signée peut donc être refusée par Apple au moment de la connexion ou de l'abonnement. Cela n'a pas encore été testé : fais un essai avant de supprimer tes téléchargements.

## 🩹 Liste des patches

<!-- PATCHES_START EXPANDED -->

<!-- Généré automatiquement par release.yml à chaque release (patches-list.json). -->

| Patch | Description |
|---|---|
| **Liquid Glass** | Remplace le flou de la barre d'onglets flottante et du mini-player par du Liquid Glass (bords réfractés, flou, vibrance, reflet). |

<!-- PATCHES_END -->

## ⚙️ Fonctionnement

Apple Music 7.0 floute déjà le contenu sous la barre et le mini-player avec la bibliothèque BlurView v3 :

- un `BlurTarget` enregistre l'écran dans un `RenderNode` ;
- des `BlurView` en forme de pilule (`bottom_nav_blur`, `mini_player_blur`) le floutent.

Le patch :

1. **injecte un appel au début de `BlurView.draw(Canvas)`**. La classe et la méthode gardent leur nom malgré l'obfuscation, ce qui rend le patch robuste aux mises à jour ;
2. **embarque une extension** (`extensions/liquidglass`) qui dessine le verre à la place du flou. Elle réutilise le `RenderNode` du `BlurTarget` et lui applique la chaîne de Backdrop (saturation → flou → lentille AGSL), puis un reflet sur le contour ;
3. **atténue la teinte `bg_apple_material`** d'Apple (55 à 60 % opaque) pour laisser voir le verre.

En cas de problème (vue inconnue, Android trop ancien, erreur), l'extension rend la main au flou d'origine : l'app ne plante jamais à cause du patch.

Les détails sont dans [PLAN.md](PLAN.md) et [docs/apple-music-7.0.0-beta.md](docs/apple-music-7.0.0-beta.md).

## 🧑‍💻 Développement

Le projet suit le [template officiel de patches Morphe](https://github.com/MorpheApp/morphe-patches-template) :

```
patches/      # patches Morphe (Kotlin) → patches-<version>.mpp
extensions/   # code Android embarqué dans l'app patchée (Java) → liquidglass.mpe
tools/        # analyse d'APK sans dépendance (Python stdlib)
docs/         # analyses par version d'Apple Music
```

- **Compiler en local :** `./gradlew buildAndroid`. Il faut un SDK Android et un jeton GitHub `read:packages` dans `~/.gradle/gradle.properties` (`gpr.user`, `gpr.key`) pour télécharger le plugin Morphe. Le fichier est produit dans `patches/build/libs/patches-*.mpp`, testable avec [Morphe Desktop](https://github.com/MorpheApp/morphe-desktop).
- **Publier :** chaque push sur `dev` (pré-version) ou `main` (version stable) lance `release.yml`. Le workflow construit le `.mpp`, crée la Release GitHub et met à jour `patches-bundle.json`, le fichier que lit Morphe Manager. Utilise des messages de commit sémantiques (`feat:`, `fix:`, `chore:`) et ne modifie pas à la main `patches-bundle.json`, `patches-list.json` ni `CHANGELOG.md`.
- **Analyser une nouvelle version d'Apple Music :**

```bash
python3 -I tools/apk_triage.py "Apple Music.apkm" --json triage.json
python3 -I tools/apk_res.py "Apple Music.apkm" layout res/layout/fragment_music_content.xml
python3 -I tools/dex_inspect.py "Apple Music.apkm" class 'Leightbitlab/com/blurview/BlurView;' --code
```

## 📜 Licence

GPLv3 ([LICENSE](LICENSE)). Les shaders portés de Backdrop restent sous Apache-2.0, et le template Morphe ajoute une condition de nom (voir [NOTICE](NOTICE)). Projet indépendant, compatible avec Morphe, sans lien avec Morphe ni Apple.
