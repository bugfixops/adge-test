# ADGE vs Monkey on 13 apps

ADGE was aimed at the methods Monkey never reached and ran for 2 hours per app, with a checkpoint at 1 hour. Monkey ran for 1 hour per app. Both used the same 13 APKs on an Android 8.1 emulator, and both were measured the same way, with JaCoCo coverage.

Aegis, Markor and MMEX were run after their first-run setup script, which brings the app to the state a normal user reaches after the one-time setup (a created vault, a database, past the intro). Without it these apps trap any tool on a setup screen. The setup's coverage is excluded for both tools.

Compare ADGE's 1-hour numbers with Monkey's hour. The 2-hour numbers show what ADGE's second hour added.

## Results

- **Methods Monkey missed:** Monkey ran 9,465 of the 28,913 app methods and missed 19,448. ADGE reached **3,940** of those in 1 hour and **4,825** (24.8%) in 2 hours.
- **Line coverage:** ADGE beat Monkey on **12 of the 13 apps** at 1 hour. Averaged over the 13 apps, ADGE reached 41.8% at 1 hour and 45.1% at 2 hours, and Monkey 33.8%.
- **The one app Monkey led:** Diary (ADGE's run broke early — see below). Monkey also ran 664 methods that ADGE never reached across all apps, 387 of them in AnkiDroid, AnyMemo and AmazeFileManager, so Monkey still covers code ADGE does not.
- **Both together:** Monkey plus ADGE's 2 hours ran 14,290 of the methods (49.4%), against 9,465 (32.7%) for Monkey alone.

| App | Methods | Missed by Monkey | ADGE reached of those, 1 h | ADGE reached of those, 2 h | Monkey line %, 1 h | ADGE line %, 1 h | ADGE line %, 2 h |
|---|---:|---:|---:|---:|---:|---:|---:|
| Aegis | 2,957 | 2,084 | 130 | 141 | 27.0 | **30.2** | 30.7 |
| AmazeFileManager | 2,982 | 2,221 | 430 | 544 | 21.2 | **30.3** | 35.8 |
| AnkiDroid | 4,178 | 3,075 | 968 | 1,017 | 20.1 | **37.7** | 39.2 |
| AnyMemo | 2,192 | 1,522 | 420 | 677 | 26.7 | **34.7** | 50.2 |
| Currency | 145 | 77 | 7 | 7 | 39.2 | **45.1** | 45.1 |
| Diary | 287 | 122 | 4 | 4 | **46.4** | 29.4 | 29.4 |
| hash-checker | 525 | 170 | 22 | 22 | 54.9 | **56.3** | 57.0 |
| LibreTorrent | 4,728 | 3,168 | 438 | 454 | 30.0 | **39.2** | 39.8 |
| Markor \* | 2,855 | 1,760 | 434 | 544 | 34.2 | **43.5** | 51.1 |
| MMEX | 4,269 | 3,142 | 381 | 648 | 22.6 | **31.4** | 37.0 |
| Omni-Notes | 1,426 | 991 | 294 | 329 | 27.1 | **44.5** | 47.1 |
| openlauncher | 901 | 550 | 230 | 234 | 35.3 | **59.0** | 59.7 |
| Vanilla-Music | 1,468 | 566 | 182 | 204 | 54.3 | **61.9** | 63.9 |
| **All 13** | 28,913 | 19,448 | 3,940 | 4,825 | 33.8 | 41.8 | 45.1 |

Bold marks the higher line coverage at 1 hour. In the last row, line coverage is the average over the 13 apps.

\* Markor: ADGE ran the first-run setup, but Monkey's baseline did not (the Monkey run clicked through the intro itself), so this row is not a like-for-like comparison — ADGE starts past a gate Monkey had to spend time on.

## Setup scripts

Three apps hide their main features behind a one-time first-run flow. ADGE now runs a setup script for each before exploring, so its 2 hours are spent in the app rather than on the setup screens:

- **Aegis and MMEX:** both tools start from the same post-setup state (Monkey's baseline used the same scripts), so the comparison is like-for-like. Setup moved both from a loss to a win for ADGE.
- **Markor:** ADGE runs the setup; Monkey's baseline did not. ADGE leads, but on an uneven field (see the note under the table).

## The one loss: Diary

Diary is the only app where Monkey leads. ADGE's run broke after 3 minutes: the app stopped starting properly when ADGE restarted it, so ADGE explored for only 3 of its 120 minutes. Diary needs a rerun.

## How it was measured

- **Runs:** ADGE ran on GitHub Actions. Aegis, Markor and MMEX were run with their first-run setup in [run 41002271340](https://github.com/bugfixops/adge-test/actions/runs/41002271340); the other 10 apps in [run 40800801720](https://github.com/bugfixops/adge-test/actions/runs/40800801720). Monkey ran for 1 hour per app with `monkey -p <package> -s 20261016 --throttle 500 --ignore-crashes --ignore-timeouts --ignore-security-exceptions`. Monkey's raw data isn't in this repository.
- **Aiming:** ADGE's targets are the methods Monkey missed (`monkey_targets/` in the repository root), and ADGE prefers widgets whose handlers can reach a target. An earlier, unaimed 1-hour ADGE run with no setup ([commit e90fcb0](https://github.com/bugfixops/adge-test/tree/e90fcb0a4f9a7558a3d308873c82ae2b18786b82/results)) reached 3,147 of the methods Monkey missed; this run reaches 3,940 at 1 hour. Most of the gain on the three setup apps is the setup, not the aiming.
- **Coverage:** JaCoCo, with the same class files for both tools. A method counts as reached if any of its code ran. The snapshot taken before a tool starts (including any setup) is left out for both. ADGE's 1-hour numbers use the snapshots taken in its first 60 minutes, plus the next 30 seconds.

## Files

- `comparison/summary.csv`: every per-app number, including branch and class coverage.
- `comparison/methods_beyond_monkey.csv`: the 4,825 methods Monkey missed that ADGE reached, and when ADGE first reached each one.
- `comparison/coverage_over_time.csv`: coverage at 10, 30, 60, 90 and 120 minutes.
- `<App>/`: each app's raw ADGE run.
