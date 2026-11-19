"""How much of the Android app's Kotlin can run on iPhone, file by file.

    python ios-boringtravel/tools/portability.py [--files] [--package <name>]

Reads the imports of every file under android-boringtravel/app/src/main and sorts it into one of five groups:

  plain     only Kotlin, coroutines and kotlinx.serialization: compiles for iPhone as it is
  screens   Compose screens that use only Compose and libraries with an iPhone build (Coil, Room, DataStore,
            navigation, lifecycle, the rich-text editor, reorderable)
  java      plain logic that also uses java.* (time, text, util, io, net): needs those calls replaced
  android   uses Android itself or an Android-only library: rewritten or put behind an interface
  resources (a flag, not a group) the file reads R.* or BuildConfig, which become Compose resources and a config object

A file's group is its worst import, or "java" when its code calls Java without importing it (java.util.Locale written
out, String.format, Math.x, System.currentTimeMillis). A plain file may still use another file of its package that is
not plain; only a compile for iPhone proves it (see docs/iphone-development.md).
"""
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "android-boringtravel/app/src/main/java"
# What already moved into the engine both phones run on (docs/shared-engine.md); not counted below.
ENGINE = ROOT / "android-boringtravel/shared/src/commonMain/kotlin"
APP = "com.justdataplease.boringtravel"

PLAIN = ("kotlin.", "kotlinx.coroutines.", "kotlinx.serialization.", APP + ".")
# Libraries that publish an iPhone build with the same package names.
MULTIPLATFORM = ("androidx.compose.", "androidx.lifecycle.", "androidx.navigation.", "androidx.room.", "androidx.sqlite.",
                 "androidx.datastore.", "coil3.", "com.mohamedrejeb.richeditor.", "sh.calvin.reorderable.", "androidx.annotation.")
# ...except these corners of them, which exist only on Android.
ANDROID_CORNERS = ("androidx.compose.ui.platform.LocalContext", "androidx.compose.ui.platform.LocalView",
                   "androidx.compose.ui.platform.LocalConfiguration", "androidx.compose.ui.platform.ComposeView",
                   "androidx.compose.ui.viewinterop.", "androidx.compose.ui.res.", "androidx.compose.ui.tooling.",
                   "androidx.lifecycle.process.", "androidx.lifecycle.ProcessLifecycleOwner", "androidx.lifecycle.Lifecycle",
                   "coil3.video.", "coil3.network.okhttp.", "kotlinx.coroutines.android", "kotlinx.coroutines.tasks.")
JAVA = ("java.", "javax.")

IMPORT = re.compile(r"^import\s+([\w.]+)", re.M)
# Java reached without an import.
JAVA_CALL = re.compile(r"java\.(util|text|time|io|net|nio|lang)\.|String\.format\(|Math\.[a-z]|System\.(currentTimeMillis|nanoTime|getProperty)|\"\.format\(")
RES = re.compile(r"\bR\.(string|plurals|drawable|font|raw|array|color|dimen|id|xml|mipmap)\b|\bBuildConfig\.")
ORDER = ["plain", "screens", "java", "android"]


def group(imports):
    worst = "plain"
    why = Counter()
    for name in imports:
        if name.startswith(ANDROID_CORNERS):
            kind = "android"
        elif name.startswith(PLAIN):
            kind = "plain"
        elif name.startswith(MULTIPLATFORM):
            kind = "screens"
        elif name.startswith(JAVA):
            kind = "java"
        else:
            kind = "android"
        if kind in ("java", "android"):
            why[".".join(name.split(".")[:3 if name.startswith(("androidx.", "com.google.", "org.maplibre")) else 2])] += 1
        if ORDER.index(kind) > ORDER.index(worst):
            worst = kind
    return worst, why


def main():
    show_files = "--files" in sys.argv
    only = sys.argv[sys.argv.index("--package") + 1] if "--package" in sys.argv else None
    packages = defaultdict(lambda: Counter())
    totals = Counter()
    files = Counter()
    resources = Counter()
    blockers = Counter()
    listing = []
    engine = sum(1 for _ in ENGINE.rglob("*.kt")) if ENGINE.is_dir() else 0
    for path in sorted(SOURCE.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        lines = text.count("\n") + 1
        kind, why = group(IMPORT.findall(text))
        body = "\n".join(line for line in text.split("\n") if not line.startswith("import "))
        if kind in ("plain", "screens") and JAVA_CALL.search(body):
            kind = "java"
            why["java calls in the code"] += 1
        package = str(path.parent.relative_to(SOURCE)).replace("\\", "/").replace(APP.replace(".", "/"), "").strip("/") or "(app)"
        packages[package][kind] += lines
        totals[kind] += lines
        files[kind] += 1
        if RES.search(text):
            resources[kind] += lines
        if kind == "android":
            blockers.update(why.keys())
        listing.append((package, path.name, lines, kind, ", ".join(sorted(why))))
    whole = sum(totals.values())
    print(f"{sum(files.values())} files, {whole:,} lines\n")
    print(f"{'group':10}{'files':>7}{'lines':>10}{'share':>8}{'of them with R./BuildConfig':>30}")
    for kind in ORDER:
        print(f"{kind:10}{files[kind]:>7}{totals[kind]:>10,}{totals[kind] / whole:>8.0%}{resources[kind]:>30,}")
    print("\nWhat holds the 'android' files back (files using it):")
    for name, count in blockers.most_common(28):
        print(f"  {count:>4}  {name}")
    print(f"\n{'package':28}" + "".join(f"{k:>10}" for k in ORDER))
    for package in sorted(packages):
        row = packages[package]
        print(f"{package:28}" + "".join(f"{row[k]:>10,}" for k in ORDER))
    if show_files or only:
        print()
        for package, name, lines, kind, why in listing:
            if only and package != only:
                continue
            print(f"{kind:8} {lines:>5}  {package}/{name}" + (f"   [{why}]" if why else ""))


if __name__ == "__main__":
    main()
