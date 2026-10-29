# ADGE vs Monkey on 13 apps

ADGE was aimed at the methods Monkey never reached and ran for 2 hours per app, with a checkpoint at 1 hour. Monkey ran for 1 hour per app. Both used the same 13 APKs on an Android 8.1 emulator, and both were measured the same way, with JaCoCo coverage.

Compare ADGE's 1-hour numbers with Monkey's hour. The 2-hour numbers show what ADGE's second hour added.

## Results

- **Methods Monkey missed:** Monkey ran 9,465 of the 28,913 app methods and missed 19,448. ADGE reached **3,128** of those in 1 hour and **3,625** (18.6%) in 2 hours.
- **Line coverage:** ADGE beat Monkey on **9 of the 13 apps** at 1 hour. Averaged over the 13 apps, ADGE reached 35.0% at 1 hour and 37.4% at 2 hours, and Monkey 33.8%.
- **Where Monkey won:** Aegis, Diary, Markor and MMEX (reasons below). Monkey also ran 2,938 methods that ADGE never reached, 2,374 of them in Markor, MMEX and Aegis.
- **Both together:** Monkey plus ADGE's 2 hours ran 13,090 of the methods (45.3%), against 9,465 (32.7%) for Monkey alone.

| App | Methods | Missed by Monkey | ADGE reached of those, 1 h | ADGE reached of those, 2 h | Monkey line %, 1 h | ADGE line %, 1 h | ADGE line %, 2 h |
|---|---:|---:|---:|---:|---:|---:|---:|
| Aegis | 2,957 | 2,084 | 65 | 65 | **27.0** | 10.1 | 12.4 |
| AmazeFileManager | 2,982 | 2,221 | 430 | 544 | 21.2 | **30.3** | 35.8 |
| AnkiDroid | 4,178 | 3,075 | 968 | 1,017 | 20.1 | **37.7** | 39.2 |
| AnyMemo | 2,192 | 1,522 | 420 | 677 | 26.7 | **34.7** | 50.2 |
| Currency | 145 | 77 | 7 | 7 | 39.2 | **45.1** | 45.1 |
| Diary | 287 | 122 | 4 | 4 | **46.4** | 29.4 | 29.4 |
| hash-checker | 525 | 170 | 22 | 22 | 54.9 | **56.3** | 57.0 |
| LibreTorrent | 4,728 | 3,168 | 438 | 454 | 30.0 | **39.2** | 39.8 |
| Markor | 2,855 | 1,760 | 0 | 0 | **34.2** | 2.6 | 2.6 |
| MMEX | 4,269 | 3,142 | 68 | 68 | **22.6** | 4.4 | 4.4 |
| Omni-Notes | 1,426 | 991 | 294 | 329 | 27.1 | **44.5** | 47.1 |
| openlauncher | 901 | 550 | 230 | 234 | 35.3 | **59.0** | 59.7 |
| Vanilla-Music | 1,468 | 566 | 182 | 204 | 54.3 | **61.9** | 63.9 |
| **All 13** | 28,913 | 19,448 | 3,128 | 3,625 | 33.8 | 35.0 | 37.4 |

Bold marks the higher line coverage at 1 hour. In the last row, line coverage is the average over the 13 apps.

## Why Monkey won on 4 apps

- **Aegis and MMEX:** Monkey's runs started after a setup script, which gave Aegis a vault with two entries and MMEX a database. ADGE started from a fresh install and spent most of its time on those setup screens: Aegis's first-run wizard, and MMEX's create-database and password screens.
- **Markor:** ADGE never got past the first-run tutorial.
- **Diary:** ADGE's run broke after 3 minutes. From then on, the app didn't start properly when ADGE restarted it, so ADGE explored for only 3 of its 120 minutes. Diary needs a rerun.

## How it was measured

- **Runs:** ADGE ran on GitHub Actions: [adge-test run 40800801720](https://github.com/bugfixops/adge-test/actions/runs/40800801720) for 12 apps and [test-13 run 40894072689](https://github.com/bugfixops/test-13/actions/runs/40894072689) for MMEX. Monkey ran for 1 hour per app with `monkey -p <package> -s 20261016 --throttle 500 --ignore-crashes --ignore-timeouts --ignore-security-exceptions`. Monkey's raw data isn't in this repository.
- **Aiming:** ADGE's targets are the methods Monkey missed (`monkey_targets/` in the repository root), and ADGE prefers widgets whose handlers can reach a target. Aiming made little difference overall: an earlier, unaimed 1-hour ADGE run ([commit e90fcb0](https://github.com/bugfixops/adge-test/tree/e90fcb0a4f9a7558a3d308873c82ae2b18786b82/results)) reached 3,147 of the methods Monkey missed, about the same as this run's 3,128.
- **Coverage:** JaCoCo, with the same class files for both tools. A method counts as reached if any of its code ran. The snapshot taken before a tool starts is left out for both. ADGE's 1-hour numbers use the snapshots taken in its first 60 minutes, plus the next 30 seconds.

## Files

- `comparison/summary.csv`: every per-app number, including branch and class coverage.
- `comparison/methods_beyond_monkey.csv`: the 3,625 methods Monkey missed that ADGE reached, and when ADGE first reached each one.
- `comparison/coverage_over_time.csv`: coverage at 10, 30, 60, 90 and 120 minutes.
- `<App>/`: each app's raw ADGE run.
