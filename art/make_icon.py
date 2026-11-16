"""Structure Overlay icon, built from the reference map render.

Outputs:
  art/icon-exact-512.png    the reference map itself, upscaled to 512 (no additions)
  art/icon-512.png          the same map with the mod's footprint overlay on top
"""
from PIL import Image, ImageDraw

SRC = '.cloud-attachments/859c0890d01eb19ed4ede7b626b85ccc65d4ceceaad575b5418d9ffad7bc95a6.webp'
S = 512

base = Image.open(SRC).convert('RGB').resize((S, S), Image.LANCZOS)
base.save('art/icon-exact-512.png')
for n in (128, 64):
    base.resize((n, n), Image.LANCZOS).save('art/icon-exact-%d-preview.png' % n)

# --- ocean/land mask (the map's sea is a saturated blue) ---
px = base.load()
ocean = [[1 if (lambda c: c[2] > 140 and c[2] - c[0] > 60 and c[2] - c[1] > 60 and c[0] < 100)(px[x, y]) else 0
          for x in range(S)] for y in range(S)]
# integral image of the ocean mask, so land fraction in any box is O(1)
integ = [[0] * (S + 1) for _ in range(S + 1)]
for y in range(S):
    run = 0
    for x in range(S):
        run += ocean[y][x]
        integ[y + 1][x + 1] = integ[y][x + 1] + run

def ocean_count(x0, y0, w, h):
    return integ[y0 + h][x0 + w] - integ[y0][x0 + w] - integ[y0 + h][x0] + integ[y0][x0]

BW, BH = 38, 28
cands = []
for y0 in range(8, S - BH - 8, 6):
    for x0 in range(8, S - BW - 8, 6):
        land = 1 - ocean_count(x0, y0, BW, BH) / float(BW * BH)
        if land >= 0.97:
            cands.append((land, x0, y0))
cands.sort(reverse=True)

# farthest-point sampling: spread the boxes across the land
COUNT = 16
chosen = [(cands[0][1], cands[0][2])] if cands else []
while len(chosen) < COUNT and cands:
    best, best_d = None, -1
    for _, x0, y0 in cands:
        dmin = min((x0 - cx) ** 2 + (y0 - cy) ** 2 for cx, cy in chosen) if chosen else 0
        if dmin > best_d:
            best, best_d = (x0, y0), dmin
    if best is None or (chosen and best_d < (BW * 1.05) ** 2):
        break
    chosen.append(best)

# footprints keep clear of the map's own markers: violet / red / teal on light ground
TYPES = [(106, 99, 201), (200, 64, 47), (32, 140, 176), (150, 110, 220), (210, 120, 40)]
over = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(over, 'RGBA')
for i, (x0, y0) in enumerate(chosen):
    col = TYPES[i % len(TYPES)]
    d.rounded_rectangle((x0, y0, x0 + BW, y0 + BH), radius=7,
                        fill=col + (70,), outline=col + (255,), width=5)
    # a marker at the structure's start, like the mod draws
    cx, cy = x0 + BW // 2, y0 + BH // 2
    d.rectangle((cx - 3, cy - 3, cx + 3, cy + 3), fill=col + (255,))

out = Image.alpha_composite(base.convert('RGBA'), over).convert('RGB')
out.save('art/icon-512.png')
for n in (128, 64):
    out.resize((n, n), Image.LANCZOS).save('art/icon-%d-preview.png' % n)
print('boxes:', chosen)
