package sim.ascsf;

/**
 * Implements CDUM v1.1: the Collaboration Utility (U_C) and the Hosting
 * Utility (U_H).
 *
 * REVISED: renewable fraction, capacity, and local-impact are already
 * naturally bounded in [0,1] in this codebase's data convention (see
 * CarbonModel/RenewableModel fallback profiles, e.g. 0.15/0.42/0.74),
 * so they are used directly rather than through rolling min-max
 * normalization. Rolling normalization was found to degenerate to a
 * constant 0.5 when a provider repeatedly queries the same DC at a
 * fixed simulation time (no real time advancement between admission
 * calls in this synchronous implementation) -- with equal renewable/
 * carbon weights, that made renewable and carbon terms cancel exactly,
 * causing hosting utility to always fall below threshold. Only network
 * cost (an arbitrary-scale DC-distance proxy, not naturally bounded)
 * still needs scaling, done here with a fixed divisor rather than a
 * rolling window, avoiding the same degeneracy.
 */
public class CollaborationUtility {

    // Fixed scale for network cost: max possible DC-distance in this
    // simulation's 3-DC convention (0,1,2) is 2.
    private static final double MAX_NETWORK_COST = 2.0;

    private final double w1_renewable;
    private final double w2_carbon;
    private final double w3_network;
    private final double w4_capacity;

    private final double v1_renewable;
    private final double v2_carbon;
    private final double v3_network;
    private final double v4_localImpact;

    public CollaborationUtility(
            double w1_renewable, double w2_carbon, double w3_network, double w4_capacity,
            double v1_renewable, double v2_carbon, double v3_network, double v4_localImpact) {
        double wSum = w1_renewable + w2_carbon + w3_network + w4_capacity;
        double vSum = v1_renewable + v2_carbon + v3_network + v4_localImpact;
        if (Math.abs(wSum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("Collaboration Utility weights must sum to 1 (CDUM Sec 3.1), got " + wSum);
        }
        if (Math.abs(vSum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("Hosting Utility weights must sum to 1 (CDUM Sec 5), got " + vSum);
        }
        this.w1_renewable = w1_renewable; this.w2_carbon = w2_carbon;
        this.w3_network = w3_network; this.w4_capacity = w4_capacity;
        this.v1_renewable = v1_renewable; this.v2_carbon = v2_carbon;
        this.v3_network = v3_network; this.v4_localImpact = v4_localImpact;
    }

    private double scaleNetworkCost(double raw) {
        return Math.max(0.0, Math.min(1.0, raw / MAX_NETWORK_COST));
    }

    /**
     * @param renewable   raw forecast renewable availability [0,1] at option x
     * @param carbon      raw forecast carbon intensity [0,1]-scale at option x
     *                    (this codebase's convention -- see CarbonModel)
     * @param networkCost raw network/migration cost; MUST be 0.0 for local
     *                    execution by construction (CDUM Sec 2, N_hat(local)=0)
     * @param capacity    raw available scheduling capacity [0,1] at option x
     */
    public double collaborationUtility(double renewable, double carbon, double networkCost, double capacity) {
        double rHat = clamp01(renewable);
        double cHat = clamp01(carbon);
        double nHat = (networkCost == 0.0) ? 0.0 : scaleNetworkCost(networkCost);
        double aHat = clamp01(capacity);
        return w1_renewable * rHat - w2_carbon * cHat - w3_network * nHat + w4_capacity * aHat;
    }

    public double collaborationUtilityLocal(double renewable, double carbon, double capacity) {
        return collaborationUtility(renewable, carbon, 0.0, capacity);
    }

    /**
     * @param renewableSelf     receiver's own forecast renewable availability
     * @param carbonSelf        receiver's own forecast carbon intensity
     * @param networkCostSelfToRequester receiver's own estimate of link cost to requester
     * @param localImpact       normalized opportunity cost [0,1] to receiver's own future workload
     */
    public double hostingUtility(double renewableSelf, double carbonSelf,
                                  double networkCostSelfToRequester, double localImpact) {
        double rHat = clamp01(renewableSelf);
        double cHat = clamp01(carbonSelf);
        double nHat = scaleNetworkCost(networkCostSelfToRequester);
        double lHat = clamp01(localImpact);
        return v1_renewable * rHat - v2_carbon * cHat - v3_network * nHat - v4_localImpact * lHat;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}