# ASCSF: Autonomous Sustainable Collaborative Scheduling Framework

Simulation code, workload inputs and result files for the paper
"ASCSF: A Bounded-Disclosure Autonomous Collaborative Scheduling Framework for Sustainable Geo-Distributed Cloud Computing"
(Malathi Selvaraj, Dhiyaneswaran Jaganathan, A, G; Sri Krishna College of Engineering and Technology).

## Requirements
Java 17, Maven, CloudSim Plus 7.3.0 (see `pom.xml`), Python 3.10+ with pandas for the data scripts.

## Layout
- `src/main/java/sim/...`  simulator, ASCSF modules, baselines (RR, Min-Min, EAS, CASPER, C-NSGA-II, GREEDY-BLIND, GREEDY-CAP)
- `trace_data/trace_workload.csv`  Azure-derived workload (trial, idx, length_mi, ds), 30 trials x 2,000 tasks
- `results/`  all result CSVs used in the paper (`review_*_paired.csv`, `simulation_results.csv`)
- `scripts/`  `build_trace_workload.py`, `build_trace_days.py`, `fetch_grid.py`

## Run
Set `RUN_MODE` and `REVIEW_SET` in `MainSimulation.java`
(`ALL | CENTRAL | FRESH | FIXEDK | LOSS | TRACE | TRACESWEEP`) and run `sim.MainSimulation` from the project root.

## Data
- **Azure Public Dataset V2** (Microsoft, CC-BY): https://github.com/Azure/AzurePublicDataset. `build_trace_workload.py` documents how the workload file was derived; the shipped `trace_workload.csv` is the file used in the paper.
- **Electricity Maps** carbon intensity and renewable share (2025; SE, DE, PL; hourly, flow-traced, lifecycle): licensed data, not redistributed here. Obtain your own API access, run `scripts/fetch_grid.py` (API key read from an environment variable, never stored in the repository) to create `carbon.csv` and `renewable.csv`, then `python scripts/build_trace_days.py carbon.csv renewable.csv trace_data/trace_days.csv`.

## Citation
See the paper. Licence: MIT (see LICENSE) before publishing.
