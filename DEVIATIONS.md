# Journal des déviations — modèle scellé → implémentation Java

Référence : `MC/document_scelle.txt` (post-purge R5) + `MC/constitution_unifiee.txt` (v2.2).
Moteur de référence : Rust `engine/` (parité verrouillée 1e-9, fixture dorée).

| # | Spec | Implémentation | Statut |
|---|---|---|---|
| D1 | Marquage résiduel « mid » (lettre du texte) | **Convention A stricte** — bid/ask exécutables partout | Déviation assumée : l'audit externe a prouvé que le mid est un artefact comptable ; plus strict que la spec |
| D2 | « 260·C » chemins bouts (forme close doc) | **259·C = 6 734** (+2 072 FX = 8 806) | L'énumération opérationnelle (POC/Rust) n'inclut pas le chemin dégénéré 0-jambe ; le doc compte 1 chemin vide/bout de plus. `ProofChecks` vérifie 259×8 nœuds |
| D3 | E2 « S(h) doit couvrir T_seq » (pas d'inégalité explicite) | `S(T_seq) > 0,30` (seuil §11) ; `T_seq = nOrd·latMs·mSeq` | Choix opérationnel documenté ; m_seq=1,5 calibrable |
| D4 | HiGHS LP/QP | `Simplex` interne (tableau primal, Bland) | JCloud ne peut embarquer de natif ; objective = Σd·S·x, contraintes marge/Kelly/concentration. Herfindahl remplacé par cap par nom (substitut linéaire) |
| D5 | ES_RU (α=0,95) auxiliaires LP | Non câblé | Exige une matrice de scénarios ; interface prévue dans `Sizing` |
| D6 | Fenêtre news 10 s | Paramétrable, OFF par défaut | Pas de calendrier externe dans le corpus |
| D7 | Séances bouts par instrument | Non implémenté — freshness gate couvre l'absence de quotes | Journal d'écart : les horaires cash des indices ne sont pas modélisés explicitement |
| D8 | Contraintes Optuna (trades≥10, abort≤30 %) | Reprise du POC, pas du document | Le document ne fixe pas de contraintes |
| D9 | BIR sur profondeur IC | Implémenté sur volumes Dukascopy ; NaN → facteur omis (=1) | Le doc prévoit l'omission sans profondeur fiable |
| D10 | Optimal stopping §10.4 | Non implémenté | Point ouvert D-10 du document lui-même ; sortie = règles §15 |
| D11 | Provision portage (funding bouts overnight) | Non implémentée | Nécessaire seulement si position bout détenue au-delà de la séance ; résiduels marqués exécutables en attendant |
| D12 | Hawkes MLE sur corpus réel | Estimateur implémenté + testé sur synthétique ; pas encore fitté sur épisodes corpus | `EpisodeTracker` produit les épisodes ; le fit en production attend des jours avec opportunités |

## Couverture implémentée

- **Univers** : 28 FX + 26 bouts J.3 opérationnels (6 softs exclus) — 8 806 chemins.
- **Eval** : panier complet Convention A, coûts par classe, tiers, commissions.
- **Dynamique §10** : Hawkes MLE, Cox S(h), Hurst R/S, BIR, p_persist, EpisodeTracker.
- **Sizing** : simplex bornes + marge ρ_min + Kelly + concentration + arrondi + re-check.
- **Exec** : E1–E9 (E3a/b, E4 résiduel, E5 partiels, E6 unwind réel N_u, E7 résidUsd) en `docMode` ; sémantique référence inchangée hors docMode.
- **Pipeline §19** : eval → E2 → p_persist → rollover → FIRE, partagé stratégie/replay/campagne.
- **Validation** : MonteCarlo fill-par-fill survivant-conditionné, ProofChecks Annexe H.
- **Tests** : 131 verts (`javac --release 11`).

## Cartographie du chemin chaud live (`onTick` → eval)

```
onTick(Instrument, ITick)
 ├─ instrMap.get()              O(1) ~20 ns     [avant : scan 28 arêtes ~0,5 µs]
 ├─ eng.ingest(si, Quote)       ~1 µs, 0 alloc/tick en régime
 │   ├─ SymbolSeries.push       SoA double[], croissance amortie
 │   ├─ medAcc.push             tas primitifs double[] (médiane exacte)
 │   └─ publish                 bid/ask/lastT — pas de quote future
 ├─ gates : inSeq / warmed / minSeqGap — ~ns
 └─ eval
     ├─ gate loop (54 instrs)   ok + fxOk + spcW (spreadCost 1×/série)
     ├─ evalAt    2 072 chemins ~65 µs médiane  (préfixe partagé, 0 alloc)
     └─ evalBoutAt 6 734 chemins ~150 µs médiane (cache préfixe par fxPi)
FIRE → runSequence → ordres IOC JForex (hors moteur)
```

Mesures A/B alternées (jour réel 22/10, médiane 15 blocs × 3 runs) :

| Étape | Avant | Après | Note |
|---|---|---|---|
| `evalAt` | 95–114 µs | **65–69 µs** | golden bit-à-bit identique |
| `evalBoutAt` | 340–414 µs | **147–165 µs** | — |
| `ingest` | 163 o/tick alloués | **0 o/tick** (régime) | tas boxed → double[], `pos` List<Long> → long[] |
| tick→eval complet | — | **~66 µs** | budget <1 ms p99, <5 ms end-to-end |

Résiduels connus : croissance amortie des séries (donnée réelle), `EvalResult`
alloué par appel (~32 o — retour public), heaps médiane non bornés = sémantique
Rust identique (~8 o/spread vs ~40 o boxed).

## Parité replay — cutoff médiane (`med_until`)

`Engine.medUntilMs` + `setMedUntil(ms)` : port de `engine.rs`/`MED_CUTOFF`.
`CalibCli` évalue le holdout sur un engine **combiné in-sample + holdout** avec
médiane figée à `epoch(holdout)` — l'ancien code chargeait un engine holdout
seul dont la médiane absorbait tout le jour évalué (lookahead dans le cap
`spread_cap × med`). Tests : `med_until_excludes_post_cutoff_ticks`,
`med_until_recomputes_over_loaded_series`.

## Noms JForex des bouts (vérifiés contre jforex-api 2.13.99 + gamme Dukascopy)

- **16/26** résolus hors ligne par `Instrument.fromString(jfx)` (13 indices,
  XAU/USD, XAG/USD, LIGHT/BRENT.CMD) — `JfxNamesTest`.
- **3/26** dynamiques (gamme officielle, résolus seulement une fois connecté) :
  `USSC2000.IDX/USD`, `XPT.CMD/USD`, `XPD.CMD/USD` — à confirmer en démo.
- **7/26 sans instrument négociable** : XAUEUR, XAGEUR, XAUGBP, XAUJPY, XAUCHF,
  XAUAUD, XAGAUD (absents de l'enum et de la gamme ; dukascopy-node : barres
  sans ticks → prix synthétiques). `BoutUniverse.jfxTradable(b)=false` : évalués
  pour la parité Rust, jamais souscrits ni exécutés. `OgStrategy` journalise au
  démarrage tout instrument non résolu.

## Budget perf JCloud (`PerfBudgetTest`, garde-fou permanent)

- Boucle d'éval sans allocation (≤ 64 o/appel = l'`EvalResult`), p99 < 1 ms,
  ingestion ≥ 10 k ticks/s. Le moteur d'origine (`057ca28`) échouait :
  83 Ko alloués par `evalAt`, `evalBoutAt` p99 = 1,28 ms.
- A/B alterné, jour réel 22/10, médiane de 15 blocs × 3 runs :
  `evalAt` 95–114 → **65–69 µs** ; `evalBoutAt` 340–414 → **147–165 µs**.
  Golden bit-à-bit 18 000 résultats (3 000 instants × 3 jeux de paramètres ×
  2 fonctions) identique avant/après.

## Verdict corpus (jour 22/10, univers 54)

8 034 évaluations : meilleur net FX **−1,79 $**, meilleur net bout **−2,32 $**,
0 déclenchement FIRE. Les bouts sont strictement dominés sous Convention A
(bid/ask + comm) — cohérent avec l'analyse cand.npz (0/1 029 811 argmax).
La couche §10 n'a rien à filtrer : pas d'edge à préserver.
