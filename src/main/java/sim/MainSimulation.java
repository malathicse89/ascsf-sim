package sim;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;
import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import sim.model.CarbonModel;
import sim.model.RenewableModel;
import sim.scheduler.CASPERScheduler;
import sim.scheduler.EASScheduler;
import sim.scheduler.MinMinScheduler;
import sim.scheduler.CNSGA2Scheduler;
import sim.scheduler.RoundRobinScheduler;
import sim.util.MetricsCollector;
import sim.util.MetricsCollector.SimResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

/**
 * Main simulation runner for the ASCSF paper.
 *
 * Runs 5 schedulers x 3 workload scales x 30 trials = 450 runs.
 * Each run produces 4 metrics: makespan, energy, carbon, renewable%.
 * Results written to simulation_results.csv for paper tables.
 *
 * Schedulers compared:
 *   1. Round Robin  (RR)      — no sustainability awareness
 *   2. Min-Min                — makespan-only greedy
 *   3. EAS                    — energy-aware, no carbon
 *   4. CASPER                 — reactive carbon+latency
 *   5. C-NSGA-II              — centralized baseline: predictive multi-objective
 *
 * Workload scales:
 *   Small  = 500  tasks
 *   Medium = 1000 tasks
 *   Large  = 2000 tasks
 */

public class MainSimulation {

    // Shared models loaded once, reused across all runs
    // ── Run configuration ────────────────────────────────────────────────────
    // RUN_MODE: "MAIN"        = the 6-scheduler comparison -> simulation_results.csv
    //           "COMPARE"     = ASCSF (8 tau_host values) + GREEDY-BLIND + GREEDY-CAP -> decentralized_final.csv
    //           "DSOFF"       = ASCSF with deadline-guarded DS offloading (tau_host x deadline factor) -> dsoffload_final.csv
    //           "DIAG"        = diagnostic: GREEDY-ALL only (DS tasks also offloadable) -> diag_greedy_all.csv
    //           "SCALE"       = scalability study: 3 / 5 / 10 providers -> scalability_final.csv
    //           "SCALE_TAU"   = tau_host sweep at 5 and 10 providers (with/without DS offloading) -> scalability_tau.csv
    //           "ROBUST"      = robustness study at 3 providers (noise, tau_offload, R_max, skewed ownership) -> robustness.csv
    //           "TAU_OFFLOAD" = the old ASCSF tau_offload sweep
    // QUICK_TEST: true = Small scale, trial 1 only (use this first to check the [CHECK] lines).
    //             NOTE: MAIN mode overwrites simulation_results.csv -> back up old results first.
    //           "NOCOLLAB"    = local-only ablation (no task seeks collaboration), paired noise -> nocollab_paired.csv (90 runs)
    //           "FINAL"       = runs COMPARE, DSOFF, SCALE and SCALE_TAU in sequence with per-run noise reseeding
    //                           (every scheduler in a trial sees the same forecast-noise stream) -> *_paired.csv files
    //           "REVIEW"      = reviewer-requested studies (see REVIEW_SET): CENTRAL (paired centralized baselines), FRESH (held-out trials 31-60),
    //                           FIXEDK (fixed candidate-set size at N=10), LOSS (message loss + lease expiry) -> review_<set>_paired.csv
    private static String  RUN_MODE   = "REVIEW";
    private static final String REVIEW_SET = "TRACESWEEP"; // ALL | CENTRAL | FRESH | FIXEDK | LOSS | TRACE | TRACESWEEP (trace-driven: real Azure workload + real 2025 SE/DE/PL grid data)
    private static boolean PAIRED     = false;      // set automatically by FINAL
    private static String out(String base) { return base + (PAIRED ? "_paired" : "") + ".csv"; }
    private static final boolean QUICK_TEST = false;

    private static int trialsFor(int configured) { return QUICK_TEST ? 1 : configured; }
    private static int scalesFor(int configured) { return QUICK_TEST ? 1 : configured; }

    private static CarbonModel    carbonModel;
    private static RenewableModel renewableModel;
    private static Random         rng = new Random(42);

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) {
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.WARN);

        System.out.println("========================================");
        System.out.println(" ASCSF Simulation Starting");
        System.out.println(" RUN_MODE   : " + RUN_MODE + (QUICK_TEST ? "  (QUICK_TEST: Small, trial 1)" : ""));
        System.out.println(" Scales     : Small(500), Medium(1000), Large(2000)");
        System.out.println(" Trials     : " + trialsFor(SimulationConfig.NUM_TRIALS));
        System.out.println("========================================\n");

        // Load carbon and renewable models once
        carbonModel    = new CarbonModel("carbon.csv");
        renewableModel = new RenewableModel("renewable.csv");

        if (RUN_MODE.equals("NOCOLLAB")) {
            PAIRED = true;
            runDecentralizedComparison();
            return;
        }
        if (RUN_MODE.equals("FINAL")) {
            PAIRED = true;
            for (String m : new String[]{"COMPARE", "DSOFF", "SCALE", "SCALE_TAU"}) {
                RUN_MODE = m;
                System.out.println("\n######## FINAL: " + m + " ########");
                if (m.startsWith("SCALE")) runScalabilityStudy(); else runDecentralizedComparison();
            }
            RUN_MODE = "FINAL";
            System.out.println("\nALL FINAL RUNS COMPLETE.");
            return;
        }
        if (RUN_MODE.equals("REVIEW")) {
            PAIRED = true;
            runReviewStudies();
            return;
        }
        if (RUN_MODE.equals("ROBUST")) {
            runRobustnessStudy();
            return;
        }
        if (RUN_MODE.equals("SCALE") || RUN_MODE.equals("SCALE_TAU")) {
            runScalabilityStudy();
            return;
        }
        if (RUN_MODE.equals("COMPARE") || RUN_MODE.equals("DIAG") || RUN_MODE.equals("DSOFF")) {
            runDecentralizedComparison();
            return;
        }
        if (RUN_MODE.equals("TAU_OFFLOAD")) {
            runAscsfTauOffloadSweep();
            return;
        }

        // ── MAIN: 6 schedulers x 3 scales x NUM_TRIALS ──
        MetricsCollector.writeCSVHeader();

        int[][] scales = {
            {SimulationConfig.TASKS_SMALL,  0},
            {SimulationConfig.TASKS_MEDIUM, 1},
            {SimulationConfig.TASKS_LARGE,  2}
        };
        String[] scaleNames = {"Small", "Medium", "Large"};

        for (int s = 0; s < scalesFor(scales.length); s++) {
            int    numTasks  = scales[s][0];
            String scaleName = scaleNames[s];

            System.out.println("\n--- Scale: " + scaleName
                + " (" + numTasks + " tasks) ---");

            for (int trial = 1; trial <= trialsFor(SimulationConfig.NUM_TRIALS); trial++) {
                runOneTrial(scaleName, numTasks, trial);
            }
        }

        System.out.println("\n========================================");
        System.out.println(" Simulation Complete.");
        System.out.println(" Results saved to: " + SimulationConfig.RESULTS_FILE);
        System.out.println("========================================");
    }

 // ── ASCSF tau_offload sensitivity sweep ──────────────────────────────
    /**
     * Runs ONLY ASCSF, sweeping tau_offload across SimulationConfig.ASCSF_TAU_OFFLOAD_SWEEP,
     * using a reduced trial count (ASCSF_SWEEP_TRIALS). Does not touch the main 6-scheduler
     * pipeline or its output files -- writes to a separate CSV with an extra TauOffload column
     * plus the ASCSF-specific collaboration counters, so each row is self-contained.
     */
    private static void runAscsfTauOffloadSweep() {
        System.out.println("\n========================================");
        System.out.println(" ASCSF tau_offload Sensitivity Sweep");
        System.out.println(" Values: " + java.util.Arrays.toString(SimulationConfig.ASCSF_TAU_OFFLOAD_SWEEP));
        System.out.println(" Trials per point: " + SimulationConfig.ASCSF_SWEEP_TRIALS);
        System.out.println("========================================\n");

        writeAscsfSweepHeader();

        int[][] scales = {
            {SimulationConfig.TASKS_SMALL,  0},
            {SimulationConfig.TASKS_MEDIUM, 1},
            {SimulationConfig.TASKS_LARGE,  2}
        };
        String[] scaleNames = {"Small", "Medium", "Large"};

        for (double tauOffload : SimulationConfig.ASCSF_TAU_OFFLOAD_SWEEP) {
            System.out.printf("--- tau_offload = %.2f ---%n", tauOffload);

            for (int s = 0; s < scalesFor(scales.length); s++) {
                int numTasks = scales[s][0];
                String scaleName = scaleNames[s];

                for (int trial = 1; trial <= trialsFor(SimulationConfig.ASCSF_SWEEP_TRIALS); trial++) {

                    double simStartTimeSec = (trial - 1) * (86400.0 / 30.0);
                    int[] taskLengths = generateTaskLengths(numTasks, trial);

                    CloudSim simulation = new CloudSim();
                    List<Datacenter> datacenters = new ArrayList<>();
                    for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++) {
                        datacenters.add(createDatacenter(simulation, dc));
                    }
                    DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
                    List<Vm> vmList = createVms(numTasks);
                    broker.submitVmList(vmList);
            pinVmsToDatacenters(broker, datacenters, vmList);
                    Map<Long, Boolean> taskTypeMap = new HashMap<>();
                    List<Cloudlet> cloudlets = createCloudlets(numTasks, taskLengths, taskTypeMap);

                    sim.ascsf.ASCSFScheduler ascsf =
                        new sim.ascsf.ASCSFScheduler(carbonModel, renewableModel);
                    ascsf.setCurrentTimeSec(simStartTimeSec);
                    ascsf.setTauOffload(tauOffload);
                    ascsf.setTauHost(SimulationConfig.ASCSF_TAU_HOST);
                    ascsf.setRMax(SimulationConfig.ASCSF_R_MAX);
                    ascsf.scheduleAndBind(broker, cloudlets, vmList);

                    int[] dcAssignments = new int[numTasks];
                    for (int i = 0; i < cloudlets.size(); i++) {
                        Vm assignedVm = cloudlets.get(i).getVm();
                        int vmIdx = vmList.indexOf(assignedVm);
                        dcAssignments[i] = (vmIdx >= 0)
                            ? vmIdx / SimulationConfig.VMS_PER_DC
                            : i % SimulationConfig.NUM_DATACENTERS;
                    }

                    broker.submitCloudletList(cloudlets);
                    simulation.start();

                    List<Cloudlet> finished = broker.getCloudletFinishedList();
                    checkFinished("ASCSF", cloudlets, finished, broker);
                    MetricsCollector collector = new MetricsCollector(carbonModel, renewableModel);
                    collector.setDcAssignments(dcAssignments);
            collector.setSubmittedCloudlets(cloudlets);
                    MetricsCollector.SimResult result =
                        collector.report("ASCSF", scaleName, trial, finished);

                    sim.ascsf.ASCSFScheduler.CollaborationStats stats = ascsf.aggregateStats();

                    writeAscsfSweepRow(tauOffload, scaleName, trial, result, stats);
                }
            }
        }

        System.out.println("\nSweep complete. Results saved to: "
            + SimulationConfig.ASCSF_SWEEP_RESULTS_FILE);
    }

    private static void writeAscsfSweepHeader() {
        try (FileWriter fw = new FileWriter(SimulationConfig.ASCSF_SWEEP_RESULTS_FILE, false);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(
                "TauOffload,Scale,Trial,Makespan_s,Energy_kWh,Carbon_kgCO2e,Renewable_pct," +
                "TotalTasks,LocalExecutions,CollaboratedOut,FallbackToLocal,HostedForOthers," +
                "OffersDeclined,AdmissionsRejected");
        } catch (IOException e) {
            System.out.println("[Sweep] Header write error: " + e.getMessage());
        }
    }

    private static void writeAscsfSweepRow(
            double tauOffload, String scale, int trial,
            MetricsCollector.SimResult r,
            sim.ascsf.ASCSFScheduler.CollaborationStats stats) {
        try (FileWriter fw = new FileWriter(SimulationConfig.ASCSF_SWEEP_RESULTS_FILE, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.printf("%.2f,%s,%d,%.4f,%.6f,%.6f,%.4f,%d,%d,%d,%d,%d,%d,%d%n",
                tauOffload, scale, trial,
                r.makespan, r.energyKWh, r.carbonKgCO2, r.renewablePercent,
                stats.totalTasks, stats.localExecutions, stats.collaboratedOut,
                stats.fallbackToLocal, stats.hostedForOthers,
                stats.offersDeclined, stats.admissionsRejected);
        } catch (IOException e) {
            System.out.println("[Sweep] Row write error: " + e.getMessage());
        }
    }

    // ── Run one trial for all 5 schedulers ───────────────────────────────────
    /**
     * Each trial creates a fresh CloudSim environment.
     * All 5 schedulers see the same tasks and VMs.
     * Simulation time offset varies by trial to sample
     * different points in the 24-hour carbon/renewable cycle.
     */
    private static void runOneTrial(String scaleName,
                                     int numTasks,
                                     int trial) {

        // Vary start time across trials to sample different
        // points in the 24-hour renewable/carbon cycle
        // Trial 1 starts at 00:00, trial 2 at 00:48, etc.
        double simStartTimeSec = (trial - 1) * (86400.0 / 30.0);

        // Task lengths — same seed per trial for fairness
        int[] taskLengths = generateTaskLengths(numTasks, trial);

        // DC assignments for metrics reporting
        // Will be set by each scheduler before reporting
        int[] dcAssignments = new int[numTasks];

        String[] schedulerNames = {
            "RR", "MinMin", "EAS", "CASPER", "C-NSGA-II", "ASCSF"
        };

        for (String schedulerName : schedulerNames) {

            // Fresh CloudSim instance per scheduler per trial
            CloudSim simulation = new CloudSim();
           // simulation.terminateAt(86400);

            // Create 3 datacenters
            List<Datacenter> datacenters = new ArrayList<>();
            for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++) {
                datacenters.add(createDatacenter(simulation, dc));
            }

            // Create broker
            DatacenterBrokerSimple broker =
                new DatacenterBrokerSimple(simulation);

            // Create VMs — distributed across 3 DCs
            List<Vm> vmList = createVms(numTasks);
            broker.submitVmList(vmList);
            pinVmsToDatacenters(broker, datacenters, vmList);

            // Create cloudlets
            Map<Long, Boolean> taskTypeMap = new HashMap<>();
            List<Cloudlet> cloudlets =
                createCloudlets(numTasks, taskLengths, taskTypeMap);

            // Apply scheduler
            applyScheduler(schedulerName, broker, cloudlets,
                           vmList, taskTypeMap,
                           simStartTimeSec, dcAssignments);

            // Submit cloudlets after binding
            broker.submitCloudletList(cloudlets);

            // Run simulation
            simulation.start();

            // Collect and report metrics
            List<Cloudlet> finished =
                broker.getCloudletFinishedList();
            checkFinished(schedulerName, cloudlets, finished, broker);

            MetricsCollector collector =
                new MetricsCollector(carbonModel, renewableModel);
            collector.setDcAssignments(dcAssignments);
            collector.setSubmittedCloudlets(cloudlets);

            SimResult result = collector.report(
                schedulerName, scaleName, trial, finished);

            MetricsCollector.writeResultToCSV(result);
        }
    }

    // ── Apply selected scheduler ──────────────────────────────────────────────
    private static void applyScheduler(
            String name,
            DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms,
            Map<Long, Boolean> taskTypeMap,
            double currentTimeSec,
            int[] dcAssignments) {

        int numDCs   = SimulationConfig.NUM_DATACENTERS;
        int vmsPerDC = Math.max(1, vms.size() / numDCs);
        int vmsPerDCFixed;
        switch (name) {

            case "RR":
                RoundRobinScheduler.scheduleAndBind(
                    broker, cloudlets, vms);
                // RR distributes round-robin — fill DC assignments
                vmsPerDCFixed = SimulationConfig.VMS_PER_DC;
                for (int i = 0; i < cloudlets.size(); i++) {
                    Vm assignedVm = cloudlets.get(i).getVm();
                    int vmIdx = vms.indexOf(assignedVm);
                    dcAssignments[i] = (vmIdx >= 0)
                        ? vmIdx / SimulationConfig.VMS_PER_DC
                        : i % numDCs;
                }
                break;

            case "MinMin":
                MinMinScheduler.scheduleAndBind(
                    broker, cloudlets, vms);
                vmsPerDCFixed = SimulationConfig.VMS_PER_DC;
                for (int i = 0; i < cloudlets.size(); i++) {
                    Vm assignedVm = cloudlets.get(i).getVm();
                    int vmIdx = vms.indexOf(assignedVm);
                    dcAssignments[i] = (vmIdx >= 0)
                        ? vmIdx / SimulationConfig.VMS_PER_DC
                        : i % numDCs;
                }
                break;

            case "EAS":
                EASScheduler.scheduleAndBind(
                    broker, cloudlets, vms);
                vmsPerDCFixed = SimulationConfig.VMS_PER_DC;
                for (int i = 0; i < cloudlets.size(); i++) {
                    Vm assignedVm = cloudlets.get(i).getVm();
                    int vmIdx = vms.indexOf(assignedVm);
                    dcAssignments[i] = (vmIdx >= 0)
                        ? vmIdx / SimulationConfig.VMS_PER_DC
                        : i % numDCs;
                }
                break;

            case "CASPER":
                CASPERScheduler casper =
                    new CASPERScheduler(carbonModel);
                casper.scheduleAndBind(broker, cloudlets,
                    vms, numDCs, currentTimeSec);
                for (int i = 0; i < cloudlets.size(); i++) {
                    Vm assignedVm = cloudlets.get(i).getVm();
                    int vmIdx = vms.indexOf(assignedVm);
                    dcAssignments[i] = (vmIdx >= 0)
                        ? vmIdx / SimulationConfig.VMS_PER_DC
                        : i % numDCs;
                }
                break;

            case "C-NSGA-II":
                CNSGA2Scheduler cnsga2 = new CNSGA2Scheduler(
                    carbonModel, renewableModel, taskTypeMap);
                cnsga2.setCurrentTimeSec(currentTimeSec);
                cnsga2.scheduleAndBind(broker, cloudlets, vms);
                // C-NSGA-II DC assignments set inside scheduler
                // Use round-DC distribution as approximation
                for (int i = 0; i < cloudlets.size(); i++) {
                    Vm assignedVm = cloudlets.get(i).getVm();
                    int vmIdx = vms.indexOf(assignedVm);
                    dcAssignments[i] = (vmIdx >= 0)
                        ? vmIdx / SimulationConfig.VMS_PER_DC
                        : i % numDCs;
                }
                break;
            case "ASCSF":
                sim.ascsf.ASCSFScheduler ascsf =
                    new sim.ascsf.ASCSFScheduler(carbonModel, renewableModel);
                ascsf.setCurrentTimeSec(currentTimeSec);
                ascsf.setTauOffload(SimulationConfig.ASCSF_TAU_OFFLOAD);
                ascsf.setTauHost(SimulationConfig.ASCSF_TAU_HOST);
                ascsf.setRMax(SimulationConfig.ASCSF_R_MAX);
                ascsf.scheduleAndBind(broker, cloudlets, vms);
                for (int i = 0; i < cloudlets.size(); i++) {
                    Vm assignedVm = cloudlets.get(i).getVm();
                    int vmIdx = vms.indexOf(assignedVm);
                    dcAssignments[i] = (vmIdx >= 0)
                        ? vmIdx / SimulationConfig.VMS_PER_DC
                        : i % numDCs;
                }
                break;

            default:
                System.out.println("[Main] Unknown scheduler: " + name);
        }
    }

    // ── Helpers added for validity checks ────────────────────────────────────
    /** Makes the physical DC of every VM equal to its logical DC (vmIndex / VMS_PER_DC). */
    private static void pinVmsToDatacenters(DatacenterBrokerSimple broker,
                                            List<Datacenter> dcs, List<Vm> vms) {
        broker.setDatacenterMapper((lastDc, vm) ->
            dcs.get(Math.min(vms.indexOf(vm) / SimulationConfig.VMS_PER_DC, dcs.size() - 1)));
    }

    /** Prints one line per run: all submitted tasks must finish and no VM may fail. */
    private static void checkFinished(String name, List<Cloudlet> submitted,
                                      List<Cloudlet> finished, DatacenterBrokerSimple broker) {
        int failedVms = broker.getVmFailedList().size();
        boolean ok = (finished.size() == submitted.size()) && failedVms == 0;
        if (ok && !QUICK_TEST) return;      // full runs: stay quiet unless something is wrong
        int same = 0;
        for (int i = 0; i < finished.size() && i < submitted.size(); i++)
            if (finished.get(i) == submitted.get(i)) same++;
        System.out.println("[CHECK] " + name + ": submitted=" + submitted.size()
            + " finished=" + finished.size() + " vmFailed=" + failedVms
            + " vmCreated=" + broker.getVmCreatedList().size()
            + " | finished-order==submit-order for " + same + " tasks"
            + (ok ? "  OK" : "  <<< PROBLEM"));
    }

    // ── Decentralized baselines + tau_host sweep (single consistent run) ─────
    private static void runDecentralizedComparison() {
        final String OUT = RUN_MODE.equals("NOCOLLAB") ? out(QUICK_TEST ? "nocollab_quicktest" : "nocollab")
            : RUN_MODE.equals("DIAG") ? out("diag_greedy_all")
            : RUN_MODE.equals("DSOFF") ? out(QUICK_TEST ? "dsoffload_quicktest" : "dsoffload_final")
            : out(QUICK_TEST ? "decentralized_quicktest" : "decentralized_final");
        try (FileWriter fw = new FileWriter(OUT, false); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("Scheduler,TauHost,Scale,Trial,Makespan_s,Energy_kWh,Carbon_kgCO2e,Renewable_pct,"
                + "TotalTasks,LocalExecutions,CollaboratedOut,FallbackToLocal,HostedForOthers,"
                + "OffersDeclined,AdmissionsRejected,Hosted_DC0,Hosted_DC1,Hosted_DC2,"
                + "DSMean_DC0,DSMean_DC1,DSMean_DC2,DSP95_DC0,DSP95_DC1,DSP95_DC2,"
                + "Energy_DC0,Energy_DC1,Energy_DC2,Carbon_DC0,Carbon_DC1,Carbon_DC2,"
                + "Exec_DC0,Exec_DC1,Exec_DC2,Finished,DsFactor");
        } catch (IOException e) { System.out.println("[Compare] header error: " + e.getMessage()); }

        int[] sizes = {SimulationConfig.TASKS_SMALL, SimulationConfig.TASKS_MEDIUM, SimulationConfig.TASKS_LARGE};
        String[] scaleNames = {"Small", "Medium", "Large"};
        double[] tauList = {0.05, 0.00, -0.05, -0.10, -0.15, -0.20, -0.30, -0.70};

        // configs: label, tauHost (NaN = n/a), kind (0 = ASCSF, 1 = greedy blind, 2 = greedy capacity-aware)
        List<Object[]> configs = new ArrayList<>();
        if (RUN_MODE.equals("NOCOLLAB")) {
            configs.add(new Object[]{"ASCSF-NOCOLLAB", 0.05, 5});     // tau_offload = -1: no task ever seeks collaboration
        } else if (RUN_MODE.equals("DIAG")) {
            configs.add(new Object[]{"GREEDY-ALL", Double.NaN, 3});   // diagnostic only
        } else if (RUN_MODE.equals("DSOFF")) {
            double[] dsTau = {0.05, -0.10, -0.15, -0.20, -0.30};
            double[] dsFac = {1.0, 1.5};
            for (double t : dsTau) for (double f : dsFac)
                configs.add(new Object[]{"ASCSF-DS", t, 4, f});
        } else {
            for (double t : tauList) configs.add(new Object[]{"ASCSF", t, 0});
            configs.add(new Object[]{"GREEDY-BLIND", Double.NaN, 1});
            configs.add(new Object[]{"GREEDY-CAP",   Double.NaN, 2});
        }

        for (int s = 0; s < scalesFor(sizes.length); s++) {
            int numTasks = sizes[s];
            for (int trial = 1; trial <= trialsFor(SimulationConfig.ASCSF_SWEEP_TRIALS); trial++) {
                double simStartTimeSec = (trial - 1) * (86400.0 / 30.0);
                int[] taskLengths = generateTaskLengths(numTasks, trial);

                for (Object[] cfg : configs) {
                    String name = (String) cfg[0]; double tau = (Double) cfg[1]; int kind = (Integer) cfg[2];
                    double dsFactor = (cfg.length > 3) ? (Double) cfg[3] : Double.NaN;

                    if (PAIRED) { carbonModel.reseed(7919L * trial + 13); renewableModel.reseed(104729L * trial + 29); }
                    CloudSim simulation = new CloudSim();
                    List<Datacenter> datacenters = new ArrayList<>();
                    for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++)
                        datacenters.add(createDatacenter(simulation, dc));
                    DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
                    List<Vm> vmList = createVms(numTasks);
                    broker.submitVmList(vmList);
                    pinVmsToDatacenters(broker, datacenters, vmList);
                    Map<Long, Boolean> taskTypeMap = new HashMap<>();
                    List<Cloudlet> cloudlets = createCloudlets(numTasks, taskLengths, taskTypeMap);

                    sim.ascsf.ASCSFScheduler.CollaborationStats stats;
                    List<sim.ascsf.CloudProvider> provs;
                    if (kind == 0 || kind == 4 || kind == 5) {
                        sim.ascsf.ASCSFScheduler a = new sim.ascsf.ASCSFScheduler(carbonModel, renewableModel);
                        a.setCurrentTimeSec(simStartTimeSec);
                        a.setTauOffload(kind == 5 ? -1.0 : SimulationConfig.ASCSF_TAU_OFFLOAD);
                        a.setTauHost(tau);
                        a.setRMax(SimulationConfig.ASCSF_R_MAX);
                        if (kind == 4) { a.setAllowDsOffload(true); a.setDsDeadlineFactor(dsFactor); }
                        a.scheduleAndBind(broker, cloudlets, vmList);
                        stats = a.aggregateStats();  provs = a.lastRunProviders;
                    } else {
                        sim.ascsf.DecentralizedPeerScheduler g = new sim.ascsf.DecentralizedPeerScheduler(
                            carbonModel, renewableModel,
                            kind == 1 ? sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_BLIND
                          : kind == 2 ? sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_CAP
                                      : sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_ALL);
                        g.setCurrentTimeSec(simStartTimeSec);
                        g.scheduleAndBind(broker, cloudlets, vmList);
                        stats = g.aggregateStats();  provs = g.lastRunProviders;
                    }

                    int[] dcAssignments = new int[numTasks];
                    for (int i = 0; i < cloudlets.size(); i++) {
                        int vmIdx = vmList.indexOf(cloudlets.get(i).getVm());
                        dcAssignments[i] = (vmIdx >= 0) ? vmIdx / SimulationConfig.VMS_PER_DC
                                                        : i % SimulationConfig.NUM_DATACENTERS;
                    }
                    broker.submitCloudletList(cloudlets);
                    simulation.start();

                    List<Cloudlet> finished = broker.getCloudletFinishedList();
                    checkFinished(name + (Double.isNaN(tau) ? "" : "(" + tau + ")"), cloudlets, finished, broker);

                    MetricsCollector collector = new MetricsCollector(carbonModel, renewableModel);
                    collector.setDcAssignments(dcAssignments);
                    collector.setSubmittedCloudlets(cloudlets);
                    MetricsCollector.SimResult r = collector.report(name, scaleNames[s], trial, finished);
                    MetricsCollector.ProviderStats ps = collector.computeProviderStats(cloudlets, finished);

                    int[] hosted = new int[3];
                    for (sim.ascsf.CloudProvider p : provs) hosted[p.globalDcIndex] = p.statHostedForOthers;

                    try (FileWriter fw = new FileWriter(OUT, true); PrintWriter pw = new PrintWriter(fw)) {
                        pw.printf(java.util.Locale.US,
                            "%s,%s,%s,%d,%.4f,%.6f,%.6f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,"
                            + "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d,%d,%s%n",
                            name, Double.isNaN(tau) ? "NA" : String.format(java.util.Locale.US, "%.2f", tau),
                            scaleNames[s], trial, r.makespan, r.energyKWh, r.carbonKgCO2, r.renewablePercent,
                            stats.totalTasks, stats.localExecutions, stats.collaboratedOut, stats.fallbackToLocal,
                            stats.hostedForOthers, stats.offersDeclined, stats.admissionsRejected,
                            hosted[0], hosted[1], hosted[2],
                            ps.dsMeanFinish[0], ps.dsMeanFinish[1], ps.dsMeanFinish[2],
                            ps.dsP95Finish[0],  ps.dsP95Finish[1],  ps.dsP95Finish[2],
                            ps.energyKWh[0], ps.energyKWh[1], ps.energyKWh[2],
                            ps.carbonKg[0],  ps.carbonKg[1],  ps.carbonKg[2],
                            ps.tasksExecuted[0], ps.tasksExecuted[1], ps.tasksExecuted[2],
                            finished.size(),
                            Double.isNaN(dsFactor) ? "NA" : String.format(java.util.Locale.US, "%.2f", dsFactor));
                    } catch (IOException e) { System.out.println("[Compare] row error: " + e.getMessage()); }
                }
            }
            System.out.println("Finished scale " + scaleNames[s]);
        }
        System.out.println("Done -> " + OUT);
    }

    // ── Scalability study: 3 / 5 / 10 providers, constant total capacity ─────
    private static void runScalabilityStudy() {
        final boolean TAU_MODE = RUN_MODE.equals("SCALE_TAU");
        final String OUT = TAU_MODE ? out(QUICK_TEST ? "scalability_tau_quicktest" : "scalability_tau")
                                    : out(QUICK_TEST ? "scalability_quicktest" : "scalability_final");
        try (FileWriter fw = new FileWriter(OUT, false); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("Scheduler,TauHost,DsFactor,Providers,Scale,Trial,Makespan_s,Energy_kWh,Carbon_kgCO2e,Renewable_pct,"
                + "TotalTasks,LocalExecutions,CollaboratedOut,FallbackToLocal,HostedForOthers,"
                + "OffersDeclined,AdmissionsRejected,DiscoveryMsgs,OffersReceived,AdmissionAttempts,Finished");
        } catch (IOException e) { System.out.println("[Scale] header error: " + e.getMessage()); }

        int[] sizes = {SimulationConfig.TASKS_SMALL, SimulationConfig.TASKS_MEDIUM, SimulationConfig.TASKS_LARGE};
        String[] scaleNames = {"Small", "Medium", "Large"};
        int[] providerCounts = TAU_MODE ? new int[]{5, 10} : new int[]{3, 5, 10};

        // label, tauHost (NaN = n/a), kind (0 ASCSF, 1 greedy blind, 2 greedy cap, 4 ASCSF-DS), dsFactor
        List<Object[]> configs = new ArrayList<>();
        if (TAU_MODE) {
            double[] taus = {-0.10, -0.15, -0.20, -0.30};
            for (double t : taus) configs.add(new Object[]{"ASCSF",    t, 0, Double.NaN});
            for (double t : taus) configs.add(new Object[]{"ASCSF-DS", t, 4, 1.0});
        } else {
            configs.add(new Object[]{"ASCSF",        0.05,         0, Double.NaN});
            configs.add(new Object[]{"ASCSF",       -0.30,         0, Double.NaN});
            configs.add(new Object[]{"ASCSF-DS",    -0.30,         4, 1.0});
        }
        configs.add(new Object[]{"GREEDY-BLIND", Double.NaN,   1, Double.NaN});
        configs.add(new Object[]{"GREEDY-CAP",   Double.NaN,   2, Double.NaN});

        for (int n : providerCounts) {
            SimulationConfig.setProviders(n);
            System.out.println("\n=== Providers: " + n + "  (VMs per provider: " + SimulationConfig.VMS_PER_DC + ") ===");
            for (int s = 0; s < scalesFor(sizes.length); s++) {
                int numTasks = sizes[s];
                for (int trial = 1; trial <= trialsFor(SimulationConfig.ASCSF_SWEEP_TRIALS); trial++) {
                    double simStartTimeSec = (trial - 1) * (86400.0 / 30.0);
                    int[] taskLengths = generateTaskLengths(numTasks, trial);

                    for (Object[] cfg : configs) {
                        String name = (String) cfg[0]; double tau = (Double) cfg[1];
                        int kind = (Integer) cfg[2]; double dsFactor = (Double) cfg[3];

                        if (PAIRED) { carbonModel.reseed(7919L * trial + 13); renewableModel.reseed(104729L * trial + 29); }
                        CloudSim simulation = new CloudSim();
                        List<Datacenter> datacenters = new ArrayList<>();
                        for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++)
                            datacenters.add(createDatacenter(simulation, dc));
                        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
                        List<Vm> vmList = createVms(numTasks);
                        broker.submitVmList(vmList);
                        pinVmsToDatacenters(broker, datacenters, vmList);
                        Map<Long, Boolean> taskTypeMap = new HashMap<>();
                        List<Cloudlet> cloudlets = createCloudlets(numTasks, taskLengths, taskTypeMap);

                        sim.ascsf.ASCSFScheduler.CollaborationStats stats;
                        long dMsgs = -1, oRecv = -1, aAtt = -1;
                        if (kind == 0 || kind == 4) {
                            sim.ascsf.ASCSFScheduler a = new sim.ascsf.ASCSFScheduler(carbonModel, renewableModel);
                            a.setCurrentTimeSec(simStartTimeSec);
                            a.setTauOffload(SimulationConfig.ASCSF_TAU_OFFLOAD);
                            a.setTauHost(tau);
                            a.setRMax(SimulationConfig.ASCSF_R_MAX);
                            if (kind == 4) { a.setAllowDsOffload(true); a.setDsDeadlineFactor(dsFactor); }
                            a.scheduleAndBind(broker, cloudlets, vmList);
                            stats = a.aggregateStats();
                            dMsgs = a.lastProtocol.discoveryRequests;
                            oRecv = a.lastProtocol.offersReceived;
                            aAtt  = a.lastProtocol.admissionAttempts;
                        } else {
                            sim.ascsf.DecentralizedPeerScheduler g = new sim.ascsf.DecentralizedPeerScheduler(
                                carbonModel, renewableModel,
                                kind == 1 ? sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_BLIND
                                          : sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_CAP);
                            g.setCurrentTimeSec(simStartTimeSec);
                            g.scheduleAndBind(broker, cloudlets, vmList);
                            stats = g.aggregateStats();
                        }

                        int[] dcAssignments = new int[numTasks];
                        for (int i = 0; i < cloudlets.size(); i++) {
                            int vmIdx = vmList.indexOf(cloudlets.get(i).getVm());
                            dcAssignments[i] = (vmIdx >= 0) ? vmIdx / SimulationConfig.VMS_PER_DC
                                                            : i % SimulationConfig.NUM_DATACENTERS;
                        }
                        broker.submitCloudletList(cloudlets);
                        simulation.start();

                        List<Cloudlet> finished = broker.getCloudletFinishedList();
                        checkFinished(name + "(N=" + n + ")", cloudlets, finished, broker);

                        MetricsCollector collector = new MetricsCollector(carbonModel, renewableModel);
                        collector.setDcAssignments(dcAssignments);
                        collector.setSubmittedCloudlets(cloudlets);
                        MetricsCollector.SimResult r = collector.report(name, scaleNames[s], trial, finished);

                        try (FileWriter fw = new FileWriter(OUT, true); PrintWriter pw = new PrintWriter(fw)) {
                            pw.printf(java.util.Locale.US,
                                "%s,%s,%s,%d,%s,%d,%.4f,%.6f,%.6f,%.4f,%d,%d,%d,%d,%d,%d,%d,%s,%s,%s,%d%n",
                                name,
                                Double.isNaN(tau) ? "NA" : String.format(java.util.Locale.US, "%.2f", tau),
                                Double.isNaN(dsFactor) ? "NA" : String.format(java.util.Locale.US, "%.2f", dsFactor),
                                n, scaleNames[s], trial,
                                r.makespan, r.energyKWh, r.carbonKgCO2, r.renewablePercent,
                                stats.totalTasks, stats.localExecutions, stats.collaboratedOut, stats.fallbackToLocal,
                                stats.hostedForOthers, stats.offersDeclined, stats.admissionsRejected,
                                dMsgs < 0 ? "NA" : Long.toString(dMsgs),
                                oRecv < 0 ? "NA" : Long.toString(oRecv),
                                aAtt  < 0 ? "NA" : Long.toString(aAtt),
                                finished.size());
                        } catch (IOException e) { System.out.println("[Scale] row error: " + e.getMessage()); }
                    }
                }
                System.out.println("Finished N=" + n + " scale " + scaleNames[s]);
            }
        }
        SimulationConfig.setProviders(3);
        System.out.println("Done -> " + OUT);
    }


    // ── Reviewer-requested studies ───────────────────────────────────────────
    private static void runReviewStudies() {
        String[] sets = REVIEW_SET.equals("ALL") ? new String[]{"CENTRAL", "FRESH", "FIXEDK", "LOSS"} : new String[]{REVIEW_SET};
        for (String set : sets) runReviewSet(set);
        SimulationConfig.setProviders(3);
        System.out.println("REVIEW STUDIES COMPLETE.");
    }

    /** kind: 0 ASCSF, 4 ASCSF-DS, 1 greedy blind, 2 greedy cap, 10 centralized (name = scheduler). */
    private static void runReviewSet(String set) {
        final String OUT = (QUICK_TEST ? "review_quick_" : "review_") + set.toLowerCase() + "_paired.csv";
        try (FileWriter fw = new FileWriter(OUT, false); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("Study,Scheduler,TauHost,Providers,K,Loss,Scale,Trial,Makespan_s,Energy_kWh,Carbon_kgCO2e,Renewable_pct,"
                + "TotalTasks,LocalExecutions,CollaboratedOut,FallbackToLocal,HostedForOthers,AdmissionsRejected,"
                + "DiscoveryMsgs,OffersReceived,AdmissionAttempts,MsgsLost,LeaseExpiries,EpisodeRetries,HostedShares,Finished");
        } catch (IOException e) { System.out.println("[Review] header error: " + e.getMessage()); }

        int[] sizes = {SimulationConfig.TASKS_SMALL, SimulationConfig.TASKS_MEDIUM, SimulationConfig.TASKS_LARGE};
        String[] scaleNames = {"Small", "Medium", "Large"};
        int[] ns; int t0, t1;
        switch (set) {
            case "CENTRAL": ns = new int[]{3};        t0 = 1;  t1 = 30; break;
            case "FRESH":   ns = new int[]{3, 5, 10}; t0 = 31; t1 = 60; break;
            case "FIXEDK":  ns = new int[]{10};       t0 = 1;  t1 = 30; break;
            case "LOSS":    ns = new int[]{3};        t0 = 1;  t1 = 30; break;
            case "TRACE":   ns = new int[]{3};        t0 = 1;  t1 = 30; break;
            case "TRACESWEEP": ns = new int[]{3};     t0 = 1;  t1 = 30; break;
            default: throw new IllegalArgumentException("unknown REVIEW_SET " + set);
        }
        if (QUICK_TEST) { t1 = t0; }

        TraceData trace = null;
        if (set.equals("TRACE") || set.equals("TRACESWEEP")) {
            try { trace = TraceData.load("trace_data"); }
            catch (Exception e) { throw new RuntimeException("trace_data folder (trace_workload.csv, trace_days.csv) not found in the project root: " + e.getMessage()); }
            carbonModel.setMinForecast(0.005);   // real SE carbon is about 0.02 kg/kWh, below the old 0.05 floor
        }
        for (int n : ns) {
            SimulationConfig.setProviders(n);
            double tauBest = (n == 3) ? -0.30 : -0.15;      // thresholds fixed from the trial 1-30 study
            // name, tauHost, kind, dsFactor, K (0 = all others), loss
            List<Object[]> configs = new ArrayList<>();
            switch (set) {
                case "CENTRAL":
                    for (String c : new String[]{"RR", "MinMin", "EAS", "CASPER", "C-NSGA-II"})
                        configs.add(new Object[]{c, Double.NaN, 10, Double.NaN, 0, 0.0});
                    break;
                case "FRESH":
                    configs.add(new Object[]{"ASCSF",        tauBest, 0, Double.NaN, 0, 0.0});
                    configs.add(new Object[]{"ASCSF-DS",     tauBest, 4, 1.0,        0, 0.0});
                    configs.add(new Object[]{"GREEDY-BLIND", Double.NaN, 1, Double.NaN, 0, 0.0});
                    configs.add(new Object[]{"GREEDY-CAP",   Double.NaN, 2, Double.NaN, 0, 0.0});
                    break;
                case "FIXEDK":
                    for (int k : new int[]{2, 3, 5}) {
                        configs.add(new Object[]{"ASCSF",    tauBest, 0, Double.NaN, k, 0.0});
                        configs.add(new Object[]{"ASCSF-DS", tauBest, 4, 1.0,        k, 0.0});
                    }
                    break;
                case "TRACE":
                    configs.add(new Object[]{"ASCSF",        0.05,    0, Double.NaN, 0, 0.0});
                    configs.add(new Object[]{"ASCSF",       -0.30,    0, Double.NaN, 0, 0.0});
                    configs.add(new Object[]{"ASCSF-DS",    -0.30,    4, 1.0,        0, 0.0});
                    configs.add(new Object[]{"GREEDY-BLIND", Double.NaN, 1, Double.NaN, 0, 0.0});
                    configs.add(new Object[]{"GREEDY-CAP",   Double.NaN, 2, Double.NaN, 0, 0.0});
                    for (String c : new String[]{"RR", "MinMin", "EAS", "CASPER", "C-NSGA-II"})
                        configs.add(new Object[]{c, Double.NaN, 10, Double.NaN, 0, 0.0});
                    break;
                case "TRACESWEEP":   // hosting-threshold sensitivity on the real trace (ASCSF-DS) plus GREEDY-CAP for pairing
                    for (double tt : new double[]{-0.60, -0.45, -0.30, -0.15, 0.0, 0.05})
                        configs.add(new Object[]{"ASCSF-DS", tt, 4, 1.0, 0, 0.0});
                    configs.add(new Object[]{"GREEDY-CAP", Double.NaN, 2, Double.NaN, 0, 0.0});
                    break;
                case "LOSS":
                    for (double lp : new double[]{0.05, 0.10, 0.20}) {
                        configs.add(new Object[]{"ASCSF",    tauBest, 0, Double.NaN, 0, lp});
                        configs.add(new Object[]{"ASCSF-DS", tauBest, 4, 1.0,        0, lp});
                    }
                    break;
            }
            System.out.println("\n=== " + set + "  providers: " + n + " ===");
            for (int sc = 0; sc < scalesFor(sizes.length); sc++) {
                int numTasks = sizes[sc];
                for (int trial = t0; trial <= t1; trial++) {
                    double simStartTimeSec = ((trial - 1) % 30) * (86400.0 / 30.0);   // same 30 phases of the daily cycle for held-out trials
                    int[] taskLengths = generateTaskLengths(numTasks, trial);
                    boolean[] traceDs = null;
                    if (trace != null) {
                        carbonModel.setProfiles(trace.carbon(trial));
                        renewableModel.setProfiles(trace.renew(trial));
                        simStartTimeSec = trace.startHour(trial) * 3600.0;
                        taskLengths = trace.lengths(trial, numTasks);
                        traceDs = trace.ds(trial, numTasks);
                    }
                    for (Object[] cfg : configs) {
                        String name = (String) cfg[0]; double tau = (Double) cfg[1]; int kind = (Integer) cfg[2];
                        double dsFactor = (Double) cfg[3]; int K = (Integer) cfg[4]; double loss = (Double) cfg[5];

                        carbonModel.reseed(7919L * trial + 13); renewableModel.reseed(104729L * trial + 29);
                        CloudSim simulation = new CloudSim();
                        List<Datacenter> datacenters = new ArrayList<>();
                        for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++) datacenters.add(createDatacenter(simulation, dc));
                        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
                        List<Vm> vmList = createVms(numTasks);
                        broker.submitVmList(vmList);
                        pinVmsToDatacenters(broker, datacenters, vmList);
                        Map<Long, Boolean> taskTypeMap = new HashMap<>();
                        List<Cloudlet> cloudlets = createCloudlets(numTasks, taskLengths, taskTypeMap);
                        if (traceDs != null) {   // real delay-sensitivity labels from the Azure trace replace the parity rule
                            for (int i = 0; i < numTasks; i++) taskTypeMap.put(cloudlets.get(i).getId(), traceDs[i]);
                            java.util.IdentityHashMap<Cloudlet, Boolean> labels = new java.util.IdentityHashMap<>();
                            int dsCount = 0;
                            for (int i = 0; i < numTasks; i++) { labels.put(cloudlets.get(i), traceDs[i]); if (traceDs[i]) dsCount++; }
                            sim.ascsf.CloudProvider.dsLabels = labels;
                            if (trial == t0 && sc == 0) {
                                java.util.Set<Long> ids = new java.util.HashSet<>();
                                for (Cloudlet c : cloudlets) ids.add(c.getId());
                                System.out.println("[TRACE CHECK] tasks=" + numTasks + " DS=" + dsCount + " distinctCloudletIds=" + ids.size());
                            }
                        } else {
                            sim.ascsf.CloudProvider.dsLabels = null;
                        }
                        int[] dcAssignments = new int[numTasks];

                        sim.ascsf.ASCSFScheduler.CollaborationStats stats = null;
                        long dMsgs = -1, oRecv = -1, aAtt = -1, mLost = 0, lExp = 0, retr = 0;
                        String shares = "NA";
                        if (kind == 10) {
                            applyScheduler(name, broker, cloudlets, vmList, taskTypeMap, simStartTimeSec, dcAssignments);
                        } else {
                            if (kind == 0 || kind == 4) {
                                sim.ascsf.ASCSFScheduler a = new sim.ascsf.ASCSFScheduler(carbonModel, renewableModel);
                                a.setCurrentTimeSec(simStartTimeSec);
                                a.setTauOffload(SimulationConfig.ASCSF_TAU_OFFLOAD);
                                a.setTauHost(tau);
                                a.setRMax(SimulationConfig.ASCSF_R_MAX);
                                if (kind == 4) { a.setAllowDsOffload(true); a.setDsDeadlineFactor(dsFactor); }
                                if (K > 0) a.setMaxCandidates(K, 31L * trial + 7);
                                if (loss > 0) a.setMessageLoss(loss, 977L * trial + 5);
                                a.scheduleAndBind(broker, cloudlets, vmList);
                                stats = a.aggregateStats();
                                dMsgs = a.lastProtocol.discoveryRequests; oRecv = a.lastProtocol.offersReceived; aAtt = a.lastProtocol.admissionAttempts;
                                mLost = a.lastProtocol.messagesLost; lExp = a.lastProtocol.leaseExpiries; retr = a.lastProtocol.episodeRetries;
                                StringBuilder sb = new StringBuilder();
                                for (sim.ascsf.CloudProvider p : a.lastRunProviders) sb.append(sb.length() == 0 ? "" : ";").append(p.statHostedForOthers);
                                shares = sb.toString();
                            } else {
                                sim.ascsf.DecentralizedPeerScheduler g = new sim.ascsf.DecentralizedPeerScheduler(
                                    carbonModel, renewableModel,
                                    kind == 1 ? sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_BLIND
                                              : sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_CAP);
                                g.setCurrentTimeSec(simStartTimeSec);
                                g.scheduleAndBind(broker, cloudlets, vmList);
                                stats = g.aggregateStats();
                            }
                            for (int i = 0; i < cloudlets.size(); i++) {
                                int vmIdx = vmList.indexOf(cloudlets.get(i).getVm());
                                dcAssignments[i] = (vmIdx >= 0) ? vmIdx / SimulationConfig.VMS_PER_DC : i % SimulationConfig.NUM_DATACENTERS;
                            }
                        }
                        broker.submitCloudletList(cloudlets);
                        simulation.start();
                        List<Cloudlet> finished = broker.getCloudletFinishedList();
                        checkFinished(name + "(N=" + n + ")", cloudlets, finished, broker);

                        MetricsCollector collector = new MetricsCollector(carbonModel, renewableModel);
                        collector.setDcAssignments(dcAssignments);
                        collector.setSubmittedCloudlets(cloudlets);
                        MetricsCollector.SimResult r = collector.report(name, scaleNames[sc], trial, finished);

                        String label = name.equals("C-NSGA-II") ? "C-NSGA-II" : name;
                        try (FileWriter fw = new FileWriter(OUT, true); PrintWriter pw = new PrintWriter(fw)) {
                            pw.printf(java.util.Locale.US,
                                "%s,%s,%s,%d,%d,%.2f,%s,%d,%.4f,%.6f,%.6f,%.4f,%d,%d,%d,%d,%d,%d,%s,%s,%s,%d,%d,%d,%s,%d%n",
                                set, label,
                                Double.isNaN(tau) ? "NA" : String.format(java.util.Locale.US, "%.2f", tau),
                                n, K, loss, scaleNames[sc], trial,
                                r.makespan, r.energyKWh, r.carbonKgCO2, r.renewablePercent,
                                stats == null ? -1 : stats.totalTasks, stats == null ? -1 : stats.localExecutions,
                                stats == null ? -1 : stats.collaboratedOut, stats == null ? -1 : stats.fallbackToLocal,
                                stats == null ? -1 : stats.hostedForOthers, stats == null ? -1 : stats.admissionsRejected,
                                dMsgs < 0 ? "NA" : Long.toString(dMsgs), oRecv < 0 ? "NA" : Long.toString(oRecv),
                                aAtt < 0 ? "NA" : Long.toString(aAtt), mLost, lExp, retr, shares, finished.size());
                        } catch (IOException e) { System.out.println("[Review] row error: " + e.getMessage()); }
                    }
                }
                System.out.println("Finished " + set + " N=" + n + " scale " + scaleNames[sc]);
            }
        }
        sim.ascsf.CloudProvider.dsLabels = null;
        if (trace != null) {   // restore the synthetic profiles for any later study
            carbonModel = new CarbonModel("carbon.csv");
            renewableModel = new RenewableModel("renewable.csv");
        }
        System.out.println("Done -> " + OUT);
    }

    // ── Robustness study (3 providers): forecast noise, tau_offload, R_max, skewed ownership ──
    private static void runRobustnessStudy() {
        final String OUT = QUICK_TEST ? "robustness_quicktest.csv" : "robustness.csv";
        try (FileWriter fw = new FileWriter(OUT, false); PrintWriter pw = new PrintWriter(fw)) {
            pw.println("Condition,Param,Scheduler,TauHost,DsFactor,Scale,Trial,Makespan_s,Energy_kWh,Carbon_kgCO2e,Renewable_pct,"
                + "TotalTasks,LocalExecutions,CollaboratedOut,FallbackToLocal,HostedForOthers,OffersDeclined,AdmissionsRejected,Finished");
        } catch (IOException e) { System.out.println("[Robust] header error: " + e.getMessage()); }

        SimulationConfig.setProviders(3);
        int[] sizes = {SimulationConfig.TASKS_SMALL, SimulationConfig.TASKS_MEDIUM, SimulationConfig.TASKS_LARGE};
        String[] scaleNames = {"Small", "Medium", "Large"};

        // condition: label, param text, noise sigma, tauOffload, rMax, ownership weights (null = round-robin), applies-to-greedy
        List<Object[]> conds = new ArrayList<>();
        double dN = SimulationConfig.NOISE_STD_DEV, dT = SimulationConfig.ASCSF_TAU_OFFLOAD; int dR = SimulationConfig.ASCSF_R_MAX;
        conds.add(new Object[]{"BASE",       "default", dN,   dT,  dR, null, true});
        for (double sg : new double[]{0.0, 0.10, 0.20})
            conds.add(new Object[]{"NOISE",  String.format(java.util.Locale.US, "%.2f", sg), sg, dT, dR, null, true});
        for (double to : new double[]{0.3, 0.7})
            conds.add(new Object[]{"TAU_OFFLOAD", String.format(java.util.Locale.US, "%.1f", to), dN, to, dR, null, false});
        for (int rm : new int[]{1, 2, 5})
            conds.add(new Object[]{"R_MAX",  Integer.toString(rm), dN, dT, rm, null, false});
        conds.add(new Object[]{"OWNERSHIP", "NORDIC_HEAVY_6-3-1", dN, dT, dR, new double[]{6, 3, 1}, true});
        conds.add(new Object[]{"OWNERSHIP", "FOSSIL_HEAVY_1-3-6", dN, dT, dR, new double[]{1, 3, 6}, true});

        // scheduler configs at 3 providers: label, tauHost, kind, dsFactor
        List<Object[]> configs = new ArrayList<>();
        configs.add(new Object[]{"ASCSF",        -0.30, 0, Double.NaN});
        configs.add(new Object[]{"ASCSF-DS",     -0.30, 4, 1.0});
        configs.add(new Object[]{"GREEDY-BLIND", Double.NaN, 1, Double.NaN});
        configs.add(new Object[]{"GREEDY-CAP",   Double.NaN, 2, Double.NaN});

        for (Object[] cond : conds) {
            String cName = (String) cond[0], cParam = (String) cond[1];
            SimulationConfig.NOISE_STD_DEV = (Double) cond[2];
            double tauOff = (Double) cond[3]; int rMax = (Integer) cond[4];
            SimulationConfig.setOwnershipWeights((double[]) cond[5]);
            boolean greedyToo = (Boolean) cond[6];
            System.out.println("\n=== Condition " + cName + " = " + cParam + " ===");

            for (int s = 0; s < scalesFor(sizes.length); s++) {
                int numTasks = sizes[s];
                for (int trial = 1; trial <= trialsFor(SimulationConfig.ASCSF_SWEEP_TRIALS); trial++) {
                    double simStartTimeSec = (trial - 1) * (86400.0 / 30.0);
                    int[] taskLengths = generateTaskLengths(numTasks, trial);

                    for (Object[] cfg : configs) {
                        String name = (String) cfg[0]; double tau = (Double) cfg[1];
                        int kind = (Integer) cfg[2]; double dsFactor = (Double) cfg[3];
                        if ((kind == 1 || kind == 2) && !greedyToo) continue;

                        // identical forecast-noise stream for every scheduler in a (condition, trial)
                        carbonModel.reseed(7919L * trial + 13);
                        renewableModel.reseed(104729L * trial + 29);

                        CloudSim simulation = new CloudSim();
                        List<Datacenter> datacenters = new ArrayList<>();
                        for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++)
                            datacenters.add(createDatacenter(simulation, dc));
                        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
                        List<Vm> vmList = createVms(numTasks);
                        broker.submitVmList(vmList);
                        pinVmsToDatacenters(broker, datacenters, vmList);
                        Map<Long, Boolean> taskTypeMap = new HashMap<>();
                        List<Cloudlet> cloudlets = createCloudlets(numTasks, taskLengths, taskTypeMap);

                        sim.ascsf.ASCSFScheduler.CollaborationStats stats;
                        if (kind == 0 || kind == 4) {
                            sim.ascsf.ASCSFScheduler a = new sim.ascsf.ASCSFScheduler(carbonModel, renewableModel);
                            a.setCurrentTimeSec(simStartTimeSec);
                            a.setTauOffload(tauOff);
                            a.setTauHost(tau);
                            a.setRMax(rMax);
                            if (kind == 4) { a.setAllowDsOffload(true); a.setDsDeadlineFactor(dsFactor); }
                            a.scheduleAndBind(broker, cloudlets, vmList);
                            stats = a.aggregateStats();
                        } else {
                            sim.ascsf.DecentralizedPeerScheduler g = new sim.ascsf.DecentralizedPeerScheduler(
                                carbonModel, renewableModel,
                                kind == 1 ? sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_BLIND
                                          : sim.ascsf.DecentralizedPeerScheduler.Mode.GREEDY_CAP);
                            g.setCurrentTimeSec(simStartTimeSec);
                            g.scheduleAndBind(broker, cloudlets, vmList);
                            stats = g.aggregateStats();
                        }

                        int[] dcAssignments = new int[numTasks];
                        for (int i = 0; i < cloudlets.size(); i++) {
                            int vmIdx = vmList.indexOf(cloudlets.get(i).getVm());
                            dcAssignments[i] = (vmIdx >= 0) ? vmIdx / SimulationConfig.VMS_PER_DC
                                                            : SimulationConfig.ownerOf(i, SimulationConfig.NUM_DATACENTERS);
                        }
                        broker.submitCloudletList(cloudlets);
                        simulation.start();

                        List<Cloudlet> finished = broker.getCloudletFinishedList();
                        checkFinished(name + "[" + cName + "=" + cParam + "]", cloudlets, finished, broker);

                        MetricsCollector collector = new MetricsCollector(carbonModel, renewableModel);
                        collector.setDcAssignments(dcAssignments);
                        collector.setSubmittedCloudlets(cloudlets);
                        MetricsCollector.SimResult r = collector.report(name, scaleNames[s], trial, finished);

                        try (FileWriter fw = new FileWriter(OUT, true); PrintWriter pw = new PrintWriter(fw)) {
                            pw.printf(java.util.Locale.US,
                                "%s,%s,%s,%s,%s,%s,%d,%.4f,%.6f,%.6f,%.4f,%d,%d,%d,%d,%d,%d,%d,%d%n",
                                cName, cParam, name,
                                Double.isNaN(tau) ? "NA" : String.format(java.util.Locale.US, "%.2f", tau),
                                Double.isNaN(dsFactor) ? "NA" : String.format(java.util.Locale.US, "%.2f", dsFactor),
                                scaleNames[s], trial,
                                r.makespan, r.energyKWh, r.carbonKgCO2, r.renewablePercent,
                                stats.totalTasks, stats.localExecutions, stats.collaboratedOut, stats.fallbackToLocal,
                                stats.hostedForOthers, stats.offersDeclined, stats.admissionsRejected, finished.size());
                        } catch (IOException e) { System.out.println("[Robust] row error: " + e.getMessage()); }
                    }
                }
            }
        }
        SimulationConfig.NOISE_STD_DEV = dN;
        SimulationConfig.setOwnershipWeights(null);
        System.out.println("Done -> " + OUT);
    }

    // ── Create datacenter ─────────────────────────────────────────────────────
    /**
     * Creates one datacenter with heterogeneous hosts.
     * Three DC types:
     *   DC0 — Nordic     : faster hosts (higher MIPS)
     *   DC1 — Europe     : medium hosts
     *   DC2 — Fossil     : standard hosts
     *
     * Host count and MIPS vary to reflect real infrastructure
     * differences between geographic regions.
     */
    private static Datacenter createDatacenter(
            CloudSim simulation, int dcIndex) {

        List<Host> hostList = new ArrayList<>();

        // Increased host count and resources to accommodate all VM tiers
        int numHosts = 40;   // spare capacity so every VM can be placed

        for (int i = 0; i < numHosts; i++) {

            List<Pe> peList = new ArrayList<>();

            // 8 cores per host — enough for multiple VMs
            int cores = 8;

            // All hosts must be able to run the largest VM tier (4000 MIPS per PE);
            // otherwise Europe/Fossil VMs of that tier fail to be created and their
            // cloudlets silently disappear. VM speed is fixed by the VM, so host speed
            // does not change task execution times.
            int mipsPerCore = 4000;

            for (int j = 0; j < cores; j++) {
                peList.add(new PeSimple(mipsPerCore));
            }

            Host host = new HostSimple(
                524288,   // 512 GB RAM in MB — large enough for all VMs
                1000000,  // BW
                8000000,  // Storage
                peList
            );
            host.setVmScheduler(new VmSchedulerTimeShared());
            hostList.add(host);
        }

        return new DatacenterSimple(simulation, hostList);
    }

    // ── Create VMs ────────────────────────────────────────────────────────────
    /**
     * Creates VMs distributed across 3 DCs.
     * Four tiers matching SimulationConfig.VM_MIPS_TIERS.
     * Total VMs = max(numTasks/10, 30) to ensure enough capacity.
     */
    private static List<Vm> createVms(int numTasks) {
        List<Vm> vmList = new ArrayList<>();
        int[] mipsTiers = SimulationConfig.VM_MIPS_TIERS;
        int vmsPerDC    = SimulationConfig.VMS_PER_DC;

        // Create VMS_PER_DC VMs for each of the 3 DCs
        // Total = 3 × VMS_PER_DC = 180 VMs
        for (int dc = 0; dc < SimulationConfig.NUM_DATACENTERS; dc++) {
            for (int v = 0; v < vmsPerDC; v++) {
                int tier = v % mipsTiers.length;
                // DC0 (Nordic) gets higher MIPS — faster machines
                
                // DC1 (Europe) gets medium MIPS
                // DC2 (Fossil) gets standard MIPS
                int mips = switch (SimulationConfig.DC_SPEED_CLASS[dc]) {
                    case 0 -> mipsTiers[Math.min(tier + 1,
                                  mipsTiers.length - 1)]; // boosted
                    case 1 -> mipsTiers[tier];             // standard
                    default -> mipsTiers[Math.max(tier - 1, 0)]; // reduced
                };
                Vm vm = new VmSimple(mips, 2);
                vm.setRam(2048 * (tier + 1))
                  .setBw(1000)
                  .setSize(10000)
                  .setCloudletScheduler(
                      new CloudletSchedulerTimeShared());
                vmList.add(vm);
            }
        }
        return vmList;
    }

    // ── Create cloudlets ──────────────────────────────────────────────────────
    /**
     * Creates cloudlets with lengths from pre-generated array.
     * DS/DT split follows DT_TASK_FRACTION from config (50%).
     */
    private static List<Cloudlet> createCloudlets(
            int numTasks,
            int[] taskLengths,
            Map<Long, Boolean> taskTypeMap) {

        List<Cloudlet> list = new ArrayList<>();

        for (int i = 0; i < numTasks; i++) {
            Cloudlet cl = new CloudletSimple(taskLengths[i], 2);
            cl.setSizes(300);

         // 50% DS (even index), 50% DT (odd index)
         // DS tasks execute immediately, DT tasks use green window deferral
         boolean isDS = (i % 2 == 0);
         taskTypeMap.put(cl.getId(), isDS);

            list.add(cl);
        }

        return list;
    }

    // ── Generate task lengths ─────────────────────────────────────────────────
    /**
     * Generates task CPU lengths in MI.
     * Uses trial number as seed so all schedulers in one trial
     * see identical workloads — ensuring fair comparison.
     * Different trials get different workloads — ensuring
     * results are not biased toward one specific workload pattern.
     */
    private static int[] generateTaskLengths(int numTasks, int trial) {
        Random taskRng = new Random(trial * 1000L);
        int[] lengths  = new int[numTasks];
        int range = SimulationConfig.CLOUDLET_LENGTH_MAX
                  - SimulationConfig.CLOUDLET_LENGTH_MIN;

        for (int i = 0; i < numTasks; i++) {
            lengths[i] = SimulationConfig.CLOUDLET_LENGTH_MIN
                       + taskRng.nextInt(range);
        }
        return lengths;
    }
}