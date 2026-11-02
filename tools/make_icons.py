#!/usr/bin/env python3
"""ランチャーアイコンを生成する。

アダプティブアイコンの前景レイヤー（108dp 相当のキャンバス）を各密度ぶん書き出す。
ベクタードローアブルでは文字を描けないため、PNG で作っている。

    python3 tools/make_icons.py

出力:
    app/src/main/res/mipmap-*dpi/ic_launcher_foreground.png
    docs/icon-512.png          配布ページに載せる見本
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs")

# 4x（xxxhdpi）で作って縮小する。108dp × 4 = 432px
BASE = 432
# アダプティブアイコンはマスクで切られる。確実に見えるのは中央 66dp の円。
# 108dp のキャンバスでは直径 264px、半径 132px。
SAFE_R = BASE * 66 / 108 / 2

TRAIL = (46, 163, 111)      # 軌跡の緑
START = (229, 193, 0)       # 起点の黄
NOW = (248, 81, 73)         # 現在地の赤
TEXT = (233, 240, 245)      # 文字

FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Black.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def load_font(size):
    for path in FONT_CANDIDATES:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def fit_font(draw, text, max_w, max_h, lo=10, hi=200):
    """max_w × max_h に収まる最大の文字サイズを二分探索で選ぶ。"""
    best = load_font(lo)
    while lo <= hi:
        mid = (lo + hi) // 2
        f = load_font(mid)
        box = draw.textbbox((0, 0), text, font=f)
        w, h = box[2] - box[0], box[3] - box[1]
        if w <= max_w and h <= max_h:
            best = f
            lo = mid + 1
        else:
            hi = mid - 1
    return best


def draw_foreground():
    """前景レイヤー。背景は透明にして、背景色は XML 側の単色に任せる。"""
    img = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = BASE / 2

    # ---- 文字（usepocket）。幅は安全円の弦に収める。
    # 文字の中心を少し下げた位置に置くので、その高さでの弦の長さを使う
    text = "usepocket"
    text_cy = cy + SAFE_R * 0.42
    half_chord = (SAFE_R ** 2 - (text_cy - cy) ** 2) ** 0.5
    max_w = half_chord * 2 * 0.96
    font = fit_font(d, text, max_w, SAFE_R * 0.52)
    box = d.textbbox((0, 0), text, font=font)
    tw, th = box[2] - box[0], box[3] - box[1]
    tx = cx - tw / 2 - box[0]
    ty = text_cy - th / 2 - box[1]
    d.text((tx, ty), text, font=font, fill=TEXT)

    # ---- 軌跡。文字の上の領域に描く。
    top = cy - SAFE_R * 0.92
    bottom = text_cy - th / 2 - SAFE_R * 0.12
    h = bottom - top
    left = cx - SAFE_R * 0.74
    right = cx + SAFE_R * 0.74

    # 左下から右上へ上がっていく折れ線。角を丸めて描く
    pts = [
        (left, top + h * 0.86),
        (left + (right - left) * 0.26, top + h * 0.52),
        (left + (right - left) * 0.46, top + h * 0.66),
        (left + (right - left) * 0.72, top + h * 0.22),
        (right, top + h * 0.34),
    ]
    lw = max(2, int(BASE * 0.034))
    d.line(pts, fill=TRAIL, width=lw, joint="curve")
    # 線の端も丸める
    for p in (pts[0], pts[-1]):
        d.ellipse([p[0] - lw / 2, p[1] - lw / 2, p[0] + lw / 2, p[1] + lw / 2], fill=TRAIL)

    # 起点（黄の輪）
    r = BASE * 0.030
    sw = max(2, int(BASE * 0.016))
    d.ellipse(
        [pts[0][0] - r, pts[0][1] - r, pts[0][0] + r, pts[0][1] + r],
        outline=START, width=sw,
    )
    # 現在地（赤の点）
    r2 = BASE * 0.040
    d.ellipse(
        [pts[-1][0] - r2, pts[-1][1] - r2, pts[-1][0] + r2, pts[-1][1] + r2],
        fill=NOW,
    )
    return img


def main():
    fg = draw_foreground()

    # アダプティブアイコンの前景は 108dp。各密度の実ピクセル数に落とす
    densities = {
        "mipmap-mdpi": 108,
        "mipmap-hdpi": 162,
        "mipmap-xhdpi": 216,
        "mipmap-xxhdpi": 324,
        "mipmap-xxxhdpi": 432,
    }
    for folder, px in densities.items():
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(
            os.path.join(out_dir, "ic_launcher_foreground.png"), optimize=True
        )
        print(f"  {folder}/ic_launcher_foreground.png  {px}x{px}")

    # 配布ページ用の見本。背景色を敷いて角丸に切り抜く
    os.makedirs(DOCS, exist_ok=True)
    size = 512
    bg = Image.new("RGBA", (size, size), (18, 32, 43, 255))
    bg.alpha_composite(fg.resize((size, size), Image.LANCZOS))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.22), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    out.save(os.path.join(DOCS, "icon-512.png"), optimize=True)
    print(f"  docs/icon-512.png  {size}x{size}")


if __name__ == "__main__":
    sys.exit(main())
