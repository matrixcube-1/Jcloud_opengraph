# Calibration — campaigns, findings, current status

## Method

- Optuna-mirror search space (6 params): `fresh_ms` log[2000,30000],
  `spread_cap` [1,3], `comm_scale` [1,1.3], `ddd_min` [0,1],
  `tau_slip_bp` [1,4], `q_min` [0.3,0.9]
- Constraints: trades ≥ 10, abort_rate ≤ 30 %
- Split: in-sample days + single-evaluation holdout (median frozen at
  `med_until` boundary — no lookahead)
- Objective: realized exec PnL (IOC fills at tick ≤ t_fill, partial fills,
  unwind, residual liquidation) — not detection PnL

## Results on consolidated corpus (Dukascopy tick feed)

| Campaign | Trials | Feasible | Best realized |
|---|---|---|---|
| 500-trial random (4 days) | 500/500 | **0** | max net observed −1.79 $ |
| Grid sweeps | — | 0 | same floor |

The corpus feed is **arbitrage-clean**: the best available path at any
instant is a 1-leg USD round trip worth exactly the cost floor
(~1.79 $ of spread + commission at 10K notional). No threshold setting can
manufacture what doesn't exist — this is the correct model answer, not a
calibration failure (verified: forcing `ddd_min = −30` produces 11 330
losing trades, −25 580 $).

## Live venue journal (different feed, real quotes)

| Journal | Ticks | Detections ≥ 0.27 $ | Episodes |
|---|---|---|---|
| 11-03 | 1.11 M | **362** | 24 |
| 11-04 | 1.31 M | **77** | 8 |

At 2.5 ms/leg simulated latency: realized mean ≈ −1 $/cycle; ~30 % of
episodes cluster +4–5 $. Detection is real; survival is latency-bound.

## Latency convention (post-audit)

`ReplayDay.LAT_MS` default = **5.0 ms/leg** (JCloud colocation, comme
`optuna_duka`). The shipped replay honors `Params.latMs` — set it via
`--lat_ms 2.5` etc. and report the value in every results table (audit
F11). The 2.5 ms/leg figure quoted for live-journal replays below came
from the external Python replay tool, not this CLI.

Commission `4.00 $/side/100K` was calibrated on **IC Markets cTrader**
demo fills (n=37) — the deployment target is Dukascopy JCloud, whose
commission structure differs: recalibrate on Dukascopy demo fills during
the demo run (audit F9).

## Correction history (why earlier numbers were inflated)

1. Mid-point residual marking → executable bid/ask (Convention A):
   +955 $ → +61.58 $ on the same holdout
2. Incomplete basket → full entry/exit positions with chained quantities:
   episodes collapse to the true cost floor
3. Commission unit bug (×10) found and fixed in replay tooling
4. `med_until` freeze: holdout no longer leaks into the spread gate

## Remaining measurement

Replay live journals at **1.0 ms/leg** (JCloud colocated) with the §10
persistence filter (Hawkes `S(h)`, Cox survival) — decides whether the
~30 % surviving episodes constitute executable alpha.
