package sim.ascsf;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;

import sim.SimulationConfig;
import sim.model.CarbonModel;
import sim.model.RenewableModel;

import java.util.*;

/**
 * ASCSF top-level scheduler. Matches the scheduleAndBind(...) pattern
 * already used by CASPERScheduler / CNSGA2Scheduler / EASScheduler so it
 * plugs into MainSimulation.applyScheduler(...) as a sixth option with
 * minimal changes (see INTEGRATION_GUIDE.md).
 *
 * Implements Algorithm 1 (ASCSF-MAIN) from the Algorithms document, calling
 * out to CloudProvider (Algorithms 2/4) and CoordinationProtocol
 * (Algorithms 3/5).
 */
public class ASCSFScheduler {

    private final CarbonModel carbonModel;
    private final RenewableModel renewableModel;
    private double currentTimeSec;

    // Illustrative defaults -- experimental parameters, not fixed constants
    // (consistent with CPDS's own framing of tau_offload/tau_host/R_max as
    // tunable). Expose setters if you want to sweep these in experiments.
    private double tauOffload = 0.5;
    private double tauHost = 0.05;
    private int rMax = 3; // total admission attempts including the first, per CPDS Sec 6 fix

    // Extension: deadline-guarded offloading of delay-sensitive tasks.
    // allowDsOffload=false reproduces the original ASCSF (DS always local).
    // dsDeadlineFactor: a host must be able to finish the DS task within
    // factor x (the initiator's own projected local completion time).
    private boolean allowDsOffload = false;
    private double dsDeadlineFactor = 1.0;

    public ASCSFScheduler(CarbonModel carbonModel, RenewableModel renewableModel) {
        this.carbonModel = carbonModel;
        this.renewableModel = renewableModel;
    }

    public void setCurrentTimeSec(double t) { this.currentTimeSec = t; }
    public void setTauOffload(double v) { this.tauOffload = v; }
    public void setTauHost(double v) { this.tauHost = v; }
    public void setRMax(int v) { this.rMax = v; }
    public void setAllowDsOffload(boolean v) { this.allowDsOffload = v; }
    public void setDsDeadlineFactor(double v) { this.dsDeadlineFactor = v; }

    // ── Reviewer-study options (defaults reproduce the published runs) ──
    private int maxCandidates = 0;          // 0 = K_i = all other providers; >0 = fixed-size random candidate set
    private long candidateSeed = 1L;
    private double lossProb = 0.0;
    private long lossSeed = 1L;
    public void setMaxCandidates(int k, long seed) { this.maxCandidates = k; this.candidateSeed = seed; }
    public void setMessageLoss(double p, long seed) { this.lossProb = p; this.lossSeed = seed; }

    /** Aggregate stats across all providers, populated after scheduleAndBind(). Useful for MetricsCollector extension. */
    public List<CloudProvider> lastRunProviders;
    public CoordinationProtocol lastProtocol;

    /**
     * Plain-data aggregation of collaboration statistics across all
     * providers from the most recent scheduleAndBind() call. Deliberately
     * a primitive-typed DTO (not exposing CloudProvider itself) so that
     * sim.util.MetricsCollector can accept this data without importing
     * sim.ascsf -- keeps package dependencies one-directional
     * (sim.ascsf depends on sim.model/sim.nsga2; sim.util stays independent).
     */
    public static class CollaborationStats {
        public final int totalTasks, localExecutions, collaboratedOut,
                          fallbackToLocal, hostedForOthers, offersDeclined, admissionsRejected;
        public CollaborationStats(int totalTasks, int localExecutions, int collaboratedOut,
                                   int fallbackToLocal, int hostedForOthers,
                                   int offersDeclined, int admissionsRejected) {
            this.totalTasks = totalTasks;
            this.localExecutions = localExecutions;
            this.collaboratedOut = collaboratedOut;
            this.fallbackToLocal = fallbackToLocal;
            this.hostedForOthers = hostedForOthers;
            this.offersDeclined = offersDeclined;
            this.admissionsRejected = admissionsRejected;
        }
    }

    public CollaborationStats aggregateStats() {
        int total = 0, local = 0, collab = 0, fallback = 0, hosted = 0, declined = 0, rejected = 0;
        if (lastRunProviders != null) {
            for (CloudProvider p : lastRunProviders) {
                local += p.statLocalExecutions;
                collab += p.statCollaboratedOut;
                fallback += p.statFallbackToLocal;
                hosted += p.statHostedForOthers;
                declined += p.statOffersDeclined;
                rejected += p.statAdmissionsRejected;
            }
        }
        total = local + collab + fallback;
        return new CollaborationStats(total, local, collab, fallback, hosted, declined, rejected);
    }

    public void scheduleAndBind(
            DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms) {

        if (vms == null || vms.isEmpty()) {
            System.out.println("[ASCSF] No VMs available.");
            return;
        }

        int numDCs = SimulationConfig.NUM_DATACENTERS;
        int vmsPerDC = Math.max(1, vms.size() / numDCs);

        // ── Set up one autonomous CloudProvider per DC (System Architecture v1.2) ──
        List<CloudProvider> providers = new ArrayList<>();
        for (int dc = 0; dc < numDCs; dc++) {
            List<Vm> localVms = vms.subList(dc * vmsPerDC, Math.min((dc + 1) * vmsPerDC, vms.size()));
            CloudProvider p = new CloudProvider(dc, localVms, carbonModel, renewableModel, tauOffload, tauHost);
            p.setCurrentTimeSec(currentTimeSec);
            providers.add(p);
        }
        this.lastRunProviders = providers;

        // ── Partition tasks among providers (initial ownership) ────────────
        // Simulation setup choice, analogous to tasks arriving at whichever
        // provider a customer originally submitted to -- round-robin by
        // task index. This is NOT a scheduling decision (that's what LCDE/
        // PEE/CPDS determine next); it only fixes which provider "receives"
        // each task first, per Architecture v1.2 (every provider is both
        // potential initiator and potential receiver).
        List<List<Cloudlet>> ownedTasks = new ArrayList<>();
        for (int dc = 0; dc < numDCs; dc++) ownedTasks.add(new ArrayList<>());
        for (int i = 0; i < cloudlets.size(); i++) {
            ownedTasks.get(SimulationConfig.ownerOf(i, numDCs)).add(cloudlets.get(i));
        }

        // ── Phase 1 prerequisite: each provider runs its own local NSGA-II batch ──
        for (int dc = 0; dc < numDCs; dc++) {
            if (!ownedTasks.get(dc).isEmpty()) {
                providers.get(dc).scheduleLocalBatch(ownedTasks.get(dc));
            }
        }

        CoordinationProtocol protocol = new CoordinationProtocol(rMax);
        protocol.setLoss(lossProb, lossSeed);
        java.util.Random candRng = new java.util.Random(candidateSeed);
        this.lastProtocol = protocol;

        // ── Algorithm 1, per task, per owning provider ──────────────────────
        for (int dc = 0; dc < numDCs; dc++) {
            CloudProvider initiator = providers.get(dc);
            List<Cloudlet> myTasks = ownedTasks.get(dc);

            for (int localIdx = 0; localIdx < myTasks.size(); localIdx++) {
                Cloudlet task = myTasks.get(localIdx);
                double uLocal = initiator.localUtilityForTask(localIdx);

                boolean seekCollaboration = initiator.lcdeSeeksCollaboration(localIdx, allowDsOffload);

                if (!seekCollaboration) {
                    int vmIdx = initiator.commitLocalTask(localIdx);
                    task.setVm(initiator.vmAt(vmIdx));
                    initiator.statLocalExecutions++;
                    continue;
                }

                // Build candidate set K_i = all other providers (CPDS Sec 3.1;
                // with only 3 providers total in this simulation configuration,
                // "bounded candidate set" and "all other providers" coincide --
                // see CPDS Sec 3.1 discussion for the general, larger-N case)
                List<CloudProvider> candidates = new ArrayList<>(providers);
                if (maxCandidates > 0 && providers.size() - 1 > maxCandidates) {
                    List<CloudProvider> others = new ArrayList<>(providers);
                    others.remove(initiator);
                    java.util.Collections.shuffle(others, candRng);      // uniform random K_i of the other providers
                    candidates = new ArrayList<>(others.subList(0, maxCandidates));
                    candidates.add(initiator);                           // kept only for the average-MIPS estimate; protocol skips it
                }

                double deadlineRel = Double.NaN;
                if (initiator.isDelaySensitive(localIdx)) {
                    deadlineRel = initiator.projectedLocalCompletionRel(localIdx) * dsDeadlineFactor;
                }
                CoordinationProtocol.Result result =
                    protocol.run(initiator, localIdx, task, uLocal, candidates, currentTimeSec, deadlineRel);

                if (result.outcome == CoordinationProtocol.Outcome.COMMITTED) {
                    task.setVm(result.committedProvider.vmAt(result.committedVmIndex));
                    initiator.statCollaboratedOut++;
                } else {
                    int vmIdx = initiator.commitLocalTask(localIdx);
                    task.setVm(initiator.vmAt(vmIdx));
                    initiator.statFallbackToLocal++;
                }
            }
        }

        printSummary(providers, cloudlets.size());
    }

    private void printSummary(List<CloudProvider> providers, int totalTasks) {
        int totalLocal = 0, totalCollab = 0, totalFallback = 0, totalHosted = 0;
        for (CloudProvider p : providers) {
            totalLocal += p.statLocalExecutions;
            totalCollab += p.statCollaboratedOut;
            totalFallback += p.statFallbackToLocal;
            totalHosted += p.statHostedForOthers;
        }
        System.out.printf(
            "[ASCSF] %d tasks: local=%d, collaborated=%d, fallback=%d, hosted-for-others=%d%n",
            totalTasks, totalLocal, totalCollab, totalFallback, totalHosted);
    }
}