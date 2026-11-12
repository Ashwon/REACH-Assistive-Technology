#!/usr/bin/env python3
"""Split a REACH dataset (dataset/README.md) into train / validation / test by SESSION, never by frame.

Consecutive frames of one recording are near-duplicates: splitting them across sets would leak the test set
into training and inflate every number. Sessions are assigned by a stable hash of their name, so adding new
sessions never moves old ones between sets.

Writes the layout MediaPipe Model Maker's object detector reads (Dataset.from_coco_folder):
  <out>/{train,validation,test}/images/*.jpg
  <out>/{train,validation,test}/labels.json   (COCO; category ids 1..N, 0 is background)
Only categories REACH targets or wants to (categories.json model_support != "excluded") become training classes;
excluded objects stay in the images as background, and hand boxes are for hand-identity evaluation only.
Each split also gets the evaluator's own files (annotations.json with the original category ids, images.csv), so
`tools/eval_on_device.sh <out>/test` scores the held-out test split directly.
Stdlib only:  python3 tools/split_dataset.py dataset/<name> build/split [--min-per-class 300]
"""
import argparse, csv, hashlib, json, os, shutil, sys
from collections import Counter

ap = argparse.ArgumentParser()
ap.add_argument('dataset'); ap.add_argument('out')
ap.add_argument('--fractions', default='0.7,0.15,0.15', help='train,validation,test (by session)')
ap.add_argument('--min-per-class', type=int, default=300, help='warn when a split has fewer training instances')
a = ap.parse_args()

here = os.path.dirname(os.path.abspath(__file__))
spec = json.load(open(os.path.join(here, '..', 'dataset', 'categories.json')))
objects = [c['name'] for c in spec['categories'] if c.get('model_support') != 'excluded']
coco = json.load(open(os.path.join(a.dataset, 'annotations.json')))
reader = csv.DictReader(open(os.path.join(a.dataset, 'images.csv')))
rows = {r['file_name']: r for r in reader}
id2name = {c['id']: c['name'] for c in spec['categories'] + spec['hand_categories']}
fr = [float(x) for x in a.fractions.split(',')]

def split_of(session):
    u = int(hashlib.sha1(session.encode()).hexdigest()[:8], 16) / 0xFFFFFFFF
    return 'train' if u < fr[0] else 'validation' if u < fr[0] + fr[1] else 'test'

out = {s: {'images': [], 'annotations': [], 'categories': [{'id': 0, 'name': 'background'}] +
           [{'id': i + 1, 'name': n} for i, n in enumerate(objects)]} for s in ('train', 'validation', 'test')}
evalset = {s: {'images': [], 'annotations': [], 'categories': coco.get('categories', [])} for s in out}
counts = {s: Counter() for s in out}
sessions = {s: set() for s in out}
anns = {}
for x in coco['annotations']: anns.setdefault(x['image_id'], []).append(x)
for img in coco['images']:
    r = rows.get(img['file_name'])
    if r is None: sys.exit(f"{img['file_name']} missing from images.csv")
    s = split_of(r['session']); sessions[s].add(r['session'])
    os.makedirs(os.path.join(a.out, s, 'images'), exist_ok=True)
    shutil.copy(os.path.join(a.dataset, 'images', img['file_name']), os.path.join(a.out, s, 'images', img['file_name']))
    out[s]['images'].append({k: img[k] for k in ('id', 'file_name', 'width', 'height')})
    evalset[s]['images'].append(img); evalset[s]['annotations'] += anns.get(img['id'], [])
    for x in anns.get(img['id'], []):
        name = id2name[x['category_id']]
        if name not in objects: continue
        out[s]['annotations'].append({'id': len(out[s]['annotations']) + 1, 'image_id': img['id'],
                                      'category_id': objects.index(name) + 1, 'bbox': x['bbox'],
                                      'area': x['bbox'][2] * x['bbox'][3], 'iscrowd': 0})
        counts[s][name] += 1
for s in out:
    os.makedirs(os.path.join(a.out, s, 'images'), exist_ok=True)
    json.dump(out[s], open(os.path.join(a.out, s, 'labels.json'), 'w'))
    json.dump(evalset[s], open(os.path.join(a.out, s, 'annotations.json'), 'w'))
    with open(os.path.join(a.out, s, 'images.csv'), 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=reader.fieldnames); w.writeheader()
        w.writerows(rows[i['file_name']] for i in evalset[s]['images'])
    print(f"{s:10} sessions={len(sessions[s]):3} images={len(out[s]['images']):5} instances={dict(counts[s])}")
low = [n for n in objects if counts['train'][n] < a.min_per_class]
if low: print(f"WARNING: fewer than {a.min_per_class} training instances for: {', '.join(low)}")
if not sessions['test']: print('WARNING: no session landed in test; record more sessions before reporting accuracy')
