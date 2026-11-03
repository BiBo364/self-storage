#!/usr/bin/env python3
"""Generate reveal.js diagrams in the Kho Mộc "Yard Board" style."""

from pathlib import Path
from html import escape

OUT = Path(__file__).parent / "assets"
OUT.mkdir(parents=True, exist_ok=True)

BG = "#0E1211"
LANE_ALT = "#121715"
SURFACE = "#1A201E"
SURFACE_SUBTLE = "#1F2623"
INK = "#EEF0EC"
INK_2 = "#C6CCC7"
STEEL = "#9AA19C"
RULE = "#2B3330"
SIGNAL = "#FFD400"
SIGNAL_INK = "#101413"
LINK = "#8FC4CC"
SUCCESS = "#8CC6A2"
WARNING = "#E3C05C"
DANGER = "#F08C7C"
SUCCESS_SOFT = "#16261D"
WARNING_SOFT = "#2A2410"
DANGER_SOFT = "#2C1A17"
INFO_SOFT = "#142325"

FONT = "Saira Semi Condensed, sans-serif"
BODY = "Source Sans 3, sans-serif"

ACTOR_COLORS = {
    "Customer": LINK,
    "FM": WARNING,
    "FS": SUCCESS,
    "System": STEEL,
    "VNPay": LINK,
    "BOM": STEEL,
    "Admin": STEEL,
    "Email": STEEL,
}

STATE_STYLE = {
    "Pending": (WARNING_SOFT, WARNING),
    "Approved": (SUCCESS_SOFT, SUCCESS),
    "Converted": (INFO_SOFT, LINK),
    "Deposited": (SUCCESS_SOFT, SUCCESS),
    "Scheduled": (INFO_SOFT, LINK),
    "InProgress": (INFO_SOFT, LINK),
    "Available": (SUCCESS_SOFT, SUCCESS),
    "Reserved": (SIGNAL, SIGNAL_INK),
    "IN_PROGRESS": (WARNING_SOFT, WARNING),
    "COMPLETED": (SUCCESS_SOFT, SUCCESS),
    "Draft": (INFO_SOFT, LINK),
    "Signed": (SUCCESS_SOFT, SUCCESS),
    "Active": (SIGNAL, SIGNAL_INK),
    "Rented": (SIGNAL, SIGNAL_INK),
    "Done": (SUCCESS_SOFT, SUCCESS),
}


def text(cx, cy, lines, size=20, fill=INK, weight=600, anchor="middle", spacing=24,
         family=FONT, halo=False):
    if isinstance(lines, str):
        lines = [lines]
    total = len(lines)
    start = cy - (total - 1) * spacing / 2
    extra = f' stroke="{BG}" stroke-width="4" paint-order="stroke"' if halo else ""
    parts = [f'<text x="{cx}" y="{start}" font-size="{size}" font-weight="{weight}" '
             f'fill="{fill}" text-anchor="{anchor}" dominant-baseline="middle" '
             f'font-family="{family}"{extra}>']
    for i, line in enumerate(lines):
        dy = 0 if i == 0 else spacing
        parts.append(f'<tspan x="{cx}" dy="{dy}">{escape(line)}</tspan>')
    parts.append("</text>")
    return "".join(parts)


def box(x, y, w, h, fill=SURFACE, stroke=INK, stroke_opacity=0.26, rx=0,
        dashed=False, stroke_width=1):
    dash = ' stroke-dasharray="6 4"' if dashed else ""
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" '
            f'fill="{fill}" stroke="{stroke}" stroke-opacity="{stroke_opacity}" '
            f'stroke-width="{stroke_width}"{dash}/>')


def arrow(path, color=STEEL, width=1.4, dashed=False, both=False, end=True):
    dash = ' stroke-dasharray="6 5"' if dashed else ""
    start = ' marker-start="url(#arrow-start)"' if both else ""
    finish = ' marker-end="url(#arrow-end)"' if (end or both) else ""
    return (f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{width}" '
            f'stroke-linejoin="round" stroke-linecap="round"{dash}{start}{finish}/>')


def elbow(x1, y1, x2, y2):
    mid = (x1 + x2) / 2
    return f"M {x1} {y1} H {mid} V {y2} H {x2}"


def lane(y, h, label, color, alt=False):
    fill = LANE_ALT if alt else BG
    return (
        f'<rect x="0" y="{y}" width="1600" height="{h}" fill="{fill}"/>'
        f'<line x1="0" y1="{y}" x2="1600" y2="{y}" stroke="{RULE}" stroke-width="1"/>'
        f'<line x1="190" y1="{y}" x2="190" y2="{y + h}" stroke="{RULE}" stroke-width="1"/>'
        f'<rect x="28" y="{y + h / 2 - 5}" width="10" height="10" fill="{color}"/>'
        + text(48, y + h / 2, label, size=20, fill=INK, weight=600, anchor="start")
    )


def step(cx, cy, num, lines, actor, outcome, w=210, h=92):
    color = ACTOR_COLORS[actor]
    x = cx - w / 2
    y = cy - h / 2
    return (
        box(x, y, w, h, fill=SURFACE, stroke=INK, stroke_opacity=0.26, rx=0,
            stroke_width=1)
        + f'<rect x="{x}" y="{y}" width="4" height="{h}" fill="{color}"/>'
        + f'<rect x="{x + 14}" y="{cy - 13}" width="26" height="26" fill="{SIGNAL}"/>'
        + text(x + 27, cy + 1, str(num), size=16, fill=SIGNAL_INK, weight=700)
        + text(cx + 20, cy - 8, lines, size=20, fill=INK, weight=600, spacing=22)
        + text(cx + 20, cy + 28, outcome, size=13, fill=LINK, weight=500,
               family=BODY)
    )


def note(cx, cy, lines, w=190, h=48):
    x = cx - w / 2
    y = cy - h / 2
    return (
        box(x, y, w, h, fill=INFO_SOFT, stroke="#2A4043", stroke_opacity=1, rx=0,
            stroke_width=1)
        + f'<rect x="{x}" y="{y}" width="3" height="{h}" fill="{LINK}"/>'
        + text(cx, cy, lines, size=15, fill=LINK, weight=500, spacing=18, family=BODY)
    )


def tag_width(label):
    return max(84, len(label) * 8 + 20)


def tag(x, y, label, fill, color, w=None):
    w = w or tag_width(label)
    return (
        box(x, y, w, 26, fill=fill, stroke=fill, stroke_opacity=1, rx=0,
            stroke_width=0)
        + text(x + w / 2, y + 13, label, size=14, fill=color, weight=600)
    )


def state_row(y, label, states):
    parts = [
        f'<line x1="56" y1="{y - 4}" x2="1544" y2="{y - 4}" stroke="{RULE}" stroke-width="1"/>',
        text(64, y + 13, label, size=14, fill=STEEL, weight=600, anchor="start"),
    ]
    x = 180
    for i, state in enumerate(states):
        fill, color = STATE_STYLE[state]
        w = tag_width(state)
        parts.append(tag(x, y, state, fill, color, w))
        x += w
        if i < len(states) - 1:
            parts.append(text(x + 13, y + 13, "→", size=14, fill=STEEL, weight=400,
                              family=BODY))
            x += 26
    return "".join(parts)


def state_panel(rows, note_text, top=540, height=200):
    parts = [box(40, top, 1520, height, fill=SURFACE, stroke=INK,
                 stroke_opacity=0.26, rx=0, stroke_width=1)]
    parts.append(text(64, top + 30, "State changes", size=14, fill=STEEL,
                      weight=600, anchor="start"))
    for i, (label, states) in enumerate(rows):
        parts.append(state_row(top + 62 + i * 34, label, states))
    if note_text:
        parts.append(text(980, top + 128, note_text, size=16, fill=STEEL,
                          weight=400, anchor="start", family=BODY))
    return "".join(parts)


def defs():
    return (
        '<defs>'
        '<marker id="arrow-end" viewBox="0 0 10 10" refX="9" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
        f'<path d="M 0 0 L 10 5 L 0 10 z" fill="{STEEL}"/></marker>'
        '<marker id="arrow-start" viewBox="0 0 10 10" refX="1" refY="5" '
        'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
        f'<path d="M 10 0 L 0 5 L 10 10 z" fill="{STEEL}"/></marker>'
        '</defs>'
    )


def actor(x, y, w, h, title, sub, color, external=False):
    parts = [
        box(x, y, w, h, fill=SURFACE, stroke=INK, stroke_opacity=0.26, rx=0,
            dashed=external, stroke_width=1),
        f'<rect x="{x}" y="{y}" width="3" height="{h}" fill="{color}"/>',
        text(x + 18, y + 30, title, size=18, fill=INK, weight=600, anchor="start",
             spacing=19),
        text(x + 18, y + 60, sub, size=13, fill=STEEL, weight=400, anchor="start",
             family=BODY),
    ]
    return "".join(parts)


def context():
    c = ['<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="760" '
         'viewBox="0 0 1600 760">', defs(), f'<rect width="1600" height="760" fill="{BG}"/>']

    c.append(box(560, 305, 480, 150, fill=SURFACE, stroke=INK, stroke_opacity=0.4,
                 rx=0, stroke_width=2))
    c.append(f'<rect x="560" y="305" width="4" height="150" fill="{SIGNAL}"/>')
    c.append(text(800, 380, "Self-Storage Platform", size=30, fill=INK, weight=700))

    g = [
        arrow("M 290 342 H 560", both=True),
        arrow("M 290 552 H 400 V 420 H 560", both=True),
        text(320, 326, "rental & payment requests", size=14, fill=STEEL,
             weight=400, anchor="start", family=BODY, halo=True),
        text(320, 360, "proposal, invoice, contract", size=14, fill=STEEL,
             weight=400, anchor="start", family=BODY, halo=True),
        text(410, 535, "check-in & handover", size=14, fill=STEEL, weight=400,
             anchor="start", family=BODY, halo=True),
        actor(50, 300, 240, 84, "Customer", "rents · pays · manages", LINK),
        actor(50, 510, 240, 84, "Facility Staff", "on-site operations", SUCCESS),
    ]
    c.append('<g class="fragment" data-fragment-index="1">' + "".join(g) + "</g>")

    g = [
        arrow("M 800 640 V 455", both=True),
        text(814, 552, "approvals & assignments", size=14, fill=STEEL,
             weight=400, anchor="start", family=BODY, halo=True),
        text(786, 592, "requests & reports", size=14, fill=STEEL,
             weight=400, anchor="end", family=BODY, halo=True),
        actor(670, 640, 260, 84, "Facility Manager", "one facility", WARNING),
    ]
    c.append('<g class="fragment" data-fragment-index="2">' + "".join(g) + "</g>")

    g = [
        arrow("M 390 134 V 200 H 640 V 305", both=True),
        arrow("M 1210 134 V 200 H 960 V 305", both=True),
        text(500, 186, "policies, pricing, facilities", size=14, fill=STEEL,
             weight=400, family=BODY, halo=True),
        text(1100, 186, "accounts, RBAC, audit", size=14, fill=STEEL,
             weight=400, family=BODY, halo=True),
        actor(260, 50, 260, 84, ["Business Operation", "Manager"], "chain-wide", STEEL),
        actor(1080, 50, 260, 84, "System Administrator", "accounts · RBAC", STEEL),
    ]
    c.append('<g class="fragment" data-fragment-index="3">' + "".join(g) + "</g>")

    g = [
        arrow("M 1040 360 H 1290", both=True),
        arrow("M 1040 420 H 1150 V 542 H 1290", both=True),
        text(1165, 344, "payments", size=14, fill=STEEL, weight=400, family=BODY,
             halo=True),
        text(1090, 404, "notifications", size=14, fill=STEEL, weight=400,
             anchor="start", family=BODY, halo=True),
        actor(1290, 280, 250, 84, "VNPay", "payment gateway", LINK, external=True),
        actor(1290, 500, 250, 84, "Email / SMTP", "notifications", STEEL,
              external=True),
    ]
    c.append('<g class="fragment" data-fragment-index="4">' + "".join(g) + "</g>")

    c.append("</svg>")
    (OUT / "context.svg").write_text("\n".join(c), encoding="utf-8")
    print("wrote", OUT / "context.svg")


def flow_1():
    cols = [330, 560, 790, 1020, 1250, 1480]
    customer_y, fm_y, vnpay_y = 125, 355, 535

    c = ['<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="760" '
         'viewBox="0 0 1600 760">', defs(), f'<rect width="1600" height="760" fill="{BG}"/>',
         lane(10, 230, "Customer", LINK),
         lane(240, 230, "Facility Manager", WARNING, alt=True),
         lane(470, 130, "VNPay", LINK)]

    steps = [
        (0, customer_y, "Customer", ["Submit rental", "request"], "7-day expiry"),
        (1, fm_y, "FM", ["Approve &", "assign unit"], "order + proposal"),
        (2, customer_y, "Customer", ["Confirm", "proposal"], "deposit invoice"),
        (3, customer_y, "Customer", ["Pay deposit", "(VNPay)"], "unit Reserved"),
        (4, customer_y, "Customer", ["Choose", "check-in slot"], "CHECKIN appointment"),
        (5, fm_y, "FM", ["Assign", "Facility Staff"], "order InProgress"),
    ]

    for num, (idx, cy, actor_name, lines, outcome) in enumerate(steps, start=1):
        cx = cols[idx]
        g = []
        if num > 1:
            pidx, pcy, _, _, _ = steps[num - 2]
            x1 = cols[pidx] + 105
            x2 = cols[idx] - 105
            if pcy == cy:
                g.append(arrow(f"M {x1} {cy} H {x2}"))
            else:
                g.append(arrow(elbow(x1, pcy, x2, cy)))
        if num == 4:
            g.append(arrow(f"M {cx} 509 V 171", color=STEEL, width=1.4,
                           dashed=True, both=True))
            g.append(box(cx - 105, vnpay_y - 26, 210, 52, fill=SURFACE,
                         stroke=INK, stroke_opacity=0.4, rx=0, dashed=True,
                         stroke_width=1))
            g.append(text(cx, vnpay_y, "payment · IPN", size=16, fill=STEEL,
                          weight=600))
        g.append(step(cx, cy, num, lines, actor_name, outcome))
        c.append(f'<g class="fragment" data-fragment-index="{num}">' + "".join(g) + "</g>")

    c.append("</svg>")
    (OUT / "flow-1-reservation.svg").write_text("\n".join(c), encoding="utf-8")
    print("wrote", OUT / "flow-1-reservation.svg")


def flow_2():
    cols = [330, 560, 790, 1020, 1250, 1480]
    fs_y, customer_y, vnpay_y = 125, 355, 535

    c = ['<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="760" '
         'viewBox="0 0 1600 760">', defs(), f'<rect width="1600" height="760" fill="{BG}"/>',
         lane(10, 230, "Facility Staff", SUCCESS),
         lane(240, 230, "Customer", LINK, alt=True),
         lane(470, 130, "VNPay", LINK)]

    steps = [
        (0, fs_y, "FS", ["Confirm", "arrival"], "appointment Done"),
        (1, fs_y, "FS", ["Verify", "identity"], "identity Verified"),
        (2, fs_y, "FS", ["Inspect unit", "+ confirm"], "condition Agreed"),
        (3, fs_y, "FS", ["Sign", "contract"], "contract Signed"),
        (4, customer_y, "Customer", ["Pay first", "month"], "invoice Paid"),
        (5, fs_y, "FS", ["Hand over", "key / code"], "unit Rented"),
    ]

    for num, (idx, cy, actor_name, lines, outcome) in enumerate(steps, start=1):
        cx = cols[idx]
        g = []
        if num > 1:
            pidx, pcy, _, _, _ = steps[num - 2]
            x1 = cols[pidx] + 105
            x2 = cols[idx] - 105
            if pcy == cy:
                g.append(arrow(f"M {x1} {cy} H {x2}"))
            else:
                g.append(arrow(elbow(x1, pcy, x2, cy)))
        if num == 5:
            g.append(arrow(f"M {cx} 509 V 401", color=STEEL, width=1.4,
                           dashed=True, both=True))
            g.append(box(cx - 105, vnpay_y - 26, 210, 52, fill=SURFACE,
                         stroke=INK, stroke_opacity=0.4, rx=0, dashed=True,
                         stroke_width=1))
            g.append(text(cx, vnpay_y, "payment · IPN", size=16, fill=STEEL,
                          weight=600))
        g.append(step(cx, cy, num, lines, actor_name, outcome))
        c.append(f'<g class="fragment" data-fragment-index="{num}">' + "".join(g) + "</g>")

    c.append("</svg>")
    (OUT / "flow-2-handover.svg").write_text("\n".join(c), encoding="utf-8")
    print("wrote", OUT / "flow-2-handover.svg")


def flow_5_access():
    c = ['<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="760" '
         'viewBox="0 0 1600 760">', defs(), f'<rect width="1600" height="760" fill="{BG}"/>']

    # Admin hub.
    c.append(box(680, 330, 240, 100, fill=SIGNAL, stroke=SIGNAL,
                 stroke_opacity=1, rx=0, stroke_width=2))
    c.append(text(800, 380, "Admin", size=36, fill=SIGNAL_INK, weight=700))

    # RBAC layer.
    c.append(box(1080, 230, 200, 80, fill=SURFACE, stroke=LINK,
                 stroke_opacity=1, rx=0, stroke_width=2))
    c.append(f'<rect x="1080" y="230" width="3" height="80" fill="{LINK}"/>')
    c.append(text(1180, 270, "RBAC", size=24, fill=LINK, weight=700))

    # Roles under RBAC. Names only.
    roles = [("Customer", 120), ("Facility Manager", 210),
             ("Facility Staff", 300), ("BOM", 390)]
    for name, y in roles:
        c.append(box(1360, y, 170, 52, fill=SURFACE, stroke=INK,
                     stroke_opacity=0.26, rx=0, stroke_width=1))
        c.append(f'<rect x="1360" y="{y}" width="3" height="52" fill="{STEEL}"/>')
        c.append(text(1445, y + 26, name, size=16, fill=INK, weight=600))
        c.append(arrow(f"M 1280 270 H 1320 V {y + 26} H 1360"))

    # Admin branches.
    branches = [("Accounts", 160), ("Role requests", 280),
                ("Login history", 400), ("Audit log", 520)]
    for name, y in branches:
        c.append(box(300, y, 220, 70, fill=SURFACE, stroke=INK,
                     stroke_opacity=0.26, rx=0, stroke_width=1))
        c.append(f'<rect x="300" y="{y}" width="3" height="70" fill="{STEEL}"/>')
        c.append(text(410, y + 35, name, size=18, fill=INK, weight=600))
        c.append(arrow(f"M 680 380 H 640 V {y + 35} H 520"))

    # Admin -> RBAC.
    c.append(arrow("M 920 380 H 1000 V 270 H 1080"))

    c.append("</svg>")
    (OUT / "flow-5-access.svg").write_text("\n".join(c), encoding="utf-8")
    print("wrote", OUT / "flow-5-access.svg")


if __name__ == "__main__":
    context()
    flow_1()
    flow_2()
    flow_5_access()
