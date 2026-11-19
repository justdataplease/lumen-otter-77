"""The Simulator tour's pictures, out of a GitHub run's log.

    gh run view <run id> -R justdataplease/boringtravel --log > run.log
    python pictures_from_log.py run.log <output folder>

The iPhone Simulator job prints a small JPEG of every picture between PICTURE-BEGIN and PICTURE-END, because a
private repository's artifact store can be full. Each file is named as the tour named it (01-trips.jpg).
"""
import base64
import json
import re
import sys
from pathlib import Path


def text_of(line):
    # gh prefixes every log line with "job<TAB>step<TAB>timestamp ".
    part = line.rstrip("\n").split("\t")[-1]
    return re.sub(r"^\S*\d{4}-\d\d-\d\dT[\d:.]+Z ", "", part)


def main(log, out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    names, manifest, picture, in_manifest, written = {}, [], None, False, []
    for raw in Path(log).read_text(encoding="utf-8", errors="replace").splitlines():
        line = text_of(raw).strip()
        if line == "PICTURES-MANIFEST-BEGIN":
            in_manifest, manifest = True, []
        elif line == "PICTURES-MANIFEST-END":
            in_manifest = False
            try:
                for test in json.loads("\n".join(manifest)):
                    for a in test.get("attachments", []):
                        # "01-trips_0_<uuid>.png" is the name the tour gave, with Xcode's suffix.
                        names[a["exportedFileName"]] = re.sub(r"_\d+_[0-9A-F-]{36}\.png$", "", a["suggestedHumanReadableName"])
            except ValueError:
                pass
        elif in_manifest:
            manifest.append(line)
        elif line.startswith("PICTURE-BEGIN "):
            picture = (line[len("PICTURE-BEGIN "):], [])
        elif line == "PICTURE-END" and picture:
            file, rows = picture
            target = out / (names.get(file, Path(file).stem) + ".jpg")
            target.write_bytes(base64.b64decode("".join(rows)))
            written.append(target.name)
            picture = None
        elif picture:
            picture[1].append(line)
    print("%d pictures in %s: %s" % (len(written), out, ", ".join(written)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
