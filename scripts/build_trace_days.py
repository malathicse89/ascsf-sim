"""Build trace_data/trace_days.csv from Electricity Maps hourly files (carbon.csv, renewable.csv: zone,datetime,value,...).
Usage: python build_trace_days.py carbon.csv renewable.csv trace_data/trace_days.csv
Carbon g/kWh -> kg/kWh; renewable % -> fraction. DC0=SE, DC1=DE, DC2=PL.
Trial t uses 2025 day (t-1)*12 and start hour (7*(t-1)) % 24; the 24-value profile is the day's hourly values (index = hour of day)."""
import sys, pandas as pd
c=pd.read_csv(sys.argv[1]); r=pd.read_csv(sys.argv[2])
for d in (c,r): d['datetime']=pd.to_datetime(d['datetime'],utc=True)
rows=[]
for t in range(1,31):
    day=(t-1)*12; start=(7*(t-1))%24
    for dc,z in enumerate(['SE','DE','PL']):
        cz=c[c.zone==z].sort_values('datetime').reset_index(drop=True); rz=r[r.zone==z].sort_values('datetime').reset_index(drop=True)
        cv=cz.value.iloc[day*24:day*24+24].values/1000.0; rv=rz.value.iloc[day*24:day*24+24].values/100.0
        date=cz.datetime.iloc[day*24].strftime('%Y-%m-%d')
        rows.append([t,dc,z,date,start]+[f'{x:.4f}' for x in cv]+[f'{x:.4f}' for x in rv])
cols=['trial','dc','zone','date','startHour']+[f'c{i}' for i in range(24)]+[f'r{i}' for i in range(24)]
pd.DataFrame(rows,columns=cols).to_csv(sys.argv[3],index=False)
