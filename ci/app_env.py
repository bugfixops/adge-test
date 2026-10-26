"""Print the shell variables for one app from ci/apps.csv: eval "$(python3 -I ci/app_env.py <App>)"."""
import csv
import shlex
import sys


def main():
    app = sys.argv[1]
    for row in csv.DictReader(open('ci/apps.csv', encoding='utf-8')):
        if row['app'] == app:
            for var, col in (('PKG', 'package'), ('RECEIVER', 'receiver'), ('APK', 'apk'), ('CLASSES', 'classes')):
                value = row[col].replace(';', ' ') if var == 'CLASSES' else row[col]
                print('%s=%s' % (var, shlex.quote(value)))
            return
    sys.exit('unknown app %s' % app)


if __name__ == '__main__':
    main()
