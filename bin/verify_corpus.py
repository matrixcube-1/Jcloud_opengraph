#!/usr/bin/env python3
"""verify_corpus.py — contrôle de complétude/intégrité du corpus Dukascopy.

Vérifie la structure data/{SYM}/{YYYY-MM-DD}/{HH}.csv produite par
fetch_24h.py AVANT toute calibration — DataLoader saute les heures
manquantes sans prévenir : un corpus incomplet passerait silencieusement
et fausserait la calibration.

Contrôles par fichier : en-tête, 5 colonnes, bid<=ask, prix/vols finis,
ms strictement croissants dans [0, 3600000), pas de doublons.
Contrôles par paire/jour : 24/24 fichiers, densité de ticks, magnitude
de prix vs niveau de référence (diviseur correct).
Verdict final : READY / NOT READY.

Usage : python verify_corpus.py --root /opt/jcloud/data --day 2026-11-11
        python verify_corpus.py --root C:/arb_duka/data --day 2026-10-22
"""
import argparse
import csv
import json
import math
import sys
from pathlib import Path

FX28 = ["EURUSD","GBPUSD","USDJPY","USDCHF","AUDUSD","USDCAD","NZDUSD",
        "EURGBP","EURJPY","EURCHF","EURAUD","EURCAD","EURNZD","GBPJPY",
        "GBPCHF","GBPAUD","GBPCAD","GBPNZD","AUDJPY","AUDCHF","AUDCAD",
        "AUDNZD","CADJPY","CADCHF","CHFJPY","NZDJPY","NZDCAD","NZDCHF"]

# niveaux indicatifs nov. 2026 — détecte un diviseur faux (x10/x100)
REF = {"EURUSD":1.11,"GBPUSD":1.30,"USDJPY":154.0,"USDCHF":0.81,"AUDUSD":0.65,
       "USDCAD":1.40,"NZDUSD":0.60,"EURGBP":0.85,"EURJPY":172.0,"EURCHF":0.90,
       "EURAUD":1.71,"EURCAD":1.56,"EURNZD":1.85,"GBPJPY":201.0,"GBPCHF":1.06,
       "GBPAUD":2.00,"GBPCAD":1.82,"GBPNZD":2.17,"AUDJPY":100.0,"AUDCHF":0.53,
       "AUDCAD":0.91,"AUDNZD":1.08,"CADJPY":110.0,"CADCHF":0.58,"CHFJPY":190.0,
       "NZDJPY":92.0,"NZDCAD":0.86,"NZDCHF":0.48}

MIN_TICKS_H_FX = 100          # sous cette densité une heure FX est suspecte
LOG_RATIO_MAX = 0.35          # |ln(px/ref)| max ≈ 40 % d'écart toléré


def mandatory_hours(day):
    """Heures où le marché FX doit avoir des ticks — calendrier réel :
    sam=fermé, dim=ouvre ~22:00 UTC, ven=ferme ~21:00 UTC (heures 21-23
    optionnelles : l'heure exacte de clôture dépend du DST US).
    Les fichiers présents hors heures obligatoires sont vérifiés pareil."""
    import datetime
    wd = datetime.date.fromisoformat(day).weekday()
    if wd == 5:                      # samedi
        return set()
    if wd == 6:                      # dimanche
        return {22, 23}
    if wd == 4:                      # vendredi
        return set(range(21))
    return set(range(24))


def check_file(path):
    """-> (n_ticks, issues, first_ms, last_ms, px_sample)"""
    issues = []
    n = 0
    last_ms = -1
    seen = set()
    px_sample = None
    first_ms = last_valid = None
    try:
        with open(path, newline="") as f:
            rdr = csv.reader(f)
            header = next(rdr, None)
            if header != ["ms_off", "ask", "bid", "ask_vol", "bid_vol"]:
                issues.append(f"header {header}")
            for row in rdr:
                if len(row) != 5:
                    issues.append(f"cols={len(row)} @l{n + 2}")
                    continue
                try:
                    ms = int(row[0])
                    a, b = float(row[1]), float(row[2])
                    av, bv = float(row[3]), float(row[4])
                except ValueError:
                    issues.append(f"nonnum @l{n + 2}")
                    continue
                n += 1
                if not (math.isfinite(a) and math.isfinite(b)
                        and math.isfinite(av) and math.isfinite(bv)):
                    issues.append("nonfin")
                if b > a:
                    issues.append("bid>ask")
                if av < 0 or bv < 0:
                    issues.append("vol<0")
                if ms < 0 or ms >= 3_600_000:
                    issues.append("ms_hors_heure")
                if ms <= last_ms:
                    issues.append("ms_non_mono")
                if ms in seen:
                    issues.append("ms_dup")
                seen.add(ms)
                if px_sample is None:
                    px_sample = a
                if first_ms is None:
                    first_ms = ms
                last_ms = ms
                last_valid = ms
    except OSError as e:
        return 0, [f"io:{e}"], None, None, None
    return n, issues, first_ms, last_valid, px_sample


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True, help="racine data/ du corpus")
    ap.add_argument("--day", required=True, help="YYYY-MM-DD")
    ap.add_argument("--syms", default=",".join(FX28))
    ap.add_argument("--ledger", default=None,
                    help="fetch_ledger.json (contrôle des fails en plus)")
    ap.add_argument("--min-ticks-h", type=float, default=MIN_TICKS_H_FX)
    args = ap.parse_args()

    root, day = Path(args.root), args.day
    syms = args.syms.split(",")
    mandatory = mandatory_hours(day)
    total_files = len(mandatory) * len(syms)
    print(f"== corpus {root} jour {day} — {len(syms)} paires x "
          f"{len(mandatory)}h obligatoires (calendrier FX) "
          f"= {total_files} fichiers attendus ==\n")

    # ledger : fichiers attendus mais jamais téléchargés
    if args.ledger and Path(args.ledger).exists():
        led = json.loads(Path(args.ledger).read_text())
        pend = [k for k, v in led.items()
                if not k.startswith("_") and not v.get("ok")]
        warns = led.get("_warn", [])
        if pend:
            print(f"LEDGER: {len(pend)} fichiers en echec: {pend[:10]}")
        if warns:
            print(f"LEDGER warnings: {warns[:10]}")

    n_missing = n_bad = 0
    worst = []
    for sym in syms:
        daydir = root / sym / day
        files = sorted(daydir.glob("*.csv")) if daydir.is_dir() else []
        hours = {int(f.stem) for f in files if f.stem.isdigit()}
        missing = sorted(mandatory - hours)
        day_ticks = 0
        sym_issues = []
        for h in sorted(hours):
            n, issues, f0, f1, px = check_file(daydir / f"{h:02d}.csv")
            day_ticks += n
            if n < args.min_ticks_h:
                sym_issues.append(f"{h:02d}h:{n}ticks")
            if issues:
                uniq = sorted(set(issues))
                sym_issues.append(f"{h:02d}h:{uniq[:3]}")
                n_bad += 1
            if px and sym in REF:
                lr = abs(math.log(px / REF[sym]))
                if lr > LOG_RATIO_MAX:
                    sym_issues.append(f"{h:02d}h:px={px} ref={REF[sym]}"
                                      f" (diviseur?)")
        if missing:
            n_missing += len(missing)
            print(f"{sym:8s} {len(hours):2d}/24h manque={missing} "
                  f"ticks={day_ticks}")
        else:
            flag = f"  << {sym_issues[:4]}" if sym_issues else ""
            print(f"{sym:8s} 24/24 ticks={day_ticks:8,}"
                  f" ~{day_ticks // 24}/h{flag}")
        if sym_issues:
            worst.append((sym, len(sym_issues)))

    print(f"\n=== VERDICT ===")
    ok = n_missing == 0 and not worst
    print(f"fichiers manquants: {n_missing}/{total_files} | "
          f"fichiers avec anomalies: {n_bad}")
    if worst:
        print("paires avec anomalies:", [s for s, _ in worst])
    print("CORPUS", "READY" if ok else "NOT READY")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
