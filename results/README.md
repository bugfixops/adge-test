# ADGE aimed at Monkey's gaps vs Monkey: 13 apps

ADGE was changed to aim at the methods Monkey never reached, then run for **2 hours** per app, with a checkpoint at **1 hour**. Monkey ran **1 hour** per app (`final_java_only_monkey/`). Both used the same 13 instrumented APKs on an Android 8.1 (API 27) emulator, and both are measured the same way: JaCoCo coverage from snapshots taken every 30 seconds.

The fair head-to-head is ADGE at 1 hour against Monkey's hour. ADGE's second hour shows what extra time adds.

## Summary

- **What Monkey missed:** in its hour, Monkey ran 9,465 of the 28,913 app methods and never reached the other **19,448**.
- **What ADGE reached of those:** **3,128** in its first hour and **3,625** after two hours (18.6% of what Monkey missed). Most came early: 1,496 in the first 10 minutes, 2,572 by 30 minutes.
- **Both tools together:** Monkey plus ADGE's 2 hours ran 13,090 methods (45.3% of app methods), against 9,465 (32.7%) for Monkey alone.
- **The other way round:** Monkey reached 2,938 methods that ADGE never did in 2 hours, 2,374 of them in Markor, MMEX and Aegis, where ADGE was stuck on setup or tutorial screens.
- **Line coverage:** ADGE beat Monkey on **9 of 13** apps at 1 hour and 9 of 13 at 2 hours. Mean line coverage: ADGE 35.0% at 1 hour and 37.4% at 2 hours, Monkey 33.8%.

Three apps are not like-for-like, and the tables mark them:

- ¹ **Aegis and MMEX:** Monkey started after a setup script (Aegis with a vault and two entries, MMEX with a database). ADGE started from a fresh install and spent most of its time on those setup screens.
- ² **Diary:** ADGE's run broke after about 3 minutes: from then on the app didn't start properly on each restart and ADGE ended every run without tapping, so it explored for only those 3 minutes. It needs a rerun.

On the other 10 apps, ADGE reached **3,488 of the 14,100 methods Monkey missed (24.7%)** in 2 hours, 2,991 (21.2%) of them in the first hour, and beat Monkey's line coverage on 9 of 10 apps at 1 hour.

## Methods Monkey missed that ADGE reached

| App | App methods | Monkey reached | Monkey missed | ADGE reached of those, 1 h | 2 h | % of missed, 2 h | Monkey reached, ADGE didn't (2 h) |
|---|---:|---:|---:|---:|---:|---:|---:|
| Aegis ¹ | 2,957 | 873 | 2,084 | 65 | 65 | 3.1 | 477 |
| AmazeFileManager | 2,982 | 761 | 2,221 | 430 | 544 | 24.5 | 96 |
| AnkiDroid | 4,178 | 1,103 | 3,075 | 968 | 1,017 | 33.1 | 169 |
| AnyMemo | 2,192 | 670 | 1,522 | 420 | 677 | 44.5 | 122 |
| Currency | 145 | 68 | 77 | 7 | 7 | 9.1 | 3 |
| Diary ² | 287 | 165 | 122 | 4 | 4 | 3.3 | 52 |
| hash-checker | 525 | 355 | 170 | 22 | 22 | 12.9 | 12 |
| LibreTorrent | 4,728 | 1,560 | 3,168 | 438 | 454 | 14.3 | 27 |
| Markor | 2,855 | 1,095 | 1,760 | 0 | 0 | 0.0 | 987 |
| MMEX ¹ | 4,269 | 1,127 | 3,142 | 68 | 68 | 2.2 | 910 |
| Omni-Notes | 1,426 | 435 | 991 | 294 | 329 | 33.2 | 6 |
| openlauncher | 901 | 351 | 550 | 230 | 234 | 42.5 | 18 |
| Vanilla-Music | 1,468 | 902 | 566 | 182 | 204 | 36.0 | 59 |
| **All 13** | **28,913** | **9,465** | **19,448** | **3,128** | **3,625** | **18.6** | **2,938** |
| **10 like-for-like** | 21,400 | 7,300 | 14,100 | 2,991 | 3,488 | 24.7 | 1,499 |

A method counts as reached if any of its code ran. The full list of the 3,625 methods, with when ADGE first reached each one, is in `comparison/methods_beyond_monkey.csv`.

## Coverage

| App | Line % ADGE 1 h | ADGE 2 h | Monkey 1 h | Branch % ADGE 1 h | ADGE 2 h | Monkey 1 h | Class % ADGE 1 h | ADGE 2 h | Monkey 1 h |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Aegis ¹ | 10.1 | 12.4 | **27.0** | 5.1 | 6.4 | 17.4 | 23.8 | 26.3 | 39.6 |
| AmazeFileManager | **30.3** | 35.8 | 21.2 | 22.4 | 27.5 | 15.9 | 44.9 | 48.7 | 36.0 |
| AnkiDroid | **37.7** | 39.2 | 20.1 | 25.1 | 26.2 | 14.3 | 57.1 | 58.4 | 33.4 |
| AnyMemo | **34.7** | 50.2 | 26.7 | 22.9 | 34.5 | 18.0 | 51.8 | 64.3 | 37.0 |
| Currency | **45.1** | 45.1 | 39.2 | 32.5 | 32.5 | 25.7 | 62.5 | 62.5 | 62.5 |
| Diary ² | 29.4 | 29.4 | **46.4** | 19.3 | 19.3 | 31.4 | 56.5 | 56.5 | 87.0 |
| hash-checker | **56.3** | 57.0 | 54.9 | 27.4 | 27.6 | 26.1 | 79.1 | 81.4 | 80.2 |
| LibreTorrent | **39.2** | 39.8 | 30.0 | 23.0 | 23.8 | 17.1 | 60.4 | 60.4 | 52.5 |
| Markor | 2.6 | 2.6 | **34.2** | 1.1 | 1.1 | 24.4 | 6.3 | 6.3 | 55.1 |
| MMEX ¹ | 4.4 | 4.4 | **22.6** | 1.6 | 1.6 | 14.1 | 12.9 | 12.9 | 31.8 |
| Omni-Notes | **44.5** | 47.1 | 27.1 | 34.1 | 36.5 | 21.8 | 66.5 | 68.3 | 41.3 |
| openlauncher | **59.0** | 59.7 | 35.3 | 42.8 | 44.0 | 22.2 | 71.4 | 71.4 | 48.2 |
| Vanilla-Music | **61.9** | 63.9 | 54.3 | 49.0 | 50.5 | 43.5 | 83.4 | 85.8 | 71.6 |
| **Mean, all 13** | 35.0 | 37.4 | 33.8 | 23.5 | 25.5 | 22.5 | 52.1 | 54.1 | 52.0 |
| **Mean, 10 like-for-like** | 41.1 | 44.0 | 34.3 | 28.0 | 30.4 | 22.9 | 58.3 | 60.8 | 51.8 |

Bold marks the higher line coverage at 1 hour. ADGE leads on AmazeFileManager, AnkiDroid, AnyMemo, Currency, hash-checker, LibreTorrent, Omni-Notes, openlauncher, Vanilla-Music; Monkey leads on Aegis, Diary, Markor, MMEX.

## Over time

Methods Monkey missed, reached by ADGE, counted at each checkpoint:

| App | 10 min | 30 min | 60 min | 90 min | 120 min |
|---|---:|---:|---:|---:|---:|
| Aegis ¹ | 61 | 61 | 65 | 65 | 65 |
| AmazeFileManager | 155 | 348 | 430 | 517 | 544 |
| AnkiDroid | 412 | 895 | 968 | 970 | 1,017 |
| AnyMemo | 258 | 420 | 420 | 532 | 677 |
| Currency | 7 | 7 | 7 | 7 | 7 |
| Diary ² | 4 | 4 | 4 | 4 | 4 |
| hash-checker | 22 | 22 | 22 | 22 | 22 |
| LibreTorrent | 133 | 255 | 438 | 444 | 454 |
| Markor | 0 | 0 | 0 | 0 | 0 |
| MMEX ¹ | 68 | 68 | 68 | 68 | 68 |
| Omni-Notes | 124 | 191 | 294 | 327 | 329 |
| openlauncher | 126 | 146 | 230 | 234 | 234 |
| Vanilla-Music | 126 | 155 | 182 | 188 | 204 |
| **All 13** | **1,496** | **2,572** | **3,128** | **3,378** | **3,625** |

ADGE found 41.3% of its 2-hour total in the first 10 minutes and 86.3% in the first hour. The second hour added 497 methods.

Line coverage (%) over time:

| App | ADGE 10 | ADGE 30 | ADGE 60 | ADGE 90 | ADGE 120 | Monkey 10 | Monkey 30 | Monkey 60 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Aegis ¹ | 7.4 | 7.4 | 10.1 | 10.1 | 12.4 | 18.2 | 21.3 | 27.0 |
| AmazeFileManager | 21.5 | 27.7 | 30.3 | 34.5 | 35.8 | 17.6 | 19.3 | 21.2 |
| AnkiDroid | 17.1 | 35.1 | 37.7 | 37.9 | 39.2 | 14.8 | 18.9 | 20.1 |
| AnyMemo | 23.6 | 34.7 | 34.7 | 44.1 | 50.2 | 21.6 | 22.7 | 26.7 |
| Currency | 41.3 | 44.9 | 45.1 | 45.1 | 45.1 | 27.2 | 32.3 | 39.2 |
| Diary ² | 29.4 | 29.4 | 29.4 | 29.4 | 29.4 | 32.1 | 41.5 | 46.4 |
| hash-checker | 51.9 | 52.7 | 56.3 | 57.0 | 57.0 | 29.7 | 46.8 | 54.9 |
| LibreTorrent | 23.7 | 31.9 | 39.2 | 39.5 | 39.8 | 17.8 | 23.1 | 30.0 |
| Markor | 2.6 | 2.6 | 2.6 | 2.6 | 2.6 | 2.5 | 33.6 | 34.2 |
| MMEX ¹ | 4.0 | 4.0 | 4.4 | 4.4 | 4.4 | 20.5 | 22.2 | 22.6 |
| Omni-Notes | 27.1 | 37.8 | 44.5 | 46.9 | 47.1 | 21.4 | 25.3 | 27.1 |
| openlauncher | 49.0 | 51.3 | 59.0 | 59.4 | 59.7 | 33.6 | 33.8 | 35.3 |
| Vanilla-Music | 49.7 | 56.4 | 61.9 | 62.5 | 63.9 | 44.1 | 49.2 | 54.3 |
| **Mean, all 13** | **26.8** | **32.0** | **35.0** | **36.4** | **37.4** | **23.2** | **30.0** | **33.8** |

## Screens opened

The ADGE paper measures how many activities (screens) a tool opens. Here an activity counts as opened if any of its code ran, using the app's own activities from the manifest.

| App | Activities | Monkey 1 h | ADGE 1 h | ADGE 2 h |
|---|---:|---:|---:|---:|
| Aegis ¹ | 14 | 7 | 3 | 3 |
| AmazeFileManager | 5 | 3 | 3 | 3 |
| AnkiDroid | 21 | 7 | 15 | 15 |
| AnyMemo | 19 | 5 | 10 | 13 |
| Currency | 7 | 4 | 4 | 4 |
| Diary ² | 4 | 3 | 1 | 1 |
| hash-checker | 1 | 1 | 1 | 1 |
| LibreTorrent | 17 | 10 | 13 | 13 |
| Markor | 11 | 4 | 2 | 2 |
| MMEX ¹ | 40 | 6 | 5 | 5 |
| Omni-Notes | 12 | 4 | 6 | 7 |
| openlauncher | 7 | 5 | 6 | 6 |
| Vanilla-Music | 13 | 5 | 9 | 9 |
| **All 13** | **171** | **64** | **78** | **82** |

In its first hour ADGE opened more activities than Monkey on 6 apps (AnkiDroid, AnyMemo, LibreTorrent, Omni-Notes, openlauncher, Vanilla-Music), the same number on 3 and fewer on 4 (Aegis, Diary, Markor, MMEX).

## Did aiming at Monkey's gaps help?

To check, the 1-hour checkpoint is compared with the earlier, unaimed 1-hour ADGE run. That run is no longer in this folder; it's in the repository history at [commit e90fcb0](https://github.com/bugfixops/adge-test/tree/e90fcb0a4f9a7558a3d308873c82ae2b18786b82/results). Both runs used the same harness, with two differences. This run force-stops the app before every ADGE restart; the unaimed run did that only for AnkiDroid and Omni-Notes, to get them out of the camera screen. And this run restarted MMEX's emulator to free memory.

| App | Monkey-missed reached, aimed 1 h | Unaimed 1 h | Line % aimed 1 h | Unaimed 1 h | Widgets linked to handlers | Handlers | Handlers that lead to Monkey's gaps |
|---|---:|---:|---:|---:|---:|---:|---:|
| Aegis ¹ | 65 | 242 | 10.1 | 24.9 | 43 | 49 | 37 |
| AmazeFileManager | 430 | 290 | 30.3 | 25.3 | 46 | 37 | 24 |
| AnkiDroid | 968 | 925 | 37.7 | 35.9 | 27 | 43 | 40 |
| AnyMemo | 420 | 518 | 34.7 | 45.5 | 45 | 17 | 15 |
| Currency | 7 | 28 | 45.1 | 54.0 | 10 | 10 | 6 |
| Diary ² | 4 | 5 | 29.4 | 29.5 | 7 | 15 | 5 |
| hash-checker | 22 | 71 | 56.3 | 66.9 | 9 | 9 | 0 |
| LibreTorrent | 438 | 357 | 39.2 | 36.3 | 8 | 14 | 5 |
| Markor | 0 | 0 | 2.6 | 2.4 | 50 | 54 | 43 |
| MMEX ¹ | 68 | 67 | 4.4 | 2.4 | 34 | 41 | 34 |
| Omni-Notes | 294 | 287 | 44.5 | 46.8 | 40 | 37 | 17 |
| openlauncher | 230 | 202 | 59.0 | 55.4 | 28 | 28 | 19 |
| Vanilla-Music | 182 | 155 | 61.9 | 59.6 | 26 | 33 | 21 |
| **All 13** | **3,128** | **3,147** | **35.0** | **37.3** | | | |

**Not clearly.** In the first hour the aimed run reached 3,128 of Monkey's missed methods and the unaimed run 3,147. The aimed run did better on AmazeFileManager (+140), AnkiDroid (+43), LibreTorrent (+81), openlauncher (+28), Vanilla-Music (+27), and worse on Aegis (-177), AnyMemo (-98), Currency (-21), hash-checker (-49). The rest differ by 10 methods or fewer.

Three things limit what aiming can do here:

- **Few widgets are guided.** ADGE's analysis links only 7 to 50 widgets per app to their handlers, and only those can be prioritised; every other tap is chosen as before. In hash-checker no handler leads to Monkey's gaps, so that run had no guidance at all.
- **The targets are broad.** Monkey missed 32.4-74.5% of each app's methods, so the targets cover most of the app rather than a few specific methods as in the paper.
- **One run each.** ADGE picks at random among equally ranked widgets, and the extra force-stop probably hurt Aegis and Diary, so app-level differences of this size can't be pinned on the targeting.

## Crashes ADGE found

| App | Crashes | Exception types |
|---|---:|---|
| Aegis | 2 | NullPointerException (1), IllegalStateException (1) |
| AmazeFileManager | 2 | NullPointerException (1), IndexOutOfBoundsException (1) |
| AnkiDroid | 1 | NumberFormatException (1) |
| Currency | 2 | RuntimeException (2) |
| hash-checker | 50 | ClassCastException (50) |
| LibreTorrent | 11 | NullPointerException (11) |
| Omni-Notes | 2 | IndexOutOfBoundsException (1), NoClassDefFoundError (1) |

The Monkey folder has no crash logs, so crash counts can't be compared. A crash loses the coverage since the last 30-second snapshot, for both tools.

## Per-app notes

- **Aegis:** ¹ Not like-for-like. Monkey's run (2026-10-23) started after a setup script (`prep_aegis`: intro finished, two entries added); it also stopped 42 seconds early. The dataset's earlier Monkey run, without the setup, reached 20.7% line coverage. ADGE started from a fresh install and spent most of its 2 hours in the first-run wizard: 1,540 of its 1,789 taps (86.1%) were on the wizard's own widgets (security options, password field, Next and Previous, Import vault), and it reached the main screen in only 3 of its 163 restarts. Because the app is now force-stopped before each restart, the unfinished wizard most likely started over every time. In the earlier unaimed run, without the force-stop, ADGE finished the wizard itself and reached 24.9%.
- **AnkiDroid:** Monkey's run (2026-10-27) is newer than the dataset's (3.4% line coverage) and has no setup record. ADGE opened 15 of the 21 activities, Monkey 7.
- **AnyMemo:** Highest share: ADGE reached 677 of the 1,522 methods Monkey missed (44.5%), 257 of them in the second hour, the biggest second-hour gain of any app.
- **Currency:** ADGE opened the chart screen twice, and each time the app crashed within 5 seconds (a NullPointerException while a result was delivered to ChartActivity), before the next snapshot. So none of ChartActivity's 25 methods were recorded; the earlier ADGE run recorded 10. ADGE still beat Monkey (45.1% vs 39.2% line coverage at 1 hour).
- **Diary:** ² Not valid. All 61 of ADGE's taps happened in the first 3 minutes. In all 231 restarts after that, the app didn't show as running for its first two or more start attempts, and ADGE then ended the run after its first screen read, without tapping anything; 232 of the 233 restarts made no taps. The logs don't show why. The earlier unaimed run, which didn't force-stop the app between restarts, had 0 such failed starts.
- **hash-checker:** No handler leads to a method Monkey missed, so this run had no target guidance. The app crashed 50 times, always with a ClassCastException, and 11 snapshots failed, each within 14 seconds of a crash.
- **Markor:** Stuck in the first-run tutorial for all 2 hours, as in the earlier ADGE run: across 162 restarts it tapped Next 484 times and swiped the pages 322 times, and line coverage stayed at 2.6% from minute 10 to the end. ADGE won't keep tapping the same button within a run, which most likely stops it from paging through to the end. Monkey was at about the same coverage after 10 minutes (2.5%) and at 33.6% after 30, so it got past the tutorial in between.
- **MMEX:** ¹ Not like-for-like. Monkey's run (2026-10-22) started after a setup script (`prep_mmex`, which creates the database). The dataset's earlier Monkey run, without the setup, reached 4.2%. ADGE started from a fresh install and spent the 2 hours on the create-database and password screens: across its 225 restarts it tapped Create database 222 times, OK 222 times and Open database 222 times, and each run then ended by itself. ADGE has no text input. Its emulator was restarted twice to free memory; those 121 seconds aren't counted as exploration.

## How this was measured

- **ADGE runs:** 12 apps from [adge-test run 40800801720](https://github.com/bugfixops/adge-test/actions/runs/40800801720), MMEX from [test-13 run 40894072689](https://github.com/bugfixops/test-13/actions/runs/40894072689). The raw outputs are in this folder, one subfolder per app.
- **Monkey data:** the Monkey runs themselves aren't in this repository. Only the numbers taken from them are, in this file and in `comparison/`.
- **Monkey runs:** `final_java_only_monkey/<App>/monkey/`, 60 minutes each: `monkey -p <package> -s 20261016 --throttle 500 --ignore-crashes --ignore-timeouts --ignore-security-exceptions`. Ten of these runs are the same as the earlier dataset's Monkey runs; Aegis, AnkiDroid and MMEX are newer runs.
- **How ADGE was aimed:** each app's targets are the methods Monkey didn't cover (`monkey_targets/<App>.txt` at the repository root). ADGE's static analysis marks a widget handler as tainted if it can reach a target through the app's call graph, and ADGE taps tainted widgets first, as with the paper's tainted ICFG. The paper builds that graph with FlowDroid's taint analysis; the code for that step isn't in ADGE's repository, so reachability over the app's call graph (from Soot) stands in for it. ADGE's Frida monitor still reports screens (Activity.onCreate); coverage comes from JaCoCo.
- **Checkpoints:** a checkpoint at N minutes uses every snapshot taken in the first N minutes of exploration, plus 30 seconds. The last checkpoint uses all snapshots. MMEX's emulator restarts aren't counted as exploration.
- **Same basis for both tools:** the snapshot taken before a tool starts (install and setup) is left out for both, as Monkey's own `merged.ec` does. Snapshots that failed, mostly right after an app crash, are skipped: 15 of 4,187 for ADGE (11 of them in hash-checker) and 3 of 1,545 for Monkey.
- **Methods:** every method in the JaCoCo report except the coverage harness (`JacocoInstrument` classes), matched by class, name and signature, using the same class files for both tools.
- **Line, branch and class %:** JaCoCo report totals, the same as Monkey's own `report.csv`. My recomputed Monkey numbers match its reports exactly. Class % counts classes with at least one method run.
- **Environment:** both on Android 8.1 (API 27) emulators. ADGE ran on GitHub's Linux runners (pixel_2 profile, 1080x1920 at 420 dpi, 3 cores, 3 GB); Monkey ran on a local emulator, whose settings the folder doesn't record.

## Files

- `comparison/summary.csv`: one row per app with every number above (methods, coverage at 1 h and 2 h, screens, crashes, guidance size, and checks against Monkey's report).
- `comparison/methods_beyond_monkey.csv`: the 3,625 methods Monkey missed that ADGE reached: app, class, method, signature, and the first checkpoint (10/30/60/90/120 min) by which ADGE had reached it.
- `comparison/coverage_over_time.csv`: line, branch and class coverage and method counts for ADGE (10-120 min), Monkey (10-60 min) and the earlier unaimed ADGE run (60 min).
- `<App>/`: ADGE's raw run output (coverage snapshots, JaCoCo report, ADGE's own results, logs, static-analysis database).
