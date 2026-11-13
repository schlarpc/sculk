"""Extracts Monocraft Regular and Bold from the nixpkgs collection (.ttc,
which browsers cannot load) and subsets them to the characters the UI uses,
as woff2.

    python3 subset-font.py Monocraft-no-ligatures.ttc OUT_DIR
"""
import sys
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTCollection

WANTED = {"Monocraft": "monocraft.woff2", "Monocraft Bold": "monocraft-bold.woff2"}

# Latin, punctuation, arrows, box drawing and blocks, geometric shapes, a few symbols.
RANGES = [(0x20, 0x7E), (0xA0, 0x17F), (0x2010, 0x205E), (0x2190, 0x21FF), (0x2200, 0x22FF),
          (0x2500, 0x25FF), (0x2600, 0x26FF), (0x2700, 0x27BF)]

ttc, out = Path(sys.argv[1]), Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
found = set()
for font in TTCollection(ttc).fonts:
    name = font["name"].getDebugName(4)
    if name not in WANTED:
        continue
    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=[c for lo, hi in RANGES for c in range(lo, hi + 1)])
    subsetter.subset(font)
    subset.save_font(font, str(out / WANTED[name]), options)
    found.add(name)
missing = set(WANTED) - found
if missing:
    sys.exit(f"subset-font: {ttc} has no {', '.join(sorted(missing))}")
