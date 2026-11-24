package sim.ascsf;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;

import sim.SimulationConfig;
import sim.model.CarbonModel;
import sim.model.RenewableModel;
import sim.nsga2.Chromosome;
import sim.nsga2.NSGAIIOptimizer;

import java.util.*;

/**
 * One autonomous Cloud Provider (CP), per System Architecture v1.2 and
 * System Assumptions v1.1. Owns a fixed subset of VMs at ONE global DC
 * index, runs its own local (numDCs=1, see PATCH_NSGAIIOptimizer.md)
 * NSGA-II scheduler, and exposes only the narrow request/offer/admission
 * interfaces used by CoordinationProtocol -- no other object may reach
 * into this provider's internal state (mirrors Assumption A2: no VM-level
 * state, prediction models, or internal optimization parameters exposed).
 *
 * Implements: LCDE (Algorithm 2), the "offer if willing" half of Phase 2
 * discovery (CPDS Sec 3), and Decision 3 / Hosting Utility (Algorithm 4).
 */
public class CloudProvider {

    public final int globalDcIndex; // 0=Nordic, 1=Europe, 2=Fossil (this sim's convention)
    private final List<Vm> localVms;
    private final CarbonModel carbonModel;
    private final RenewableModel renewableModel;
    private final CollaborationUtility util;
    private final double tauOffload;
    private final double tauHost;
    private double currentTimeSec;

 // Per-local-VM finish time -- this provider's own load state, used for
    // scheduling/load-balancing (leastLoadedVmIndex). Absolute-time-based
    // by design (reflects when a VM is actually free to run something next).
    private final double[] vmFinishTime;

    // v1.2 fix: pure accumulated execution work per VM, in seconds, WITHOUT
    // the currentTimeSec offset that vmFinishTime carries. Headroom/local-
    // impact must be based on this, not vmFinishTime -- see
    // currentCapacityHeadroom() changelog. Never exposed outside this class
    // except via the coarse capacity headroom figure (Assumption A2).
    private final double[] vmAssignedWorkSec;

    // Populated by scheduleLocalBatch(); local_plan is this provider's
    // own NSGA-II result over ONLY its own tasks and ONLY its own VMs.
    private Chromosome localPlan;
    private List<Cloudlet> ownedTasks;
    private int[] ownedTaskLengths;

    // ── Statistics (for evaluation, NOT a trust/reputation mechanism -- CPDS Sec 7) ──
    public int statLocalExecutions = 0;
    public int statCollaboratedOut = 0;   // tasks this provider offloaded successfully
    public int statFallbackToLocal = 0;   // sought collaboration but fell back
    public int statHostedForOthers = 0;   // tasks this provider accepted from others
    public int statOffersDeclined = 0;    // times this provider silently didn't respond to discovery
    public int statAdmissionsRejected = 0;
    public int statDeadlineRejections = 0; // subset of statAdmissionsRejected: host could not meet a DS deadline

    public CloudProvider(int globalDcIndex, List<Vm> localVms,
                          CarbonModel carbonModel, RenewableModel renewableModel,
                          double tauOffload, double tauHost) {
        this.globalDcIndex = globalDcIndex;
        this.localVms = localVms;
        this.carbonModel = carbonModel;
        this.renewableModel = renewableModel;
        this.tauOffload = tauOffload;
        this.tauHost = tauHost;
        this.vmFinishTime = new double[localVms.size()];
        this.vmAssignedWorkSec = new double[localVms.size()];
        // Default weight split: renewable/carbon weighted equally (0.35 each),
        // network cost 0.20, capacity 0.10 for U_C; hosting utility mirrors
        // the sustainability terms but weights local impact more heavily
        // (0.30) since protecting one's own future workload matters more
        // when deciding whether to host others' work (CDUM Sec 5 rationale).
        // These are illustrative defaults -- treat as tunable experimental
        // parameters, consistent with CPDS's own "illustrative default" framing.
        this.util = new CollaborationUtility(
            0.35, 0.35, 0.20, 0.10,   // w1..w4 (Collaboration Utility)
            0.30, 0.30, 0.15, 0.25);  // v1..v4 (Hosting Utility)
    }

    public void setCurrentTimeSec(double t) { this.currentTimeSec = t; }
    public List<Vm> getLocalVms() { return localVms; }

    // ── Local scheduling (batch, mirrors CNSGA2Scheduler's own usage pattern) ──

    /**
     * Runs this provider's own local NSGA-II, scoped to ONLY its own VMs
     * (numDCs=1 -- see PATCH_NSGAIIOptimizer.md). This is "reuse C-NSGA-II's
     * optimization engine, not redesign it" (Architecture v1.2 Principle 1),
     * applied per-provider rather than globally.
     */
    public void scheduleLocalBatch(List<Cloudlet> tasks) {
        this.ownedTasks = tasks;
        this.ownedTaskLengths = new int[tasks.size()];
        for (int i = 0; i < tasks.size(); i++) {
            ownedTaskLengths[i] = (int) tasks.get(i).getLength();
        }

        int[] vmMips = new int[localVms.size()];
        int[] vmTier = new int[localVms.size()];
        for (int v = 0; v < localVms.size(); v++) {
            vmMips[v] = (int) localVms.get(v).getMips();
            vmTier[v] = vmTierOf(localVms.get(v).getMips());
        }

        NSGAIIOptimizer optimizer = new NSGAIIOptimizer(
            carbonModel, renewableModel,
            tasks.size(), localVms.size(),
            ownedTaskLengths, vmMips, vmTier,
            currentTimeSec, /* numDCs = */ 1);
        optimizer.setGlobalDcOffset(globalDcIndex); // see PATCH_NSGAIIOptimizer.md

        this.localPlan = optimizer.run();
    }

    /**
     * U_C(local) for task at local index i, per CDUM Sec 3/Math Sec 4
     * Decision 1. Uses current (not per-task-start-shifted) forecast values
     * as a tractable proxy for the LCDE go/no-go decision -- the more
     * precise per-task start-time forecast is still what NSGA-II itself
     * used internally when computing the actual carbon objective; this
     * proxy only governs whether collaboration is *sought*, not the
     * energy/carbon accounting itself.
     */
    public double localUtilityForTask(int localIndex) {
        double renewable = renewableModel.getForecastRenewableFraction(globalDcIndex, currentTimeSec);
        double carbon = carbonModel.getForecastCarbonIntensity(globalDcIndex, currentTimeSec);
        double capacity = currentCapacityHeadroom();
        return util.collaborationUtilityLocal(renewable, carbon, capacity);
    }

    /** Optional real delay-sensitivity labels (cloudlet id -> DS), used by the trace-driven study; null = parity rule. */
    public static java.util.Map<Cloudlet, Boolean> dsLabels = null;   // identity-keyed: cloudlet ids are not unique before broker submission

    public boolean isDelaySensitive(int localIndex) {
        if (dsLabels != null && ownedTasks != null && localIndex >= 0 && localIndex < ownedTasks.size()) {
            Boolean b = dsLabels.get(ownedTasks.get(localIndex));
            if (b != null) return b;
        }
        // Matches existing codebase convention: even position-index = DS (MainSimulation, CNSGA2Scheduler)
        return (localIndex % 2 == 0);
    }

    /**
     * Ranks an incoming OFFER using THIS provider's own CollaborationUtility
     * instance (own rolling normalization history) -- CDUM Sec 2 requires
     * normalization to be independent per provider, and critically, the SAME
     * instance used to compute U_local must be reused here so U_local and
     * U(offer) are computed on a comparable normalized scale. Only the
     * initiator ever calls this (on itself), never on another provider's
     * instance -- CoordinationProtocol calls initiator.rankOfferUtility(...),
     * not peer.rankOfferUtility(...).
     */
    public double rankOfferUtility(SCA offer, int peerGlobalDcIndex) {
        double networkCost = estimateNetworkCost(peerGlobalDcIndex);
        return util.collaborationUtility(
            offer.forecastRenewableAvailability, offer.forecastCarbonIntensity,
            networkCost, offer.availableSchedulingCapacity);
    }

    // ── LCDE: Algorithm 2 ────────────────────────────────────────────────────

    public boolean lcdeSeeksCollaboration(int localIndex) {
        return lcdeSeeksCollaboration(localIndex, false);
    }

    /**
     * Extension (DS offloading): when allowDsOffload is true, delay-sensitive tasks
     * may also seek collaboration under the same U_C < tau_offload trigger. They are
     * then protected by a completion-time deadline that the host must be able to
     * meet (see evaluateAdmission with deadline). allowDsOffload=false reproduces
     * the original rule (DS defaults local, CDUM Sec 3).
     */
    public boolean lcdeSeeksCollaboration(int localIndex, boolean allowDsOffload) {
        if (isDelaySensitive(localIndex) && !allowDsOffload) return false;
        double uLocal = localUtilityForTask(localIndex);
        return uLocal < tauOffload;
    }

    /**
     * Initiator-side estimate of when task localIndex would complete if run locally,
     * relative to the current simulation time (queue delay on the VM it would use
     * + its execution time). Uses only this provider's own state.
     */
    public double projectedLocalCompletionRel(int localIndex) {
        int planned = localPlan.assignment[localIndex] % localVms.size();
        int least = leastLoadedVmIndex();
        int v = (vmFinishTime[least] + 1e-6 < vmFinishTime[planned]) ? least : planned;
        double exec = ownedTaskLengths[localIndex] / localVms.get(v).getMips();
        return Math.max(0.0, vmFinishTime[v] - currentTimeSec) + exec;
    }

    // ── Discovery response: "offer if willing" (CPDS Sec 3.3) ──────────────

    /**
     * Called by CoordinationProtocol during Phase 2 discovery. A provider
     * is never obligated to respond (Assumption A3) -- if headroom is
     * essentially exhausted, it silently declines (returns empty),
     * requiring no explicit rejection message, per CPDS Sec 3.2.
     */
    public Optional<SCA> maybeRespondToRequest(SCA request, double simTimeSec) {
        double headroom = currentCapacityHeadroom();
        if (headroom < 0.05) { // minimal-sanity threshold, tunable
            statOffersDeclined++;
            return Optional.empty();
        }
        double renewable = renewableModel.getForecastRenewableFraction(globalDcIndex, simTimeSec);
        double carbon = carbonModel.getForecastCarbonIntensity(globalDcIndex, simTimeSec);

        SCA offer = SCA.offerBuilder(globalDcIndex, request.taskCloudletId)
            .resourceEnvelope(request.vCpuRequired, request.memoryGbRequired, request.estDurationSec)
            .sustainability(carbon, renewable, headroom)
            .validity(simTimeSec, simTimeSec + SimulationConfig.MAX_DEFER_SECONDS)
            .build();
        return Optional.of(offer);
    }

    // ── Admission: Algorithm 4 / Decision 3, Hosting Utility ────────────────

    /**
     * Re-evaluates CURRENT state at admission time, not the state captured
     * when the OFFER was generated (CPDS Sec 5, step 2) -- this provider's
     * headroom may have changed if it accepted other commitments earlier
     * in this same simulation run.
     */
    public boolean evaluateAdmission(SCA request, int requesterGlobalDcIndex, double simTimeSec) {
        double renewableSelf = renewableModel.getForecastRenewableFraction(globalDcIndex, simTimeSec);
        double carbonSelf = carbonModel.getForecastCarbonIntensity(globalDcIndex, simTimeSec);
        double networkCost = estimateNetworkCost(requesterGlobalDcIndex);
        double localImpact = estimateLocalImpact(request);

        double uHost = util.hostingUtility(renewableSelf, carbonSelf, networkCost, localImpact);
        //System.out.printf("[ASCSF-DEBUG]     uHost=%.4f (tauHost=%.4f)%n", uHost, tauHost);
        boolean accept = uHost >= tauHost;
        if (!accept) statAdmissionsRejected++;
        return accept;
    }
    

    /**
     * Admission with an optional completion deadline carried in the REQUEST
     * (seconds from now). The host first checks that, given its own current load,
     * it can finish the task by the deadline; only then does the usual Hosting
     * Utility test (tau_host) apply. deadlineRelSec NaN or <= 0 means no deadline.
     */
    public boolean evaluateAdmission(SCA request, int requesterGlobalDcIndex,
                                     double simTimeSec, double deadlineRelSec) {
        if (!Double.isNaN(deadlineRelSec) && deadlineRelSec > 0) {
            double projected = Math.max(0.0, vmFinishTime[leastLoadedVmIndex()] - currentTimeSec)
                             + request.estDurationSec;
            if (projected > deadlineRelSec) {
                statDeadlineRejections++;
                statAdmissionsRejected++;
                return false;
            }
        }
        return evaluateAdmission(request, requesterGlobalDcIndex, simTimeSec);
    }

    // ── Commitment: bind an externally-accepted task to a local VM ─────────

    public int commitExternalTask(SCA request) {
        int vmIdx = leastLoadedVmIndex();
        double execTime = request.estDurationSec;
        vmFinishTime[vmIdx] = Math.max(vmFinishTime[vmIdx], currentTimeSec) + execTime;
        vmAssignedWorkSec[vmIdx] += execTime; // v1.2 fix: pure work, no currentTimeSec offset
        statHostedForOthers++;
        return vmIdx;
    }

    public int commitLocalTask(int localIndex) {
        int vmIdx = localPlan.assignment[localIndex] % localVms.size();
        // Simple load check: if NSGA-II's recommended VM is much busier than
        // the least-loaded option, prefer the least-loaded one instead
        // (mirrors the load-balancing refinement already present in
        // CNSGA2Scheduler.findLeastLoadedVM).
        int leastLoaded = leastLoadedVmIndex();
        if (vmFinishTime[leastLoaded] + 1e-6 < vmFinishTime[vmIdx]) {
            vmIdx = leastLoaded;
        }
        double execTime = ownedTaskLengths[localIndex] / localVms.get(vmIdx).getMips();
        vmFinishTime[vmIdx] = Math.max(vmFinishTime[vmIdx], currentTimeSec) + execTime;
        vmAssignedWorkSec[vmIdx] += execTime; // v1.2 fix: pure work, no currentTimeSec offset
        return vmIdx;
    }

    public Vm vmAt(int localVmIndex) { return localVms.get(localVmIndex); }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Coarse capacity headroom proxy in [0,1]: fraction of local VMs that
     * are currently idle or near-idle, relative to currentTimeSec. This is
     * an implementation-level choice (not fixed by CDUM/CPDS, which
     * deliberately left "Available_Scheduling_Capacity" and "Local Impact"
     * as operational quantities to be defined at implementation time --
     * SCA Information Model Sec 2.3 note).
     
    private double currentCapacityHeadroom() {
        int idleCount = 0;
        for (double ft : vmFinishTime) {
            if (ft <= currentTimeSec) idleCount++;
        }
        return (double) idleCount / vmFinishTime.length;
    }

    /**
     * Opportunity-cost proxy for Hosting Utility's L_hat term: how much of
     * this provider's remaining headroom the incoming request would consume.
     * Higher = more impact = less willing to host (CDUM Sec 5).
     
    private double estimateLocalImpact(SCA request) {
        double headroom = currentCapacityHeadroom();
        if (headroom < 1e-6) return 1.0; // no headroom left: maximal impact
        double demandFraction = 1.0 / Math.max(1, vmFinishTime.length); // one VM-slot's worth
        return Math.min(1.0, demandFraction / headroom);
    }
    */
 // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Utilization-based capacity headroom proxy in [0,1], per CDUM Sec 5's
     * "remaining scheduling capability" framing (not "idle relative to a
     * frozen simulation clock" -- see v1.1 changelog below).
     *
     * v1.1 fix: the original implementation compared each VM's finish time
     * against a fixed currentTimeSec that never advances during a batch
     * scheduling pass. Since vmFinishTime only increases as tasks commit,
     * idleCount monotonically collapsed to 0 and never recovered once
     * provider load exceeded VM capacity (confirmed via tau_offload sweep:
     * CollaboratedOut=0 and OffersDeclined large across all five tau_offload
     * values, with AdmissionsRejected=0 -- i.e. failing at discovery, before
     * admission was ever reached). This is now computed as relative load
     * (mean finish time vs. peak finish time across this provider's own
     * VMs), which stays meaningful regardless of absolute simulation time
     * and cannot collapse to a permanent zero.
     */
    private double currentCapacityHeadroom() {
        if (vmAssignedWorkSec.length == 0) return 0.0;
        double maxWork = 0.0;
        double totalWork = 0.0;
        for (double w : vmAssignedWorkSec) {
            totalWork += w;
            if (w > maxWork) maxWork = w;
        }
        if (maxWork < 1e-9) return 1.0; // nothing committed yet: fully idle
        double load = totalWork / (vmAssignedWorkSec.length * maxWork);
        return Math.max(0.0, Math.min(1.0, 1.0 - load));
    }

    private double estimateLocalImpact(SCA request) {
        double headroom = currentCapacityHeadroom();
        if (headroom < 1e-6) return 1.0; // no headroom left: maximal impact

        double maxWork = 0.0;
        for (double w : vmAssignedWorkSec) if (w > maxWork) maxWork = w;

        double baseSlotFraction = 1.0 / Math.max(1, vmAssignedWorkSec.length);
        double durationFactor = 1.0; // neutral if no meaningful reference duration yet
        if (maxWork > 1e-9 && request.estDurationSec > 0) {
            double typicalSlice = maxWork / Math.max(1, vmAssignedWorkSec.length);
            if (typicalSlice > 1e-9) {
                durationFactor = request.estDurationSec / typicalSlice;
            }
        }
        double demandFraction = Math.min(1.0, baseSlotFraction * durationFactor);
        return Math.min(1.0, demandFraction / headroom);
    }

    /**
     * Simple network cost proxy keyed by DC-pair "distance" (Nordic=0,
     * Europe=1, Fossil=2 in this simulation's convention) -- an
     * implementation stand-in for a real topology/latency model. Receiver-
     * computed per SCA Information Model Sec 3 (never issuer-declared).
     */
    private double estimateNetworkCost(int otherGlobalDcIndex) {
        // Normalised so the maximum cost stays 2 for any provider count (identical to |i-j| when N = 3).
        int n = sim.SimulationConfig.NUM_DATACENTERS;
        return 2.0 * Math.abs(this.globalDcIndex - otherGlobalDcIndex) / Math.max(1, n - 1);
    }

    private int leastLoadedVmIndex() {
        int best = 0;
        for (int i = 1; i < vmFinishTime.length; i++) {
            if (vmFinishTime[i] < vmFinishTime[best]) best = i;
        }
        return best;
    }

    private static int vmTierOf(double mips) {
        if (mips <= 500)  return 0;
        if (mips <= 1000) return 1;
        if (mips <= 2000) return 2;
        return 3;
    }
}