"""Small, diff-friendly summaries of one ADGE app run (stdlib only).

Usage: python3 -I ci/report.py <outdir> key=value [key=value ...]
Reads report.xml and report.csv (JaCoCo), segments.csv, db/stats.json, adge/results/*/result.json and logcat_crash.txt
in <outdir>, whichever exist.
Writes methods_hit.csv (class,method,desc,hit with JaCoCo's class names, instrumentation classes left out) and meta.json.
"""
import csv
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def pct(covered, missed):
    total = covered + missed
    return round(100.0 * covered / total, 2) if total else None


def main():
    out = sys.argv[1]
    meta = dict(a.split('=', 1) for a in sys.argv[2:] if '=' in a)
    rows = []
    xml_path = os.path.join(out, 'report.xml')
    if os.path.exists(xml_path):
        for cls in ET.parse(xml_path).getroot().iter('class'):
            name = cls.get('name')
            if 'JacocoInstrument' in name:
                continue
            for m in cls.findall('method'):
                counters = {c.get('type'): int(c.get('covered')) for c in m.findall('counter')}
                rows.append((name, m.get('name'), m.get('desc'), 1 if counters.get('METHOD', 0) > 0 else 0))
        rows.sort()
        with open(os.path.join(out, 'methods_hit.csv'), 'w', newline='', encoding='utf-8') as f:
            w = csv.writer(f)
            w.writerow(['class', 'method', 'desc', 'hit'])
            w.writerows(rows)
    meta['methods_total'] = len(rows)
    meta['methods_hit'] = sum(r[3] for r in rows)

    # line / branch / instruction / method / class coverage over every row of report.csv, computed as in the
    # Monkey/APE baseline tables (summary_ape_api27.csv, combined_ape_monkey_api27.csv), so the numbers compare directly
    csv_path = os.path.join(out, 'report.csv')
    if os.path.exists(csv_path):
        sums = {}
        classes_total = classes_hit = 0
        with open(csv_path, encoding='utf-8') as f:
            for r in csv.DictReader(f):
                for kind in ('INSTRUCTION', 'BRANCH', 'LINE', 'METHOD'):
                    c, m = int(r[kind + '_COVERED']), int(r[kind + '_MISSED'])
                    sums[kind] = (sums.get(kind, (0, 0))[0] + c, sums.get(kind, (0, 0))[1] + m)
                classes_total += 1
                classes_hit += 1 if int(r['METHOD_COVERED']) > 0 else 0
        for kind, key in (('LINE', 'line_pct'), ('BRANCH', 'branch_pct'), ('INSTRUCTION', 'instruction_pct'), ('METHOD', 'method_pct')):
            if kind in sums:
                meta[key] = pct(*sums[kind])
        meta['class_pct'] = pct(classes_hit, classes_total - classes_hit)

    seg = os.path.join(out, 'segments.csv')
    if os.path.exists(seg):
        parts = [line.split(',', 4) for line in open(seg, encoding='utf-8') if line.strip()]
        meta['segments_total'] = len(parts)
        meta['segments_valid'] = sum(1 for p in parts if len(p) > 3 and p[3] == 'true')

    # the static-analysis database ADGE was guided by
    stats = os.path.join(out, 'db', 'stats.json')
    if os.path.exists(stats):
        db = json.load(open(stats, encoding='utf-8'))
        meta['db_mode'] = db.get('mode')
        for key in ('resource_ids', 'mappings', 'icc_methods', 'complete',
                    'targets_mode', 'targets_total', 'targets_matched', 'tainted_methods', 'reach_nodes'):
            if key in db:
                meta['db_' + key] = db[key]

    # ADGE's own result: activities reached (Frida hook on Activity.onCreate) over the manifest's activities
    results = glob.glob(os.path.join(out, 'adge', 'results', '*', 'result.json'))
    if results:
        res = json.load(open(results[0], encoding='utf-8'))
        meta['activities_triggered'] = len(res.get('activityTriggerMap', {}))
        meta['activities_total'] = len(res.get('activitySet', []))
        meta['activity_coverage'] = res.get('coverage')
        meta['triggers'] = res.get('triggerCnt')

    crash_log = os.path.join(out, 'logcat_crash.txt')
    if os.path.exists(crash_log) and meta.get('package'):
        text = open(crash_log, encoding='utf-8', errors='replace').read()
        meta['crashes'] = text.count('Process: %s,' % meta['package'])

    with open(os.path.join(out, 'meta.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=1, sort_keys=True)
        f.write('\n')


if __name__ == '__main__':
    main()
