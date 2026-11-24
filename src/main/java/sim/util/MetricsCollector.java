package sim.util;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import sim.SimulationConfig;
import sim.model.CarbonModel;
import sim.model.RenewableModel;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Collects and reports all four performance metrics for one
 * scheduler run across one workload scale and one trial.
 *
 * Metrics computed:
 *   1. Makespan          (seconds)     — max finish time
 *   2. Total Energy      (kWh)         — SPEC CPU2017 power model
 *   3. Carbon Emissions  (kgCO2e)      — energy × actual CI at DC
 *   4. Renewable Util.   (%)           — fraction of energy from RE
 *
 * Why actual CI and not forecast CI here?
 * The scheduler optimises using FORECAST CI (C-NSGA-II/NSGA-II).
 * But we REPORT using ACTUAL CI recorded at execution time.
 * This ensures forecast errors are fully reflected in results —
 * a scheduler cannot hide poor real-world performance by using
 * optimistic forecasts in the metric calculation.
 *
 * Results are written to simulation_results.csv for analysis.
 *
 * ASCSF extension: also reports collaboration statistics (how many tasks
 * were executed locally vs. collaboratively vs. fell back) to a SEPARATE
 * file, collaboration_stats.csv -- the existing simulation_results.csv
 * schema above is completely untouched by this extension.
 */
public class MetricsCollector {

    private final CarbonModel    carbonModel;
    private final RenewableModel renewableModel;

    // DC assignment per task — needed to look up correct CI profile
    // dcAssignments[i] = DC index for cloudlet i
    private int[] dcAssignments;

    // Number of DCs in simulation
    private final int numDCs;

    // Original (pre-simulation) index of each cloudlet, matched by object
    // identity. broker.getCloudletFinishedList() is in COMPLETION order, so
    // finished.get(i) is NOT cloudlet i -- dcAssignments must be looked up
    // through this map, not through the position in the finished list.
    private IdentityHashMap<Cloudlet, Integer> originalIndex = null;

    public MetricsCollector(CarbonModel carbonModel,
                             RenewableModel renewableModel) {
        this.carbonModel    = carbonModel;
        this.renewableModel = renewableModel;
        this.numDCs         = SimulationConfig.NUM_DATACENTERS;
        this.dcAssignments  = null;
    }

    // ── Set DC assignments from scheduler ─────────────────────────────────────
    /**
     * Called by SimulationRunner after scheduling, before reporting.
     * Maps each cloudlet index to the DC it was assigned to.
     */
    public void setDcAssignments(int[] dcAssignments) {
        this.dcAssignments = dcAssignments;
    }

    /**
     * Must be called with the SAME cloudlet list (same order) that was used to
     * fill dcAssignments, before report() / computeProviderStats().
     */
    public void setSubmittedCloudlets(List<Cloudlet> submitted) {
        originalIndex = new IdentityHashMap<>();
        for (int i = 0; i < submitted.size(); i++) originalIndex.put(submitted.get(i), i);
    }

    // ── Main report method ────────────────────────────────────────────────────
    /**
     * Computes all four metrics and prints a formatted result row.
     *
     * @param schedulerName  label for output (e.g. "C-NSGA-II")
     * @param scale          workload scale label ("Small"/"Medium"/"Large")
     * @param trial          trial number (1-30)
     * @param finished       list of completed cloudlets from broker
     * @return               SimResult object containing all four metrics
     */
    public SimResult report(String schedulerName,
                             String scale,
                             int trial,
                             List<Cloudlet> finished) {

        if (finished == null || finished.isEmpty()) {
            System.out.println("[Metrics] No finished cloudlets.");
            return new SimResult(schedulerName, scale, trial,
                                 0, 0, 0, 0);
        }

        // ── Metric 1: Makespan ────────────────────────────────────────────────
        double makespan = finished.stream()
            .mapToDouble(Cloudlet::getFinishTime)
            .max()
            .orElse(0.0);

        // ── Metrics 2, 3, 4: Energy, Carbon, Renewable Util ──────────────────
        double totalEnergyKWh      = 0.0;
        double totalCarbonKgCO2    = 0.0;
        double totalRenewableKWh   = 0.0;

        for (int i = 0; i < finished.size(); i++) {
            Cloudlet cl = finished.get(i);

            // Determine which DC this task ran on
            // If dcAssignments not set, distribute round-robin across DCs
            Integer oi = (originalIndex != null) ? originalIndex.get(cl) : null;
            int dc;
            if (oi != null && dcAssignments != null && oi < dcAssignments.length) {
                dc = dcAssignments[oi] % numDCs;            // correct: original task index
            } else {
                dc = (dcAssignments != null && i < dcAssignments.length)
                     ? dcAssignments[i] % numDCs
                     : i % numDCs;                          // legacy fallback (mapping not set)
            }

            double execTimeSec = cl.getActualCpuTime();
            double lengthMI    = cl.getLength();
            double mips        = (execTimeSec > 0)
                                 ? lengthMI / execTimeSec
                                 : 1000.0;

            // VM tier from MIPS
            int tier = getVmTier(mips);

            // CPU utilisation
            double utilisation = Math.min(1.0,
                (execTimeSec > 0) ? lengthMI / (mips * execTimeSec) : 1.0);

            // Power (Watts) — SPEC CPU2017 linear model
            double pIdle  = SimulationConfig.VM_POWER_IDLE[tier];
            double pPeak  = SimulationConfig.VM_POWER_PEAK[tier];
            double powerW = pIdle + (pPeak - pIdle) * utilisation;

            // Energy (kWh)
            double energyKWh = (powerW * execTimeSec) / 3_600_000.0;
            totalEnergyKWh  += energyKWh;

            // Actual carbon intensity at this DC at finish time
            // We use ACTUAL (not forecast) for honest reporting
            double actualCI = carbonModel
                .getCarbonIntensity(dc, cl.getFinishTime());

            // Carbon (kgCO2e) — CI is in gCO2e/kWh, divide by 1000
            totalCarbonKgCO2 += energyKWh * actualCI;

            // Renewable fraction at this DC at finish time
            double rf = renewableModel
                .getRenewableFraction(dc, cl.getFinishTime());
            totalRenewableKWh += energyKWh * rf;
        }

        // Renewable utilisation percentage
        double renewablePercent = (totalEnergyKWh > 0)
            ? (totalRenewableKWh / totalEnergyKWh) * 100.0
            : 0.0;

        // ── Print result row ──────────────────────────────────────────────────
        System.out.printf(
            "[%-10s] Scale=%-6s Trial=%2d | " +
            "Makespan=%8.1fs | Energy=%8.4f kWh | " +
            "Carbon=%8.4f kgCO2e | Renewable=%5.1f%%%n",
            schedulerName, scale, trial,
            makespan, totalEnergyKWh,
            totalCarbonKgCO2, renewablePercent);

        return new SimResult(schedulerName, scale, trial,
                             makespan, totalEnergyKWh,
                             totalCarbonKgCO2, renewablePercent);
    }

    // ── VM tier from MIPS ─────────────────────────────────────────────────────
    private static int getVmTier(double mips) {
        if (mips <= 500)  return 0;
        if (mips <= 1000) return 1;
        if (mips <= 2000) return 2;
        return 3;
    }

    // ── CSV writer ────────────────────────────────────────────────────────────
    /**
     * Appends one result row to the CSV output file.
     * Called by SimulationRunner after each trial.
     *
     * CSV columns:
     *   Scheduler, Scale, Trial, Makespan, Energy, Carbon, Renewable
     */
    public static void writeResultToCSV(SimResult r) {
        try (FileWriter fw = new FileWriter(
                SimulationConfig.RESULTS_FILE, true);
             PrintWriter pw = new PrintWriter(fw)) {

            pw.printf("%s,%s,%d,%.4f,%.6f,%.6f,%.4f%n",
                r.schedulerName, r.scale, r.trial,
                r.makespan, r.energyKWh,
                r.carbonKgCO2, r.renewablePercent);

        } catch (IOException e) {
            System.out.println("[Metrics] CSV write error: "
                + e.getMessage());
        }
    }

    // ── Write CSV header ──────────────────────────────────────────────────────
    public static void writeCSVHeader() {
        try (FileWriter fw = new FileWriter(
                SimulationConfig.RESULTS_FILE, false);
             PrintWriter pw = new PrintWriter(fw)) {

            pw.println(
                "Scheduler,Scale,Trial," +
                "Makespan_s,Energy_kWh," +
                "Carbon_kgCO2e,Renewable_pct");

        } catch (IOException e) {
            System.out.println("[Metrics] Header write error: "
                + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── ASCSF EXTENSION: collaboration statistics (ASCSF-only, optional) ──
    // ══════════════════════════════════════════════════════════════════════
    //
    // Everything below is NEW. Nothing above this line was changed from
    // your original file. Writes to a SEPARATE file (collaboration_stats.csv)
    // so simulation_results.csv's schema/content is completely unaffected
    // for all 6 schedulers (including ASCSF's own makespan/energy/carbon/
    // renewable numbers, which still go through report()/writeResultToCSV()
    // above, unchanged).

    /**
     * Reports collaboration statistics for one ASCSF run. Takes primitive
     * fields (not sim.ascsf.ASCSFScheduler.CollaborationStats directly) so
     * this class has no compile-time dependency on the sim.ascsf package --
     * MainSimulation bridges the two by calling ascsf.aggregateStats() and
     * passing its fields through.
     */
    public void reportCollaborationStats(
            String scale, int trial,
            int totalTasks, int localExecutions, int collaboratedOut,
            int fallbackToLocal, int hostedForOthers,
            int offersDeclined, int admissionsRejected) {

        double collabRate = (totalTasks > 0) ? (100.0 * collaboratedOut / totalTasks) : 0.0;
        double fallbackRate = (totalTasks > 0) ? (100.0 * fallbackToLocal / totalTasks) : 0.0;

        System.out.printf(
            "[ASCSF-Collab] Scale=%-6s Trial=%2d | " +
            "Local=%4d Collaborated=%4d (%.1f%%) Fallback=%4d (%.1f%%) " +
            "HostedForOthers=%4d Declined=%4d Rejected=%4d%n",
            scale, trial, localExecutions, collaboratedOut, collabRate,
            fallbackToLocal, fallbackRate, hostedForOthers, offersDeclined, admissionsRejected);

        writeCollaborationStatsToCSV(scale, trial, totalTasks, localExecutions,
            collaboratedOut, fallbackToLocal, hostedForOthers, offersDeclined, admissionsRejected);
    }

    private static void writeCollaborationStatsToCSV(
            String scale, int trial,
            int totalTasks, int localExecutions, int collaboratedOut,
            int fallbackToLocal, int hostedForOthers,
            int offersDeclined, int admissionsRejected) {
        try (FileWriter fw = new FileWriter(
                SimulationConfig.COLLABORATION_STATS_FILE, true);
             PrintWriter pw = new PrintWriter(fw)) {

            pw.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d%n",
                scale, trial, totalTasks, localExecutions, collaboratedOut,
                fallbackToLocal, hostedForOthers, offersDeclined, admissionsRejected);

        } catch (IOException e) {
            System.out.println("[Metrics] Collaboration CSV write error: " + e.getMessage());
        }
    }

    public static void writeCollaborationCSVHeader() {
        try (FileWriter fw = new FileWriter(
                SimulationConfig.COLLABORATION_STATS_FILE, false);
             PrintWriter pw = new PrintWriter(fw)) {

            pw.println(
                "Scale,Trial,TotalTasks,LocalExecutions,CollaboratedOut," +
                "FallbackToLocal,HostedForOthers,OffersDeclined,AdmissionsRejected");

        } catch (IOException e) {
            System.out.println("[Metrics] Collaboration header write error: " + e.getMessage());
        }
    }

    // ── Per-provider metrics (host-impact analysis) ───────────────────────────
    public static class ProviderStats {
        public final double[] dsMeanFinish, dsP95Finish;   // finish time of each provider's OWN delay-sensitive tasks
        public final double[] energyKWh, carbonKg;         // energy / carbon of work EXECUTED at each DC
        public final int[]    tasksExecuted;               // tasks executed at each DC (own + hosted)
        ProviderStats(int n) {
            dsMeanFinish = new double[n]; dsP95Finish = new double[n];
            energyKWh = new double[n]; carbonKg = new double[n]; tasksExecuted = new int[n];
        }
    }

    /**
     * submitted = the cloudlet list in the SAME order used to fill dcAssignments;
     * finished  = broker.getCloudletFinishedList(). Matching is by object identity.
     * Owner of cloudlet i is DC (i % numDCs); it is delay-sensitive if its index inside the
     * owner's batch (i / numDCs) is even -- the convention CloudProvider.isDelaySensitive uses.
     * (Meaningful for ASCSF and the decentralized baselines, which assign ownership round-robin.)
     */
    public ProviderStats computeProviderStats(List<Cloudlet> submitted, List<Cloudlet> finished) {
        int n = numDCs;
        ProviderStats ps = new ProviderStats(n);
        IdentityHashMap<Cloudlet, Integer> idx = new IdentityHashMap<>();
        for (int i = 0; i < submitted.size(); i++) idx.put(submitted.get(i), i);

        List<List<Double>> ds = new ArrayList<>();
        for (int d = 0; d < n; d++) ds.add(new ArrayList<>());

        for (Cloudlet cl : finished) {
            Integer ii = idx.get(cl);
            if (ii == null || dcAssignments == null || ii >= dcAssignments.length) continue;
            int i = ii;
            int execDc = dcAssignments[i] % n;
            int owner  = i % n;
            boolean isDS = ((i / n) % 2 == 0);

            double execTimeSec = cl.getActualCpuTime();
            double lengthMI = cl.getLength();
            double mips = (execTimeSec > 0) ? lengthMI / execTimeSec : 1000.0;
            int tier = getVmTier(mips);
            double util = Math.min(1.0, (execTimeSec > 0) ? lengthMI / (mips * execTimeSec) : 1.0);
            double pIdle = SimulationConfig.VM_POWER_IDLE[tier];
            double pPeak = SimulationConfig.VM_POWER_PEAK[tier];
            double e = ((pIdle + (pPeak - pIdle) * util) * execTimeSec) / 3_600_000.0;

            ps.energyKWh[execDc] += e;
            ps.carbonKg[execDc]  += e * carbonModel.getCarbonIntensity(execDc, cl.getFinishTime());
            ps.tasksExecuted[execDc]++;
            if (isDS) ds.get(owner).add(cl.getFinishTime());
        }
        for (int d = 0; d < n; d++) {
            List<Double> v = ds.get(d);
            if (v.isEmpty()) continue;
            Collections.sort(v);
            double s = 0; for (double x : v) s += x;
            ps.dsMeanFinish[d] = s / v.size();
            ps.dsP95Finish[d]  = v.get((int) Math.max(0, Math.ceil(0.95 * v.size()) - 1));
        }
        return ps;
    }

    // ── Inner result class ────────────────────────────────────────────────────
    /**
     * Simple data container for one simulation result.
     * Passed between SimulationRunner and MetricsCollector.
     */
    public static class SimResult {

        public final String schedulerName;
        public final String scale;
        public final int    trial;
        public final double makespan;
        public final double energyKWh;
        public final double carbonKgCO2;
        public final double renewablePercent;

        public SimResult(String schedulerName, String scale,
                         int trial, double makespan,
                         double energyKWh, double carbonKgCO2,
                         double renewablePercent) {
            this.schedulerName   = schedulerName;
            this.scale           = scale;
            this.trial           = trial;
            this.makespan        = makespan;
            this.energyKWh       = energyKWh;
            this.carbonKgCO2     = carbonKgCO2;
            this.renewablePercent = renewablePercent;
        }
    }
}