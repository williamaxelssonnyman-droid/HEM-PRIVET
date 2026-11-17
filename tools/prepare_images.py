"""Download the wallpapers and build the app banner/icon (runs in GitHub Actions)."""
import io
import sys
import urllib.request

from PIL import Image, ImageDraw, ImageFilter, ImageFont

BASE = "https://d8j0ntlcm91z4.cloudfront.net/user_3LZWEcwdfRjgJGtNaXrgLUoXQzZ/"
FILES = [
    "hf_20261117_221000_d45532e1-9621-4843-8f57-a329bac63446.png",  # wp1 aurora over the peak
    "hf_20261117_221105_209f874c-6d86-4283-98e4-d1d7297c742b.png",  # wp2 aurora snowy mountains
    "hf_20261117_221029_9a508333-449c-4a99-a826-323be333627d.png",  # wp3 dark ridges
    "hf_20261117_221129_c0b347ee-cabb-42b7-a947-390fbfa8719c.png",  # wp4 midnight peak
    "hf_20261117_221044_5db459f8-5bba-4d4a-9473-fc8b5ef0e9a9.png",  # wp5 pink dusk
    "hf_20261117_221000_ee30275a-6b3d-4bd9-b211-65c863739747.png",  # wp6 aurora frozen lake
    "hf_20261117_215700_f4e6e858-bdf9-4b57-9df9-58f3a0067f30.png",  # wp7 night lake
]
RES = "app/src/main/res/"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

ok = 0
images = {}
for i, name in enumerate(FILES, start=1):
    try:
        data = urllib.request.urlopen(BASE + name, timeout=60).read()
        im = Image.open(io.BytesIO(data)).convert("RGB")
        im.save(f"{RES}drawable-nodpi/wp{i}.jpg", quality=88, optimize=True)
        images[i] = im
        ok += 1
        print(f"wp{i}: {im.size}")
    except Exception as e:  # keep the placeholder
        print(f"::warning::wp{i} could not be downloaded: {e}")


def font(size):
    try:
        return ImageFont.truetype(FONT, size)
    except Exception:
        return ImageFont.load_default()


src = images.get(1) or images.get(6) or Image.new("RGB", (1280, 720), (12, 20, 30))

# Banner: 320x180, full bleed (Android TV shows it edge to edge).
w, h = src.size
crop = src.crop((int(w * 0.1), int(h * 0.05), int(w * 0.1) + int(h * 0.9 * 16 / 9), int(h * 0.95)))
banner = crop.resize((320, 180), Image.LANCZOS)
shade = Image.new("L", (320, 180), 0)
ImageDraw.Draw(shade).rectangle([0, 110, 320, 180], fill=150)
shade = shade.filter(ImageFilter.GaussianBlur(25))
banner.paste((0, 0, 0), (0, 0), shade)
d = ImageDraw.Draw(banner)
f = font(44)
text = "HOME"
tw = d.textlength(text, font=f)
d.text(((320 - tw) / 2 + 2, 118), text, font=f, fill=(0, 0, 0))
d.text(((320 - tw) / 2, 116), text, font=f, fill=(255, 255, 255))
banner.save(f"{RES}drawable-xhdpi/banner.png")

# Icon: square crop, rounded.
s = min(w, h)
icon = src.crop(((w - s) // 2, 0, (w - s) // 2 + s, s)).resize((192, 192), Image.LANCZOS).convert("RGBA")
mask = Image.new("L", (192, 192), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, 191, 191], 42, fill=255)
icon.putalpha(mask)
icon.save(f"{RES}drawable/ic_launcher.png")

print(f"{ok}/{len(FILES)} wallpapers downloaded")
sys.exit(0)
