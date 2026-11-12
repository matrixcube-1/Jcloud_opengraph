# Cartographie de l'audit complet — binaire JForex/JCloud

État : `feature/java-port`, 134/134 tests, jar `opengraph-jforex-1.0.0.jar`.
Vérification rejouable en une commande : `bash verify_all.sh`.

## 1. Arborescence (S1)

```
opengraph/
├─ core/       Universe(28 FX, 8 devises) · Paths(DFS 2072) · PathTables ·
│              ExtPathTables(6734) · Engine(evalAt/evalBoutAt/execSeq/settle)
│              DataLoader · Fees · SymbolSeries · SpreadMedian · Bout(×26)
├─ dynamics/   Hawkes MLE · Cox S(h) · Hurst R/S · BIR · EpisodeTracker
├─ pipeline/   Pipeline §19 : eval → E2 → p_persist → rollover → FIRE
├─ regime/     RegimeEngine
├─ sizing/     Simplex (LP interne) · Kelly · marge · concentration
├─ replay/     CalibCli · ReplayDay · VerifyAll (chaîne d'audit)
├─ strategy/   OgStrategy (docMode @Configurable, FX-only exec)
└─ validation/ MonteCarlo E9 · ProofChecks (comptes en forme close)
```

Comptes prouvés : 28 FX · 8 devises · **2 072 chemins FX** (56+336+1 680 jambes)
· **6 734 étendus** → 8 806 total · **0 cycle / 0 nœud répété** · `pEnd` cohérent.
Forme close : `ProofChecks` (2072, 259/fin, 8806, puits bout→devise cotée).

## 2. Mapping (S2) — preuve par injection

| Vérifié | Méthode | Résultat |
|---|---|---|
| `sidx` symbole→série (tri alpha) | injection par index série | livre cohérent → plancher −4,60 $ |
| `fxSi[e]` arête→série | 28 FX injectés par e | produits corrects |
| `upPair` USD direct/inverse | `usdPrice` test + main | EUR=×1.10, JPY=÷150 |
| Direction `pDir` | désynchro EURJPY +1 % | gagnant traverse EUR→JPY sens vente |
| `boutSi`/`jfx` | API réelle 4.9.8 | 16 statiques + 3 dynamiques + 7 croisés métal non négociables (`jfxTradable`) |

## 3. Fonctions (S3)

| Fonction | Preuve |
|---|---|
| `evalAt` | = POC Python au centime sur 4 jours corpus (best −1,790, même pi) |
| `evalBoutAt` | golden bit-à-bit 18 000 résultats (3 jeux de paramètres) |
| `execSeq` | E1–E9 testés (IOC/partiel/unwind/settle) + MC : pnl exec = net évalué |
| `settle` NETTED | hand-computation tests, résiduel Convention A |

## 4. Formules (S4)

`net = N·Π rates − N − 2·Σcomm·scale − ΣspreadCost` — **moteur = calcul main
à 0,01 près** (93,0851 = 93,0851). Sides exécutables (bid vente / ask achat),
panier complet entrée+sortie USD, DFS ouvert sans revisite.

## 5. Puissance (S5/S6) — A/B mesuré, jour réel 22/10

| Étape | Avant | Après | Budget |
|---|---|---|---|
| `evalAt` 2 072 | 95–114 µs | **65–69 µs** | <1 ms p99 |
| `evalBoutAt` 6 734 | 340–414 µs | **147–165 µs** | <1 ms p99 |
| `ingest` | 163 o/tick | **0 o/tick** | ≥10 k ticks/s (912 k mesuré) |
| GC hot loop | 83 Ko/eval | **32 o/eval** | — |
| Routage tick | scans 28/26 | Map O(1) | — |

Optimisations : buffers réutilisés, cache préfixe FX par `fxPi`, `spreadCost`
précalculé par série, `SpreadMedian` primitif, `pos` en `long[]`.
Golden bit-à-bit identique → aucune divergence sémantique.

## 6. Chaîne de vérification (bout final = MC)

`verify_all.sh` : compile → 134 tests JUnit → `VerifyAll` :
S1 comptes+ProofChecks → S2 injection → S4 main=centime → S5 perf →
S6 alloc → **MonteCarlo E9** (survie=1 : 300 exec, pnl = net évalué ;
survie=0 : tout meurt ; corpus réel : eval→execSeq chaîné).

## Reste à fixer / limites

- **Démo JForex réelle** : 3 bouts dynamiques à confirmer en ligne ;
  `OgStrategy` jamais exécuté dans la plateforme (compilé + lié à API 4.9.8).
- **Exec bout** : évalué, `SKIP` en live (Solution A — FX only).
- **Parité tick-par-tick Java↔Rust** execSeq : **prouvée** (12/11) —
  rows `exec2` de la fixture dorée couvrent Done, KillIoc jambe 0,
  KillIoc jambe 1 + unwind (−2,80 $), BelowQMin + unwind, NoQuote +
  unwind, volGuard partiel — PnL égal à 1e-9 relatif.
- **JCloud** : upload via espace client Dukascopy (compte DEMO sauvegardé
  dans JForex4 local — `users.xml`, dernier login 07/11). Jar prêt :
  `jfMainClass` posé, `tools/PackJfx.java` (IClient→packToJfx) compilé.

## Mesure alpha — journal venue cTrader (12/11)

Rejoue des détections réelles `arb_cs_journal` (4 jours, ~230 k ticks)
dans le moteur Rust + exec_seq, à `lat=1,0 ms` (latence cible JCloud) :

| jour | épisodes | détecté | réalisé | survie |
|---|---|---|---|---|
| 11-02 | 0 | — | — | — |
| 11-03 | 24 | +1,80 $ | −0,98 $ | −54 % |
| 11-04 | 8 | +0,66 $ | −1,37 $ | −206 % |
| 11-10 | 0 | — | — | — |

Survie identique à `lat=2,5 ms` → le kill n'est PAS la latence des
jambes mais la sélection adverse sur rafraîchissement de quote stale
(le journal est échantillonné ~1–2 ticks/s ; la dernière quote « fraîche
à 8 s » est en fait périmée et saute contre l'exécution). Sweep
`fresh_ms` ∈ {500, 2000, 4000, 8000} : aucun seuil ne réalise un gain
répliqué (le +0,88 $ à fresh=2000 sur 11-03 ne se réplique pas sur
11-04 : −1,37 $). Verdict sur ce canal : pas d'alpha exécutable.
Réserve : granularité du journal (borne optimiste/pessimiste
documentée) et feed cTrader ≠ Dukascopy — la rejoue sur corpus
Dukascopy (la venue de JCloud) reste le test conclusif.
