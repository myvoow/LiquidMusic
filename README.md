# LiquidMusic

Mod (module LSPosed) qui remplace le fond de la barre d'onglets flottante et du mini-player d'Apple Music Android par un vrai effet **Liquid Glass** : flou, réfraction, reflet. Le rendu est porté de [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0).

- [PLAN.md](PLAN.md) : faisabilité, architecture et plan de travail.
- [tools/apk_triage.py](tools/apk_triage.py) : analyse d'un `.apk` / `.apkm` / `.xapk` / `.apks` (Python 3, stdlib uniquement) pour la phase 0.

```bash
python3 -I tools/apk_triage.py "Apple Music.apkm" --json triage.json
```
