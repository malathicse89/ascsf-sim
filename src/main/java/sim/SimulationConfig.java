package sim;

import java.util.ArrayList;
import java.util.List;

/**
 * Central configuration for the ASCSF simulation.
 * All parameters are defined here so sensitivity analysis
 * can be performed by changing a single value and re-running.
 *
 * Paper: ASCSF (submitted to IEEE Transactions on Sustainable Computing)
 * Author: Malathi S., Sri Krishna College of Engineering and Technology
 */
public class SimulationConfig {

    // ── Workload scales ──────────────────────────────────────────────────────
    public static final int TASKS_SMALL  = 500;
    public static final int TASKS_MEDIUM = 1000;
    public static final int TASKS_LARGE  = 2000;

    // ── Data centers ─────────────────────────────────────────────────────────
    // Provider count is configurable for the scalability study (3 / 5 / 10).
    // Total VM capacity is held constant (TOTAL_VMS), so VMS_PER_DC = TOTAL_VMS / providers.
    public static final int TOTAL_VMS = 180;
    public static int NUM_DATACENTERS = 3;
    public static int VMS_PER_DC = 60;
    /** Speed class per provider: 0 = boosted (Nordic-like), 1 = standard, 2 = reduced. Matches the profile rows in carbon.csv / renewable.csv. */
    public static final int[] DC_SPEED_CLASS = {0, 1, 2, 0, 1, 2, 1, 0, 2, 1};
    // ── Task-ownership pattern (null = round-robin, the default used in all main experiments) ──
    private static int[] OWNER_PATTERN = null;
    /** weights e.g. {6,3,1}: provider 0 owns 60% of tasks, provider 1 30%, provider 2 10% (interleaved, deterministic). null = round-robin. */
    public static void setOwnershipWeights(double[] w) {
        if (w == null) { OWNER_PATTERN = null; return; }
        int slots = 10; double tot = 0; for (double x : w) tot += x;
        int[] cnt = new int[w.length]; int used = 0;
        for (int p = 0; p < w.length; p++) { cnt[p] = (int) Math.floor(slots * w[p] / tot); used += cnt[p]; }
        for (int p = 0; used < slots; p = (p + 1) % w.length) { cnt[p]++; used++; }
        List<double[]> pos = new ArrayList<>();
        for (int p = 0; p < w.length; p++) for (int j = 0; j < cnt[p]; j++) pos.add(new double[]{(j + 0.5) / cnt[p], p});
        pos.sort((a, b) -> Double.compare(a[0], b[0]));
        OWNER_PATTERN = new int[pos.size()];
        for (int i = 0; i < pos.size(); i++) OWNER_PATTERN[i] = (int) pos.get(i)[1];
    }
    public static int ownerOf(int taskIndex, int numProviders) {
        return (OWNER_PATTERN == null) ? taskIndex % numProviders : OWNER_PATTERN[taskIndex % OWNER_PATTERN.length];
    }

    public static void setProviders(int n) {
        if (n < 3 || n > DC_SPEED_CLASS.length) throw new IllegalArgumentException("providers must be 3.." + DC_SPEED_CLASS.length);
        NUM_DATACENTERS = n;
        VMS_PER_DC = TOTAL_VMS / n;
    }
    // DC index 0 = Nordic (high-RE, low-carbon)
    // DC index 1 = Central Europe (mixed)
    // DC index 2 = Fossil-heavy region

    // ── VMs per data center ──────────────────────────────────────────────────
    public static final int VMS_PER_DC_MIN = 20;
    public static final int VMS_PER_DC_MAX = 60;

    // VM tiers: MIPS values representing 2/4/8/16 vCPU equivalents
    public static final int[] VM_MIPS_TIERS = {500, 1000, 2000, 4000};

    // ── NSGA-II parameters ───────────────────────────────────────────────────
    public static final int POPULATION_SIZE   = 100;
    public static final int NUM_GENERATIONS   = 200;
    public static final double CROSSOVER_RATE = 0.90;
    // Mutation rate = 1/N where N = number of tasks (set dynamically)

    // ── C-NSGA-II utility weights ───────────────────────────────────────────────
    // U(k,t) = alpha*RF - beta*CI + gamma*W
    // alpha = renewable reward, beta = carbon penalty, gamma = headroom bonus
    public static final double WEIGHT_ALPHA = 0.4;  // renewable fraction weight
    public static final double WEIGHT_BETA  = 0.4;  // carbon intensity penalty
    public static final double WEIGHT_GAMMA = 0.2;  // VM headroom weight
    
 // ── ASCSF parameters (illustrative defaults; treat as experimental) ────────
    public static final double ASCSF_TAU_OFFLOAD = 0.5;  // LCDE threshold (CDUM Sec 3)
    public static final double ASCSF_TAU_HOST    = 0.05;  // Hosting Utility threshold (CDUM Sec 5)
    public static final int    ASCSF_R_MAX       = 3;     // max total admission attempts (CPDS Sec 6)
    
 // ── ASCSF tau_offload sensitivity sweep ─────────────────────────────
    public static final double[] ASCSF_TAU_OFFLOAD_SWEEP = {0.50};
    public static final int    ASCSF_SWEEP_TRIALS = 30;
    public static final String ASCSF_SWEEP_RESULTS_FILE = "ascsf_tauhost_-0.70.csv";
    

    // Utility threshold: DT task dispatched when U >= UTILITY_THRESHOLD
    public static final double UTILITY_THRESHOLD = 0.5;

    // ── Forecasting ──────────────────────────────────────────────────────────
    // Look-ahead horizon in hours
    public static final int FORECAST_HORIZON_HOURS = 4;

    // ── Power model (SPEC CPU2017 linear interpolation) ──────────────────────
    // P(u) = P_idle + (P_peak - P_idle) * u
    // Values in Watts per VM tier (index matches VM_MIPS_TIERS)
    public static final double[] VM_POWER_IDLE = {50.0, 80.0, 120.0, 180.0};
    public static final double[] VM_POWER_PEAK = {150.0, 250.0, 380.0, 560.0};

    // ── Simulation trials ────────────────────────────────────────────────────
    public static final int NUM_TRIALS = 30;

    // ── Task characteristics ─────────────────────────────────────────────────
    // Cloudlet length range in MI (Million Instructions)
    public static final int CLOUDLET_LENGTH_MIN = 5000;
    public static final int CLOUDLET_LENGTH_MAX = 50000;

    // Fraction of tasks that are Delay-Tolerant (DT)
    // Remaining fraction are Delay-Sensitive (DS)
    public static final double DT_TASK_FRACTION = 0.5;

    // Maximum deferral for DT tasks (seconds)
    public static final double MAX_DEFER_SECONDS = 300.0;

    // ── Noise model ──────────────────────────────────────────────────────────
    // Gaussian noise applied to renewable/carbon profiles
    // to simulate forecast-to-actual mismatch
    public static double NOISE_STD_DEV = 0.05;   // non-final: varied in the robustness study

    // ── Output ───────────────────────────────────────────────────────────────
    public static final String RESULTS_FILE = "simulation_results.csv";
    public static final String COLLABORATION_STATS_FILE = "collaboration_stats.csv";
}