#!/usr/bin/env python3
"""
Erzeugt die Bilder für den Kinosaal (Texturen und Zuschauer) als PNG.

Alle Figuren sind selbst gezeichnete, stark vereinfachte Fan-Art im Silhouetten-Stil.
Ausgabe nach app/src/main/res/drawable-nodpi/cinema_*.png

Aufruf: python3 tools/cinema_art.py
"""
import math
import os
import random

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable-nodpi")
SS = 4  # Überabtastung für glatte Kanten
FONT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "font", "space_mono.ttf")


class Pic:
    """Zeichenfläche mit Überabtastung. Koordinaten in Zielpixeln."""

    def __init__(self, w, h, bg=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w * SS, h * SS), bg)
        self.d = ImageDraw.Draw(self.img)

    def s(self, pts):
        return [(x * SS, y * SS) for x, y in pts]

    def poly(self, pts, fill):
        self.d.polygon(self.s(pts), fill=fill)

    def ellipse(self, x0, y0, x1, y1, fill):
        self.d.ellipse([x0 * SS, y0 * SS, x1 * SS, y1 * SS], fill=fill)

    def rect(self, x0, y0, x1, y1, fill, r=0):
        if r:
            self.d.rounded_rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], r * SS, fill=fill)
        else:
            self.d.rectangle([x0 * SS, y0 * SS, x1 * SS, y1 * SS], fill=fill)

    def line(self, pts, fill, width):
        self.d.line(self.s(pts), fill=fill, width=int(width * SS), joint="curve")
        for x, y in (pts[0], pts[-1]):
            r = width / 2
            self.ellipse(x - r, y - r, x + r, y + r, fill)

    def text(self, xy, txt, size, fill, anchor="mm"):
        f = ImageFont.truetype(FONT, int(size * SS))
        self.d.text((xy[0] * SS, xy[1] * SS), txt, font=f, fill=fill, anchor=anchor)

    def layer(self):
        """Neue leere Ebene gleicher Größe (für Verläufe in Formen)."""
        p = Pic(self.w, self.h)
        return p

    def paste(self, other):
        self.img = Image.alpha_composite(self.img, other.img)
        self.d = ImageDraw.Draw(self.img)

    def done(self):
        return self.img.resize((self.w, self.h), Image.LANCZOS)


def smooth_poly(pts, n=8):
    """Catmull-Rom durch geschlossene Punktliste, für weiche Umrisse."""
    out = []
    m = len(pts)
    for i in range(m):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % m], pts[(i + 2) % m]
        for k in range(n):
            t = k / n
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    return out


def shade(img, fn):
    """RGB mit fn(x, y) in [0..2] multiplizieren (x, y normiert 0..1)."""
    a = np.asarray(img).astype(np.float32)
    h, w = a.shape[:2]
    ys, xs = np.mgrid[0:h, 0:w]
    f = fn(xs / max(1, w - 1), ys / max(1, h - 1)).astype(np.float32)
    a[..., :3] = np.clip(a[..., :3] * f[..., None], 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def rim(img, color=(255, 220, 200), strength=0.55, radius=3, top_bias=True):
    """Lichtkante von der Leinwand: helle Kontur an den oberen Rändern."""
    alpha = img.split()[3]
    inner = alpha.filter(ImageFilter.MinFilter(radius * 2 + 1))
    edge = np.asarray(alpha).astype(np.float32) - np.asarray(inner).astype(np.float32)
    edge = np.clip(edge / 255.0, 0, 1)
    h, w = edge.shape
    if top_bias:
        ys = np.linspace(1.0, 0.25, h)[:, None]
        edge *= ys
    a = np.asarray(img).astype(np.float32)
    for i in range(3):
        a[..., i] = np.clip(a[..., i] + edge * color[i] * strength, 0, 255)
    return Image.fromarray(a.astype(np.uint8), "RGBA")


def noise_tex(w, h, base, var, seed, scale=1.0):
    rng = np.random.default_rng(seed)
    n = rng.normal(0, var, (h, w, 1)).astype(np.float32)
    img = np.ones((h, w, 3), np.float32) * np.array(base, np.float32) + n * scale
    return np.clip(img, 0, 255)


def save(img, name):
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name)
    img.save(path, optimize=True)
    print("geschrieben", path, img.size)


# ---------------------------------------------------------------- Texturen

def seat_texture():
    """Rückseite einer Rückenlehne: roter Samt mit Steppnähten, oben heller."""
    w, h = 256, 320
    a = noise_tex(w, h, (120, 18, 26), 6, 1)
    ys, xs = np.mgrid[0:h, 0:w]
    # Gepolsterte Kammern senkrecht
    ch = 0.82 + 0.18 * np.cos((xs / w) * 2 * math.pi * 4)
    # Oben gewölbt, unten dunkler
    v = 1.15 - 0.55 * (ys / h)
    edge = np.minimum(np.minimum(xs, w - 1 - xs) / 26.0, 1.0) * 0.35 + 0.65
    a *= (ch * v * edge)[..., None]
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(img)
    # Nähte
    for i in range(1, 4):
        x = int(w * i / 4)
        d.line([(x, 18), (x, h - 30)], fill=(60, 8, 12, 255), width=2)
    d.line([(14, 74), (w - 14, 74)], fill=(70, 10, 14, 255), width=2)
    return img


def curtain_texture():
    """Samtvorhang: senkrechte Falten, kachelbar in der Breite."""
    w, h = 256, 512
    ys, xs = np.mgrid[0:h, 0:w]
    folds = 0.55 + 0.45 * np.cos(xs / w * 2 * math.pi * 3) ** 2
    shine = 0.12 * np.maximum(0, np.cos(xs / w * 2 * math.pi * 3 + 0.6)) ** 8
    base = noise_tex(w, h, (110, 8, 16), 5, 2)
    v = 1.0 - 0.25 * (ys / h)
    a = base * (folds * v)[..., None] + (shine * 255)[..., None] * np.array([1.0, 0.5, 0.5])
    return Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def carpet_texture():
    """Dunkler Teppich mit kleinem Muster, kachelbar."""
    w = h = 256
    a = noise_tex(w, h, (34, 16, 20), 7, 3)
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(img)
    for y in range(0, h, 64):
        for x in range(0, w, 64):
            ox = 32 if (y // 64) % 2 else 0
            cx, cy = (x + ox) % w, y + 32
            d.polygon([(cx, cy - 10), (cx + 10, cy), (cx, cy + 10), (cx - 10, cy)], fill=(70, 40, 20, 255))
    return img.filter(ImageFilter.GaussianBlur(0.6))


def wall_texture():
    """Wandbespannung: dunkles Bordeaux, Stoffstruktur, waagerechte Fuge."""
    w = h = 256
    a = noise_tex(w, h, (52, 16, 20), 4, 4)
    ys, xs = np.mgrid[0:h, 0:w]
    a *= (0.9 + 0.1 * np.cos(ys / h * 2 * math.pi))[..., None]
    img = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    d = ImageDraw.Draw(img)
    d.line([(0, 0), (0, h)], fill=(20, 6, 8, 255), width=4)
    return img


# ---------------------------------------------------------------- Zuschauer von hinten

CELL_W, CELL_H = 256, 320


def back_vader(p):
    # Umhang und Schultern
    p.poly(smooth_poly([(20, 320), (40, 230), (90, 205), (166, 205), (216, 230), (236, 320)]), (14, 14, 16, 255))
    # Helm: Kuppel mit ausgestelltem Nackenschutz
    p.poly(smooth_poly([(70, 210), (62, 150), (78, 92), (128, 68), (178, 92), (194, 150), (186, 210), (160, 222), (96, 222)], 6), (22, 22, 26, 255))
    p.ellipse(84, 78, 172, 160, (30, 30, 34, 255))
    # Glanzlicht
    p.line([(108, 90), (100, 140)], (120, 120, 140, 160), 7)


def back_trooper(p):
    p.poly(smooth_poly([(24, 320), (40, 240), (96, 214), (160, 214), (216, 240), (232, 320)]), (225, 225, 222, 255))
    p.rect(100, 196, 156, 226, (40, 40, 44, 255), 8)
    p.poly(smooth_poly([(78, 204), (70, 140), (88, 92), (128, 74), (168, 92), (186, 140), (178, 204), (128, 214)], 6), (238, 238, 236, 255))
    p.rect(78, 176, 178, 186, (70, 70, 76, 255), 4)
    p.line([(128, 82), (128, 170)], (190, 190, 194, 255), 5)


def back_yoda(p):
    # Klein: Kopf tiefer, große Ohren
    p.poly(smooth_poly([(64, 320), (76, 262), (128, 248), (180, 262), (192, 320)]), (96, 70, 44, 255))
    p.poly(smooth_poly([(98, 200), (20, 178), (36, 196), (100, 228)], 4), (110, 140, 70, 255))
    p.poly(smooth_poly([(158, 200), (236, 178), (220, 196), (156, 228)], 4), (110, 140, 70, 255))
    p.ellipse(90, 176, 166, 256, (118, 150, 76, 255))
    for i in range(7):
        x = 100 + i * 9
        p.line([(x, 184), (x - 6 + i * 2, 170)], (220, 220, 210, 200), 2)


def back_chewie(p):
    rng = random.Random(5)
    body = smooth_poly([(10, 320), (24, 200), (70, 150), (90, 70), (128, 46), (166, 70), (186, 150), (232, 200), (246, 320)], 6)
    p.poly(body, (92, 62, 36, 255))
    for _ in range(420):
        x = rng.uniform(30, 226)
        y = rng.uniform(56, 318)
        if y < 140 and abs(x - 128) > 48:
            continue
        c = rng.choice([(70, 46, 26, 255), (120, 84, 50, 255), (60, 40, 22, 255)])
        p.line([(x, y), (x + rng.uniform(-4, 4), y + rng.uniform(8, 16))], c, 2.2)
    # Patronengurt
    p.line([(40, 214), (214, 318)], (60, 42, 26, 255), 22)
    for i in range(6):
        t = 0.12 + i * 0.15
        x = 40 + (214 - 40) * t
        y = 214 + (318 - 214) * t
        p.rect(x - 8, y - 9, x + 8, y + 9, (150, 150, 156, 255), 3)


def back_r2(p):
    p.rect(64, 200, 192, 330, (220, 222, 228, 255), 16)
    p.rect(96, 220, 160, 300, (40, 80, 170, 255), 6)
    p.d.pieslice([64 * SS, 120 * SS, 192 * SS, 280 * SS], 180, 360, fill=(200, 202, 210, 255))
    p.rect(80, 168, 176, 184, (40, 80, 170, 255), 3)
    p.rect(118, 136, 138, 156, (40, 80, 170, 255), 3)


def back_threepio(p):
    p.poly(smooth_poly([(30, 320), (46, 236), (98, 214), (158, 214), (210, 236), (226, 320)]), (190, 150, 60, 255))
    p.ellipse(82, 110, 174, 214, (206, 166, 70, 255))
    p.rect(92, 180, 164, 214, (176, 136, 52, 255), 10)
    p.line([(100, 136), (96, 170)], (255, 230, 150, 180), 6)


def back_leia(p):
    p.poly(smooth_poly([(30, 320), (46, 238), (100, 214), (156, 214), (210, 238), (226, 320)]), (236, 234, 228, 255))
    p.ellipse(88, 110, 168, 210, (70, 42, 24, 255))
    p.ellipse(50, 136, 102, 196, (82, 50, 28, 255))
    p.ellipse(154, 136, 206, 196, (82, 50, 28, 255))
    for r in (10, 18):
        p.d.arc([(76 - r) * SS, (166 - r) * SS, (76 + r) * SS, (166 + r) * SS], 0, 300, fill=(54, 32, 18, 255), width=3 * SS)
        p.d.arc([(180 - r) * SS, (166 - r) * SS, (180 + r) * SS, (166 + r) * SS], 0, 300, fill=(54, 32, 18, 255), width=3 * SS)


def back_boba(p):
    p.rect(54, 214, 202, 320, (90, 96, 80, 255), 20)
    # Raketenrucksack
    p.rect(88, 196, 168, 300, (110, 120, 104, 255), 10)
    p.ellipse(110, 170, 146, 210, (180, 60, 40, 255))
    p.poly(smooth_poly([(84, 200), (78, 140), (92, 98), (128, 82), (164, 98), (178, 140), (172, 200)], 6), (100, 114, 92, 255))
    p.line([(170, 120), (178, 60)], (60, 60, 60, 255), 5)
    p.rect(172, 52, 184, 72, (70, 70, 70, 255), 3)


BACKS = [back_vader, back_trooper, back_yoda, back_chewie, back_r2, back_threepio, back_leia, back_boba]


def audience_atlas():
    atlas = Image.new("RGBA", (CELL_W * len(BACKS), CELL_H), (0, 0, 0, 0))
    for i, fn in enumerate(BACKS):
        p = Pic(CELL_W, CELL_H)
        fn(p)
        img = p.done()
        # Von hinten gesehen, Licht kommt von vorn: dunkel, mit heller Kante oben
        img = shade(img, lambda x, y: 0.42 + 0.25 * (1 - y))
        img = rim(img, strength=0.35, radius=2)
        atlas.paste(img, (i * CELL_W, 0), img)
    return atlas


def jawa_peek():
    """Jawa, der sich umdreht und über die Lehne schaut: Kapuze, leuchtende Augen."""
    p = Pic(256, 220)
    p.poly(smooth_poly([(40, 230), (60, 120), (100, 50), (128, 30), (156, 50), (196, 120), (216, 230)], 6), (74, 52, 34, 255))
    p.ellipse(82, 92, 174, 190, (12, 8, 6, 255))
    img = p.done()
    img = shade(img, lambda x, y: 0.55 + 0.3 * (1 - y))
    glow = Pic(256, 220)
    for cx in (110, 146):
        for r, a in ((22, 40), (14, 90), (8, 255)):
            glow.ellipse(cx - r, 138 - r * 0.6, cx + r, 138 + r * 0.6, (255, 230, 90, a))
    return Image.alpha_composite(img, glow.done())


# ---------------------------------------------------------------- Nachbarn von der Seite

# Wie CinemaArt: z von -0.62 (vorn, links im Bild) bis 0.34, y von -1.2 (Boden) bis 0.32
SZ0, SZ1, SY0, SY1 = -0.62, 0.34, -1.2, 0.32
SW = 640
SH = int(SW * (SY1 - SY0) / (SZ1 - SZ0))


def X(z):
    return (z - SZ0) / (SZ1 - SZ0) * SW


def Y(y):
    return (SY1 - y) / (SY1 - SY0) * SH


def M(m):
    return m / (SZ1 - SZ0) * SW


def side_yoda(turned):
    p = Pic(SW, SH)
    robe = (104, 76, 46, 255)
    robe_d = (84, 60, 36, 255)
    green = (120, 152, 78, 255)
    # Sitzt ganz hinten auf dem Sitz, Füße reichen nicht zum Boden
    p.poly(smooth_poly([(X(0.22), Y(-0.74)), (X(-0.18), Y(-0.74)), (X(-0.24), Y(-0.60)), (X(0.0), Y(-0.56)), (X(0.12), Y(-0.40)), (X(0.26), Y(-0.40))], 6), robe)
    p.line([(X(-0.2), Y(-0.70)), (X(-0.26), Y(-0.88))], robe_d, M(0.07))
    p.ellipse(X(-0.32), Y(-0.86), X(-0.20), Y(-0.92), (96, 128, 60, 255))
    # Oberkörper
    p.poly(smooth_poly([(X(0.04), Y(-0.70)), (X(0.06), Y(-0.36)), (X(0.14), Y(-0.30)), (X(0.24), Y(-0.36)), (X(0.26), Y(-0.70))], 6), robe)
    # Stock
    p.line([(X(-0.10), Y(-0.30)), (X(-0.14), Y(-0.86))], (60, 40, 24, 255), M(0.018))
    p.line([(X(0.14), Y(-0.40)), (X(-0.08), Y(-0.48))], robe_d, M(0.06))
    p.ellipse(X(-0.12), Y(-0.44), X(-0.06), Y(-0.51), green)
    hx, hy = 0.12, -0.20
    if not turned:
        # Ohr nach hinten
        p.poly(smooth_poly([(X(hx + 0.02), Y(hy + 0.03)), (X(hx + 0.20), Y(hy + 0.10)), (X(hx + 0.17), Y(hy + 0.04)), (X(hx + 0.05), Y(hy - 0.04))], 4), (104, 136, 66, 255))
        p.ellipse(X(hx - 0.10), Y(hy + 0.09), X(hx + 0.08), Y(hy - 0.10), green)
        p.ellipse(X(hx - 0.115), Y(hy + 0.0), X(hx - 0.07), Y(hy - 0.04), green)
        p.ellipse(X(hx - 0.07), Y(hy + 0.035), X(hx - 0.045), Y(hy + 0.01), (30, 22, 14, 255))
        p.line([(X(hx - 0.085), Y(hy - 0.06)), (X(hx - 0.04), Y(hy - 0.065))], (70, 90, 44, 255), M(0.006))
        for i in range(6):
            p.line([(X(hx - 0.02 + i * 0.02), Y(hy + 0.085)), (X(hx - 0.03 + i * 0.024), Y(hy + 0.12))], (220, 220, 210, 200), 2)
    else:
        # Zum Betrachter gedreht: Gesicht von vorn, beide Ohren seitlich
        p.poly(smooth_poly([(X(hx - 0.05), Y(hy + 0.02)), (X(hx - 0.20), Y(hy + 0.10)), (X(hx - 0.17), Y(hy + 0.02)), (X(hx - 0.06), Y(hy - 0.05))], 4), (104, 136, 66, 255))
        p.poly(smooth_poly([(X(hx + 0.05), Y(hy + 0.02)), (X(hx + 0.20), Y(hy + 0.10)), (X(hx + 0.17), Y(hy + 0.02)), (X(hx + 0.06), Y(hy - 0.05))], 4), (104, 136, 66, 255))
        p.ellipse(X(hx - 0.09), Y(hy + 0.09), X(hx + 0.09), Y(hy - 0.10), green)
        for ex in (-0.035, 0.035):
            p.ellipse(X(hx + ex - 0.02), Y(hy + 0.035), X(hx + ex + 0.02), Y(hy - 0.005), (20, 14, 8, 255))
            p.ellipse(X(hx + ex - 0.006), Y(hy + 0.028), X(hx + ex + 0.002), Y(hy + 0.02), (255, 255, 255, 220))
        p.line([(X(hx - 0.03), Y(hy - 0.06)), (X(hx + 0.03), Y(hy - 0.06))], (60, 80, 40, 255), M(0.006))
        # Finger an den Lippen
        p.line([(X(hx + 0.0), Y(hy - 0.16)), (X(hx + 0.0), Y(hy - 0.045))], green, M(0.016))
    img = finish_side(p.done())
    if turned:
        # Sprechblase, nicht abgedunkelt
        b = Pic(SW, SH)
        bx0, by0, bx1, by1 = X(-0.60), Y(0.31), X(0.10), Y(0.06)
        b.rect(bx0, by0, bx1, by1, (235, 235, 235, 255), 30)
        b.poly([(X(-0.05), by1 - 2), (X(0.08), Y(-0.06)), (X(0.04), by1 - 2)], (235, 235, 235, 255))
        b.text(((bx0 + bx1) / 2, (by0 + by1) / 2 - 16), "LEISE SEIN,", 34, (0, 0, 0, 255))
        b.text(((bx0 + bx1) / 2, (by0 + by1) / 2 + 22), "DU MUSST!", 34, (0, 0, 0, 255))
        img = Image.alpha_composite(img, b.done())
    return img


def side_vader():
    p = Pic(SW, SH)
    black = (18, 18, 22, 255)
    black2 = (30, 30, 36, 255)
    # Stiefel, Beine
    p.rect(X(-0.60), Y(-1.12), X(-0.36), Y(-1.2), (10, 10, 12, 255), 10)
    p.line([(X(-0.44), Y(-0.62)), (X(-0.45), Y(-1.12))], black2, M(0.13))
    p.line([(X(0.14), Y(-0.64)), (X(-0.44), Y(-0.62))], black2, M(0.18))
    # Umhang hinten
    p.poly(smooth_poly([(X(0.32), Y(-0.98)), (X(0.30), Y(-0.20)), (X(0.20), Y(-0.06)), (X(0.06), Y(-0.70))], 6), (12, 12, 14, 255))
    # Oberkörper mit Brustpanel
    p.poly(smooth_poly([(X(0.02), Y(-0.68)), (X(0.04), Y(-0.16)), (X(0.14), Y(-0.08)), (X(0.26), Y(-0.14)), (X(0.28), Y(-0.68))], 6), black)
    p.rect(X(0.01), Y(-0.26), X(0.06), Y(-0.40), (60, 60, 66, 255), 4)
    for i, c in enumerate([(220, 40, 40, 255), (60, 200, 90, 255), (60, 120, 230, 255)]):
        p.ellipse(X(0.012), Y(-0.29 - i * 0.03), X(0.026), Y(-0.30 - i * 0.03), c)
    # Arm mit Popcorn in der Hand
    p.line([(X(0.17), Y(-0.16)), (X(0.10), Y(-0.44))], black2, M(0.09))
    p.line([(X(0.10), Y(-0.44)), (X(-0.16), Y(-0.46))], black2, M(0.08))
    p.poly([(X(-0.26), Y(-0.30)), (X(-0.10), Y(-0.30)), (X(-0.12), Y(-0.52)), (X(-0.24), Y(-0.52))], (200, 30, 30, 255))
    for i in range(3):
        p.rect(X(-0.235 + i * 0.05), Y(-0.31), X(-0.215 + i * 0.05), Y(-0.51), (240, 238, 230, 255))
    rng = random.Random(9)
    for _ in range(26):
        x = rng.uniform(X(-0.26), X(-0.10))
        y = Y(-0.30) - rng.uniform(0, 22)
        p.ellipse(x - 9, y - 8, x + 9, y + 8, (250, 236, 190, 255))
    # Helm im Profil
    hx, hy = 0.10, 0.03
    p.poly(smooth_poly([
        (X(hx + 0.16), Y(hy - 0.17)), (X(hx + 0.13), Y(hy + 0.07)), (X(hx + 0.04), Y(hy + 0.14)),
        (X(hx - 0.06), Y(hy + 0.12)), (X(hx - 0.10), Y(hy + 0.04)), (X(hx - 0.11), Y(hy - 0.03)),
        (X(hx - 0.14), Y(hy - 0.10)), (X(hx - 0.09), Y(hy - 0.15)), (X(hx + 0.02), Y(hy - 0.16)),
    ], 6), black)
    # Augenglas und Atemgitter
    p.ellipse(X(hx - 0.105), Y(hy + 0.03), X(hx - 0.05), Y(hy - 0.01), (60, 50, 60, 255))
    p.poly([(X(hx - 0.14), Y(hy - 0.09)), (X(hx - 0.08), Y(hy - 0.06)), (X(hx - 0.07), Y(hy - 0.14)), (X(hx - 0.11), Y(hy - 0.145))], (46, 46, 52, 255))
    p.line([(X(hx - 0.02), Y(hy + 0.12)), (X(hx + 0.10), Y(hy + 0.06))], (110, 110, 130, 150), 6)
    img = p.done()
    return finish_side(img)


def finish_side(img):
    # Licht von der Leinwand links, nach rechts dunkler, dazu Lichtkante
    img = shade(img, lambda x, y: 1.0 - 0.6 * x)
    img = rim(img, strength=0.25, radius=3, top_bias=False)
    return img


# ---------------------------------------------------------------- Notausgang

def exit_sign():
    p = Pic(600, 200, (0, 0, 0, 0))
    p.rect(0, 0, 600, 200, (16, 150, 80, 255), 18)
    p.text((250, 104), "AUSGANG", 74, (255, 255, 255, 255))
    p.poly([(560, 100), (500, 55), (500, 145)], (255, 255, 255, 255))
    return p.done()


if __name__ == "__main__":
    save(seat_texture(), "cinema_seat.png")
    save(curtain_texture(), "cinema_curtain.png")
    save(carpet_texture(), "cinema_carpet.png")
    save(wall_texture(), "cinema_wall.png")
    save(audience_atlas(), "cinema_audience.png")
    save(jawa_peek(), "cinema_jawa.png")
    save(side_yoda(False), "cinema_yoda.png")
    save(side_yoda(True), "cinema_yoda_turned.png")
    save(side_vader(), "cinema_vader.png")
    save(exit_sign(), "cinema_exit.png")
