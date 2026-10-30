"""Initial-state ("first-run setup") scripts for apps whose main functions are gated behind a one-time setup.

Each function takes an emulator port and brings a freshly installed app (data cleared) to the state a normal user reaches
after finishing the first-run flow. They drive the UI through uiautomator (see uidrive.py) and return True on success.

  python3 prep_scripts.py MMEX 5576          # run one script by hand
"""
import sys, time
from uidrive import Device


def prep_mmex(port, pkg='com.money.manager.ex', timeout=150):
    """Money Manager Ex first-run setup, state-driven (reacts to whatever screen is showing, so it does not depend on speed):
    tutorial -> first-run settings -> create database (empty password) -> create one account named 'Checking'.
    Handles runtime-permission prompts at any point."""
    d = Device(port)
    d.sh('am', 'force-stop', pkg)
    d.sh('rm', '-rf', '/sdcard/MoneyManagerEx', '/sdcard/Download/your_data.mmb')
    for perm in ('READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'): d.sh('pm', 'grant', pkg, 'android.permission.' + perm)
    d.start(pkg)
    typed = False
    end = time.time() + timeout
    while time.time() < end:
        top = d.top(); nodes = d.nodes(); ids = {n['id'] for n in nodes}; texts = {n['text'] for n in nodes} | {n['desc'] for n in nodes}
        if 'GrantPermissionsActivity' in top:
            d.tap_id('permission_allow_button', timeout=3)
        elif 'lblPassword' in ids and 'btnSubmit' in ids:                 # password prompt (empty password is accepted)
            d.tap_id('btnSubmit', timeout=3)
        elif 'documentsui' in top and 'SAVE' in texts:                    # system "save as" picker, default name your_data.mmb
            d.tap_text('SAVE', timeout=3)
        elif 'skipTextView' in ids:                                       # tutorial "CLOSE"
            d.tap_id('skipTextView', timeout=3)
        elif 'GeneralSettingsActivity' in top:                            # first-run settings page: just leave
            d.back()
        elif 'createDatabaseButton' in ids:
            d.tap_id('createDatabaseButton', timeout=3)
        elif 'editTextAccountName' in ids:                                # account form
            if not typed:
                d.tap_id('editTextAccountName', timeout=3); time.sleep(1); d.type_text('Checking'); typed = True; time.sleep(1)
            d.tap_text('Save', timeout=3)
        elif 'buttonAddAccount' in ids:                                   # "Welcome" page: no account yet
            d.tap_id('buttonAddAccount', timeout=3)
        elif 'MainActivity' in top and 'Checking' in texts:               # done: home page with the account
            return True
        time.sleep(2)
    return False


LAST_LOG = []   # step log of the last script run (the runner stores it in setup.json)


AEGIS_ENTRIES = [  # (account name, issuer, Base32 secret) - made-up test data
    ('alice', 'GitHub', 'JBSWY3DPEHPK3PXP'),
    ('bob', 'Dropbox', 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'),
]


def _fill(d, field_id, value, clear=False):
    """Type into one field (soft keyboard is disabled by the caller, so every field stays reachable)."""
    if not d.tap_id(field_id, timeout=10): return False
    time.sleep(0.8)
    if clear:
        d.key(123)
        for _ in range(40): d.key(67)
    d.type_text(value); time.sleep(0.8)
    return True


def prep_aegis(port, pkg='com.beemdevelopment.aegis.debug', timeout=240, entries=AEGIS_ENTRIES):
    d0 = Device(port)
    with d0.no_keyboard():
        return _prep_aegis(port, pkg, timeout, entries)


def _prep_aegis(port, pkg, timeout, entries):
    """Aegis Authenticator, state-driven.
    1) first-run intro: Welcome -> Next -> Security (choose None = no unlock screen later) -> Next -> 'Setup completed' -> Next
    2) add the entries manually: + -> 'Enter manually' -> name / issuer / secret -> SAVE   (2 entries with different secrets)."""
    d = Device(port)
    d.sh('am', 'force-stop', pkg)
    d.start(pkg)
    end = time.time() + timeout
    while time.time() < end:                                              # --- 1) intro
        top = d.top(); ids = {n['id'] for n in d.nodes()}
        if 'GrantPermissionsActivity' in top: d.tap_id('permission_allow_button', timeout=3)
        elif 'IntroActivity' in top and 'rb_none' in ids: d.tap_id('rb_none', timeout=3); time.sleep(1); d.tap_id('btnNext', timeout=3)
        elif 'IntroActivity' in top and 'btnNext' in ids: d.tap_id('btnNext', timeout=3)
        elif 'MainActivity' in top and pkg in top and 'fab' in ids:       # MainActivity (with its '+') also flashes by at startup -> must be stable
            time.sleep(3)
            if 'MainActivity' in d.top() and 'IntroActivity' not in d.top() and d.find(id='fab'): break
        time.sleep(2)
    else:
        return False
    LAST_LOG.clear(); LAST_LOG.append('intro done')
    for name, issuer, secret in entries:                                  # --- 2) entries (3 attempts each)
        have = sum(1 for n in d.nodes() if n['id'] == 'profile_issuer')
        for attempt in range(3):
            if 'EditEntryActivity' in d.top() or d.find(id='fab_menu_item_enter'):   # leftover form/menu from a failed attempt
                d.back(); time.sleep(1.5); d.tap_text('DISCARD', timeout=2); time.sleep(1)
            steps = []
            ok = d.tap_id('fab', timeout=8); steps.append(f'fab={ok}'); time.sleep(1.5)
            ok = ok and d.tap_id('fab_menu_item_enter', timeout=8); steps.append(f'enter_manually={ok}'); time.sleep(2)
            ok = ok and _fill(d, 'text_name', name) and _fill(d, 'text_issuer', issuer) and _fill(d, 'text_secret', secret); steps.append(f'filled={ok}')
            if ok:
                d.tap_id('action_save', timeout=5)
                t_end = time.time() + 20
                while time.time() < t_end and sum(1 for n in d.nodes() if n['id'] == 'profile_issuer') <= have: time.sleep(1.5)
            added = sum(1 for n in d.nodes() if n['id'] == 'profile_issuer') > have
            LAST_LOG.append(f'entry {name} attempt {attempt + 1}: ' + ', '.join(steps) + f', added={added}')
            if added: break
        else:
            LAST_LOG.append('FAILED on entry %s; top=%s ids=%s' % (name, d.top(), sorted({n['id'] for n in d.nodes() if n['id']})[:12]))
            return False
    return sum(1 for n in d.nodes() if n['id'] == 'profile_issuer') >= len(entries)


def prep_markor(port, pkg='net.gsantner.markor_test', timeout=90):
    """Markor first-run flow, state-driven: 5-slide intro (NEXT x4, DONE) -> 'storage permission required' dialog (OK) -> system
    permission prompt (ALLOW) -> first-run changelog dialog (closed with BACK) -> MainActivity (file browser). Without it every path stops at IntroActivity.
    NOTE: the monkey / APE baselines of Markor were measured without this setup (random tools click through the intro themselves)."""
    d = Device(port)
    d.sh('am', 'force-stop', pkg)
    for perm in ('READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'): d.sh('pm', 'grant', pkg, 'android.permission.' + perm)
    d.start(pkg)
    end = time.time() + timeout
    while time.time() < end:
        top = d.top(); ids = {n['id'] for n in d.nodes()}
        if 'GrantPermissionsActivity' in top: d.tap_id('permission_allow_button', timeout=3)
        elif 'StoragePermissionActivity' in top: d.tap_id('button1', timeout=3)
        elif 'IntroActivity' in top:
            if not d.tap_id('next', timeout=2): d.tap_id('done', timeout=2)
        elif 'MainActivity' in top and pkg in top and 'customPanel' in ids:        # first-run changelog dialog over the file browser
            d.back()
        elif 'MainActivity' in top and pkg in top and 'toolbar' in ids:
            time.sleep(2)
            if 'MainActivity' in d.top() and 'customPanel' not in {n['id'] for n in d.nodes()}: return True
        time.sleep(1.5)
    return False


SCRIPTS = {'MMEX': prep_mmex, 'Aegis': prep_aegis, 'Markor': prep_markor}

if __name__ == '__main__':
    name, port = sys.argv[1], sys.argv[2]
    ok = SCRIPTS[name](port)
    print(name, 'prep', 'OK' if ok else 'FAILED'); print('LOG:', *globals().get('LAST_LOG', []), sep='\n  '); Device(port).screen()
