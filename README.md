# LiquidMusic

Mod (module LSPosed) qui remplace le fond de la barre d'onglets flottante et du mini-player d'Apple Music Android par un vrai effet **Liquid Glass** : flou, réfraction, reflet. Le rendu est porté de [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0).

- [PLAN.md](PLAN.md) : verdict, architecture et plan de travail.
- [docs/apple-music-7.0.0-beta.md](docs/apple-music-7.0.0-beta.md) : analyse de l'APK (hiérarchie des vues, BlurView v3, Play Integrity).

## Outils d'analyse (Python 3, stdlib uniquement)

```bash
python3 -I tools/apk_triage.py "Apple Music.apkm" --json triage.json           # vue d'ensemble
python3 -I tools/apk_res.py "Apple Music.apkm" layout layout/mini_player       # layouts / ressources en clair
python3 -I tools/apk_res.py "Apple Music.apkm" find 'bottom_nav|mini_player' --values
python3 -I tools/dex_inspect.py "Apple Music.apkm" class 'BlurView;' --code    # classes, champs, appels
```
