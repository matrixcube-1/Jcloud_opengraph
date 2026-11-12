# Jcloud_opengraph — open-path FX arbitrage engine for JForex/JCloud

Production-grade Java implementation of an **open-path FX arbitrage** engine,
targeting **Dukascopy JForex / JCloud** deployment. Semantic reference: a Rust
engine (`opengraph` private repo) — this Java port is verified bit-for-bit on
the evaluation hot path.

- **8 currencies** → **28 spot FX pairs** (all C(8,2))
- **26 terminal instruments** ("bouts": 13 indices, 11 metals, 2 energy)
- **2,072 open FX paths** (1–3 legs, no repeated currency, **no closed cycles**)
  + **6,734 bout-extended paths** = **8,806 evaluated paths**
- Complete basket per path: `USD→start` + legs + `end→USD`, quantities chained
  from actual fills, residuals marked at **executable** bid/ask (Convention A)

## Why open paths only

Closed triangles are saturated (every LP/HFT sees them). Open multi-leg paths
across mixed instruments are where residual desync survives — if anywhere.

## Build & verify (one command)

```bash
export JF_API=/path/to/jforex-api.jar          # Dukascopy public repo, scope provided
export JUNIT_JAR=/path/to/junit-platform-console-standalone.jar
# JDK / JAVA_HOME optional — defaults to javac/java on PATH
bash verify_all.sh [corpus_root]               # compile → 134 JUnit tests → VerifyAll chain
```

Without `corpus_root` the chain runs fully on the synthetic book and
SKIPs the corpus replay step explicitly.

`VerifyAll` runs the full audit end-to-end, fail-fast:

```
S1 counts + ProofChecks (2072/259/8806/puits, 0 cycles)
S2 mapping by injection (coherent book → cost floor; EURJPY perturbation →
   correct winner, correct side)
S3/S4 formulas (engine == hand computation to the cent)
S5/S6 performance + zero-alloc hot loop
MC  Monte-Carlo E9 (survival-conditioned fill-by-fill execution)
```

## Performance (measured, real corpus day)

| Stage | Median | JCloud budget |
|---|---|---|
| `evalAt` (2,072 paths) | **65–69 µs** | <1 ms p99 |
| `evalBoutAt` (6,734 paths) | **147–165 µs** | <1 ms p99 |
| `ingest` per tick | ~1 µs, **0 alloc** | ≥10 k ticks/s (912 k benched) |
| GC pressure hot loop | **32 B / eval** | — |

Optimizations: zero-allocation eval loop (reused buffers), per-call FX prefix
cache in `evalBoutAt`, precomputed spread costs, primitive-array median heaps,
primitive position cursors, O(1) instrument routing in the strategy.

## Architecture

```
engine/src/    Rust semantic reference (authoritative spec — engine.rs,
               eval.rs, exec.rs, paths.rs, fees.rs, pybind.rs)

src/main/java/opengraph/
├─ core/       Universe, Paths (open DFS), PathTables, ExtPathTables,
│              Engine (book/eval/exec/settle), SymbolSeries, SpreadMedian,
│              DataLoader, Fees, Bout/BoutUniverse (26 terminals)
├─ dynamics/   Hawkes MLE, Cox survival S(h), Hurst R/S, BIR, EpisodeTracker
├─ pipeline/   Pipeline §19: eval → E2 persistence → sizing → FIRE
├─ regime/     RegimeEngine (SVD gramian, tension, smoothing gate)
├─ sizing/     internal simplex LP, Kelly, margin, concentration caps
├─ replay/     ReplayDay, CalibCli (Optuna-mirror random/grid search),
│              VerifyAll (chained audit)
├─ strategy/   OgStrategy — IStrategy: ingest → eval → IOC basket exec,
│              unwind, NETTED settle, breaker, allowLive=false by default
└─ validation/ MonteCarlo (E9), ProofChecks (closed-form counts)
```

Artifacts: `artifacts/opengraph-jforex-1.0.0.jar` (85 entries) —
SHA-256 `adfcbfb7562d5683e232…` (rebuild: `javac --release 11` then
`jar cf` over `target/classes`).

## Execution semantics (the parts that kill naive arb bots)

- Sell = bid, buy = ask — **never** midpoint marking (Convention A)
- Full basket entry/exit costs: every position pays spread + commission
- Commission: measured live rate `4.00 $/side/100K` → `0.40 $` at 10K notional
- IOC per leg with τ_slip guard, partial-fill abort → unwind all created
  positions, NETTED settlement aggregated by PositionId
- `med_until` median freeze: no holdout leakage into the spread gate
- `docMode`: optional §19 pipeline — Hawkes survival `S(h)` gate, LP sizing

## Calibration status (honest)

- 500-trial Optuna-mirror campaign on consolidated Dukascopy corpus:
  **0 feasible** — the market feed is arbitrage-clean at tick granularity
  (best net observed = −1.79 $, the pure cost floor of a round trip).
- On **real venue tick journals** (live feed, different venue): 362 + 77
  detections ≥0.27 $ under the corrected model; at 2.5 ms/leg latency the
  realized PnL decays (~−1 $/cycle mean, ~30 % of episodes reach +4–5 $).
- Conclusion: alpha is a property of **venue + latency + persistence
  selection**, not of thresholds. See [docs/CALIBRATION.md](docs/CALIBRATION.md).

## Deploy

1. Build: `javac --release 11` or reuse `artifacts/opengraph-jforex-1.0.0.jar`
2. Local JForex: drop the `.jfx`/jar into `Strategies/`, run on DEMO
3. JCloud: upload via the Dukascopy client area (strategy runs colocated,
   ~1–5 ms wire latency)
4. `allowLive=false` by default — live trading requires an explicit flag and
   a NETTED account; see [ROADMAP.md](ROADMAP.md)

## Docs

- [docs/AUDIT_RESPONSE.md](docs/AUDIT_RESPONSE.md) — resolution of the
  external audit's 11 findings (F1–F11)
- [AUDIT_MAP.md](AUDIT_MAP.md) — complete audit cartography with proofs
- [DEVIATIONS.md](DEVIATIONS.md) — spec→code deviations + hot-path map
- [docs/CALIBRATION.md](docs/CALIBRATION.md) — calibration campaign results
- [ROADMAP.md](ROADMAP.md) — done / remaining tasks

## License

MIT — see [LICENSE](LICENSE).
