# Fetch 1m OHLC des 28 paires FX depuis Yahoo Finance -> CSV moteur.
# Usage: python fetch_yahoo.py <out_dir> <minutes>
import json
import sys
import time
import urllib.request
from pathlib import Path

FX = [
    "EURUSD", "GBPUSD", "USDJPY", "USDCHF", "AUDUSD", "USDCAD", "NZDUSD",
    "EURGBP", "EURJPY", "EURCHF", "EURAUD", "EURCAD", "EURNZD", "GBPJPY",
    "GBPCHF", "GBPAUD", "GBPCAD", "GBPNZD", "AUDJPY", "AUDCHF", "AUDCAD",
    "AUDNZD", "CADJPY", "CADCHF", "CHFJPY", "NZDJPY", "NZDCAD", "NZDCHF",
]

def fetch(sym):
    url = (f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}=X"
           f"?range=1d&interval=1m")
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=20) as r:
        j = json.loads(r.read())
    res = j["chart"]["result"][0]
    ts = res.get("timestamp") or []
    q = res["indicators"]["quote"][0]
    closes = res["indicators"].get("adjclose", [{}])[0].get("adjclose") or q["close"]
    out = []
    for t, c in zip(ts, closes):
        if c is not None:
            out.append((int(t) * 1000, float(c)))
    return out

def main():
    out_dir = Path(sys.argv[1]); minutes = int(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    cutoff = int(time.time() * 1000) - minutes * 60_000
    all_ts = None
    series = {}
    for sym in FX:
        try:
            rows = fetch(sym)
        except Exception as e:
            print(f"{sym}: ERREUR {e}", file=sys.stderr)
            continue
        rows = [r for r in rows if r[0] >= cutoff]
        series[sym] = dict(rows)
        s = set(series[sym])
        all_ts = s if all_ts is None else (all_ts & s)
        print(f"{sym}: {len(rows)} bars", file=sys.stderr)
        time.sleep(0.4)
    common = sorted(all_ts or [])
    print(f"timestamps communs: {len(common)}", file=sys.stderr)
    # forward-fill max 5 min pour les trous idiosyncratiques
    for sym, d in series.items():
        p = out_dir / f"{sym}.csv"
        last = None
        with open(p, "w") as f:
            f.write("ts_ms,mid\n")
            for t in common:
                if t in d:
                    last = d[t]
                if last is not None:
                    f.write(f"{t},{last}\n")
    print(f"OK -> {out_dir}")

if __name__ == "__main__":
    main()
