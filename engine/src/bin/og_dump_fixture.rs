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
use opengraph::paths::{self, PathTables};
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

// ---- couverture execSeq : livres 3 ticks {1000,1002,1004} ----
// Permet de placer un mouvement/volume ENTRE la quote d'envoi et la
// quote de fill d'une jambe précise (t_f = t + lat).
fn book_var(
    perturb: impl Fn(&'static str, usize) -> f64,
    vol: impl Fn(&'static str, usize) -> f64,
    late: &[&'static str],
) -> Vec<SymbolSeries> {
    let u = ups();
    FX.iter()
        .map(|&s| {
            let a = ci(&s[0..3]) as usize;
            let b = ci(&s[3..6]) as usize;
            let mid0 = u[a] / u[b];
            let base_ts: Vec<i64> = if late.contains(&s) {
                vec![1_500, 1_502, 1_504]
            } else {
                vec![1_000, 1_002, 1_004]
            };
            let mut ts = Vec::new();
            let mut bid = Vec::new();
            let mut ask = Vec::new();
            let mut avol = Vec::new();
            let mut bvol = Vec::new();
            for (k, &tk) in base_ts.iter().enumerate() {
                let m = mid0 * perturb(s, k);
                ts.push(tk);
                bid.push(m * 0.9999);
                ask.push(m * 1.0001);
                avol.push(vol(s, k));
                bvol.push(vol(s, k));
            }
            SymbolSeries { sym: s, ts, bid, ask, avol, bvol }
        })
        .collect()
}

/// Chemin JPY -> CAD : 1 jambe [CADJPY] dir=-1, panier
/// [USDJPY, CADJPY, USDCAD] — miroir de ExecTest::pathJpyCad.
fn path_jpy_cad(pt: &PathTables) -> usize {
    let cad = FX.iter().position(|&s| s == "CADJPY").unwrap() as u8;
    (0..pt.len())
        .find(|&pi| {
            pt.p_s[pi] == ci("JPY")
                && pt.paths[pi].len() == 1
                && pt.paths[pi][0] == cad
                && pt.p_dir[pi][0] == -1
        })
        .expect("chemin JPY->CAD introuvable")
}

fn emit_exec2(
    name: &str,
    e: &mut Engine,
    t0: i64,
    pi: usize,
    ep: &ExecParams,
    pt: &PathTables,
) {
    e.sync(t0);
    let head = format!(
        "exec2\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
        name, t0, pi, ep.lat_ms, ep.tau_slip_bp, ep.q_min,
        ep.vol_guard as u8
    );
    match e.exec_seq(t0, pi, ep, pt) {
        Some(ExecOutcome::Done {
            pnl_usd,
            filled_legs,
            partial_legs,
            dur_ms,
        }) => println!(
            "{}\tDONE\t{:.17e}\t{}\t{}\t{:.3}",
            head, pnl_usd, filled_legs, partial_legs, dur_ms
        ),
        Some(ExecOutcome::Abort {
            reason,
            pnl_usd,
            filled_legs,
            partial_legs,
            dur_ms,
        }) => println!(
            "{}\tABORT:{:?}\t{:.17e}\t{}\t{}\t{:.3}",
            head, reason, pnl_usd, filled_legs, partial_legs, dur_ms
        ),
        None => println!("{}\tNONE\tnan\t0\t0\t0", head),
    }
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

    // ---- execSeq approfondi : kill-IOC jambe 0/1, partiel, below-qmin,
    // no-quote mid-sequence (unwind + liquidation Convention A) ----
    let pi = path_jpy_cad(&pt);
    let one = |_s: &'static str, _k: usize| 1.0f64;
    let big = |_s: &'static str, _k: usize| 1e9f64;

    // kill0 : USDJPY saute +1% au tick 1002 -> kill jambe 0, filled=0.
    let mut e = Engine::new(book_var(
        |s, k| if s == "USDJPY" && k >= 1 { 1.01 } else { 1.0 },
        &big, &[]));
    emit_exec2("kill0", &mut e, 1_000, pi,
               &ExecParams { lat_ms: 2.5, tau_slip_bp: 0.8,
                             q_min: 0.5, vol_guard: false }, &pt);

    // kill1 : CADJPY saute +1% au tick 1004 -> kill jambe 1 apres fill
    // de USDJPY (filled=1 -> unwind de la position ouverte).
    let mut e = Engine::new(book_var(
        |s, k| if s == "CADJPY" && k >= 2 { 1.01 } else { 1.0 },
        &big, &[]));
    emit_exec2("kill1", &mut e, 1_000, pi,
               &ExecParams { lat_ms: 2.5, tau_slip_bp: 0.8,
                             q_min: 0.5, vol_guard: false }, &pt);

    // volp : volume top-of-book CADJPY reduit au tick de fill (1004)
    // -> fill partiel jambe 1 (vol_guard), Done avec partial_legs.
    let mut e = Engine::new(book_var(&one,
        |s, k| if s == "CADJPY" && k == 2 { 0.008 } else { 1e9 }, &[]));
    emit_exec2("volp", &mut e, 1_000, pi,
               &ExecParams { lat_ms: 2.5, tau_slip_bp: 0.8,
                             q_min: 0.5, vol_guard: true }, &pt);

    // vollow : volume quasi nul au fill -> frac < q_min -> BelowQMin
    // apres 1 fill -> unwind + residuel.
    let mut e = Engine::new(book_var(&one,
        |s, k| if s == "CADJPY" && k == 2 { 0.0005 } else { 1e9 }, &[]));
    emit_exec2("vollow", &mut e, 1_000, pi,
               &ExecParams { lat_ms: 2.5, tau_slip_bp: 0.8,
                             q_min: 0.5, vol_guard: true }, &pt);

    // noq : CADJPY ne cote qu'a partir de 1500 -> NoQuote jambe 1
    // apres fill USDJPY -> unwind + residuel marque Convention A.
    let mut e = Engine::new(book_var(&one, &big, &["CADJPY"]));
    emit_exec2("noq", &mut e, 1_000, pi,
               &ExecParams { lat_ms: 2.5, tau_slip_bp: 0.8,
                             q_min: 0.5, vol_guard: false }, &pt);

    // done_vg : livre plat, memes params vol_guard — Done temoin.
    let mut e = Engine::new(book_var(&one, &big, &[]));
    emit_exec2("done_vg", &mut e, 1_000, pi,
               &ExecParams { lat_ms: 2.5, tau_slip_bp: 0.8,
                             q_min: 0.5, vol_guard: true }, &pt);
}
