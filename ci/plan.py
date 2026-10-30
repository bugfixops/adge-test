"""Decide what one workflow run does and publish it as step outputs.

Manual runs ("Run workflow") pass their options in IN_* variables; a push of ci/run.conf is read from the file.
Outputs: matrix (JSON for the job matrix), apps, count, minutes, db, test_restart, prep.
"""
import csv
import json
import os
import sys


def read_conf(path):
    conf = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.split('#', 1)[0].strip()
            if '=' in line:
                key, value = line.split('=', 1)
                conf[key.strip().upper()] = value.strip()
    return conf


def fail(msg):
    print('::error title=plan::%s' % msg)
    sys.exit(1)


def main():
    if os.environ.get('EVENT') == 'workflow_dispatch':
        conf = {'APPS': os.environ.get('IN_APPS', ''), 'MINUTES': os.environ.get('IN_MINUTES', ''),
                'DB': os.environ.get('IN_DB', ''), 'TEST_RESTART_AFTER_RUNS': os.environ.get('IN_TEST_RESTART', ''),
                'PREP': os.environ.get('IN_PREP', '')}
    else:
        conf = read_conf('ci/run.conf')
    test_restart = (conf.get('TEST_RESTART_AFTER_RUNS') or '').strip()
    if test_restart and not test_restart.isdigit():
        fail('TEST_RESTART_AFTER_RUNS must be a whole number or empty, got %r' % test_restart)
    prep = (conf.get('PREP') or 'off').strip().lower()
    if prep not in ('on', 'off'):
        fail('PREP must be on or off, got %r' % prep)
    known = [r['app'] for r in csv.DictReader(open('ci/apps.csv', encoding='utf-8'))]
    apps = (conf.get('APPS') or 'all').strip()
    want = known if apps.lower() == 'all' else [a.strip() for a in apps.split(',') if a.strip()]
    unknown = [a for a in want if a not in known]
    if unknown:
        fail('unknown app(s) %s; known: %s' % (', '.join(unknown), ', '.join(known)))
    db = (conf.get('DB') or 'analysis').strip().lower()
    if db not in ('analysis', 'empty'):
        fail('DB must be analysis or empty, got %r' % db)
    try:
        minutes = int(conf.get('MINUTES') or 60)
    except ValueError:
        fail('MINUTES must be an integer')
    if not 1 <= minutes <= 240:
        fail('MINUTES must be between 1 and 240 (a hosted job is limited to 6 hours)')
    out = {'matrix': json.dumps({'app': want}), 'apps': ','.join(want), 'count': str(len(want)), 'minutes': str(minutes),
           'db': db, 'test_restart': test_restart, 'prep': prep}
    text = ''.join('%s=%s\n' % kv for kv in out.items())
    sys.stdout.write(text)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as f:
            f.write(text)
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as f:
            f.write('**Plan:** ADGE for %d min each (database: %s, first-run setup: %s%s) on %d app(s): %s\n' % (
                minutes, db, prep, ', forced emulator restart after %s runs' % test_restart if test_restart else '',
                len(want), ', '.join(want)))


if __name__ == '__main__':
    main()
