# Port Java/JForex — engine + regime + replay (spec)

> Execution: native, this session. Source = `main` (`220945c`) — **sans** les
> commits d'audit du worktree `fix/audit-2026-11-11`. Zéro Python, zéro Rust
> dans le livrable : tout est Java.

**Goal** : porter fidèlement la sémantique du moteur Rust (`engine/` +
`regime_indicators/` + équivalent du replay POC) en Java 11, packagé comme
stratégie JForex (JCloud-ready), avec les mêmes tests que la référence.

**Architecture** : module Maven unique `jforex/` avec 3 packages —
`opengraph.core` (moteur pur, aucune dépendance), `opengraph.regime`
(indicateurs, solver interne 8×8 remplaçant nalgebra), `opengraph.replay`
(chargeur corpus `C:\arb_duka` + replay paramétré = calibrage), plus
`opengraph.jforex` (adaptateur IStrategy, API JForex en `provided`).

**Tech stack** : Java 11, Maven (fallback : `javac` + junit-platform-console),
JUnit 5, JForex API (`com.dukascopy.api`, scope provided ou stubs locaux
générés pour compiler hors-ligne).

**Spec** : `docs/08_FORMULE_PANIER.md` (worktree corrections) — sémantique
reprise du code Rust de `main`, qui l'implémente déjà.

## Global constraints

- `NOTIONAL_USD = 10_000.0`, `COMM_PER_100K_USD = 4.00`, `comm_usd() = 0.40`,
  `ABORT_COST_PER_LEG_USD = 12.0` — valeurs exactes de `fees.rs`.
- Ordres de table figés : `CURR` (8 devises, ordre POC non alphabétique),
  `FX` (28 paires, ordre Annexe J) — aucun réordonnancement.
- Exactement **2 072 chemins** ; `enum` + `build_tables` identiques
  (DFS, arêtes bidirectionnelles, `d = +1` si node == base).
- Convention A : entrée USD→s0 et sortie end→USD sont des positions réelles
  pricées au bid/ask exécutable — jamais de mid.
- `net = usd_out − NOTIONAL − 2·comm·nb_pos·comm_scale − unwind` où
  `unwind = Σ (ask−bid)/mid × NOTIONAL` par position.
- Tri stable partout (ordre de lecture conservé à ts égal = `np.argsort`
  stable) ; argmax = premier maximum.
- Jamais de quote future : `ts[j] <= t` ; fraîcheur bilatérale
  `last_t ≤ t ≤ last_t + fresh_ms`.
- `NO_TICK = Long.MIN_VALUE` (miroir de `i64::MIN` = `-inf`).
- Quotes rejetées à l'ingestion : non finies ou croisées (`ask < bid`).
- La médiane de spread est incrémentale (deux tas = `PriorityQueue`) avec
  filtre non-fini — mêmes valeurs que la médiane par tri.
- `exec_seq` : E1-E9 du header `exec.rs` (t_fill = t_prev + lat, q_send =
  quote à t_prev, mv > 3τ → KillIoc, τ < mv ≤ 3τ → frac 0.6, garde volume
  sur le côté touché, frac < q_min → abort, partiel → cash à liquidation
  exécutable, qty chaînée, comm flat/position, unwind positions remplies).
- `exec_settle` : même arithmétique sur fills externes `(frac, px_node)` ;
  `fills > orders → null`.
- Aucune dépendance externe dans `core`/`regime`/`replay` (hors JDK).
- Solver linéaire interne : Cholesky 8×8 + Jacobi symétrique + SVD via
  gramien centré — déterministe (tri décroissant + tie-break par indice).

## Review focus

1. **Volatile reference vs arc_swap** : `RegimePublisher` Rust utilise
   `ArcSwap` — en Java, `AtomicReference<RegimeSnapshot>` suffit
   (single-writer atomic swap, lock-free read). Test : publication
   strictement croissante du seq.
2. **`partial_cmp` vs `Double.compare`** : Java `Double.compare` trie NaN
   différemment — les tests filtrent non-fini en amont ; documenter que les
   tris de valeurs supposent fini (comme Rust `unwrap_or(Equal)`).
3. **`u64` timestamps régime** : Rust utilise `u64` ns ; Java `long` signé
   suffit (valeurs positives, `saturating_sub` → `Math.max(0, t-last)`).
4. **`partition_point`** = `Arrays.binarySearch` custom ou boucle —
   implémenter explicitement (dernier index ≤ t) pour garantir la parité.
5. **JForex API hors-ligne** : si l'artefact n'est pas récupérable, fournir
   des stubs `com.dukascopy.api.*` minimaux marqués « compile-only, remplacés
   par le vrai jar dans JCloud » — le strategy adapter reste compilable.

---

### Task 1 : `opengraph.core` — univers, quotes, séries, ingestion

**Files:**
- Create: `jforex/src/main/java/opengraph/core/Universe.java`
- Create: `jforex/src/main/java/opengraph/core/Quote.java`
- Create: `jforex/src/main/java/opengraph/core/Fees.java`
- Create: `jforex/src/main/java/opengraph/core/SymbolSeries.java`
- Create: `jforex/src/main/java/opengraph/core/SpreadMedian.java`
- Create: `jforex/src/main/java/opengraph/core/Engine.java` (partie 1 :
  build, advance, tick/sync/ingest/publish, ok, quote_at(_f), usd_price,
  usd_liq_price, usd_liq_comm, usd_pair, edge_nodes/edge_other)
- Test: `jforex/src/test/java/opengraph/core/EngineStateTest.java`

**Interfaces (produites)** :
- `Universe.CURR: String[8]`, `Universe.FX: String[28]`,
  `int ci(String)`, `(base,quote)` via `pairNodes(int edge) → int[2]`,
  `Integer usdEdge(int c)` (null = absent).
- `Fees.NOTIONAL_USD`, `COMM_PER_100K_USD`, `commUsd()`,
  `ABORT_COST_PER_LEG_USD`.
- `Quote(long tsMs, double bid, double ask, double vol)`, `mid()`.
- `SymbolSeries(String sym, long[] ts, double[] bid, double[] ask,
  double[] avol, double[] bvol)` — mutable pour ingest.
- `SpreadMedian.push(double)`, `median()` (NaN si vide, ignore non-fini).
- `Engine`: `Engine(List<SymbolSeries>)` (drop-empty) / `Engine.live()`,
  `void tick(int si,long t)`, `void ingest(int si,Quote)`,
  `void sync(long t)`, `boolean ok(int si,long t,double freshMs)`,
  `double[] quoteAt(int si,long t)` (ts,bid,ask,vol=max) ou null,
  `double[] quoteAtF(int si,double t)` (ts,bid,ask,avol,bvol) ou null,
  `double[] usdPrice(boolean[] ok)`, `double[] usdLiqPrice(boolean[] ok)`,
  `double[] usdLiqComm(boolean[] ok)`, `int[] usdPair(int c)` ({si,direct}
  ou null), `static int[] edgeNodes(int e)`, `static int edgeOther(int e,
  int node)`. Champs publics : `syms`, `sidx Map`, `data`, `pos`, `bid`,
  `ask`, `lastT`, `med`, `isFx`, `fxSi Integer[28]`, `ev List<long[]>`,
  `commPerSide`.

- [ ] Écrire les tests JUnit miroirs de `engine.rs::tests` :
  `t1_no_future_quote_on_early_sync`, `t4_rewind_reexposes_past`,
  `t3_bilateral_freshness`, `quote_at_boundaries`,
  `ev_order_at_equal_timestamps_follows_fx_table`,
  `ingest_rejects_crossed_or_nonfinite_quotes`,
  `ingest_live_updates_state_and_median`,
  `spread_median_ignores_non_finite`, `usd_price_direct_inverse`
  (9 tests — mêmes valeurs d'assert).
- [ ] `mvn test -Dtest=EngineStateTest` (ou junit-console) → FAIL (classes
  absentes), puis implémenter → PASS.

### Task 2 : `opengraph.core` — paths + eval

**Files:**
- Create: `jforex/src/main/java/opengraph/core/Paths.java`
  (`enumPaths() → Paths.Result {List<int[]> paths; int[] starts}`,
  `PathTables` classe avec `pS,pEnd,pL` int[] + `pE,pDir,pBase` int[3][])
- Create: `jforex/src/main/java/opengraph/core/EvalResult.java`
  (netUsd, pathIdx, dddUsd)
- Modify: `Engine.java` → `evalAt(long t,double freshMs,double spreadCap,
  double commScale,PathTables pt) → EvalResult|null`
- Test: `jforex/src/test/java/opengraph/core/EvalTest.java`

- [ ] Tests miroirs `paths.rs` (fx_path_count_is_2072,
  tables_node_propagation, one_leg_paths_both_sides) + `eval.rs`
  (4 tests livre synthétique — helper `book(ups,half,distort)` porté à
  l'identique, mêmes valeurs attendues dont `assert abs(net−expected)<1e-6`).
- [ ] Implémenter → PASS. Assert `paths.size()==2072`.

### Task 3 : `opengraph.core` — exec (panier complet)

**Files:**
- Create: `jforex/src/main/java/opengraph/core/ExecParams.java`
  (latMs, tauSlipBp, qMin, volGuard)
- Create: `jforex/src/main/java/opengraph/core/ExecOutcome.java`
  (sealed-family : `Done{pnlUsd,filledLegs,partialLegs,durMs}`,
  `Abort{reason NoQuote|KillIoc|BelowQMin, ...}`)
- Modify: `Engine.java` → `execSeq`, `ordersOf`, `startQty`, `abortCost`,
  `execSettle` (même ordre d'opérations f64 que Rust pour la parité 1e-12).
- Test: `jforex/src/test/java/opengraph/core/ExecTest.java`

- [ ] Tests miroirs `exec.rs` (8 tests : full fill vs calcul main, kill IOC,
  no-quote, garde volume ×2, partiel, settle=exec à 1e-12, settle abort
  calcul main, settle partiel = exec partiel, start_qty, fills>legs→null).
- [ ] Implémenter → PASS.

### Task 4 : `opengraph.core` — scan + replay + data loader

**Files:**
- Create: `jforex/src/main/java/opengraph/core/Params.java` (9 champs,
  `DOC_DEFAULTS`)
- Create: `jforex/src/main/java/opengraph/core/Scan.java` (Cand, Episode,
  ScanStats, `scan(Engine,PathTables,...)`, `replay(Engine,Cand[],Params,
  PathTables) → ReplayResult`)
- Create: `jforex/src/main/java/opengraph/core/DataLoader.java`
  (`epochMs(String day)` Howard Hinnant porté, `loadSymbol(Path,String,
  String[])`, `loadFxEngine(Path,String[])`, rejet >5% → exception).
- Test: `ScanTest.java` (3 tests), `DataLoaderTest.java` (4 tests dont
  rejet NaN/croisée et fail-fast 5 %).

- [ ] Tests miroirs `scan.rs` + `data.rs` → PASS.

### Task 5 : `opengraph.regime` — solver interne + indicateurs

**Files:**
- Create: `jforex/src/main/java/opengraph/regime/Mat8.java` (double[8][8],
  ops minimales) ou méthodes statiques `LinAlg` — **décision** : classe
  `LinAlg` statique {`choleskySolve`, `jacobiEigen` (sym 8×8, pivot max,
  100 sweeps max, tol 1e-14), helpers matmul}.
- Create: `Tension.java`, `Smoothing.java` (Ewma, RobustZ, median),
  `Gate.java` (+ModParams/modulation), `SvdRegime.java`
  (dominanceFromReturns via eigen de YYᵀ centré + RollingGram),
  `RegimeSnapshot.java` + `RegimePublisher` (AtomicReference),
  `RegimeEngine.java`.
- Test: `TensionTest`, `SmoothingTest`, `GateTest`, `SvdRegimeTest`,
  `RegimeEngineTest` — miroirs 1:1 des ~20 tests Rust.

- [ ] Tests miroirs (mêmes tolérances 1e-9/1e-12) → PASS. Le solver Jacobi
  est validé par les tests spectraux existants (spectrum ⊆ [−1,1],
  invariance d'échelle, gram = SVD directe).

### Task 6 : `opengraph.replay` — corpus + replay_day

**Files:**
- Create: `jforex/src/main/java/opengraph/replay/ReplayDay.java`
  — port exact de `optuna_duka.py::replay_day` : `WARMUP=100`,
  `EVAL_EVERY_MS=500`, `MIN_SEQ_GAP_MS=2000`, `MAX_HOLD_MS=5000`,
  `LAT_MS=5.0`, locks par arête panier (`basketEdges`),
  `dayData` (événements uniques + ts par symbole + seen_min via
  searchsorted). Retour `{pnl, trades, aborts}`.
- Create: `jforex/src/main/java/opengraph/replay/CalibCli.java` — driver
  `java opengraph.replay.CalibCli <root> <day[,day...]> <params.json|args>`
  imprimant pnl/trades/aborts (point d'entrée de calibrage sans Optuna).
- Test: `ReplayDayTest.java` — sur corpus synthétique généré en test
  (livre distordu écrit dans tmp), assert trades ≥1 + pnl déterministe.

### Task 7 : `opengraph.jforex` — stratégie IStrategy

**Files:**
- Create: `jforex/src/main/java/opengraph/jforex/OgStrategy.java`
  implémentant `com.dukascopy.api.IStrategy` :
  `onStart` (Engine.live(), PathTables, params), `onTick(Instrument,ITick)`
  → `ingest(si, Quote)` puis `evalAt(tick.getTime())` → si net≥ddd_min et
  pas de séquence active → ordres `IEngine.submitOrder` IOC par position
  du panier (labels chaînés, tailles chaînées à la quantité résiduelle),
  garde `MinSeqGapMs=2000`, `MaxHoldMs=5000`, breaker 5 échecs,
  `onStop` flatten/log. Port comportemental de la sémantique exec —
  le sizing/ordre mapping utilise `ordersOf` (arête + sens → Instrument,
  OrderCommand BUY/SELL, amount = qty de la devise entrante).
- Create: `jforex/README.md` (build, drop-in JForex IDE, params).
- Si jar API inaccessible : stubs `com.dukascopy.api` minimal
  (IStrategy, IContext, IEngine, IOrder, Instrument, ITick, OfferSide,
  OrderCommand, JFException) sous `src/main/java-stubs` profil séparé.
- Test: `OgStrategyStateTest` — machine à états de séquence sur mocks.

### Task 8 : parité croisée Rust↔Java (verrou de fidélité)

- [ ] `cargo test` côté Rust émet une fixture dorée (ajout d'un petit test
  `parity_golden` qui imprime eval/exec sur `book_distorted()` + fixture
  csv existante dans `MC/` — JSON ou lignes `t,pi,net` ; **ajout au Rust
  autorisé** car c'est un test, pas un changement sémantique — à valider).
- [ ] `GoldenParityTest.java` rejoue les mêmes entrées et compare à 1e-9.

### Task 9 : docs + build final

- `jforex/pom.xml` (Java 11, JUnit 5, jforex-api provided), `README` racine
  complété (section JCloud), `mvn test` complet vert, résumé des écarts
  connus (solver Jacobi vs nalgebra, EVAL_EVERY_MS, latence strategy).

**Écarts assumés à documenter** : timestamps JForex = ms (u64→long),
`Long.MIN` = NO_TICK, tri stable `Collections.sort`/`Arrays.sort` (tim sort
= stable ✓), `Double.compare` sur valeurs finies.
