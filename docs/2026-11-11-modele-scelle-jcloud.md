# Bot JCloud complet — modèle du Document scellé + Constitution

## Intention

Implémenter en Java/JForex le modèle **original complet** des deux textes
scellés (`MC/document_scelle.txt` post-purge + `MC/constitution_unifiee.txt`),
y compris la couche §10 jamais écrite (Hawkes λ, Cox S(h), Hurst Ĥ,
p_persist, sizing LP) — jusqu'au bot déployable, avec tests Monte-Carlo
et corpus `C:\arb_duka`.

Canon : `MC/document_scelle.txt` (post-purge R5). Déviation assumée
documentée : marquage résiduel **exécutable** (Convention A stricte), plus
strict que la lettre « mid de marquage » du texte — justifiée par l'audit
externe (artefact comptable prouvé).

## Ce qui existe déjà (branche feature/java-port)

- `core` : 28 FX, 2 072 chemins DFS, eval Convention A, exec_seq, settle,
  scan, DataLoader CSV — 35 tests + parité Rust 1e-9.
- `regime` : tension/dominance/gate (indicateurs *d'implémentation* — le
  document ne les connaît pas ; conservés comme télémétrie, pas spec).
- `replay` : ReplayDay + CalibCli (campagne 500 en cours).
- `strategy` : OgStrategy squelette IStrategy.
- Corpus : 28 FX + **26 bouts opérationnels** (13 indices + 11 métaux +
  2 énergie, J.3 moins 6 softs) — même format CSV, 3 jours (22/23/26/10).

## À construire — le modèle complet

### 1. Univers bouts (`core`)
- 26 instruments terminaux : table classe (indice/métal/énergie), devise
  cotée, tier (S/L/X par coût de spread), granularité lot, taux marge
  (3,33/5/10 %), commission (formule par classe, XAU 3,50/côté/lot →
  +0,875 $/100k ; indices cash 0 ; énergie 0).
- CHEIDXCHF : coté CHF → conversion USDCHF à l'ingestion (J.3, A10).
- DFS étendue : jambes FX ≤3 sans revisite + **arête terminale bout**
  (puits, pas d'arête sortante). Chemins : 2 072 + 260×26 = **8 832**
  (forme close Annexe H, test obligatoire).
- Eval : P_arrivée = liquidation exécutable du bout en USD via sa devise
  cotée (Convention A) + coûts bout + provision portage si h>session.

### 2. Dynamique §10 (`dynamics` — première implémentation réelle)
- **Chocs** : variation mid > 2σ / 30 s (rolling σ par instrument).
- **HawkesMLE** : μ, α, β sur inter-chocs — L-BFGS-B interne (~150 lignes,
  gradient analytique) ou EM ; contrainte α/β < 1 (stationnarité).
- **Cox** : S(h) = exp(−∫λ) — survie du déséquilibre à horizon h ;
  `h = délai exec moyen + 50 %`.
- **Hurst** : R/S multi-N sur log-returns du mid de l'arrivée, OLS → Ĥ ;
  qualité si > 0,55.
- **BIR** : (V_bid−V_ask)/(V_bid+V_ask) ajusté exp(−γ·spread/mid), γ=10 ;
  IC sans profondeur fiable → facteur omis (BIR:=0 → p_persist sans lui).
- **p_persist** = S(h)×1[Ĥ>0.55](×BIR_adj) — filtre avant sizing, seuil
  τ_persist calibrable (pas de défaut documentaire → Optuna).
- **EpisodeTracker** : épisodes d'opportunité → entrée du fit Hawkes.
- **Admission E2** [choix] : `S(T_seq) > 0,30` avec
  `T_seq = Σ latences_est × m_seq` — la lecture opérationnelle de
  « S(h) doit couvrir T_seq » (le seuil §11 est 0,30).
- **Admission §8.5** : spread ≤ 1,7×normal par jambe ; fenêtre rollover
  23:55→00:10 serveur ; séance bout ouverte ; marge post ≥ ρ_min=300 % ;
  mode intra-venue.

### 3. Sizing LP (`sizing` — remplacement interne de HiGHS)
- Simplex à bornes (~250 lignes, déterministe) :
  `max Σ_j d_j·S(h)_j·x_j` s.t.
  - `0 ≤ x_j ≤ x_max,j` (Kelly fractionné κ=0,25 : x_max = κ·W₀·N_ref·μ/σ²)
  - marges par classe, Niveau = Équité/ΣMarge ≥ ρ_min
  - ES_RU (α=0,95) via auxiliaires [L−z]⁺
  - Herfindahl Σw² ≤ seuil (anti-concentration)
- Arrondi granularité vers le bas + **re-check règle d'envoi** (règle 21).
- Impact itératif : Q figé → LP → re-vérif (1-2 itérations, F09).

### 4. Exécution E1–E9 (`exec`/`strategy`)
- E3 pré-fill : âge quote ≤ τ_fresh, spread ≤ 1,7×, **DDD_eff résiduel
  recalculé** aux marks courants (manquant dans la version actuelle).
- E4 continuation : continuer ssi E[net_résiduel] ≥ −(C_unwind + m_abort).
- E5 partiel : fill ≥ q_min → re-prix aval à q_fill (rounding_residual).
- E6 unwind inverse au marché, retry ≤ N_u.
- E7 résiduels : prévu → résorption sur h ; imprévu → surveillé, sorti au
  premier prix acceptable, hors stats.
- E8 journal par fill (JSONL — même format que l'existant).
- Fenêtres : rollover 23:55→00:10 ; news 10 s [choix : pas de calendrier
  news externe → fenêtre paramétrable, off par défaut sur corpus].
- Séances bouts : horaires par instrument (indices : session cash ;
  métaux/énergie : 23 h) — filtre horaire data-driven.

### 5. Validation (« test MC » = Monte-Carlo **et** corpus MC/)
- **MonteCarlo fill-par-fill** (E9) : chaque fill conditionné à la survie
  de la quote à son heure ; « un chemin non exécutable dans sa durée de
  vie prédite ne compte pas comme un gain » → métrique P&L réalisable.
- **ProofChecks** : 12/12 dénombrements Annexe H (forme close vs DFS),
  contrôles de coûts, contrôle dimensionnel.
- **QQ Cox-Snell** : résidus du fit Hawkes sur diagonale.
- Métriques §15.2 : ratio DDD réalisé/théorique, fréquence de résorption
  avant h, stabilité H, coût réalisé/ex ante.
- Split : calib 22+23 / test 26 (corpus = 3 jours, pas 4+2 mois —
  approximation documentée ; holdout 27 FX seul).

### 6. Intégration JForex (`strategy`)
- OgStrategy étendue : ingest 28 FX + 26 bouts → eval 8 832 chemins →
  filtres §8.5/E2/p_persist → sizing LP → séquence E1-E9 → journal.
- Params @Configurable : tableau §17 complet (κ, ρ_min, τ_persist,
  m_seq, m_abort, N_u, h×1,5, γ, seuil Ĥ, tiers).

## Non-objectifs

- Cross-venue (frontière documentaire — jamais envoyé).
- CME réel (pas de venue FIX/FCM — frais CME restent calibrables morts).
- Bouts feuilles/softs (exclus opérationnel).
- Optimal stopping §10.4 (point ouvert D-10, schématique — sortie = §15).
- Optuna : CalibCli grille/random suffit (pas de TPE).

## Tests (TDD, ~40 nouveaux)

Hawkes : fit sur processus synthétique récupère (μ,α,β) ±10 % ; α/β<1 ;
S(h) forme fermée ; QQ Cox-Snell diagonal. Hurst : brownien ≈0,5 ±0,05,
série tendue >0,55, mean-revert <0,5. BIR : formule + ajout spread.
p_persist : gating correct. Chemins : **8 832** DFS = forme close ;
bouts = puits (pas d'arête sortante) ; R6 : départ réel propagé.
Eval bout : panier USD→FX→XAUUSD→USD calculé main. Simplex : optimum
connu, contraintes marges/ES/Herfindahl, arrondi re-check. E4/E3/E5 :
bornes exactes. MC : fills non survivants exclus. Parité existante :
87 tests conservés verts.

## Étapes

1. `BoutUniverse` + catalogue 26 bouts + DFS étendue (8 832) + tests
   dénombrement.
2. Eval bout (Convention A, coûts classe, tier, portage) + tests.
3. `dynamics` : shocks, HawkesMLE, S(h), Hurst, BIR, p_persist,
   EpisodeTracker + tests.
4. `sizing` : simplex bornes + marges + Kelly + ES + Herfindahl + tests.
5. `exec` : E3/E4/E5/E6/E7 + fenêtres/séances + tests.
6. `validation` : MonteCarlo fill-par-fill + ProofChecks + métriques + tests.
7. `strategy` : intégration complète OgStrategy + @Configurable.
8. Campagne CalibCli sur corpus (params E9 : τ_fresh, τ_slip, m_seq,
   q_min, m_abort, N_u + τ_persist) → verdict.
9. README + journal des déviations documentaires.

## Verdict attendu (honnêteté)

La couche §10 est un filtre d'**admission** — elle réduit les
déclenchements, n'en crée pas. Sur le corpus actuel où net<0 partout
(0/126 essais), la statistique ne changera pas ; sa valeur est sur un
régime où des edges existent (moins d'aborts sur écarts mourants). La
campagne finale quantifiera : trades, aborts, pnl, S(h) moyen des
candidats, taux de survie des fills.
