"""Build trace_data/trace_workload.csv from the Azure Public Dataset V2 VM table (vmtable.csv.gz).
Usage: python build_trace_workload.py vmtable.csv.gz trace_data/trace_workload.csv
Columns of vmtable (no header): vmid, subscription, deployment, created, deleted, maxcpu, avgcpu, p95cpu, category, cores, memory.
Interactive -> delay-sensitive (ds=1); Delay-insensitive -> delay-tolerant (ds=0); Unknown dropped.
Length = lifetime(>=300 s) * cores * avgcpu/100, rescaled log-linearly (P1-P99 -> 5,000-50,000 MI). Trial t samples 2,000 VMs (random_state 1000+t)."""
import sys, numpy as np, pandas as pd
parts=[]
for ch in pd.read_csv(sys.argv[1],header=None,usecols=[3,4,6,8,9],names=['created','deleted','avgcpu','category','cores'],chunksize=500000):
    parts.append(ch[ch.category.isin(['Interactive','Delay-insensitive'])])
v=pd.concat(parts,ignore_index=True)
v['life']=v.deleted-v.created
v=v[v.category.isin(['Interactive','Delay-insensitive'])&(v.life>=300)].copy()
w=np.log(v.life*v.cores*v.avgcpu.clip(lower=0.01)/100.0)
lo,hi=np.percentile(w,[1,99]); x=((w-lo)/(hi-lo)).clip(0,1)
v['length_mi']=np.exp(np.log(5000)+x*(np.log(50000)-np.log(5000))).round().astype(int)
v['ds']=(v.category=='Interactive').astype(int)
out=[]
for t in range(1,31):
    s=v.sample(2000,random_state=1000+t).reset_index(drop=True)
    out.append(pd.DataFrame({'trial':t,'idx':range(2000),'length_mi':s.length_mi,'ds':s.ds}))
pd.concat(out).to_csv(sys.argv[2],index=False)
