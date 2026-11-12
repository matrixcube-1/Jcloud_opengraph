//! Génère la fixture dorée de parité Rust→Java.
//!
//! Livre synthétique identique au côté Java (bookTs / bookDay) :
//! mids cohérents ups[base]/ups[quote], spread ×0.9999/×1.0001,
//! distorsion CADJPY ×0.995 à partir de k>=shift, volumes 1e9.
//!
//! Sortie TSV sur stdout :
//!   eval<TAB>book<TAB>t_ms<TAB>path_idx<TAB>net_usd<TAB>ddd_usd
//!   exec<TAB>book<TAB>t_ms<TAB>path_idx<TAB>outcome<TAB>pnl<TAB>filled<TAB>partial
//!
//! Usage : cargo run --release --bin og_dump_fixture > golden.tsv

use opengraph::engine::{Engine, SymbolSeries};
use opengraph::exec::{ExecOutcome, ExecParams};
use opengraph::paths;
use opengraph::universe::{ci, FX};

fn ups() -> [f64; 8] {
    [
        1.0,
        1.10,
        1.30,
        1.0 / 150.0,
        1.0 / 0.90,
        1.0 / 1.35,
        0.65,
        0.60,
    ]
}

/// 4 ticks à ts = 1000 + 100k ; CADJPY distordu pour k >= shift ;
/// `distort=false` => livre plat. Volumes illimités (fills complets).
fn book_ts(shift: usize, distort: bool) -> Vec<SymbolSeries> {
    let u = ups();
    FX.iter()
        .map(|&s| {
            let a = ci(&s[0..3]) as usize;
            let b = ci(&s[3..6]) as usize;
            let mid0 = u[a] / u[b];
            let mut ts = Vec::new();
            let mut bid = Vec::new();
            let mut ask = Vec::new();
            let mut avol = Vec::new();
            let mut bvol = Vec::new();
            for k in 0..4usize {
                let m = if distort && s == "CADJPY" && k >= shift {
                    mid0 * 0.995
                } else {
                    mid0
                };
                ts.push(1_000 + 100 * k as i64);
                bid.push(m * 0.9999);
                ask.push(m * 1.0001);
                avol.push(1e9);
                bvol.push(1e9);
            }
            SymbolSeries { sym: s, ts, bid, ask, avol, bvol }
        })
        .collect()
}

fn main() {
    let pt = paths::build_tables();
    let ep = ExecParams {
        lat_ms: 0.0,
        tau_slip_bp: 0.8,
        q_min: 0.5,
        vol_guard: false,
    };

    for (name, distort) in [("flat", false), ("dist", true)] {
        let mut e = Engine::new(book_ts(2, distort));
        for t in [1_100i64, 1_200, 1_300] {
            e.sync(t);
            match e.eval_at(t, 5_000.0, 10.0, 1.0, &pt) {
                Some(r) => println!(
                    "eval\t{}\t{}\t{}\t{:.17e}\t{:.17e}",
                    name, t, r.path_idx, r.net_usd, r.ddd_usd
                ),
                None => println!("eval\t{}\t{}\t-1\tnan\tnan", name, t),
            }
        }
        // exec sur l'argmax à t=1200 (lat=0 : verrou eval==exec)
        e.sync(1_200);
        if let Some(r) = e.eval_at(1_200, 5_000.0, 10.0, 1.0, &pt) {
            match e.exec_seq(1_200, r.path_idx, &ep, &pt) {
                Some(ExecOutcome::Done {
                    pnl_usd,
                    filled_legs,
                    partial_legs,
                    ..
                }) => println!(
                    "exec\t{}\t1200\t{}\tDONE\t{:.17e}\t{}\t{}",
                    name, r.path_idx, pnl_usd, filled_legs, partial_legs
                ),
                Some(ExecOutcome::Abort {
                    reason,
                    pnl_usd,
                    filled_legs,
                    partial_legs,
                    ..
                }) => println!(
                    "exec\t{}\t1200\t{}\tABORT:{:?}\t{:.17e}\t{}\t{}",
                    name, r.path_idx, reason, pnl_usd, filled_legs,
                    partial_legs
                ),
                None => println!("exec\t{}\t1200\t{}\tNONE\tnan\t0\t0",
                                name, r.path_idx),
            }
        }
    }

    // régression spreads : médianes après ingestion complète du livre
    // (vérifie la dérive de médiane incrémentale vs batch)
    let e2 = Engine::new(book_ts(0, true));
    for (i, s) in e2.data.iter().enumerate() {
        let m = e2.med[i];
        println!("med\t{}\t{}\t{:.17e}", "dist", s.sym, m);
    }
}
