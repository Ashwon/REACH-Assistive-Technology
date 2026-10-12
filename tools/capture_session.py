#!/usr/bin/env python3
"""Record one session of real camera frames from the phone into a dataset, ready to annotate.

  python3 tools/capture_session.py dataset/<name> --session kitchen-dim-1 --frames 60 --lighting dim

Uses the debug build's capture intent (the app's own upright 480x640 analysis frames, exactly what the models
see), pulls them, stores JPEG q95 in <dataset>/images/ as <session>_f_<ms>.jpg and appends rows to
<dataset>/images.csv. Then annotate the images (dataset/README.md) and export COCO to annotations.json.
Needs Pillow for the JPEG conversion; run with python3 -I.
"""
import argparse, csv, io, os, subprocess, tarfile, time
from PIL import Image

ap = argparse.ArgumentParser()
ap.add_argument('dataset'); ap.add_argument('--session', required=True)
ap.add_argument('--sequence', help='defaults to the session name'); ap.add_argument('--frames', type=int, default=40)
ap.add_argument('--lighting', default='normal', choices=['bright', 'normal', 'dim', 'dark'])
a = ap.parse_args()
PKG = 'com.reach.app'
adb = lambda *x, **k: subprocess.run(['adb', *x], check=True, capture_output=True, **k).stdout

adb('shell', 'run-as', PKG, 'rm', '-rf', 'files/captures')
adb('shell', 'am', 'start', '-f', '0x20000000', '-n', f'{PKG}/.MainActivity', '--ei', 'capture', str(a.frames))
for _ in range(120):  # PNG writes trail the camera; wait until all frames are on disk
    n = adb('shell', 'run-as', PKG, 'sh', '-c', "'ls files/captures 2>/dev/null | wc -l'").decode().strip()
    if n.isdigit() and int(n) >= a.frames: break
    time.sleep(1)
time.sleep(2)
tar = tarfile.open(fileobj=io.BytesIO(adb('exec-out', 'run-as', PKG, 'tar', 'cf', '-', 'files/captures')))
os.makedirs(os.path.join(a.dataset, 'images'), exist_ok=True)
csv_path = os.path.join(a.dataset, 'images.csv'); new = not os.path.exists(csv_path)
members = sorted((m for m in tar.getmembers() if m.name.endswith('.png')), key=lambda m: m.name)
t0 = int(os.path.basename(members[0].name)[2:-4]) if members else 0
with open(csv_path, 'a', newline='') as fh:
    w = csv.writer(fh)
    if new: w.writerow(['file_name', 'session', 'sequence', 'frame_ms', 'lighting', 'synthetic', 'labeled', 'target', 'reach_ms'])
    for m in members:
        ms = int(os.path.basename(m.name)[2:-4]) - t0
        name = f"{a.session}_f_{ms:08d}.jpg"
        Image.open(tar.extractfile(m)).convert('RGB').save(os.path.join(a.dataset, 'images', name), quality=95)
        w.writerow([name, a.session, a.sequence or a.session, ms, a.lighting, 0, 'all', '', ''])
print(f'{len(members)} frames -> {a.dataset}/images ({a.session}); now annotate them (dataset/README.md)')
