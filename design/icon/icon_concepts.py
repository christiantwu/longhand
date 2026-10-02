"""Icon concepts for Longhand, defined once and emitted as SVG (preview) or
Android VectorDrawable (app). The app ships Ink Wave; Duet and Turns were the
alternatives considered (Duet's themed version needs `pip install skia-pathops`).

Coordinates are in the 108x108 adaptive-icon space: launchers show the central
72x72 (18..90) through a mask; the circle of radius 33 around (54,54) is always
visible.

Only features VectorDrawable supports are used: paths made of M/L/C/Z, linear and
radial gradients in user space, fill/stroke alpha, round stroke caps, even-odd fill.
"""

K = 0.5523  # cubic approximation of a quarter circle


# ---------------------------------------------------------------- path building
class P:
    """A path as a list of (cmd, args) with cmd in M L C Z."""

    def __init__(self):
        self.cmds = []

    def M(self, x, y): self.cmds.append(("M", (x, y))); return self
    def L(self, x, y): self.cmds.append(("L", (x, y))); return self
    def C(self, *a): self.cmds.append(("C", a)); return self
    def Z(self): self.cmds.append(("Z", ())); return self

    def __add__(self, other):
        p = P(); p.cmds = self.cmds + other.cmds; return p

    def d(self):
        out = []
        for c, a in self.cmds:
            out.append(c + " ".join(f"{v:.3f}".rstrip("0").rstrip(".") for v in a))
        return " ".join(out)

    def mirrored_x(self, cx):
        p = P()
        for c, a in self.cmds:
            p.cmds.append((c, tuple((2 * cx - v) if i % 2 == 0 else v for i, v in enumerate(a))))
        return p

    def to_skia(self):
        import pathops
        sp = pathops.Path()
        pen = sp.getPen()
        for c, a in self.cmds:
            if c == "M": pen.moveTo(a)
            elif c == "L": pen.lineTo(a)
            elif c == "C": pen.curveTo(a[0:2], a[2:4], a[4:6])
            elif c == "Z": pen.closePath()
        return sp

    @staticmethod
    def from_skia(sp):
        p = P()
        for verb, pts in sp.segments:
            if verb == "moveTo": p.M(*pts[0])
            elif verb == "lineTo": p.L(*pts[0])
            elif verb == "curveTo": p.C(*pts[0], *pts[1], *pts[2])
            elif verb == "qCurveTo":
                # quadratic -> cubic (pathops may emit these)
                (x0, y0) = p_last(p)
                (qx, qy), (x, y) = pts
                p.C(x0 + 2 / 3 * (qx - x0), y0 + 2 / 3 * (qy - y0), x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y)
            elif verb in ("closePath", "endPath"): p.Z()
        return p


def p_last(p):
    for c, a in reversed(p.cmds):
        if c in "MLC": return a[-2], a[-1]
    return 0, 0


def rrect(x, y, w, h, r):
    k = K * r
    return (P().M(x + r, y).L(x + w - r, y).C(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
            .L(x + w, y + h - r).C(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
            .L(x + r, y + h).C(x + r - k, y + h, x, y + h - r + k, x, y + h - r)
            .L(x, y + r).C(x, y + r - k, x + r - k, y, x + r, y).Z())


def pill(x1, x2, y, t):
    """Horizontal capsule from x1 to x2 (cap centres), thickness t, centred on y."""
    return rrect(x1 - t / 2, y - t / 2, x2 - x1 + t, t, t / 2)


def vpill(x, y1, y2, t):
    return rrect(x - t / 2, y1 - t / 2, t, y2 - y1 + t, t / 2)




def bubble(x, y, w, h, r, tail="left", tip=(1.6, 5.2)):
    """Rounded rectangle whose bottom-left (or right) corner becomes a speech tail."""
    k = K * r
    tx, ty = tip
    p = (P().M(x + r, y).L(x + w - r, y).C(x + w - r + k, y, x + w, y + r - k, x + w, y + r)
         .L(x + w, y + h - r).C(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
         .L(x + r + 2, y + h)
         # a short hook with a softly rounded tip just outside the left edge
         .C(x + r * 0.45, y + h, x + 0.8, y + h + ty, x - tx + 1.2, y + h + ty - 0.1)
         .C(x - tx + 0.4, y + h + ty - 0.05, x - tx, y + h + ty - 0.6, x - tx, y + h + ty - 1.4)
         .C(x - tx + 0.3, y + h + ty - 3.2, x, y + h - 0.5, x, y + h - r * 0.7)
         .L(x, y + r).C(x, y + r - k, x + r - k, y, x + r, y).Z())
    return p if tail == "left" else p.mirrored_x(x + w / 2)


def sine_wave(x0, y, halfwaves):
    """Smooth wave starting at (x0, y): halfwaves = [(width, height)], height>0 is up."""
    p = P().M(x0, y)
    x = x0
    for w, h in halfwaves:
        c = 4 / 3 * h
        p.C(x + w * 0.25, y - c, x + w * 0.75, y - c, x + w, y)
        x += w
    return p, x


# ---------------------------------------------------------------- paint
class Lin:
    def __init__(self, x1, y1, x2, y2, stops): self.a = (x1, y1, x2, y2); self.stops = stops


class Rad:
    def __init__(self, cx, cy, r, stops): self.a = (cx, cy, r); self.stops = stops


def fill(path, paint, alpha=1.0, evenodd=False): return dict(kind="fill", path=path, paint=paint, alpha=alpha, evenodd=evenodd)
def stroke(path, paint, width, alpha=1.0): return dict(kind="stroke", path=path, paint=paint, width=width, alpha=alpha)


def rgba(c):
    """'#RRGGBB' or ('#RRGGBB', alpha) -> (hex, alpha)."""
    return (c, 1.0) if isinstance(c, str) else c


# ---------------------------------------------------------------- emitters
def to_svg(shapes, prefix):
    defs, body = [], []
    for i, s in enumerate(shapes):
        paint = s["paint"]
        if isinstance(paint, (Lin, Rad)):
            gid = f"{prefix}g{i}"
            stops = "".join(
                f'<stop offset="{o}" stop-color="{rgba(c)[0]}" stop-opacity="{rgba(c)[1]}"/>' for o, c in paint.stops)
            if isinstance(paint, Lin):
                x1, y1, x2, y2 = paint.a
                defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}">{stops}</linearGradient>')
            else:
                cx, cy, r = paint.a
                defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{cx}" cy="{cy}" r="{r}">{stops}</radialGradient>')
            ref = f"url(#{gid})"
        else:
            ref, a = rgba(paint)
        alpha = s["alpha"] * (1.0 if isinstance(paint, (Lin, Rad)) else a)
        if s["kind"] == "fill":
            rule = ' fill-rule="evenodd"' if s["evenodd"] else ""
            body.append(f'<path d="{s["path"].d()}" fill="{ref}" fill-opacity="{alpha:.3f}"{rule}/>')
        else:
            body.append(f'<path d="{s["path"].d()}" fill="none" stroke="{ref}" stroke-width="{s["width"]}" '
                        f'stroke-linecap="round" stroke-linejoin="round" stroke-opacity="{alpha:.3f}"/>')
    return "".join(defs), "".join(body)


def vd_color(c):
    h, a = rgba(c)
    return f"#{round(a * 255):02X}{h[1:].upper()}"


def to_vector_drawable(shapes, comment=""):
    out = ['<?xml version="1.0" encoding="utf-8"?>']
    if comment: out.append(f"<!-- {comment} -->")
    out.append('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
               '    xmlns:aapt="http://schemas.android.com/aapt"\n'
               '    android:width="108dp" android:height="108dp"\n'
               '    android:viewportWidth="108" android:viewportHeight="108">')
    for s in shapes:
        attr = "android:fillColor" if s["kind"] == "fill" else "android:strokeColor"
        extra = ""
        if s["kind"] == "fill":
            if s["alpha"] != 1.0: extra += f'\n        android:fillAlpha="{s["alpha"]}"'
            if s["evenodd"]: extra += '\n        android:fillType="evenOdd"'
        else:
            extra += (f'\n        android:strokeWidth="{s["width"]}"\n        android:strokeLineCap="round"'
                      f'\n        android:strokeLineJoin="round"')
            if s["alpha"] != 1.0: extra += f'\n        android:strokeAlpha="{s["alpha"]}"'
        paint = s["paint"]
        if isinstance(paint, (Lin, Rad)):
            if isinstance(paint, Lin):
                x1, y1, x2, y2 = paint.a
                g = (f'<gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
                     f'android:endX="{x2}" android:endY="{y2}">')
            else:
                cx, cy, r = paint.a
                g = (f'<gradient android:type="radial" android:centerX="{cx}" android:centerY="{cy}" '
                     f'android:gradientRadius="{r}">')
            items = "".join(f'\n                <item android:offset="{o}" android:color="{vd_color(c)}" />' for o, c in paint.stops)
            out.append(f'    <path\n        android:pathData="{s["path"].d()}"{extra}>\n'
                       f'        <aapt:attr name="{attr}">\n            {g}{items}\n            </gradient>\n'
                       f'        </aapt:attr>\n    </path>')
        else:
            out.append(f'    <path\n        android:pathData="{s["path"].d()}"\n        {attr}="{vd_color(paint)}"{extra} />')
    out.append("</vector>")
    return "\n".join(out) + "\n"


# ---------------------------------------------------------------- concepts
FULL = rrect(0, 0, 108, 108, 0)
WHITE = "#FFFFFF"
INK = "#000000"


def duet():
    """Two speech bubbles overlapping: a conversation, written down."""
    bw, bh, r = 30, 23, 9.5
    back = bubble(31, 33, bw, bh, r, "left")
    front = bubble(46, 46.5, bw, bh, r, "right")
    lines = [pill(52.5, 69.5, 53.6, 3.3), pill(52.5, 66, 58.1, 3.3), pill(52.5, 61, 62.6, 3.3)]
    bg = [
        fill(FULL, Lin(18, 18, 90, 90, [(0, "#4361FF"), (1, "#1D29B0")])),
        fill(FULL, Rad(30, 24, 52, [(0, ("#FFFFFF", 0.22)), (1, ("#FFFFFF", 0.0))])),
    ]
    fg = [
        fill(back, Lin(31, 33, 61, 61, [(0, "#FFCD70"), (1, "#FF8447")])),
        # soft shadow of the front bubble falling on the back one
        fill(bubble(46, 48.2, bw, bh, r, "right"), "#0B1260", alpha=0.22),
        fill(front, Lin(46, 46.5, 76, 75, [(0, "#FFFFFF"), (1, "#E3E9FF")])),
    ] + [fill(l, c) for l, c in zip(lines, ["#2F45E0", ("#2F45E0", 0.5), ("#2F45E0", 0.5)])]
    # monochrome: back bubble with a gap around the front one; text lines cut out of the front
    g = 2.4
    import pathops
    halo = bubble(46 - g, 46.5 - g, bw + 2 * g, bh + 2 * g, r + g, "right", tip=(1.6 + g * 0.7, 5.2 + g * 0.7))
    back_cut = P.from_skia(pathops.op(back.to_skia(), halo.to_skia(), pathops.PathOp.DIFFERENCE))
    mono = [fill(back_cut, INK), fill(front + lines[0] + lines[1] + lines[2], INK, evenodd=True)]
    return dict(bg=bg, fg=fg, mono=mono)


def ink_wave():
    """A voice wave that settles into a line of text, with more lines below."""
    y1, gap, t = 44.5, 13.5, 4.4
    xl, xr = 33, 75
    # strongest at the start, fading out as it becomes a line of text
    wave, xe = sine_wave(xl, y1, [(5, 8.5), (5, -7), (5, 5), (5, -3), (5, 1.4)])
    wave.L(xr, y1)
    grad = Lin(xl, y1, xr, y1, [(0, "#FF8A6B"), (0.45, "#D07BFF"), (1, "#7DB8FF")])
    l2 = P().M(xl, y1 + gap).L(xr - 6, y1 + gap)
    l3 = P().M(xl, y1 + 2 * gap).L(xl + 22, y1 + 2 * gap)
    bg = [
        fill(FULL, Lin(18, 18, 90, 90, [(0, "#1C2152"), (1, "#090B22")])),
        fill(FULL, Rad(34, 30, 46, [(0, ("#6464FF", 0.32)), (1, ("#6464FF", 0.0))])),
    ]
    fg = [
        stroke(wave, grad, t),
        stroke(l2, WHITE, t, alpha=0.92),
        stroke(l3, WHITE, t, alpha=0.5),
    ]
    mono = [stroke(wave, INK, t), stroke(l2, INK, t), stroke(l3, INK, t, alpha=0.6)]
    return dict(bg=bg, fg=fg, mono=mono)


def turns():
    """A waveform in two colours: two people taking turns."""
    heights = [10, 20, 30, 16, 26, 34, 18, 12]
    who = [0, 0, 0, 1, 1, 1, 0, 0]
    t, gap = 4.6, 2.4
    n = len(heights)
    x0 = 54 - ((n - 1) * (t + gap)) / 2
    bars = [vpill(x0 + i * (t + gap), 54 - h / 2 + t / 2, 54 + h / 2 - t / 2, t) for i, h in enumerate(heights)]
    blue = Lin(0, 36, 0, 72, [(0, "#5C7CFF"), (1, "#2F45E0")])
    amber = Lin(0, 36, 0, 72, [(0, "#FFC15E"), (1, "#FF7F45")])
    bg = [
        fill(FULL, Lin(18, 18, 90, 90, [(0, "#FFFFFF"), (1, "#E4E9FF")])),
    ]
    fg = [fill(b, amber if w else blue) for b, w in zip(bars, who)]
    mono = [fill(b, INK) for b in bars]
    return dict(bg=bg, fg=fg, mono=mono)


CONCEPTS = {"duet": duet, "ink": ink_wave, "turns": turns}
