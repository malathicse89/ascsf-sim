package sim.ascsf;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;

import sim.SimulationConfig;
import sim.model.CarbonModel;
import sim.model.RenewableModel;

import java.util.*;

/**
 * Decentralized comparison baselines for ASCSF (reviewer-requested).
 *
 * Same setting as ASCSF: one autonomous CloudProvider per DC, round-robin
 * initial task ownership, each provider runs its OWN local NSGA-II over its
 * own tasks/VMs, delay-sensitive (even-index) tasks always stay local.
 * What differs is ONLY the delay-tolerant (odd-index) collaboration rule:
 *
 *  GREEDY_BLIND : every provider offloads each DT task to the peer whose
 *                 advertised grid score (renewable - carbon) is highest, if
 *                 that beats its own score. No utility threshold (tau_offload),
 *                 no hosting decision (tau_host), no lease/retry: the peer
 *                 cannot refuse and does not check its own load.
 *
 *  GREEDY_CAP   : as GREEDY_BLIND, but a peer silently declines when it has
 *                 no spare capacity (same headroom < 0.05 rule ASCSF peers use,
 *                 via maybeRespondToRequest). Still no tau_offload, no
 *                 tau_host, no cost/local-impact reasoning.
 *
 *  GREEDY_ALL   : DIAGNOSTIC ONLY. Like GREEDY_CAP, but delay-sensitive tasks may ALSO be
 *                 offloaded (ownership constraint removed). Measures how much of the gap
 *                 to centralized C-NSGA-II comes from pinning DS tasks to their owner DC.
 *
 * Together they bracket ASCSF: they use the same signals but remove the
 * autonomy/admission machinery, so any difference is attributable to it.
 */
public class DecentralizedPeerScheduler {

    public enum Mode { GREEDY_BLIND, GREEDY_CAP, GREEDY_ALL }

    private final CarbonModel carbonModel;
    private final RenewableModel renewableModel;
    private final Mode mode;
    private double currentTimeSec;

    public List<CloudProvider> lastRunProviders;

    public DecentralizedPeerScheduler(CarbonModel c, RenewableModel r, Mode mode) {
        this.carbonModel = c;
        this.renewableModel = r;
        this.mode = mode;
    }

    public void setCurrentTimeSec(double t) { this.currentTimeSec = t; }

    /** Same DTO as ASCSF so the CSV writer can be shared. */
    public ASCSFScheduler.CollaborationStats aggregateStats() {
        int local = 0, collab = 0, fallback = 0, hosted = 0, declined = 0, rejected = 0;
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
        return new ASCSFScheduler.CollaborationStats(
            local + collab + fallback, local, collab, fallback, hosted, declined, rejected);
    }

    public void scheduleAndBind(DatacenterBrokerSimple broker,
                                List<Cloudlet> cloudlets, List<Vm> vms) {
        if (vms == null || vms.isEmpty()) return;

        int numDCs = SimulationConfig.NUM_DATACENTERS;
        int vmsPerDC = Math.max(1, vms.size() / numDCs);

        List<CloudProvider> providers = new ArrayList<>();
        for (int dc = 0; dc < numDCs; dc++) {
            List<Vm> localVms = vms.subList(dc * vmsPerDC, Math.min((dc + 1) * vmsPerDC, vms.size()));
            // tauOffload/tauHost are unused by this scheduler (never consulted).
            CloudProvider p = new CloudProvider(dc, localVms, carbonModel, renewableModel, 0.0, 0.0);
            p.setCurrentTimeSec(currentTimeSec);
            providers.add(p);
        }
        this.lastRunProviders = providers;

        List<List<Cloudlet>> owned = new ArrayList<>();
        for (int dc = 0; dc < numDCs; dc++) owned.add(new ArrayList<>());
        for (int i = 0; i < cloudlets.size(); i++) owned.get(SimulationConfig.ownerOf(i, numDCs)).add(cloudlets.get(i));

        for (int dc = 0; dc < numDCs; dc++) {
            if (!owned.get(dc).isEmpty()) providers.get(dc).scheduleLocalBatch(owned.get(dc));
        }

        double avgMips = averageMips(providers);

        for (int dc = 0; dc < numDCs; dc++) {
            CloudProvider me = providers.get(dc);
            List<Cloudlet> myTasks = owned.get(dc);

            for (int idx = 0; idx < myTasks.size(); idx++) {
                Cloudlet task = myTasks.get(idx);

                if (me.isDelaySensitive(idx) && mode != Mode.GREEDY_ALL) {   // DS stays local (as in ASCSF)
                    bindLocal(me, idx, task);
                    me.statLocalExecutions++;
                    continue;
                }

                double myScore = score(me.globalDcIndex);
                CloudProvider best = null;
                double bestScore = myScore;                // must strictly beat own score

                SCA request = SCA.requestBuilder(me.globalDcIndex, task.getId())
                    .taskType(me.isDelaySensitive(idx) ? SCA.TaskType.DS : SCA.TaskType.DT)
                    .resourceEnvelope(2, 0, task.getLength() / avgMips)
                    .validity(currentTimeSec, currentTimeSec + 300.0)
                    .build();

                for (CloudProvider peer : providers) {
                    if (peer == me) continue;
                    if (mode != Mode.GREEDY_BLIND
                            && !peer.maybeRespondToRequest(request, currentTimeSec).isPresent()) {
                        continue;                          // peer out of headroom -> no offer
                    }
                    double s = score(peer.globalDcIndex);
                    if (s > bestScore) { bestScore = s; best = peer; }
                }

                if (best == null) {
                    bindLocal(me, idx, task);
                    me.statFallbackToLocal++;              // wanted to offload, no better/available peer
                    // NOTE: when no peer beats local this counts as "fallback", same bucket ASCSF uses
                } else {
                    int vmIdx = best.commitExternalTask(request);   // unconditional, no admission test
                    task.setVm(best.vmAt(vmIdx));
                    me.statCollaboratedOut++;
                }
            }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private double score(int dcIndex) {
        // advertised (forecast) signals, same source and noise model ASCSF peers advertise
        double rf = renewableModel.getForecastRenewableFraction(dcIndex, currentTimeSec);
        double ci = carbonModel.getForecastCarbonIntensity(dcIndex, currentTimeSec);
        return rf - ci;                                    // both already in ~[0,1]
    }

    private void bindLocal(CloudProvider p, int localIdx, Cloudlet task) {
        int vmIdx = p.commitLocalTask(localIdx);
        task.setVm(p.vmAt(vmIdx));
    }

    private double averageMips(List<CloudProvider> providers) {
        double sum = 0; int n = 0;
        for (CloudProvider p : providers) for (Vm vm : p.getLocalVms()) { sum += vm.getMips(); n++; }
        return n == 0 ? 1000.0 : sum / n;
    }
}
