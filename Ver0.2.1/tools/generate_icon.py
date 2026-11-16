#!/usr/bin/env python3
"""Процедурно генерує іконку додатка (екран осцилографа) для всіх щільностей.
Запуск:  python3 tools/generate_icon.py [seed]      (потрібен Pillow: pip install pillow)
Результат: app/src/main/res/mipmap-*/ic_launcher_foreground.png + tools/icon_preview.png (512x512)."""
import math, random, sys, os
from PIL import Image, ImageDraw, ImageFilter

SEED = int(sys.argv[1]) if len(sys.argv) > 1 else 2026
BG = (11, 20, 23)
SCREEN = (8, 14, 16)
ACCENT = (255, 152, 0)

def trace(rnd, n):
    f1, f2, f3 = 1 + rnd.randrange(2), 3 + rnd.randrange(4), 7 + rnd.randrange(6)
    p1, p2, p3 = (rnd.random() * 6.28 for _ in range(3))
    a2, a3 = 0.35 + rnd.random() * 0.25, 0.1 + rnd.random() * 0.15
    return [(math.sin(f1*t+p1) + a2*math.sin(f2*t+p2) + a3*math.sin(f3*t+p3)) / (1 + a2 + a3)
            for t in (i / (n - 1) * 2 * math.pi for i in range(n))]

def render(size, adaptive=True):
    S = size * 4                                   # суперсемплінг
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = S / 2
    r = S * (66 / 108) / 2 * (1.0 if adaptive else 1.28)   # безпечна зона адаптивної іконки = 66dp із 108dp
    d.ellipse([c - r, c - r, c + r, c + r], fill=SCREEN + (255,), outline=(40, 70, 70, 255), width=max(2, S // 140))
    # сітка
    g = Image.new("RGBA", (S, S), (0, 0, 0, 0)); gd = ImageDraw.Draw(g)
    for i in range(-4, 5):
        a = 150 if i == 0 else 55
        x = c + i * r / 4
        gd.line([x, c - r, x, c + r], fill=ACCENT + (a,), width=max(1, S // 300))
        gd.line([c - r, x, c + r, x], fill=ACCENT + (a,), width=max(1, S // 300))
    mask = Image.new("L", (S, S), 0); ImageDraw.Draw(mask).ellipse([c - r + 3, c - r + 3, c + r - 3, c + r - 3], fill=255)
    img.paste(g, (0, 0), Image.composite(g.split()[3], Image.new("L", (S, S), 0), mask))
    # осцилограма
    rnd = random.Random(SEED)
    n = 400
    v = trace(rnd, n)
    pts = [(c - r * 0.92 + i / (n - 1) * r * 1.84, c - v[i] * r * 0.55) for i in range(n)]
    layers = []
    for width, alpha, blur in ((S // 22, 90, S // 40), (S // 40, 150, S // 120), (S // 90, 255, 0)):
        L = Image.new("RGBA", (S, S), (0, 0, 0, 0)); ld = ImageDraw.Draw(L)
        color = ACCENT if width > S // 90 else (255, 236, 200)
        ld.line(pts, fill=color + (alpha,), width=max(1, width), joint="curve")
        if blur: L = L.filter(ImageFilter.GaussianBlur(blur))
        layers.append(L)
    clip = Image.new("L", (S, S), 0); ImageDraw.Draw(clip).ellipse([c - r + 3, c - r + 3, c + r - 3, c + r - 3], fill=255)
    for L in layers:
        a = Image.composite(L.split()[3], Image.new("L", (S, S), 0), clip)
        L.putalpha(a)
        img = Image.alpha_composite(img, L)
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
    for dens, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        out = os.path.join(root, "app/src/main/res", f"mipmap-{dens}")
        os.makedirs(out, exist_ok=True)
        render(px).save(os.path.join(out, "ic_launcher_foreground.png"))
    # попередній перегляд (як на аватарці): круг на темному тлі
    prev = Image.new("RGBA", (512, 512), BG + (255,))
    prev.alpha_composite(render(512 * 108 // 66 if False else 512, adaptive=False))
    prev.save(os.path.join(root, "tools/icon_preview.png"))
    print("done, seed", SEED)
