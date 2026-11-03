#!/usr/bin/env python3
"""Build index.html by inlining generated SVG diagrams into the deck template."""

from pathlib import Path

BASE = Path(__file__).parent
TEMPLATE = BASE / "deck.template.html"


def main() -> None:
    html = TEMPLATE.read_text(encoding="utf-8")
    for key, name in (
        ("CONTEXT", "context.svg"),
        ("FLOW1", "flow-1-reservation.svg"),
        ("FLOW2", "flow-2-handover.svg"),
        ("FLOW5", "flow-5-access.svg"),
    ):
        svg = (BASE / "assets" / name).read_text(encoding="utf-8")
        html = html.replace("{{" + key + "}}", svg)
    (BASE / "index.html").write_text(html, encoding="utf-8")
    print("wrote", BASE / "index.html")


if __name__ == "__main__":
    main()
