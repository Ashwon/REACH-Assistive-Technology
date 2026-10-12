# Synthetic low-light frames (labelled synthetic): pure black, sensor-noise-only, and real captured frames
# underexposed by a gain + Gaussian noise. Frame timing 100 ms apart so the app's tracker sees a sequence.
import sys, glob, os, numpy as np
from PIL import Image
src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
rng = np.random.default_rng(0)
frames = sorted(glob.glob(os.path.join(src, '*.png')))[::6][:10]
ts = 1_000_000
def save(a, tag):
    global ts
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(os.path.join(out, f'f_{ts:012d}.png')); print(f'f_{ts:012d}.png', tag); ts += 100
for i in range(5): save(np.zeros((640, 480, 3)), 'black')
for i in range(5): save(8 + rng.normal(0, 4, (640, 480, 3)), 'noise mean8 sd4')
for gain in (0.25, 0.12, 0.06):
    for f in frames:
        a = np.asarray(Image.open(f).convert('RGB'), dtype=np.float32)
        save(a * gain + rng.normal(0, 3 + 20 * gain, a.shape), f'dim x{gain} {os.path.basename(f)}')
