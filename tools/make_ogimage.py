#!/usr/bin/env python3
"""配布ページの OG 画像（SNS などで共有したときに出る画像）を作る。

    python3 tools/make_ogimage.py

出力: docs/og.jpg  （1200x630 / JPEG）

JPEG なのは、透過を持たない形式のほうが各サービスで扱いが安定するため。
文字は Noto Sans CJK で描く。
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")

W, H = 1200, 630
BG = (14, 15, 17)
PANEL = (23, 26, 29)
TEXT = (232, 234, 237)
DIM = (154, 160, 166)
TRAIL = (46, 163, 111)
START = (229, 193, 0)
NOW = (248, 81, 73)
ACCENT = (31, 111, 235)

FONTS = {
    "black": "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc",
    "bold": "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "medium": "/usr/share/fonts/opentype/noto/NotoSansCJK-Medium.ttc",
}


def font(weight, size):
    path = FONTS.get(weight)
    if path and os.path.exists(path):
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            pass
    return ImageFont.load_default()


def main():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    # 背景にうっすら軌跡を流す。主役は文字なので彩度は抑える
    faint = (26, 36, 32)
    pts = [(-40, 470), (150, 400), (300, 440), (470, 300), (640, 350),
           (820, 190), (1000, 250), (1260, 120)]
    d.line(pts, fill=faint, width=34, joint="curve")

    # ---- アイコン
    icon_px = 200
    icon_xy = (84, (H - icon_px) // 2 - 28)
    icon_path = os.path.join(DOCS, "icon-512.png")
    if os.path.exists(icon_path):
        ic = Image.open(icon_path).convert("RGBA").resize((icon_px, icon_px), Image.LANCZOS)
        img.paste(ic, icon_xy, ic)

    x = icon_xy[0] + icon_px + 56

    # ---- アプリ名
    f_title = font("black", 92)
    d.text((x, 196), "GeoLogger", font=f_title, fill=TEXT)

    # ---- 説明
    f_lead = font("medium", 33)
    d.text((x, 310), "歩いた場所を記録して、地図に描いて、", font=f_lead, fill=DIM)
    d.text((x, 356), "自分のサーバーへ送る Android アプリ", font=f_lead, fill=DIM)

    # ---- 下の帯: 形式と対応
    f_tag = font("bold", 27)
    tags = ["CSV / GPX / GeoJSON", "Android 8.0+", "広告なし"]
    tx = x
    for t in tags:
        box = d.textbbox((0, 0), t, font=f_tag)
        w = box[2] - box[0]
        d.rounded_rectangle([tx, 424, tx + w + 36, 424 + 54], radius=27, fill=PANEL)
        d.text((tx + 18, 424 + 11), t, font=f_tag, fill=DIM)
        tx += w + 36 + 14

    # ---- 開発元
    f_by = font("bold", 29)
    d.text((x, 516), "usePocket", font=f_by, fill=ACCENT)

    # ---- 右上に軌跡の記号（起点と現在地）
    d.line([(980, 112), (1046, 78), (1104, 100)], fill=TRAIL, width=9, joint="curve")
    d.ellipse([970, 102, 990, 122], outline=START, width=6)
    d.ellipse([1094, 88, 1116, 110], fill=NOW)

    os.makedirs(DOCS, exist_ok=True)
    out = os.path.join(DOCS, "og.jpg")
    img.save(out, "JPEG", quality=88, optimize=True, progressive=True)
    print(f"  docs/og.jpg  {W}x{H}  {os.path.getsize(out):,} bytes")


if __name__ == "__main__":
    sys.exit(main())
