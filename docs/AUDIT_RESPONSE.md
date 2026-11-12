# Audit response — resolution of the 11 findings

External audit: `Audit_Jcloud_opengraph_15pages.pdf` (13/11/2026, commit
`a53e81c`). Each finding re-verified against the code, then fixed or
documented below. All fixes verified by `bash verify_all.sh`
(**134/134 tests**, chain green incl. real-corpus MC).

| ID | Grav. | Resolution | Status |
|---|---|---|---|
| F1 | P1 | `OgStrategy` fed ALL instrument mids into ONE `DynamicsProbe` — but the rolling σ *learned* the inter-instrument jumps (σ≈4.9 vs ~1e-5), **blinding** shock detection rather than spamming it. Fixed: one `DynamicsProbe` per series; `Pipeline.onBest` hook reads the probe of the winning path's entry instrument. Tests `probeMixedInstrumentsInflatesSigma`/`BlindsRealShocks`/`PerInstrumentDetectsRealShock` prove the mechanism. | ✅ FIXED |
| F2 | P2 | E1–E9 docMode layer exists only in the Java port; shipped Rust `exec.rs` has 3 abort reasons. Documented as an explicit traceability gap in DEVIATIONS.md + ROADMAP (Rust docMode port + golden fixtures = multi-day task). | 📌 DOCUMENTED + tracked |
| F3 | P2 | DEVIATIONS claimed `med_until` ported from `engine.rs`/`MED_CUTOFF` — actually from the Python POC. Corrected in DEVIATIONS + code comments. | ✅ FIXED |
| F4 | P2 | **False positive** — the reported `vec!id * 0.9999; 2]` corruption does not exist at `a53e81c` (the `[m` was lost in PDF text extraction). Verified: `cargo check --tests` + `cargo test` → **35/35 green** on the shipped tree. | ✅ VERIFIED N/A |
| F5 | P2 | `verify_all.sh` rewritten: no hardcoded Windows paths (`JDK`/`JAVA_HOME`/PATH, `JF_API`, `JUNIT_JAR`, `CORPUS_ROOT` env/args), `set -euo pipefail` (javac failure can't be masked), OS-aware classpath separator, local `target/` classpath only, auto-discovery in `tools/`/`lib/`/`deps/`/`../../tools/`. | ✅ FIXED |
| F6 | P2 | ~1 400 dead lines (`regime/`, `dynamics` BIR/Shocks/EpisodeTracker/HawkesMLE, `Engine.scan/replay`) — documented in DEVIATIONS "Code non câblé" section with the binary decision (wire to §19 vs sequester); tests retained per module. | 📌 DOCUMENTED |
| F7 | P3 | D6 news window (no code → marked not implemented), D9 BIR (class never called → `birAdj` NaN = permanently omitted), D12 Hawkes fit (tests only → E2 inert with μ=α=0) — all three entries corrected in DEVIATIONS. | ✅ FIXED |
| F8 | P3 | `unwind()` re-closed the already-closed partial-fill order → now skips orders in `IOrder.State.CLOSED`. | ✅ FIXED |
| F9 | P3 | `COMM_PER_100K_USD = 4.00` calibrated on IC Markets cTrader fills (n=37) — noted in `Fees.java` + `fees.rs` + CALIBRATION: recalibrate on Dukascopy demo fills during the planned demo run. | 📌 DOCUMENTED (needs demo) |
| F10 | P3 | Median double-push on same-ms re-emit → dedup (push only on new ts); `VerifyAll` corpus-missing → explicit `[SKIP]` instead of obscure crash; `Cargo.toml` license → MIT (was LicenseRef-Proprietary); committed jar kept intentionally (deliverable) + SHA-256 in README. | ✅ FIXED |
| F11 | P3 | `ReplayDay` used constant `LAT_MS` ignoring `Params.latMs` → now `p.latMs`; `--lat_ms` CLI flag works in grid + random search; latency printed in every result line (pnl/BEST/HOLDOUT/desc). | ✅ FIXED |

## Remaining (tracked in ROADMAP, not fixable in-repo)

- F2: Rust-side docMode port + E1–E9 golden fixtures.
- F6: wire-or-sequester decision for `regime/` when the regime layer lands.
- F9: commission recalibration requires the JForex demo run.
- Corpus/live journals are not shipped (size + license) — synthetic-book
  chain + MC is fully reproducible; corpus checks SKIP cleanly.
