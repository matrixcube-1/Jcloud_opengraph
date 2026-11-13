//+------------------------------------------------------------------+
//| OgArb.mq5 — EA open-path FX : wrapper venue sur OgCore.mqh        |
//| Detect-only par defaut (InpAllowLive=false).                      |
//+------------------------------------------------------------------+
#property copyright "OpenGraph"
#property version   "1.10"

#include <Trade\Trade.mqh>
#include "OgCore.mqh"

input group "=== Univers ==="
input string InpSymbols      = "";            // Paires (vide = 28 Annexe J.2), virgule
input string InpSymPrefix    = "";            // Prefixe broker (ex. "pro.")
input string InpSymSuffix    = "";            // Suffixe broker (ex. ".r", "m")
input int    InpMaxLegs      = 3;             // Jambes FX max par chemin (1..3)

input group "=== Economie (Convention A) ==="
input double InpNotionalUSD  = 10000.0;       // Notionnel USD par panier
input double InpCommPer100K  = 4.0;           // Commission $/cote/100K USD
input double InpCommScale    = 1.10;          // Multiplicateur commission
input double InpDddMin       = 0.27;          // Seuil detection net ($)

input group "=== Gates ==="
input long   InpFreshMs      = 8000;          // Fraicheur quote max (ms)
input double InpSpreadCap    = 1.76;          // Cap spread x mediane
input int    InpMedWindow    = 256;           // Fenetre mediane spread (ticks, 16..256)
input int    InpWarmupTicks  = 100;           // Ticks/sym avant evaluation

input group "=== Cadence ==="
input int    InpEvalMs       = 500;           // Intervalle evaluation (ms)
input int    InpHbSec        = 60;            // Heartbeat (s)
input int    InpSessFromUTC  = -1;            // Session debut (h UTC, -1 = tout)
input int    InpSessToUTC    = -1;            // Session fin (h UTC)

input group "=== Execution ==="
input bool   InpAllowLive    = false;         // AUTORISE les ordres reels
input double InpTauSlipBp    = 0.69;          // Slippage IOC tolere (bp)
input double InpQMin         = 0.5;           // Fraction fill min acceptee
input long   InpMinSeqGapMs  = 2000;          // Gap min entre sequences
input int    InpBreakerFails = 5;             // Breaker apres N echecs
input long   InpMagic        = 20261112;      // Magic number

input group "=== Journal ==="
input string InpLogFile      = "ogarb_journal.jsonl";  // Fichier journal
input bool   InpLogTicks     = false;                  // Log chaque eval

// Annexe J.2
string FX_DEF[] = {
   "EURUSD","GBPUSD","USDJPY","USDCHF","AUDUSD","USDCAD","NZDUSD",
   "EURGBP","EURJPY","EURCHF","EURAUD","EURCAD","EURNZD","GBPJPY",
   "GBPCHF","GBPAUD","GBPCAD","GBPNZD","AUDJPY","AUDCHF","AUDCAD",
   "AUDNZD","CADJPY","CADCHF","CHFJPY","NZDJPY","NZDCAD","NZDCHF"
};

COgEngine eng;

// mediane spread par symbole (maintenue par l'EA)
double sprBuf[OG_MAX_SYMS][256];
int    sprLen[OG_MAX_SYMS];
int    sprPos[OG_MAX_SYMS];
double medArr[OG_MAX_SYMS];
int    symSeen[OG_MAX_SYMS];
int    medWin = 256;

// stats
long   gEvals=0, gDetects=0, gTicks=0;
double gMaxNet=-1e18;
int    gMaxPath=-1;
datetime gLastHb=0;
long   gLastSeq=0;
int    gFails=0;
int    gLogH=INVALID_HANDLE;
CTrade gTrade;

//+------------------------------------------------------------------+
void jlog(const string type,const string msg)
{
   string line=StringFormat("{\"type\":\"%s\",\"ts\":%I64d,%s}",
                type,(long)(GetMicrosecondCount()/1000),msg);
   if(gLogH!=INVALID_HANDLE){ FileWriteString(gLogH,line+"\n"); FileFlush(gLogH); }
   Print(line);
}

double medianOf(int si)
{
   if(sprLen[si]<=0) return 0;
   double tmp[256];
   int n=sprLen[si];
   for(int i=0;i<n;i++) tmp[i]=sprBuf[si][i];
   ArraySort(tmp);
   return (n%2==1)?tmp[n/2]:0.5*(tmp[n/2-1]+tmp[n/2]);
}

void poll()
{
   MqlTick tk;
   for(int i=0;i<eng.nSym;i++)
   {
      if(!SymbolInfoTick(eng.symName[i],tk)){ eng.bidP[i]=0; eng.askP[i]=0; continue; }
      double nb=tk.bid,na=tk.ask;
      if(na<nb||nb<=0) continue;
      if(nb!=eng.bidP[i]||na!=eng.askP[i])
      {
         symSeen[i]++;
         double sp=na-nb;
         sprBuf[i][sprPos[i]%medWin]=sp;
         sprPos[i]++;
         if(sprLen[i]<medWin) sprLen[i]++;
      }
      eng.bidP[i]=nb; eng.askP[i]=na;
      eng.lastT[i]=tk.time_msc;
   }
}

//+------------------------------------------------------------------+
bool sendLeg(int e,int from,double qtyFrom,double &qtyTo,string &err)
{
   int a=eng.symA[e],b=eng.symB[e];
   bool sellBase=(from==a);
   double lot=qtyFrom;
   double lots=sellBase? lot/100000.0 : (lot/eng.askP[e])/100000.0;
   double vmin=SymbolInfoDouble(eng.symName[e],SYMBOL_VOLUME_MIN);
   double vstep=SymbolInfoDouble(eng.symName[e],SYMBOL_VOLUME_STEP);
   double vmax=SymbolInfoDouble(eng.symName[e],SYMBOL_VOLUME_MAX);
   lots=MathFloor(lots/vstep)*vstep;
   lots=MathMax(vmin,MathMin(vmax,lots));
   gTrade.SetDeviationInPoints((long)(InpTauSlipBp*10));
   gTrade.SetTypeFilling(ORDER_FILLING_IOC);
   bool sent=sellBase
      ? gTrade.Sell(lots,eng.symName[e],0.0,0.0,0.0,"ogLeg")
      : gTrade.Buy (lots,eng.symName[e],0.0,0.0,0.0,"ogLeg");
   if(!sent||gTrade.ResultRetcode()!=TRADE_RETCODE_DONE)
   {
      err=StringFormat("%s %s %.2f : %s",sellBase?"SELL":"BUY",
           eng.symName[e],lots,gTrade.ResultRetcodeDescription());
      return false;
   }
   double px=gTrade.ResultPrice();
   qtyTo=sellBase? lot*px : lot/px;
   err="";
   return true;
}

bool execBasket(int pi,long tMs)
{
   if(!InpAllowLive) return false;
   int s0=eng.pS[pi],end=eng.pEnd[pi];
   double qty=InpNotionalUSD,qtyTo=qty; string err;
   int filled=0;
   if(s0!=0)
   {
      int e=eng.usdSym[s0];
      if(!sendLeg(e,0,qty,qtyTo,err)){ onFail(); jlog("ABORT","\"leg\":\"entry\",\"err\":\""+err+"\""); return false; }
      qty=qtyTo; filled++;
   }
   int node=s0;
   for(int li=0;li<eng.pL[pi];li++)
   {
      int e=eng.pE[pi][li];
      node=(li==0)?s0:((eng.pDir[pi][li-1]==1)?eng.symB[eng.pE[pi][li-1]]:eng.symA[eng.pE[pi][li-1]]);
      if(!sendLeg(e,node,qty,qtyTo,err)){ onFail(); jlog("ABORT","\"leg\":"+IntegerToString(li)+",\"err\":\""+err+"\""); return false; }
      qty=qtyTo; filled++;
   }
   if(end!=0)
   {
      int e=eng.usdSym[end];
      if(!sendLeg(e,end,qty,qtyTo,err)){ onFail(); jlog("ABORT","\"leg\":\"exit\",\"err\":\""+err+"\""); return false; }
      qty=qtyTo; filled++;
   }
   jlog("EXEC",StringFormat("\"path\":%d,\"filled\":%d,\"usdEnd\":%.2f",pi,filled,qty));
   return true;
}

void onFail()
{
   if(++gFails>=InpBreakerFails)
   {
      jlog("EVT","\"msg\":\"breaker - halt\"");
      ExpertRemove();
   }
}

bool sessionOk()
{
   if(InpSessFromUTC<0||InpSessToUTC<0) return true;
   MqlDateTime dt; TimeToStruct(TimeGMT(),dt);
   int h=dt.hour;
   if(InpSessFromUTC<=InpSessToUTC) return h>=InpSessFromUTC&&h<InpSessToUTC;
   return h>=InpSessFromUTC||h<InpSessToUTC;
}

//+------------------------------------------------------------------+
int OnInit()
{
   if(StringLen(InpSymbols)>0)
   {
      string parts[];
      int k=StringSplit(InpSymbols,',',parts);
      for(int i=0;i<k;i++)
      {
         StringTrimLeft(parts[i]); StringTrimRight(parts[i]);
         string nm=InpSymPrefix+parts[i]+InpSymSuffix;
         if(!SymbolSelect(nm,true)){ PrintFormat("OgArb: %s absent",nm); continue; }
         eng.addSym(parts[i],nm);
      }
   }
   else
      for(int i=0;i<ArraySize(FX_DEF);i++)
      {
         string nm=InpSymPrefix+FX_DEF[i]+InpSymSuffix;
         if(!SymbolSelect(nm,true)){ PrintFormat("OgArb: %s absent",nm); continue; }
         eng.addSym(FX_DEF[i],nm);
      }
   if(eng.nSym==0){ Print("OgArb: aucun symbole"); return INIT_FAILED; }
   medWin=(int)MathMin(MathMax((double)InpMedWindow,16.0),256.0);
   eng.buildPaths(MathMin(MathMax(InpMaxLegs,1),3));
   PrintFormat("OgArb: %d syms, %d chemins (max %d jambes)",eng.nSym,eng.nPaths,InpMaxLegs);
   gTrade.SetExpertMagicNumber(InpMagic);
   gTrade.SetAsyncMode(false);
   gLogH=FileOpen(InpLogFile,FILE_READ|FILE_WRITE|FILE_TXT|FILE_ANSI|FILE_COMMON);
   if(gLogH!=INVALID_HANDLE) FileSeek(gLogH,0,SEEK_END);
   EventSetMillisecondTimer(InpEvalMs);
   jlog("EVT",StringFormat("\"msg\":\"OgArb start %d syms %d paths live=%s\"",
        eng.nSym,eng.nPaths,InpAllowLive?"true":"false"));
   return INIT_SUCCEEDED;
}

void OnDeinit(const int reason)
{
   EventKillTimer();
   if(gLogH!=INVALID_HANDLE){ FileClose(gLogH); gLogH=INVALID_HANDLE; }
   jlog("EVT","\"msg\":\"OgArb stop\"");
}

//+------------------------------------------------------------------+
void OnTimer()
{
   gTicks++;
   poll();
   for(int i=0;i<eng.nSym;i++) medArr[i]=medianOf(i);
   int warmed=0;
   for(int i=0;i<eng.nSym;i++) if(symSeen[i]>=InpWarmupTicks) warmed++;
   if(warmed<eng.nSym||!sessionOk()){ heartbeat(warmed); return; }

   long tMs=(long)TimeCurrent()*1000;
   OgParams P;
   P.notional=InpNotionalUSD; P.commPer100k=InpCommPer100K;
   P.commScale=InpCommScale; P.spreadCap=InpSpreadCap;
   P.freshMs=InpFreshMs; P.maxLegs=InpMaxLegs;
   double net,ddd; int pi;
   if(!eng.evalAll(tMs,P,medArr,net,pi,ddd)){ heartbeat(warmed); return; }
   gEvals++;
   if(net>gMaxNet){ gMaxNet=net; gMaxPath=pi; }
   if(InpLogTicks) jlog("EVAL",StringFormat("\"net\":%.4f,\"pi\":%d",net,pi));
   if(net>=InpDddMin&&tMs>=gLastSeq+InpMinSeqGapMs)
   {
      gLastSeq=tMs; gDetects++;
      jlog("OPP",StringFormat("\"net\":%.4f,\"pi\":%d,\"ddd\":%.4f,\"p\":\"%s\"",
           net,pi,ddd,eng.pathStr(pi)));
      execBasket(pi,tMs);
   }
   heartbeat(warmed);
}

void heartbeat(int warmed)
{
   datetime now=TimeGMT();
   if(now-gLastHb<InpHbSec) return;
   gLastHb=now;
   jlog("HB",StringFormat("\"ticks\":%I64d,\"warmed\":\"%d/%d\",\"evals\":%I64d,"
        "\"det\":%I64d,\"maxNet\":%.4f,\"path\":%d",
        gTicks,warmed,eng.nSym,gEvals,gDetects,
        gMaxNet>-1e17?gMaxNet:0.0,gMaxPath));
}
//+------------------------------------------------------------------+
