#!/usr/bin/env python3
"""
LiquidMusic — triage d'un APK / APKM / XAPK / APKS (stdlib uniquement).

Répond aux questions de la phase 0 du plan (voir PLAN.md) sans apktool ni jadx :
  * version, minSdk / targetSdk, activité de lancement ;
  * l'app embarque-t-elle Jetpack Compose (et quelles versions) ?
  * quels noms de ressources ressemblent à la barre d'onglets / au mini-player ?
  * quelles classes / API sensibles sont référencées (BottomNavigationView,
    ComposeView, RenderEffect, RuntimeShader, vérification de signature,
    Play Integrity...) ?
  * quelles bibliothèques natives sont présentes ?

Usage :
    python3 -I tools/apk_triage.py chemin/vers/AppleMusic.apkm [--json out.json]
"""

from __future__ import annotations

import argparse
import io
import json
import re
import struct
import sys
import zipfile
from collections import defaultdict

# --------------------------------------------------------------------------- #
# Motifs recherchés
# --------------------------------------------------------------------------- #

# Noms de ressources (layouts, ids, dimens...) qui ressemblent aux éléments visés.
RES_NAME_PATTERNS = re.compile(
    r"(tab_?bar|bottom_?nav|navigation_?bar|nav_?bar|bottom_?bar|"
    r"mini_?player|miniplayer|now_?playing|playback_?bar|player_?bar|"
    r"floating|_pill|pill_|glass|blur|translucen)",
    re.IGNORECASE,
)

# Chaînes de DEX intéressantes : (étiquette, regex sur la chaîne complète).
DEX_MARKERS = [
    # Sections de trace du runtime Compose : survivent à l'obfuscation R8.
    ("Compose: traces runtime (fiable même obfusqué)", r"^Compose:(recompose|applyChanges)$"),
    ("Compose: AndroidComposeView (msg d'erreur)", r"^Cannot locate windowRecomposer"),
    ("Compose: ComposeView", r"^Landroidx/compose/ui/platform/ComposeView;$"),
    ("Compose: AbstractComposeView", r"^Landroidx/compose/ui/platform/AbstractComposeView;$"),
    ("Compose: runtime Composer", r"^Landroidx/compose/runtime/Composer;$"),
    ("Compose: NavigationBar (M3)", r"androidx/compose/material3/NavigationBar"),
    ("Views: BottomNavigationView", r"^Lcom/google/android/material/bottomnavigation/BottomNavigationView;$"),
    ("Views: NavigationBarView", r"^Lcom/google/android/material/navigation/NavigationBarView;$"),
    ("Views: TabLayout", r"^Lcom/google/android/material/tabs/TabLayout;$"),
    ("Views: CoordinatorLayout", r"^Landroidx/coordinatorlayout/widget/CoordinatorLayout;$"),
    ("Views: RecyclerView", r"^Landroidx/recyclerview/widget/RecyclerView;$"),
    ("Views: DataBinding", r"^Landroidx/databinding/ViewDataBinding;$"),
    ("Epoxy", r"^Lcom/airbnb/epoxy/"),
    ("Rendu: RenderEffect", r"^Landroid/graphics/RenderEffect;$"),
    ("Rendu: RuntimeShader (AGSL)", r"^Landroid/graphics/RuntimeShader;$"),
    ("Rendu: RenderNode", r"^Landroid/graphics/RenderNode;$"),
    ("Rendu: BlurMaskFilter", r"^Landroid/graphics/BlurMaskFilter;$"),
    ("Rendu: setRenderEffect", r"^setRenderEffect$"),
    ("Rendu: Backdrop (Kyant)", r"^Lcom/kyant/backdrop/"),
    ("Sécurité: signingInfo", r"^signingInfo$|^getSigningInfo$|^getApkContentsSigners$"),
    ("Sécurité: Play Integrity", r"com/google/android/play/core/integrity"),
    ("Sécurité: SafetyNet", r"com/google/android/gms/safetynet"),
    ("Sécurité: PairIP / licence Play", r"com/pairip/|com/android/vending/licensing"),
    ("Sécurité: détection root/Xposed", r"(?i)de/robv/android/xposed|xposedbridge|lsposed|magisk|/system/xbin/su"),
]

# Noms de classes de l'app ressemblant aux éléments visés (non obfusqués).
APP_CLASS_PATTERN = re.compile(
    r"^L(com/apple/[A-Za-z0-9_/$]*(Tab|BottomNav|Navigation|MiniPlayer|Miniplayer|"
    r"NowPlaying|PlaybackBar|PlayerBar|MainContent|MainActivity)[A-Za-z0-9_$]*);$"
)

# --------------------------------------------------------------------------- #
# Lecture des conteneurs (apk, apkm, xapk, apks)
# --------------------------------------------------------------------------- #


def open_apks(path: str) -> dict[str, zipfile.ZipFile]:
    """Retourne {nom: ZipFile} pour chaque APK contenu (ou l'APK lui-même)."""
    try:
        outer = zipfile.ZipFile(path)
    except zipfile.BadZipFile:
        sys.exit(f"{path} n'est pas une archive zip lisible (APKM chiffré ? ré-exporter avec SAI / APKMirror Installer).")
    names = outer.namelist()
    if "AndroidManifest.xml" in names:
        return {"base.apk": outer}
    apks = {}
    for name in names:
        if name.lower().endswith(".apk"):
            apks[name] = zipfile.ZipFile(io.BytesIO(outer.read(name)))
    if not apks:
        sys.exit(f"Aucun APK trouvé dans {path}")
    return apks


def pick_base(apks: dict[str, zipfile.ZipFile]) -> tuple[str, zipfile.ZipFile]:
    for name, z in apks.items():
        if name.endswith("base.apk") or "classes.dex" in z.namelist():
            return name, z
    return next(iter(apks.items()))


# --------------------------------------------------------------------------- #
# Binary XML (AXML) minimal : suffisant pour AndroidManifest.xml
# --------------------------------------------------------------------------- #

ANDROID_ATTR_IDS = {
    0x01010003: "name",
    0x0101020C: "minSdkVersion",
    0x01010270: "targetSdkVersion",
    0x0101021B: "versionCode",
    0x0101021C: "versionName",
    0x01010010: "exported",
    0x01010001: "label",
}


def _read_string_pool(data: bytes, off: int) -> list[str]:
    _type, header_size, _size, count, _style_count, flags, strings_start, _styles_start = struct.unpack_from(
        "<HHIIIIII", data, off
    )
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", data, off + header_size)
    base = off + strings_start
    out = []
    for o in offsets:
        p = base + o
        if utf8:
            # longueur en caractères puis en octets (1 ou 2 octets chacune)
            n = data[p]
            p += 2 if n & 0x80 else 1
            n = data[p]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | data[p + 1]
                p += 2
            else:
                p += 1
            out.append(data[p : p + n].decode("utf-8", "replace"))
        else:
            n = struct.unpack_from("<H", data, p)[0]
            p += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | struct.unpack_from("<H", data, p)[0]
                p += 2
            out.append(data[p : p + 2 * n].decode("utf-16-le", "replace"))
    return out


def parse_axml(data: bytes) -> list[tuple[str, dict[str, object]]]:
    """Retourne la liste des éléments (tag, {attr: valeur}) dans l'ordre."""
    strings: list[str] = []
    res_ids: list[int] = []
    elements = []
    off = 8  # en-tête du fichier
    while off < len(data):
        ctype, hsize, csize = struct.unpack_from("<HHI", data, off)
        if csize == 0:
            break
        if ctype == 0x0001:
            strings = _read_string_pool(data, off)
        elif ctype == 0x0180:
            res_ids = list(struct.unpack_from(f"<{(csize - hsize) // 4}I", data, off + hsize))
        elif ctype == 0x0102:
            _ns, name_idx, attr_start, attr_size, attr_count = struct.unpack_from("<iiHHH", data, off + 16)
            tag = strings[name_idx] if 0 <= name_idx < len(strings) else "?"
            attrs: dict[str, object] = {}
            a = off + 16 + attr_start
            for i in range(attr_count):
                _ans, aname, araw, _vsize, _res0, vtype, vdata = struct.unpack_from("<iiiHBBI", data, a + i * attr_size)
                key = strings[aname] if 0 <= aname < len(strings) else ""
                if aname < len(res_ids) and res_ids[aname] in ANDROID_ATTR_IDS:
                    key = ANDROID_ATTR_IDS[res_ids[aname]]
                if vtype == 0x03 and 0 <= vdata < len(strings):
                    val: object = strings[vdata]
                elif araw >= 0 and araw < len(strings):
                    val = strings[araw]
                elif vtype == 0x12:
                    val = vdata != 0
                elif vtype == 0x01:
                    val = f"@0x{vdata:08x}"
                else:
                    val = vdata
                attrs[key] = val
            elements.append((tag, attrs))
        off += csize
    return elements


def manifest_summary(base: zipfile.ZipFile) -> dict[str, object]:
    elements = parse_axml(base.read("AndroidManifest.xml"))
    info: dict[str, object] = {"activities": [], "launcher": None}
    current_activity = None
    in_launcher_filter = False
    for tag, attrs in elements:
        if tag == "manifest":
            info["package"] = attrs.get("package")
            info["versionName"] = attrs.get("versionName")
            info["versionCode"] = attrs.get("versionCode")
        elif tag == "uses-sdk":
            info["minSdk"] = attrs.get("minSdkVersion")
            info["targetSdk"] = attrs.get("targetSdkVersion")
        elif tag in ("activity", "activity-alias"):
            current_activity = attrs.get("name")
            info["activities"].append(current_activity)
            in_launcher_filter = False
        elif tag == "category" and attrs.get("name") == "android.intent.category.LAUNCHER":
            in_launcher_filter = True
            if current_activity and not info["launcher"]:
                info["launcher"] = current_activity
    return info


# --------------------------------------------------------------------------- #
# DEX : table des chaînes
# --------------------------------------------------------------------------- #


def _uleb128(data: bytes, p: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        b = data[p]
        p += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, p
        shift += 7


def dex_strings(data: bytes):
    if not data.startswith(b"dex\n"):
        return
    size, off = struct.unpack_from("<II", data, 0x38)
    for i in range(size):
        sdo = struct.unpack_from("<I", data, off + 4 * i)[0]
        _n, p = _uleb128(data, sdo)
        end = data.index(b"\x00", p)
        yield data[p:end].decode("utf-8", "replace")


# --------------------------------------------------------------------------- #
# Analyse
# --------------------------------------------------------------------------- #


def analyse(path: str) -> dict[str, object]:
    apks = open_apks(path)
    base_name, base = pick_base(apks)
    report: dict[str, object] = {"file": path, "apks": sorted(apks), "base": base_name}

    report["manifest"] = manifest_summary(base)

    # Versions des bibliothèques AndroidX / Compose (META-INF/*.version)
    versions = {}
    for name in base.namelist():
        if name.startswith("META-INF/") and name.endswith(".version"):
            lib = name[len("META-INF/") : -len(".version")]
            versions[lib] = base.read(name).decode("utf-8", "replace").strip()
    report["compose_libs"] = {k: v for k, v in sorted(versions.items()) if "compose" in k}
    report["notable_libs"] = {
        k: v
        for k, v in sorted(versions.items())
        if any(s in k for s in ("material", "recyclerview", "coordinatorlayout", "databinding", "fragment", "navigation"))
    }

    # DEX
    dex_names = sorted(n for n in base.namelist() if re.fullmatch(r"classes\d*\.dex", n))
    report["dex_count"] = len(dex_names)
    markers = {label: 0 for label, _ in DEX_MARKERS}
    compiled = [(label, re.compile(rx)) for label, rx in DEX_MARKERS]
    app_classes = set()
    compose_class_count = 0
    for dn in dex_names:
        for s in dex_strings(base.read(dn)):
            if s.startswith("Landroidx/compose/"):
                compose_class_count += 1
            for label, rx in compiled:
                if rx.search(s):
                    markers[label] += 1
            m = APP_CLASS_PATTERN.match(s)
            if m:
                app_classes.add(m.group(1).replace("/", "."))
    report["compose_type_descriptors"] = compose_class_count
    report["dex_markers"] = markers
    report["app_classes_of_interest"] = sorted(app_classes)[:300]

    # Noms de ressources (table de resources.arsc, hors bibliothèques Material/AppCompat)
    res_hits = set()
    if "resources.arsc" in base.namelist():
        from pathlib import Path

        sys.path.insert(0, str(Path(__file__).resolve().parent))
        from apk_res import ResTable  # import tardif : apk_res importe ce module

        table = ResTable(base.read("resources.arsc"))
        lib_prefixes = ("m3_", "mtrl_", "abc_", "design_", "material_", "exo_")
        for name in table.names.values():
            entry = name.split("/", 1)[1]
            if RES_NAME_PATTERNS.search(entry) and not entry.startswith(lib_prefixes + ("$",)) \
                    and "ActionButton" not in entry and "magnifyingglass" not in entry \
                    and name.split("/", 1)[0] in ("id", "layout", "drawable", "color", "dimen", "attr"):
                res_hits.add(name)
    layouts = [n for n in base.namelist() if n.startswith("res/") and n.endswith(".xml")]
    layout_hits = sorted(n for n in layouts if RES_NAME_PATTERNS.search(n.rsplit("/", 1)[-1]))
    report["resource_names_of_interest"] = sorted(res_hits)[:400]
    report["layout_files_of_interest"] = layout_hits[:200]

    # Layouts contenant des widgets clés (les layouts compilés gardent les noms de classes)
    widget_usage = defaultdict(list)
    for n in layouts:
        try:
            raw = base.read(n)
        except KeyError:
            continue
        if not raw.startswith(b"\x03\x00\x08\x00"):
            continue
        try:
            tags = {tag for tag, _ in parse_axml(raw)}
        except Exception:
            continue
        for t in tags:
            if any(k in t for k in ("ComposeView", "BottomNavigationView", "NavigationBarView", "TabLayout",
                                    "CoordinatorLayout", "FragmentContainerView", "BlurView")):
                widget_usage[t].append(n)
    report["layouts_with_key_widgets"] = {k: sorted(v)[:30] for k, v in sorted(widget_usage.items())}

    # Bibliothèques natives (toutes les splits)
    natives = set()
    for z in apks.values():
        for n in z.namelist():
            if n.startswith("lib/") and n.endswith(".so"):
                natives.add(n.split("/", 2)[-1] + "  [" + n.split("/")[1] + "]")
    report["native_libs"] = sorted(natives)

    report["verdict_hints"] = verdict_hints(report)
    return report


def verdict_hints(r: dict[str, object]) -> list[str]:
    hints = []
    m = r["dex_markers"]
    compose = bool(r["compose_libs"]) or bool(m["Compose: traces runtime (fiable même obfusqué)"])
    views_nav = m["Views: BottomNavigationView"] or m["Views: NavigationBarView"]
    if compose and not views_nav:
        hints.append("Compose présent et aucune BottomNavigationView : la barre est probablement en Compose -> variante B du plan.")
    elif compose and views_nav:
        hints.append("App hybride Views + Compose : vérifier sur l'appareil (dumpsys activity top) qui dessine la barre.")
    else:
        hints.append("Pas de Compose : UI en Views -> variante A du plan (la plus simple).")
    if m["Sécurité: Play Integrity"] or m["Sécurité: PairIP / licence Play"]:
        hints.append("Play Integrity / licence Play référencés : le chemin sans root (LSPatch, re-signature) est à risque.")
    if m["Sécurité: signingInfo"]:
        hints.append("L'app lit des signatures de paquets : tester la re-signature tôt (peut être une simple vérif. d'autres apps).")
    if m["Sécurité: détection root/Xposed"]:
        hints.append("Chaînes de détection root/Xposed trouvées : prévoir un masquage (Shamiko/HMA) côté LSPosed.")
    mn = r["manifest"].get("minSdk")
    if isinstance(mn, int) and mn < 33:
        hints.append(f"minSdk={mn} : réfraction (AGSL) seulement sur Android 13+, flou seul sur 12, repli opaque en dessous.")
    return hints


def print_report(r: dict[str, object]) -> None:
    man = r["manifest"]
    print(f"Fichier      : {r['file']}")
    print(f"APKs         : {', '.join(r['apks'])}")
    print(f"Paquet       : {man.get('package')}  v{man.get('versionName')} ({man.get('versionCode')})")
    print(f"SDK          : min {man.get('minSdk')} / cible {man.get('targetSdk')}")
    print(f"Lanceur      : {man.get('launcher')}")
    print(f"Activités    : {len(man['activities'])}")
    print(f"DEX          : {r['dex_count']}")
    print()
    print("== Compose ==")
    print(f"  descripteurs androidx.compose.* : {r['compose_type_descriptors']}")
    for k, v in r["compose_libs"].items():
        print(f"  {k} = {v}")
    print("\n== Bibliothèques notables ==")
    for k, v in r["notable_libs"].items():
        print(f"  {k} = {v}")
    print("\n== Marqueurs DEX ==")
    for k, v in r["dex_markers"].items():
        print(f"  {'✔' if v else '·'} {k}: {v}")
    print("\n== Classes de l'app (non obfusquées) liées à la nav / au lecteur ==")
    for c in r["app_classes_of_interest"][:80]:
        print(f"  {c}")
    print("\n== Layouts contenant des widgets clés ==")
    for k, v in r["layouts_with_key_widgets"].items():
        print(f"  {k}: {', '.join(v[:8])}{' …' if len(v) > 8 else ''}")
    print("\n== Fichiers de layout au nom intéressant ==")
    for n in r["layout_files_of_interest"][:60]:
        print(f"  {n}")
    print("\n== Noms de ressources intéressants ==")
    print("  " + ", ".join(r["resource_names_of_interest"][:150]))
    print("\n== Bibliothèques natives ==")
    for n in r["native_libs"]:
        print(f"  {n}")
    print("\n== Pistes ==")
    for h in r["verdict_hints"]:
        print(f"  -> {h}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("package", help=".apk, .apkm, .xapk ou .apks")
    ap.add_argument("--json", help="écrit aussi le rapport complet en JSON")
    args = ap.parse_args()
    report = analyse(args.package)
    print_report(report)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"\nRapport JSON : {args.json}")


if __name__ == "__main__":
    main()
