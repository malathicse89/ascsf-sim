package sim.nsga2;

import java.util.Arrays;
import java.util.Random;

/**
 * Represents one candidate scheduling solution (chromosome)
 * in the NSGA-II population.
 *
 * Encoding:
 *   assignment[i] = VM index assigned to task i
 *   dcAssignment[i] = DC index (0/1/2) for task i
 *
 * Three objectives are stored separately — never combined.
 * This is fundamental to multi-objective optimisation:
 * no single weighted sum can capture all Pareto trade-offs.
 *
 *   f1 = makespan        (seconds)         → minimise
 *   f2 = total energy    (kWh)             → minimise
 *   f3 = carbon emissions (kgCO2e)         → minimise
 *
 * NSGA-II uses rank and crowdingDistance to select survivors.
 * rank=1 means Pareto-optimal (non-dominated).
 */
public class Chromosome implements Comparable<Chromosome> {

    // ── Gene encoding ─────────────────────────────────────────────────────────
    // assignment[i] = VM index for task i (within the DC)
    public int[] assignment;

    // dcAssignment[i] = DC index for task i (0=Nordic,1=Europe,2=Fossil)
    public int[] dcAssignment;

    // Number of tasks
    public final int numTasks;

    // ── Objective values ──────────────────────────────────────────────────────
    public double f1Makespan;    // seconds
    public double f2Energy;      // kWh
    public double f3Carbon;      // kgCO2e

    // ── NSGA-II fields ────────────────────────────────────────────────────────
    // Pareto front rank (1 = non-dominated = best)
    public int rank;

    // Crowding distance — higher = more isolated = preferred when rank ties
    public double crowdingDistance;

    // Number of solutions that dominate this one (used during sorting)
    public int dominationCount;

    // ── Constructor ───────────────────────────────────────────────────────────
    public Chromosome(int numTasks) {
        this.numTasks = numTasks;
        this.assignment = new int[numTasks];
        this.dcAssignment = new int[numTasks];
        this.rank = 0;
        this.crowdingDistance = 0.0;
        this.dominationCount = 0;
        this.f1Makespan = Double.MAX_VALUE;
        this.f2Energy   = Double.MAX_VALUE;
        this.f3Carbon   = Double.MAX_VALUE;
    }

    // ── Random initialisation ─────────────────────────────────────────────────
    /**
     * Randomly assigns each task to a VM and DC.
     * Called when building the initial population.
     *
     * @param numVmsPerDC  number of VMs available at each DC
     * @param numDCs       number of data centres (3)
     * @param rng          shared Random instance
     */
    public void randomise(int numVmsPerDC, int numDCs, Random rng) {
        for (int i = 0; i < numTasks; i++) {
            dcAssignment[i] = rng.nextInt(numDCs);
            assignment[i]   = rng.nextInt(numVmsPerDC);
        }
    }

    // ── Dominance check ───────────────────────────────────────────────────────
    /**
     * Returns true if THIS chromosome dominates OTHER.
     *
     * A dominates B if:
     *   - A is no worse than B on ALL objectives, AND
     *   - A is strictly better than B on AT LEAST ONE objective
     *
     * This is the core rule of Pareto optimality.
     * We never say one solution is "better" overall —
     * only that it dominates another.
     *
     * @param other  the chromosome to compare against
     * @return true if this dominates other
     */
    public boolean dominates(Chromosome other) {
        boolean atLeastOneBetter = false;

        // Check all three objectives (all are minimisation)
        if (this.f1Makespan > other.f1Makespan) return false;
        if (this.f2Energy   > other.f2Energy)   return false;
        if (this.f3Carbon   > other.f3Carbon)   return false;

        if (this.f1Makespan < other.f1Makespan) atLeastOneBetter = true;
        if (this.f2Energy   < other.f2Energy)   atLeastOneBetter = true;
        if (this.f3Carbon   < other.f3Carbon)   atLeastOneBetter = true;

        return atLeastOneBetter;
    }

    // ── Deep copy ────────────────────────────────────────────────────────────
    /**
     * Creates an independent copy of this chromosome.
     * Needed for crossover and mutation — we never modify
     * a chromosome in-place during genetic operations.
     */
    public Chromosome copy() {
        Chromosome c = new Chromosome(numTasks);
        c.assignment   = Arrays.copyOf(this.assignment, numTasks);
        c.dcAssignment = Arrays.copyOf(this.dcAssignment, numTasks);
        c.f1Makespan   = this.f1Makespan;
        c.f2Energy     = this.f2Energy;
        c.f3Carbon     = this.f3Carbon;
        c.rank         = this.rank;
        c.crowdingDistance = this.crowdingDistance;
        return c;
    }

    // ── Comparable: used for sorting by rank then crowding distance ──────────
    /**
     * NSGA-II tournament selection rule:
     *   1. Prefer lower rank (closer to Pareto front)
     *   2. If same rank, prefer higher crowding distance (more isolated)
     *
     * This comparison is used directly in Collections.sort()
     * during survivor selection.
     */
    @Override
    public int compareTo(Chromosome other) {
        if (this.rank != other.rank) {
            return Integer.compare(this.rank, other.rank);
        }
        // Higher crowding distance is better → reverse order
        return Double.compare(other.crowdingDistance, this.crowdingDistance);
    }

    // ── Debug print ──────────────────────────────────────────────────────────
    @Override
    public String toString() {
        return String.format(
            "Chromosome[rank=%d, cd=%.3f, f1=%.1fs, f2=%.4fkWh, f3=%.4fkgCO2]",
            rank, crowdingDistance, f1Makespan, f2Energy, f3Carbon);
    }
}