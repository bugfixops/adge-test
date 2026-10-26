"""Combine the per-app artifacts of one workflow run into one summary table (stdlib only).

Usage: python3 -I ci/collect.py <downloaded_dir> <out_dir> <expected apps, comma separated>
<downloaded_dir>/results-<App>/ holds what ci/run_app.sh wrote; apps that left nothing are listed as no_artifact.
Writes <out_dir>/SUMMARY.md, <out_dir>/summary.csv and <out_dir>/meta/<App>.json.
"""
import csv
import json
import os
import sys

COLS = ['app', 'status', 'tool', 'minutes', 'line_pct', 'branch_pct', 'method_pct', 'class_pct', 'methods_hit', 'methods_total',
        'activities_triggered', 'activities_total', 'activity_coverage', 'triggers', 'actions', 'iterations', 'completed_runs',
        'db_mode', 'db_resource_ids', 'db_mappings', 'db_icc_methods', 'segments_valid', 'segments_total', 'crashes',
        'app_alive_at_end']


def main():
    src, dst = sys.argv[1], sys.argv[2]
    expected = [a for a in (sys.argv[3] if len(sys.argv) > 3 else '').split(',') if a]
    os.makedirs(os.path.join(dst, 'meta'), exist_ok=True)
    seen = set()
    rows = []
    if os.path.isdir(src):
        for name in sorted(os.listdir(src)):
            folder = os.path.join(src, name)
            if not (name.startswith('results-') and os.path.isdir(folder)):
                continue
            app = name[len('results-'):]
            seen.add(app)
            try:
                with open(os.path.join(folder, 'meta.json'), encoding='utf-8') as f:
                    meta = json.load(f)
            except (OSError, ValueError):
                meta = {'status': 'no_meta'}
            meta['app'] = app
            with open(os.path.join(dst, 'meta', app + '.json'), 'w', encoding='utf-8') as f:
                json.dump(meta, f, indent=1, sort_keys=True)
            rows.append(meta)
    for app in expected:
        if app not in seen:
            rows.append({'app': app, 'status': 'no_artifact'})
    rows.sort(key=lambda r: r['app'].lower())
    with open(os.path.join(dst, 'summary.csv'), 'w', newline='', encoding='utf-8') as f:
        w = csv.writer(f)
        w.writerow(COLS)
        for r in rows:
            w.writerow([r.get(c, '') for c in COLS])
    lines = ['# ADGE run %s' % os.environ.get('GITHUB_RUN_ID', ''), '',
             'Line/branch/class % are computed as in the Monkey/APE baseline tables (every row of JaCoCo report.csv).', '',
             '| App | Status | Min | Line % | Branch % | Class % | Methods hit/all | Activities reached/all | Actions | Runs | DB ids (ICC) |',
             '|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for r in rows:
        lines.append('| %s | %s | %s | %s | %s | %s | %s/%s | %s/%s | %s | %s | %s (%s) |' % (
            r['app'], r.get('status', ''), r.get('minutes', ''), r.get('line_pct', ''), r.get('branch_pct', ''), r.get('class_pct', ''),
            r.get('methods_hit', ''), r.get('methods_total', ''), r.get('activities_triggered', ''), r.get('activities_total', ''),
            r.get('actions', ''), r.get('iterations', ''), r.get('db_resource_ids', ''), r.get('db_icc_methods', '')))
    text = '\n'.join(lines) + '\n'
    with open(os.path.join(dst, 'SUMMARY.md'), 'w', encoding='utf-8') as f:
        f.write(text)
    sys.stdout.write(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as f:
            f.write(text)


if __name__ == '__main__':
    main()
