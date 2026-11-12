"""python3 -m pytest -q tools/  — split_dataset.py keeps sessions whole and writes files the evaluator can read."""
import csv, json, os, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))


def test_split_by_session_with_eval_files(tmp_path):
    ds = tmp_path / 'ds'; (ds / 'images').mkdir(parents=True)
    images, anns, rows = [], [], []
    for i in range(60):  # 20 sessions x 3 frames; a cup (6, native) and a chair (21, excluded) in each frame
        name = f's{i // 3}_{i}.jpg'; (ds / 'images' / name).write_bytes(b'x')
        images.append({'id': i, 'file_name': name, 'width': 480, 'height': 640})
        anns += [{'id': 2 * i, 'image_id': i, 'category_id': 6, 'bbox': [1, 1, 10, 10]},
                 {'id': 2 * i + 1, 'image_id': i, 'category_id': 21, 'bbox': [50, 50, 10, 10]}]
        rows.append({'file_name': name, 'session': f's{i // 3}', 'labeled': 'all'})
    (ds / 'annotations.json').write_text(json.dumps({'images': images, 'annotations': anns, 'categories': []}))
    with open(ds / 'images.csv', 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=['file_name', 'session', 'labeled']); w.writeheader(); w.writerows(rows)

    out = tmp_path / 'split'
    subprocess.run([sys.executable, '-I', os.path.join(HERE, 'split_dataset.py'), str(ds), str(out)], check=True, capture_output=True)

    seen = {}
    for s in ('train', 'validation', 'test'):
        split_rows = list(csv.DictReader(open(out / s / 'images.csv')))
        for r in split_rows:
            assert seen.setdefault(r['session'], s) == s  # a session never spans two splits
        ev = json.load(open(out / s / 'annotations.json'))
        assert {i['file_name'] for i in ev['images']} == {r['file_name'] for r in split_rows}
        assert all(a['category_id'] in (6, 21) for a in ev['annotations'])  # original ids for the evaluator
        train = json.load(open(out / s / 'labels.json'))
        assert 'chair' not in [c['name'] for c in train['categories']]   # excluded: background, not a class
        assert all(a['category_id'] == [c['name'] for c in train['categories']].index('cup') for a in train['annotations'])
    assert len(seen) == 20 and len(set(seen.values())) == 3
