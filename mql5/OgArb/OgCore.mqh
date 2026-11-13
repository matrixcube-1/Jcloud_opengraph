//+------------------------------------------------------------------+
//| OgCore.mqh — moteur open-path FX (port Java Engine/PathTables)    |
//| Pur : aucun appel terminal sauf Print. Testable hors-venue.       |
//| Convention A : panier complet [USD->s0]+jambes+[end->USD],        |
//| bid/ask executables, comm x2 + unwind par position.               |
//+------------------------------------------------------------------+
#ifndef OG_CORE_MQH
#define OG_CORE_MQH

#define OG_N_CURR    8
#define OG_MAX_LEGS  3
#define OG_MAX_PATHS 8192
#define OG_MAX_SYMS  64

struct OgParams
{
   double notional;      // USD par panier
   double commPer100k;   // $/cote/100K USD
   double commScale;     // multiplicateur
   double spreadCap;     // cap spread x mediane
   long   freshMs;       // fraicheur max (ms)
   int    maxLegs;       // jambes max
};

class COgEngine
{
public:
   string CURR[OG_N_CURR];
   int    nCurr;

   // symboles
   string symName[OG_MAX_SYMS];
   int    symA[OG_MAX_SYMS], symB[OG_MAX_SYMS];
   int    nSym;

   // quotes courantes (poussees par l'appelant)
   double bidP[OG_MAX_SYMS], askP[OG_MAX_SYMS];
   long   lastT[OG_MAX_SYMS];        // epoch ms du dernier tick

   // chemins
   int    pS[OG_MAX_PATHS];
   int    pEnd[OG_MAX_PATHS];
   int    pL[OG_MAX_PATHS];
   int    pE[OG_MAX_PATHS][OG_MAX_LEGS];
   int    pDir[OG_MAX_PATHS][OG_MAX_LEGS];
   int    nPaths;

   // paire USD par devise
   int    usdSym[OG_N_CURR];
   int    usdDir[OG_N_CURR];         // 1 = cUSD (direct), 0 = USDc

   void COgEngine()
   {
      CURR[0]="USD"; CURR[1]="EUR"; CURR[2]="GBP"; CURR[3]="JPY";
      CURR[4]="CHF"; CURR[5]="CAD"; CURR[6]="AUD"; CURR[7]="NZD";
      nCurr=OG_N_CURR;
      nSym=0; nPaths=0;
      for(int i=0;i<OG_MAX_SYMS;i++){ bidP[i]=0; askP[i]=0; lastT[i]=0; }
      for(int c=0;c<OG_N_CURR;c++){ usdSym[c]=-1; usdDir[c]=0; }
   }

   int ci(const string c)
   {
      for(int i=0;i<nCurr;i++) if(CURR[i]==c) return i;
      return -1;
   }

   // enregistre une paire def "EURUSD" + nom broker resolu
   int addSym(const string def, const string resolved)
   {
      if(nSym>=OG_MAX_SYMS) return -1;
      int a=ci(StringSubstr(def,0,3)), b=ci(StringSubstr(def,3,3));
      if(a<0||b<0) return -1;
      int i=nSym++;
      symName[i]=resolved; symA[i]=a; symB[i]=b;
      if(b==0){ usdSym[a]=i; usdDir[a]=1; }
      if(a==0){ usdSym[b]=i; usdDir[b]=0; }
      return i;
   }

   void setQuote(int i,double bid,double ask,long tMs)
   {
      if(i<0||i>=nSym) return;
      bidP[i]=bid; askP[i]=ask; lastT[i]=tMs;
   }

   void buildPaths(int maxLegs)
   {
      int legs[OG_MAX_LEGS];
      nPaths=0;
      for(int s=0;s<nCurr;s++)
         dfs(s,1<<s,0,legs,s,maxLegs);
      for(int pi=0;pi<nPaths;pi++)
      {
         int node=pS[pi];
         for(int li=0;li<pL[pi];li++)
         {
            int e=pE[pi][li];
            int a=symA[e],b=symB[e];
            pDir[pi][li]=(node==a)?1:-1;
            node=(node==a)?b:a;
         }
      }
   }

   //--- eval : med[] = spread mediane par symbole (0 = gate off)
   bool evalAll(long tMs,const OgParams &P,const double &med[],
                double &bestNet,int &bestPi,double &bestDdd)
   {
      double commPerSide=P.commPer100k*P.notional/100000.0;
      bool ok[OG_MAX_SYMS],fxOk[OG_MAX_SYMS];
      double spc[OG_MAX_SYMS];
      for(int i=0;i<nSym;i++)
      {
         ok[i]=lastT[i]>0 && lastT[i]<=tMs && (tMs-lastT[i])<=P.freshMs
               && bidP[i]>0 && askP[i]>=bidP[i];
         fxOk[i]=ok[i] && !(med[i]>0 && (askP[i]-bidP[i])>P.spreadCap*med[i]);
         double mid=0.5*(bidP[i]+askP[i]);
         spc[i]=(mid>0)?(askP[i]-bidP[i])/mid*P.notional:EMPTY_VALUE;
      }
      bestNet=-1e18; bestPi=-1; bestDdd=0;
      for(int pi=0;pi<nPaths;pi++)
      {
         double rate=1.0,comm=0.0,unwind=0.0;
         int s0=pS[pi],end=pEnd[pi];
         bool valid=true;
         if(s0!=0)
         {
            int xs=usdSym[s0];
            if(xs<0||!fxOk[xs]) valid=false;
            else{
               rate*=(usdDir[s0]==1)?1.0/askP[xs]:bidP[xs];
               unwind+=spc[xs]; comm+=commPerSide;
            }
         }
         if(!valid) continue;
         for(int li=0;li<pL[pi];li++)
         {
            int e=pE[pi][li];
            if(!fxOk[e]){ valid=false; break; }
            rate*=(pDir[pi][li]==1)?bidP[e]:1.0/askP[e];
            unwind+=spc[e]; comm+=commPerSide;
         }
         if(!valid) continue;
         if(end!=0)
         {
            int xe=usdSym[end];
            if(xe<0||!fxOk[xe]) continue;
            rate*=(usdDir[end]==1)?bidP[xe]:1.0/askP[xe];
            unwind+=spc[xe]; comm+=commPerSide;
         }
         double usdOut=P.notional*rate;
         double commFx=2.0*comm*P.commScale;
         if(!(MathIsValidNumber(usdOut)&&MathIsValidNumber(commFx)
              &&MathIsValidNumber(unwind))) continue;
         double net=usdOut-P.notional-commFx-unwind;
         if(net>bestNet){ bestNet=net; bestPi=pi; bestDdd=usdOut-P.notional; }
      }
      return bestPi>=0;
   }

   string pathStr(int pi)
   {
      string s="s0="+CURR[pS[pi]];
      for(int li=0;li<pL[pi];li++)
         s+=" ->"+symName[pE[pi][li]]+(pDir[pi][li]==1?"+":"-");
      s+=" end="+CURR[pEnd[pi]];
      return s;
   }

private:
   void dfs(int node,int mask,int depth,int &legs[],int s0,int maxLegs)
   {
      if(depth>0 && nPaths<OG_MAX_PATHS)
      {
         int pi=nPaths;
         pS[pi]=s0; pEnd[pi]=node; pL[pi]=depth;
         for(int li=0;li<depth;li++) pE[pi][li]=legs[li];
         nPaths++;
      }
      if(depth>=maxLegs) return;
      for(int e=0;e<nSym;e++)
      {
         int a=symA[e],b=symB[e];
         int nxt=(node==a)?b:(node==b?a:-1);
         if(nxt<0||(mask&(1<<nxt))!=0) continue;
         legs[depth]=e;
         dfs(nxt,mask|(1<<nxt),depth+1,legs,s0,maxLegs);
      }
   }
};
#endif
//+------------------------------------------------------------------+
