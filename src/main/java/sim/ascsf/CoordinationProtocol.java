package sim.ascsf;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;

import java.util.*;

/**
 * Implements CPDS v1.1 Phases 2-6 (Discovery, Selection, Admission,
 * Commitment) for a single task. Phase 1 (Trigger) lives in
 * CloudProvider.lcdeSeeksCollaboration(); Phase 7 (Execution/Feedback) is
 * simplified in this implementation -- see class-level note below.
 *
 * Simplification note (implementation-level, not a design-spec change):
 * the existing C-NSGA-II codebase precomputes all scheduling decisions
 * before CloudSim Plus's simulation.start() runs (see MainSimulation /
 * CNSGA2Scheduler) -- scheduling itself is not event-driven in this
 * codebase. ASCSF follows the same pattern: Phases 2-5 execute
 * synchronously in plain Java (no simulated network delay for the
 * response window W or lease timers). Providers still only exchange SCA
 * objects through the narrow interfaces on CloudProvider (never reach
 * into each other's internal state), so the protocol's information-
 * exchange discipline is preserved even though wall-clock asynchrony is
 * not modeled in this first implementation. Upgrading to a fully
 * event-driven CloudSim Plus implementation (real message-passing with
 * simulated delay) is a natural extension, noted for the Implementation
 * section's limitations paragraph.
 */
public class CoordinationProtocol {

    public enum Outcome { COMMITTED, FALLBACK }

    public static class Result {
        public final Outcome outcome;
        public final CloudProvider committedProvider; // null if FALLBACK
        public final int committedVmIndex;             // -1 if FALLBACK
        Result(Outcome o, CloudProvider p, int vmIdx) {
            this.outcome = o; this.committedProvider = p; this.committedVmIndex = vmIdx;
        }
    }

    // Coordination-overhead counters (for the scalability analysis)
    public long discoveryRequests = 0;   // discovery requests sent to peers
    public long offersReceived    = 0;   // SCA offers returned
    public long admissionAttempts = 0;   // admission requests sent

    private final int rMax; // CPDS Sec 6 -- max TOTAL admission attempts, including the first

    // ── Optional message-loss model (reviewer study); lossProb = 0 reproduces the original behaviour exactly ──
    private double lossProb = 0.0;
    private int eMax = 2;                 // max discovery/admission episodes per task (paper Algorithm 1)
    private java.util.Random lossRng = new java.util.Random(1);
    private boolean episodeHadLoss = false;
    public long messagesLost = 0;         // discovery/offer/admission messages dropped
    public long leaseExpiries = 0;        // admission exchanges that ended because a message was lost (tentative lease lapses, nothing committed)
    public long episodeRetries = 0;       // second episodes started after a loss
    public void setLoss(double p, long seed) { this.lossProb = p; this.lossRng = new java.util.Random(seed); }
    public void setEMax(int e) { this.eMax = e; }
    private boolean lost() {
        if (lossProb > 0 && lossRng.nextDouble() < lossProb) { messagesLost++; episodeHadLoss = true; return true; }
        return false;
    }

    public CoordinationProtocol(int rMax) {
        this.rMax = rMax;
    }

    /**
     * Executes Phases 2-5 for one task on behalf of {@code initiator}.
     *
     * @param initiator     the provider seeking collaboration (already ran
     *                      LCDE and decided SEEK_COLLABORATION)
     * @param localIndex    task's index within initiator's owned-task batch
     * @param task          the Cloudlet itself (for resource envelope fields)
     * @param uLocal        initiator's own U_C(local), fixed baseline (CDUM Sec 3/4 -- not recomputed)
     * @param candidates    initiator's bounded candidate set K_i (CPDS Sec 3.1)
     * @param simTimeSec    current simulation time
     */
    public Result run(CloudProvider initiator, int localIndex, Cloudlet task,
            double uLocal, List<CloudProvider> candidates, double simTimeSec) {
        return run(initiator, localIndex, task, uLocal, candidates, simTimeSec, Double.NaN);
    }

    /**
     * Same as above, with an optional completion deadline (seconds from now) carried
     * in the request. Used for delay-sensitive tasks when DS offloading is enabled;
     * NaN means no deadline (original behaviour).
     */
    public Result run(CloudProvider initiator, int localIndex, Cloudlet task,
            double uLocal, List<CloudProvider> candidates, double simTimeSec,
            double deadlineRelSec) {
        Result r = null;
        for (int e = 1; e <= Math.max(1, eMax); e++) {
            episodeHadLoss = false;
            r = runEpisode(initiator, localIndex, task, uLocal, candidates, simTimeSec, deadlineRelSec);
            if (r.outcome == Outcome.COMMITTED || !episodeHadLoss) return r;   // success, or a genuine (loss-free) fallback
            if (e < eMax) episodeRetries++;
        }
        return r;
    }

    private Result runEpisode(CloudProvider initiator, int localIndex, Cloudlet task,
            double uLocal, List<CloudProvider> candidates, double simTimeSec,
            double deadlineRelSec) {

// ── Phase 2: Discovery ──────────────────────────────────────────────
SCA.TaskType type = initiator.isDelaySensitive(localIndex) ? SCA.TaskType.DS : SCA.TaskType.DT;
SCA request = SCA.requestBuilder(initiator.globalDcIndex, task.getId())
 .taskType(type)
 .resourceEnvelope(2, 0, task.getLength() / averageMips(candidates))
 .validity(simTimeSec, simTimeSec + 300.0)
 .build();

List<SCA> offers = new ArrayList<>();
Map<String, CloudProvider> offerOwner = new HashMap<>();
for (CloudProvider candidate : candidates) {
 if (candidate == initiator) continue;
 discoveryRequests++;
 if (lost()) continue;                                   // request lost: candidate never sees it
 Optional<SCA> maybeOffer = candidate.maybeRespondToRequest(request, simTimeSec);
 if (maybeOffer.isPresent() && lost()) continue;         // offer lost on the way back
 maybeOffer.ifPresent(offer -> {
     offers.add(offer);
     offersReceived++;
     offerOwner.put(offer.scaId, candidate);
 });
}



// ── Phase 3: Selection (CDUM Sec 4 / Math Sec 4 Decision 2) ─────────
List<SCA> filtered = new ArrayList<>();
Map<String, Double> offerUtility = new HashMap<>();
for (SCA offer : offers) {
 if (offer.isExpired(simTimeSec)) continue;
 CloudProvider owner = offerOwner.get(offer.scaId);
 double uOffer = initiator.rankOfferUtility(offer, owner.globalDcIndex);



 if (uOffer > uLocal) {
     filtered.add(offer);
     offerUtility.put(offer.scaId, uOffer);
 }
}

if (filtered.isEmpty()) {
 return new Result(Outcome.FALLBACK, null, -1);
}

filtered.sort((a, b) -> Double.compare(offerUtility.get(b.scaId), offerUtility.get(a.scaId)));
Deque<SCA> fallbackQueue = new ArrayDeque<>(filtered);

// ── Phase 4/5: Admission and Commitment, bounded by rMax ────────────
int attempts = 0;
while (!fallbackQueue.isEmpty() && attempts < rMax) {
 SCA candidateOffer = fallbackQueue.pollFirst();
 CloudProvider peer = offerOwner.get(candidateOffer.scaId);
 attempts++;
 admissionAttempts++;

 if (lost()) { leaseExpiries++; continue; }              // admission request lost: initiator times out, attempt consumed
 boolean accepted = peer.evaluateAdmission(request, initiator.globalDcIndex, simTimeSec, deadlineRelSec);
 if (accepted && lost()) { leaseExpiries++; continue; }  // acceptance lost: tentative lease at the host lapses, nothing committed



 if (accepted) {
     int vmIdx = peer.commitExternalTask(request);
     return new Result(Outcome.COMMITTED, peer, vmIdx);
 }
}

return new Result(Outcome.FALLBACK, null, -1);
}

    // ── Helpers ──────────────────────────────────────────────────────────────

    private double averageMips(List<CloudProvider> candidates) {
        double sum = 0; int n = 0;
        for (CloudProvider p : candidates) {
            for (var vm : p.getLocalVms()) { sum += vm.getMips(); n++; }
        }
        return n == 0 ? 1000.0 : sum / n;
    }
}