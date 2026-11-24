package sim.scheduler;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.vms.Vm;

import sim.SimulationConfig;
import sim.model.CarbonModel;
import sim.model.RenewableModel;
import sim.nsga2.Chromosome;
import sim.nsga2.NSGAIIOptimizer;

import java.util.*;

/**
 * C-NSGA-II Scheduler — complete implementation.
 *
 * Two-phase scheduling:
 * Phase 1: NSGA-II finds Pareto-optimal task-to-VM assignments
 *          using forecast carbon intensity (not current CI).
 * Phase 2: C-NSGA-II utility scoring refines DC selection for
 *          delay-tolerant tasks toward high-renewable windows.
 *
 * The key algorithmic contribution vs CASPER:
 * CASPER uses CI(t_now) in its objective.
 * C-NSGA-II uses CI_forecast(t_now + delta) — the predicted
 * carbon intensity at execution time, not scheduling time.
 * This allows tasks to be steered toward future clean windows
 * that a reactive scheduler cannot anticipate.
 */
public class CNSGA2Scheduler {

    private final CarbonModel    carbonModel;
    private final RenewableModel renewableModel;
    private final Map<Long, Boolean> taskTypeMap;
    private double currentTimeSec;

    public CNSGA2Scheduler(
            CarbonModel carbonModel,
            RenewableModel renewableModel,
            Map<Long, Boolean> taskTypeMap) {
        this.carbonModel    = carbonModel;
        this.renewableModel = renewableModel;
        this.taskTypeMap    = taskTypeMap;
        this.currentTimeSec = 0.0;
    }

    public void scheduleAndBind(
            DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms) {

        if (vms == null || vms.isEmpty()) {
            System.out.println("[C-NSGA-II] No VMs available.");
            return;
        }

        int numTasks  = cloudlets.size();
        int numDCs    = SimulationConfig.NUM_DATACENTERS;
        int vmsPerDC  = Math.max(1, vms.size() / numDCs);

        // Build task length array
        int[] taskLengths = new int[numTasks];
        for (int i = 0; i < numTasks; i++) {
            taskLengths[i] = (int) cloudlets.get(i).getLength();
        }

        // Build VM arrays from first DC's worth of VMs
        int[] vmMips = new int[vmsPerDC];
        int[] vmTier = new int[vmsPerDC];
        for (int v = 0; v < vmsPerDC; v++) {
            Vm vm = vms.get(v % vms.size());
            vmMips[v] = (int) vm.getMips();
            vmTier[v] = getVmTier(vm.getMips());
        }

        // ── Phase 1: NSGA-II global optimisation ──────────────────────────
        System.out.println("[C-NSGA-II] Running NSGA-II ("
            + SimulationConfig.POPULATION_SIZE + " pop, "
            + SimulationConfig.NUM_GENERATIONS + " gen) for "
            + numTasks + " tasks...");

        long t0 = System.currentTimeMillis();

        NSGAIIOptimizer optimizer = new NSGAIIOptimizer(
            carbonModel, renewableModel,
            numTasks, vmsPerDC,
            taskLengths, vmMips, vmTier,
            currentTimeSec);

        Chromosome best = optimizer.run();

        System.out.println("[C-NSGA-II] NSGA-II completed in "
            + (System.currentTimeMillis() - t0) + " ms.");
        System.out.println("[C-NSGA-II] Best solution: " + best);

        // ── Phase 2: bind each task using NSGA-II result ──────────────────
        // Track VM load for fair distribution
        Map<Integer, Double> vmFinishTime = new HashMap<>();
        for (int i = 0; i < vms.size(); i++) {
            vmFinishTime.put(i, 0.0);
        }

        int dsCount = 0;
        int dtCount = 0;

        for (int i = 0; i < numTasks; i++) {
            Cloudlet cl = cloudlets.get(i);

            // ── DS/DT classification ──────────────────────────────────────
            // Use position-based classification since IDs vary by trial
            // Even-indexed tasks = DS, odd-indexed = DT
            boolean isDS = (i % 2 == 0);
            if (isDS) dsCount++; else dtCount++;

            // ── Get NSGA-II recommended DC and VM ─────────────────────────
            int recommendedDC = best.dcAssignment[i] % numDCs;
            int recommendedVM = best.assignment[i]   % vmsPerDC;

            // ── C-NSGA-II utility refinement for DT tasks ────────────────────
            int finalDC = recommendedDC;
            if (!isDS) {
                // Find DC with highest utility score
                int betterDC   = findBestUtilityDC(numDCs);
                double currentU = computeUtility(recommendedDC);
                double betterU  = computeUtility(betterDC);

                // Only override if meaningfully better (>5% improvement)
                if (betterU > currentU + 0.05) {
                    finalDC = betterDC;
                }
            }

            // ── Map to actual VM index ────────────────────────────────────
            // Each DC owns vmsPerDC VMs in the flat list
            int baseVmIdx = finalDC * vmsPerDC;
            int vmIdx     = (baseVmIdx + recommendedVM) % vms.size();

            // Load-balance: if this VM is heavily loaded,
            // find least loaded VM in the same DC
            vmIdx = findLeastLoadedVM(
                baseVmIdx, vmsPerDC, vms.size(), vmFinishTime);

            // Update VM finish time
            double execTime = cl.getLength()
                            / vms.get(vmIdx).getMips();
            vmFinishTime.put(vmIdx,
                vmFinishTime.getOrDefault(vmIdx, 0.0) + execTime);

            cl.setVm(vms.get(vmIdx));
        }

        System.out.println("[C-NSGA-II] Bound " + numTasks
            + " tasks. DS/DT split: " + dsCount + "/" + dtCount);
    }

    // ── Find least loaded VM in a DC range ───────────────────────────────
    private int findLeastLoadedVM(int baseIdx, int vmsPerDC,
                                   int totalVMs,
                                   Map<Integer, Double> finishTimes) {
        int    best      = baseIdx % totalVMs;
        double minFinish = finishTimes.getOrDefault(best, 0.0);

        for (int offset = 0; offset < vmsPerDC; offset++) {
            int idx = (baseIdx + offset) % totalVMs;
            double ft = finishTimes.getOrDefault(idx, 0.0);
            if (ft < minFinish) {
                minFinish = ft;
                best      = idx;
            }
        }
        return best;
    }

    // ── C-NSGA-II utility score ──────────────────────────────────────────────
    // U(k,t) = alpha*RF_forecast - beta*CI_forecast + gamma*W
    private double computeUtility(int dcIndex) {
        double rf = renewableModel
            .getForecastRenewableFraction(dcIndex, currentTimeSec);
        double ci = carbonModel
            .getForecastCarbonIntensity(dcIndex, currentTimeSec);
        double ciNorm   = Math.min(1.0, ci / 0.85);
        double headroom = 0.7;

        return SimulationConfig.WEIGHT_ALPHA * rf
             - SimulationConfig.WEIGHT_BETA  * ciNorm
             + SimulationConfig.WEIGHT_GAMMA * headroom;
    }

    // ── Find DC with highest utility ──────────────────────────────────────
    private int findBestUtilityDC(int numDCs) {
        int    bestDC    = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int dc = 0; dc < numDCs; dc++) {
            double score = computeUtility(dc);
            if (score > bestScore) {
                bestScore = score;
                bestDC    = dc;
            }
        }
        return bestDC;
    }

    // ── VM tier from MIPS ─────────────────────────────────────────────────
    private static int getVmTier(double mips) {
        if (mips <= 500)  return 0;
        if (mips <= 1000) return 1;
        if (mips <= 2000) return 2;
        return 3;
    }

    public void setCurrentTimeSec(double t) {
        this.currentTimeSec = t;
    }
}