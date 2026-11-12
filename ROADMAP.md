# Roadmap — done / remaining

## Done (verified)

- [x] Full audit S1–S7: counts, mappings, formulas, parity, perf, bottlenecks
- [x] `verify_all.sh` — single-command chained verification (134 tests + MC)
- [x] Zero-alloc hot loop: buffers, primitive median, primitive cursors,
      prefix cache, O(1) routing — golden bit-for-bit preserved
- [x] `med_until` port — holdout lookahead closed in replay
- [x] Instrument names verified vs real JForex API (16 static + 3 dynamic
      + 7 metal crosses flagged non-tradable)
- [x] Binary links against platform API 4.9.8 (Java 25 runtime)
- [x] OgStrategy docMode pipeline wired (Pipeline §19 + DynamicsProbe)

## Next

- [ ] **Replay live journals at lat=1.0 ms + persistence filter** — the
      decisive alpha measurement at JCloud latency
- [ ] **Demo run**: deploy to JForex4 `Strategies/` on a DEMO account —
      first real execution of OgStrategy; confirm dynamic instruments
      (USSC2000, XPT, XPD) resolve online
- [ ] Tick-by-tick Java↔Rust parity replay of `execSeq`/`settle` (last
      cross-language reserve — covered by E1–E9 tests + golden fixture,
      not by full cross-replay)
- [ ] JCloud upload via Dukascopy client area
- [ ] Bout execution path (currently evaluated + SKIP — Solution A is FX-only)

## Hard rules (project canon)

- Open paths only — never reintroduce closed-cycle logic
- Convention A — residuals at executable prices, never midpoint
- `allowLive=false` until recalibration on clean holdout + venue-verified
- FX-only production first (bouts dominated under Convention A)
