"""Download hourly 2025 grid data from Electricity Maps for SE, DE, PL (flow-traced) and write
carbon.csv and renewable.csv.   Your API key stays on your computer.

Windows:  set EMAPS_TOKEN=your_key        Linux/Mac:  export EMAPS_TOKEN=your_key
Then:     python fetch_grid.py [year]     (default 2025)

If the API version/paths differ for your licence, change API_VERSION below (v3 or v4) or the two
ENDPOINTS, and send me the printed error message. The script saves each raw response to raw/ too.
"""
import os, sys, json, csv, time, urllib.request, urllib.error
from datetime import datetime, timedelta, timezone
TOKEN=os.environ.get('EMAPS_TOKEN'); YEAR=int(sys.argv[1]) if len(sys.argv)>1 else 2025
if not TOKEN: sys.exit('Set the EMAPS_TOKEN environment variable first.')
API_VERSION='v4'
ENDPOINTS={'carbon':'carbon-intensity/past-range','renewable':'renewable-energy/past-range'}
ZONES=['SE','DE','PL']; CHUNK_DAYS=9
BASE=f'https://api.electricitymaps.com/{API_VERSION}'
os.makedirs('raw',exist_ok=True)
def get(path,params):
    url=f"{BASE}/{path}?"+'&'.join(f'{k}={v}' for k,v in params.items())
    req=urllib.request.Request(url,headers={'auth-token':TOKEN})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req,timeout=60) as r: return json.load(r)
        except urllib.error.HTTPError as e:
            if e.code==429: time.sleep(5*(attempt+1)); continue
            raise SystemExit(f'HTTP {e.code} for {path}: {e.read().decode()[:400]}')
    raise SystemExit('Too many rate-limit retries; run again later.')
iso=lambda d:d.strftime('%Y-%m-%dT%H:%M:%SZ')
start=datetime(YEAR,1,1,tzinfo=timezone.utc); end=datetime(YEAR+1,1,1,tzinfo=timezone.utc)
def value_of(rec,sig):
    if 'value' in rec: return rec['value']
    return rec.get('carbonIntensity' if sig=='carbon' else 'renewablePercentage')
for sig,ep in ENDPOINTS.items():
    out=[]; seen=set()
    for zone in ZONES:
        t=start; n=0
        while t<end:
            nxt=min(t+timedelta(days=CHUNK_DAYS),end)
            d=get(ep,{'zone':zone,'start':iso(t),'end':iso(nxt),'temporalGranularity':'hourly','flowTraced':'true'})
            if n==0: json.dump(d,open(f'raw/{sig}_{zone}_first.json','w'))
            for rec in d.get('data',d.get('history',[])):
                ts=rec.get('datetime');
                if (zone,ts) in seen: continue
                seen.add((zone,ts))
                out.append([zone,ts,value_of(rec,sig),rec.get('isEstimated'),rec.get('estimationMethod')])
            t=nxt; n+=1; time.sleep(0.3)
        print(sig,zone,'rows so far',len(out))
    with open(f'{sig}.csv','w',newline='') as f:
        w=csv.writer(f); w.writerow(['zone','datetime','value','isEstimated','estimationMethod']); w.writerows(sorted(out))
    print('wrote',f'{sig}.csv',len(out),'rows (expected about',3*365*24,')')
