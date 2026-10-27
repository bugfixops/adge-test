# ADGE results — 13 Java-only Android apps

One complete 60-minute ADGE run per app, compared against the **Monkey** and **APE** baselines from
`dataset/final_java_only/`. Every app was explored for the full hour on the same emulator setup, the same APKs,
and the same coverage method as those baselines, so the three tools are directly comparable.

- **Tool under test:** ADGE-Explorer ([github.com/ADGE-ICST/ADGE](https://github.com/ADGE-ICST/ADGE)), ported to Linux and run on GitHub Actions ([bugfixops/adge-test](https://github.com/bugfixops/adge-test)).
- **Apps (13):** Aegis, AmazeFileManager, AnkiDroid, AnyMemo, Currency, Diary, hash-checker, LibreTorrent, Markor, MMEX, Omni-Notes, openlauncher, Vanilla-Music.
- **Metric:** JaCoCo line / branch / class coverage of each app's own classes (the instrumentation helper classes are excluded), computed exactly as in the dataset tables. Recomputed Monkey/APE values match the dataset within 0.2 points.

## The three tools

| Tool | What it is | Ran by |
|---|---|---|
| **ADGE** | The tool in this repo. Reads the app's code to learn which widget runs which method, then explores the UI preferring widgets that lead to a target or open a new screen. Frida reports when target code runs. | us, on GitHub Actions |
| **Monkey** | Android's built-in random tester: fast random taps, swipes and key events. | dataset baseline (earlier local run) |
| **APE** | A research tool that builds a model of the app's screens while exploring and uses it to choose actions. | dataset baseline (earlier local run) |

## Headline results

Mean over the 13 apps (%):

| Metric | ADGE | Monkey | APE | ADGE > Monkey | ADGE > APE |
|---|---:|---:|---:|---:|---:|
| Line | **37.3** | 30.5 | 44.9 | 10 / 13 | 3 / 13 |
| Branch | **25.4** | 20.1 | 32.0 | 10 / 13 | 3 / 13 |
| Class | **54.0** | 48.5 | 62.5 | 10 / 13 | 3 / 13 |

ADGE lands between Monkey and APE: it beats Monkey on 10 of 13 apps and matches or beats APE on 3
(Aegis, hash-checker, openlauncher). The three apps where ADGE trails Monkey are Diary, Markor and MMEX
(all explained in [Per-app notes](#per-app-notes)).

### Line coverage, per app (%)

| App | ADGE | Monkey | APE | Best | ADGE adds to Monkey∪APE¹ |
|---|---:|---:|---:|:--|---:|
| Aegis | **24.9** | 20.7 | 15.0 | ADGE | +6.05 |
| AmazeFileManager | 25.3 | 21.1 | **32.5** | APE | +1.97 |
| AnkiDroid | 35.9 | 3.3 | **39.9** | APE | +3.32 |
| AnyMemo | 45.5 | 26.8 | **54.1** | APE | +7.51 |
| Currency | 54.0 | 39.2 | **57.5** | APE | +0.27 |
| Diary | 29.5 | 46.4 | **53.9** | APE | +0.04 |
| hash-checker | **66.9** | 54.8 | 64.1 | ADGE | +5.53 |
| LibreTorrent | 36.3 | 29.8 | **41.4** | APE | +1.34 |
| Markor | 2.4 | 34.1 | **50.5** | APE | 0.00 |
| MMEX | 2.4 | 4.2 | **4.2** | APE | 0.00 |
| Omni-Notes | 46.8 | 27.1 | **54.0** | APE | +1.97 |
| openlauncher | **55.4** | 35.1 | 51.8 | ADGE | +7.15 |
| Vanilla-Music | 59.6 | 54.3 | **64.9** | APE | +1.48 |
| **mean** | **37.3** | **30.5** | **44.9** | | |

¹ Extra line coverage, in percentage points, that ADGE reaches on top of what Monkey and APE cover **combined**.
Across all apps ADGE hit **4,116 lines neither baseline did**. Full branch/class numbers are in `summary.csv`.

### Coverage over time (line %, at 10 / 30 / 60 min)

| App | ADGE | Monkey | APE |
|---|---|---|---|
| Aegis | 16.2 / 23.8 / 24.9 | 15.3 / 18.2 / 20.7 | 15.1 / 15.1 / 15.1 |
| AmazeFileManager | 21.1 / 21.2 / 25.3 | 17.6 / 19.3 / 21.2 | 25.9 / 30.9 / 32.6 |
| AnkiDroid | 17.7 / 28.8 / 35.9 | 2.9 / 3.3 / 3.4 | 3.1 / 29.1 / 40.1 |
| AnyMemo | 33.6 / 45.2 / 45.5 | 21.6 / 22.7 / 26.7 | 36.3 / 46.7 / 54.1 |
| Currency | 40.9 / 45.4 / 54.0 | 27.2 / 32.3 / 39.2 | 55.5 / 57.5 / 57.5 |
| Diary | 25.4 / 25.4 / 29.5 | 32.1 / 41.5 / 46.4 | 46.7 / 52.0 / 53.9 |
| hash-checker | 51.8 / 66.6 / 66.9 | 29.7 / 46.8 / 54.9 | 60.4 / 64.0 / 64.2 |
| LibreTorrent | 15.8 / 18.2 / 36.3 | 17.8 / 23.1 / 30.0 | 33.4 / 39.8 / 41.6 |
| Markor | 2.4 / 2.4 / 2.4 | 2.5 / 33.6 / 34.2 | 35.9 / 49.2 / 50.5 |
| MMEX | 2.4 / 2.4 / 2.4 | 2.9 / 4.2 / 4.2 | 4.1 / 4.2 / 4.2 |
| Omni-Notes | 28.9 / 39.1 / 46.8 | 21.4 / 25.3 / 27.1 | 41.0 / 52.3 / 54.1 |
| openlauncher | 43.0 / 54.9 / 55.4 | 33.6 / 33.8 / 35.3 | 51.9 / 51.9 / 52.0 |
| Vanilla-Music | 42.1 / 52.8 / 59.6 | 44.2 / 49.2 / 54.3 | 51.6 / 62.5 / 65.0 |
| **mean** | **26.3 / 32.8 / 37.3** | **20.7 / 27.2 / 30.5** | **35.6 / 42.7 / 44.9** |

Full per-snapshot data is in `coverage_over_time.csv`.

### Crashes found (app crashes during exploration)

| | ADGE | Monkey | APE |
|---|---:|---:|---:|
| hash-checker | 21 | 6 | 33 |
| LibreTorrent | 4 | 0 | 2 |
| Omni-Notes | 2 | 0 | 7 |

ADGE's hash-checker crashes are all one `ClassCastException`; its LibreTorrent crash is a `NullPointerException`.
Monkey/APE crash counts come from the dataset.

## Per-app notes

Every run is a complete 60-minute run, but five apps carry context worth knowing:

- **Markor (2.4%)** — stuck in the first-run tutorial the whole hour. ADGE never repeats a tap it has already
  made, so it could not page through the tutorial to the app. Monkey was also stuck at 10 min but escaped by 30 min;
  APE escaped immediately.
- **MMEX (2.4%)** — blocked at the "create database" setup screen, which needs typed input. This gates all three
  tools (APE and Monkey also sit near 4%).
- **Diary (29.5%)** — about 45 of the 60 minutes went to browsing external websites inside the app's built-in web
  view (the emulator has internet). Coverage is real but lower than it would be focused on the app.
- **Vanilla-Music (59.6%)** — coverage is complete and valid; only the *screens-reached* count (2/13) is understated
  because Frida stopped reporting after ~10 minutes. Coverage collection (JaCoCo) was unaffected.
- **AnkiDroid (35.9%) and Omni-Notes (46.8%)** — these were rerun after a fix. Both apps open the camera via
  "take photo"; that activity sits on top of the app's task and a plain relaunch kept landing on it. The harness now
  clears the app's task before each restart, so both explored the full hour (see `github_run` 40430345422).

### Important caveat: ADGE ran with partial guidance

ADGE's top priority is widgets on a path to a **target** (its taint/ICFG data). The tool that computes that data is
**not included in the ADGE repository**, so that input was empty for every app here. ADGE still used the two things its
own code can compute — widget→listener mappings and inter-component (ICC) widgets — but the strongest guidance signal
was absent. These results therefore measure ADGE as a general explorer, not the fully-guided tool from its paper.
Per-app guidance strength is in `summary.csv` (`db_widget_ids`, `db_icc_methods`).

## How the runs were produced

- **Platform:** GitHub Actions, one job per app, Android 8.1 emulator (API 27, x86_64, AOSP image, rooted,
  1080×1920 @ 420 dpi) via `reactivecircus/android-emulator-runner`.
- **Per app:** build ADGE → build the static-analysis database from the APK → boot the emulator → install the app and
  push the sample files → start frida-server and the uiautomator2 agent → run ADGE for 60 minutes, restarting it each
  time it finishes (as the original `run.bat` loops) → dump JaCoCo coverage every 30 s → merge and report.
- **Same inputs as the baselines:** identical APKs (SHA-256 verified against the dataset), identical `fixtures/`, and
  the same JaCoCo 0.8.8 report pipeline used for the Monkey/APE runs.
- **Measurement basis:** the coverage snapshot taken before ADGE starts (install/setup only) is excluded, matching how
  the dataset reports Monkey/APE. The pipeline that produced these runs is in the
  [bugfixops/adge-test](https://github.com/bugfixops/adge-test) repo (`ci/`, `.github/workflows/adge.yml`).

## Files

```
results/
├── README.md               this file
├── summary.csv             one row per app: ADGE vs Monkey vs APE (line/branch/class), plus ADGE's
│                           active minutes, restarts, actions, screens reached, crashes, guidance size,
│                           validity note, and the GitHub run it came from
├── overlap.csv             per-tool unique line coverage (what each tool hit that the others missed) and the
│                           union of all three
├── coverage_over_time.csv  line/branch % for each tool at 10, 30 and 60 minutes
└── apps/<App>/             one complete run per app:
    ├── meta.json           run metadata and headline numbers (device, SDK, run id, coverage %)
    ├── summary / report.csv, report.xml.gz   JaCoCo coverage report
    ├── methods_hit.csv     per-method hit/not-hit (JaCoCo names, instrumentation classes excluded)
    ├── merged.ec, segments.csv, segments.tar.gz   raw JaCoCo data (one snapshot per 30 s)
    ├── logcat_crash.txt, run.log, jacoco.log      device crash log and harness logs
    ├── db/                 the static-analysis database ADGE was guided by (+ build log and stats.json)
    └── adge/               ADGE's own output:
        ├── results/<package>/result.json         screens reached and time-to-reach each
        ├── results/<package>/path-*.txt           the exact taps that first reached each screen
        ├── iterations.csv  each restart: exit code and duration
        ├── config.json, hook.js                   the config and Frida hook used
        └── logs.tar.gz     full per-restart ADGE / Frida / uiautomator2 logs
```

### Reading `summary.csv`

`adge_line` / `monkey_line` / `ape_line` (and `_branch`, `_class`) are the headline coverage numbers. Other columns:
`adge_active_minutes` (time actually exploring, not setup), `adge_runs` (restarts within the hour), `adge_actions`
(taps/swipes), `screens_reached`/`screens_total`, `adge_crashes`, `db_widget_ids`/`db_icc_methods` (how much guidance
the database held), `adge_adds_to_monkey_ape_line` (unique line pp over both baselines), and `github_run` (provenance).
`adge_line_incl_setup_dump` is the raw per-app number before the setup snapshot is excluded; `adge_line` is the
comparable value used in the tables above.

## Provenance

| Run | GitHub Actions run | Apps |
|---|---|---|
| Main (11 apps) | [40399232327](https://github.com/bugfixops/adge-test/actions/runs/40399232327) | Aegis, AmazeFileManager, Currency, Diary, hash-checker, LibreTorrent, Markor, Vanilla-Music |
| Stop-rule rerun | [40402882307](https://github.com/bugfixops/adge-test/actions/runs/40402882307) | MMEX, openlauncher |
| Stop-rule rerun | [40409386388](https://github.com/bugfixops/adge-test/actions/runs/40409386388) | AnyMemo |
| Camera-fix rerun | [40430345422](https://github.com/bugfixops/adge-test/actions/runs/40430345422) | AnkiDroid, Omni-Notes |

Each app's exact run id is in its `meta.json` (`github_run_id`) and in `summary.csv` (`github_run`).
