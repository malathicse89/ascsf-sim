package sim.nsga2;

import sim.SimulationConfig;
import sim.model.CarbonModel;
import sim.model.RenewableModel;

import java.util.*;

/**
 * Full NSGA-II implementation for multi-objective task scheduling.
 *
 * Three objectives optimised simultaneously:
 *   f1 = Makespan        (minimise)
 *   f2 = Total Energy    (minimise)
 *   f3 = Carbon Emissions(minimise)
 *
 * Why NSGA-II and not a weighted-sum approach?
 * A weighted sum collapses three objectives into one number,
 * forcing the designer to pre-commit to a trade-off ratio.
 * NSGA-II instead produces a SET of Pareto-optimal solutions —
 * the scheduler (or operator) can then pick the operating point
 * that best matches current priorities without re-running.
 *
 * Algorithm outline (one generation):
 *   1. Evaluate objectives for every chromosome
 *   2. Non-dominated sorting → assign Pareto ranks
 *   3. Crowding distance → preserve diversity within each front
 *   4. Binary tournament selection
 *   5. Two-point crossover (pc=0.9)
 *   6. Bitwise mutation (pm=1/N)
 *   7. Merge parents + offspring, keep best Ps by (rank, distance)
 */
public class NSGAIIOptimizer {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private final CarbonModel   carbonModel;
    private final RenewableModel renewableModel;
    private final Random rng;

    // ── Problem dimensions ────────────────────────────────────────────────────
    private final int numTasks;
    private final int numVmsPerDC;
    private final int numDCs;

    // Task lengths in MI (Million Instructions)
    private final int[] taskLengths;

    // VM MIPS values — one per VM slot
    // vmMips[vmIndex] = processing speed in MIPS
    private final int[] vmMips;

    // VM tier index — used to look up power model values
    // vmTier[vmIndex] = index into SimulationConfig.VM_POWER_IDLE/PEAK
    private final int[] vmTier;

    // Current simulation time — used for forecast lookup
    private double currentTimeSec;

    // ── ASCSF extension ──────────────────────────────────────────────────────
    // Maps this optimizer's LOCAL dcAssignment values (always 0 when numDCs=1,
    // i.e. a single autonomous CloudProvider's own scope) to the REAL global
    // DC index used to index into CarbonModel/RenewableModel (0=Nordic,
    // 1=Europe, 2=Fossil). Defaults to 0, which makes "local + offset" equal
    // to "local" unchanged — so all 5 existing callers (CASPER, C-NSGA-II,
    // EAS, MinMin, RR) that never call setGlobalDcOffset(...) behave exactly
    // as before this patch.
    private int globalDcOffset = 0;

    // ── Constructor ───────────────────────────────────────────────────────────
    /**
     * @param carbonModel    loaded carbon intensity profiles
     * @param renewableModel loaded renewable availability profiles
     * @param numTasks       number of cloudlets to schedule
     * @param numVmsPerDC    number of VMs at each data centre
     * @param taskLengths    CPU length of each task in MI
     * @param vmMips         MIPS rating of each VM
     * @param vmTier         power tier index of each VM
     * @param currentTimeSec current simulation time for forecasting
     */
    public NSGAIIOptimizer(
            CarbonModel carbonModel,
            RenewableModel renewableModel,
            int numTasks,
            int numVmsPerDC,
            int[] taskLengths,
            int[] vmMips,
            int[] vmTier,
            double currentTimeSec) {

        this.carbonModel    = carbonModel;
        this.renewableModel = renewableModel;
        this.numTasks       = numTasks;
        this.numVmsPerDC    = numVmsPerDC;
        this.numDCs         = SimulationConfig.NUM_DATACENTERS;
        this.taskLengths    = taskLengths;
        this.vmMips         = vmMips;
        this.vmTier         = vmTier;
        this.currentTimeSec = currentTimeSec;
        this.rng            = new Random(42);
    }

    /**
     * ASCSF extension: allows scoping optimization to a subset of DCs
     * (numDCs = 1 for a single autonomous provider's local-only optimization,
     * per System Assumptions v1.1 A2/A4 -- no provider may optimize across
     * DCs it does not own). All other behaviour identical to the original
     * constructor. Pair with setGlobalDcOffset(...) so forecast lookups still
     * hit the correct real-world DC.
     */
    public NSGAIIOptimizer(
            CarbonModel carbonModel,
            RenewableModel renewableModel,
            int numTasks,
            int numVmsPerDC,
            int[] taskLengths,
            int[] vmMips,
            int[] vmTier,
            double currentTimeSec,
            int numDCs) {

        this.carbonModel    = carbonModel;
        this.renewableModel = renewableModel;
        this.numTasks       = numTasks;
        this.numVmsPerDC    = numVmsPerDC;
        this.numDCs         = numDCs;
        this.taskLengths    = taskLengths;
        this.vmMips         = vmMips;
        this.vmTier         = vmTier;
        this.currentTimeSec = currentTimeSec;
        this.rng            = new Random(42);
    }

    // ── Main entry point ──────────────────────────────────────────────────────
    /**
     * Runs NSGA-II for NUM_GENERATIONS generations.
     * Returns the first Pareto front (rank=1 solutions).
     *
     * The best single solution is selected from this front
     * by choosing minimum carbon (C-NSGA-II priority).
     */
    public Chromosome run() {

        int Ps = SimulationConfig.POPULATION_SIZE;
        int G  = SimulationConfig.NUM_GENERATIONS;

        // Step 1: initialise population
        List<Chromosome> population = initialisePopulation(Ps);

        // Step 2: evaluate initial population
        evaluateAll(population);

        // Step 3: evolve for G generations
        for (int gen = 0; gen < G; gen++) {

            // Non-dominated sort + crowding distance
            List<List<Chromosome>> fronts = nonDominatedSort(population);
            assignCrowdingDistance(fronts);

            // Generate offspring
            List<Chromosome> offspring = new ArrayList<>();
            while (offspring.size() < Ps) {
                Chromosome p1 = tournamentSelect(population);
                Chromosome p2 = tournamentSelect(population);
                List<Chromosome> children = crossover(p1, p2);
                for (Chromosome child : children) {
                    mutate(child);
                    offspring.add(child);
                    if (offspring.size() >= Ps) break;
                }
            }

            // Evaluate offspring
            evaluateAll(offspring);

            // Merge parent + offspring
            List<Chromosome> combined = new ArrayList<>();
            combined.addAll(population);
            combined.addAll(offspring);

            // Re-sort combined pool
            fronts = nonDominatedSort(combined);
            assignCrowdingDistance(fronts);

            // Select next generation: fill by front until Ps reached
            population = new ArrayList<>();
            for (List<Chromosome> front : fronts) {
                if (population.size() + front.size() <= Ps) {
                    population.addAll(front);
                } else {
                    // Sort this front by crowding distance (desc)
                    front.sort((a, b) ->
                        Double.compare(b.crowdingDistance,
                                       a.crowdingDistance));
                    int needed = Ps - population.size();
                    population.addAll(front.subList(0, needed));
                    break;
                }
            }
        }

        // Return best solution: from rank-1 front, minimum carbon
        List<List<Chromosome>> finalFronts = nonDominatedSort(population);
        List<Chromosome> paretoFront = finalFronts.get(0);
        return selectBestFromFront(paretoFront);
    }

    // ── Initialise population ─────────────────────────────────────────────────
    private List<Chromosome> initialisePopulation(int size) {
        List<Chromosome> pop = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Chromosome c = new Chromosome(numTasks);
            c.randomise(numVmsPerDC, numDCs, rng);
            pop.add(c);
        }
        return pop;
    }

    // ── Evaluate all chromosomes ──────────────────────────────────────────────
    private void evaluateAll(List<Chromosome> population) {
        for (Chromosome c : population) {
            evaluate(c);
        }
    }

    // ── Evaluate single chromosome ────────────────────────────────────────────
    /**
     * Computes f1 (makespan), f2 (energy), f3 (carbon) for one chromosome.
     *
     * Energy model: SPEC CPU2017 linear interpolation
     *   P(u) = P_idle + (P_peak - P_idle) * u
     *   where u = utilisation = taskLength / (vmMips * execTime)
     *
     * Carbon: uses FORECAST CI (not current CI) —
     *   this is the key difference from reactive schedulers.
     *   A reactive scheduler uses CI(t_now).
     *   C-NSGA-II uses CI_forecast(t_now + delta) in its objective,
     *   so NSGA-II optimises against future grid conditions.
     */
    private void evaluate(Chromosome c) {

        // Track finish time per VM per DC
        // vmFinishTime[dc][vm] = when that VM finishes current tasks
        double[][] vmFinishTime =
            new double[numDCs][numVmsPerDC];

        double totalEnergyKWh = 0.0;
        double totalCarbonKgCO2 = 0.0;

        for (int i = 0; i < numTasks; i++) {

            int dc  = c.dcAssignment[i];
            int vm  = c.assignment[i] % numVmsPerDC;
            int tier = vmTier[vm % vmTier.length];

            double mips     = vmMips[vm % vmMips.length];
            double lengthMI = taskLengths[i];

            // Execution time in seconds
            double execTimeSec = (lengthMI / mips);

            // Start time = when VM is free
            double startTime = vmFinishTime[dc][vm];
            double finishTime = startTime + execTimeSec;
            vmFinishTime[dc][vm] = finishTime;

            // CPU utilisation during execution
            double utilisation = Math.min(1.0,
                lengthMI / (mips * execTimeSec));

            // Power in Watts (SPEC linear model)
            double pIdle = SimulationConfig.VM_POWER_IDLE[tier];
            double pPeak = SimulationConfig.VM_POWER_PEAK[tier];
            double powerW = pIdle + (pPeak - pIdle) * utilisation;

            // Energy in kWh
            double energyKWh = (powerW * execTimeSec) / 3_600_000.0;
            totalEnergyKWh += energyKWh;

            // Carbon: use FORECAST intensity at execution DC
            // currentTimeSec + startTime approximates execution window
            // ASCSF: "dc" is LOCAL to this optimizer's scope (always 0 when
            // numDCs=1); add globalDcOffset to recover the real-world DC
            // index for CarbonModel/RenewableModel lookups. For the 5
            // existing callers, globalDcOffset stays 0, so this line behaves
            // exactly as before the patch.
            double forecastCI = carbonModel.getForecastCarbonIntensity(
                dc + globalDcOffset, currentTimeSec + startTime);

            // Carbon in kgCO2e (CI in gCO2e/kWh → divide by 1000)
            totalCarbonKgCO2 += energyKWh * forecastCI;
        }

        // Makespan = latest finish time across all VMs and DCs
        double makespan = 0.0;
        for (int dc = 0; dc < numDCs; dc++) {
            for (int vm = 0; vm < numVmsPerDC; vm++) {
                makespan = Math.max(makespan, vmFinishTime[dc][vm]);
            }
        }

        c.f1Makespan = makespan;
        c.f2Energy   = totalEnergyKWh;
        c.f3Carbon   = totalCarbonKgCO2;
    }

    // ── Non-dominated sorting ─────────────────────────────────────────────────
    /**
     * Implements fast non-dominated sorting from Deb et al. [1].
     *
     * For each solution p:
     *   - Count how many solutions dominate p (dominationCount)
     *   - Track which solutions p dominates (dominatedSet)
     *
     * Front 1 = all solutions with dominationCount == 0
     * When a solution is assigned to a front, reduce dominationCount
     * of everything it dominates — next front forms naturally.
     *
     * Time complexity: O(M * N^2) where M=objectives, N=population size
     * For our problem: O(3 * 100^2) = 30,000 ops per generation — fast.
     */
    private List<List<Chromosome>> nonDominatedSort(
            List<Chromosome> population) {

        int n = population.size();
        List<List<Integer>> dominatedSets = new ArrayList<>();
        int[] domCount = new int[n];

        for (int i = 0; i < n; i++) {
            dominatedSets.add(new ArrayList<>());
        }

        List<List<Chromosome>> fronts = new ArrayList<>();
        List<Integer> firstFront = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            domCount[i] = 0;
            dominatedSets.get(i).clear();

            for (int j = 0; j < n; j++) {
                if (i == j) continue;

                if (population.get(i).dominates(population.get(j))) {
                    dominatedSets.get(i).add(j);
                } else if (population.get(j).dominates(population.get(i))) {
                    domCount[i]++;
                }
            }

            if (domCount[i] == 0) {
                population.get(i).rank = 1;
                firstFront.add(i);
            }
        }

        // Build subsequent fronts
        List<Integer> currentFrontIndices = firstFront;
        int frontRank = 1;

        while (!currentFrontIndices.isEmpty()) {
            List<Chromosome> frontChromosomes = new ArrayList<>();
            for (int idx : currentFrontIndices) {
                frontChromosomes.add(population.get(idx));
            }
            fronts.add(frontChromosomes);

            List<Integer> nextFront = new ArrayList<>();
            for (int i : currentFrontIndices) {
                for (int j : dominatedSets.get(i)) {
                    domCount[j]--;
                    if (domCount[j] == 0) {
                        population.get(j).rank = frontRank + 1;
                        nextFront.add(j);
                    }
                }
            }

            frontRank++;
            currentFrontIndices = nextFront;
        }

        return fronts;
    }

    // ── Crowding distance ─────────────────────────────────────────────────────
    /**
     * Assigns crowding distance to each solution within a front.
     *
     * Crowding distance measures how isolated a solution is
     * from its neighbours in objective space.
     * Higher distance = more isolated = more diverse = preferred.
     *
     * Boundary solutions get distance = INFINITY (always kept).
     * Interior solutions get distance = sum of normalised gaps
     * between neighbours on each objective axis.
     *
     * This preserves diversity across the Pareto front —
     * preventing solutions from clustering in one region.
     */
    private void assignCrowdingDistance(List<List<Chromosome>> fronts) {
        for (List<Chromosome> front : fronts) {
            int size = front.size();
            if (size == 0) continue;

            // Reset distances
            for (Chromosome c : front) c.crowdingDistance = 0.0;

            // For each objective
            double[][] objValues = new double[3][size];
            for (int i = 0; i < size; i++) {
                objValues[0][i] = front.get(i).f1Makespan;
                objValues[1][i] = front.get(i).f2Energy;
                objValues[2][i] = front.get(i).f3Carbon;
            }

            for (int obj = 0; obj < 3; obj++) {
                // Sort front by this objective
                final int objFinal = obj;
                List<Chromosome> sorted = new ArrayList<>(front);
                sorted.sort((a, b) -> {
                    double va = objFinal == 0 ? a.f1Makespan :
                                objFinal == 1 ? a.f2Energy : a.f3Carbon;
                    double vb = objFinal == 0 ? b.f1Makespan :
                                objFinal == 1 ? b.f2Energy : b.f3Carbon;
                    return Double.compare(va, vb);
                });

                // Boundary solutions → infinity
                sorted.get(0).crowdingDistance   = Double.POSITIVE_INFINITY;
                sorted.get(size-1).crowdingDistance = Double.POSITIVE_INFINITY;

                double minVal = objFinal == 0 ?
                    sorted.get(0).f1Makespan :
                    objFinal == 1 ? sorted.get(0).f2Energy :
                                    sorted.get(0).f3Carbon;

                double maxVal = objFinal == 0 ?
                    sorted.get(size-1).f1Makespan :
                    objFinal == 1 ? sorted.get(size-1).f2Energy :
                                    sorted.get(size-1).f3Carbon;

                double range = maxVal - minVal;
                if (range == 0) continue;

                // Interior solutions
                for (int i = 1; i < size - 1; i++) {
                    double prev = objFinal == 0 ?
                        sorted.get(i-1).f1Makespan :
                        objFinal == 1 ? sorted.get(i-1).f2Energy :
                                        sorted.get(i-1).f3Carbon;

                    double next = objFinal == 0 ?
                        sorted.get(i+1).f1Makespan :
                        objFinal == 1 ? sorted.get(i+1).f2Energy :
                                        sorted.get(i+1).f3Carbon;

                    sorted.get(i).crowdingDistance += (next - prev) / range;
                }
            }
        }
    }

    // ── Tournament selection ──────────────────────────────────────────────────
    /**
     * Binary tournament selection.
     * Pick 2 random solutions, return the better one.
     * "Better" = lower rank; if tied, higher crowding distance.
     */
    private Chromosome tournamentSelect(List<Chromosome> population) {
        int i1 = rng.nextInt(population.size());
        int i2 = rng.nextInt(population.size());
        Chromosome c1 = population.get(i1);
        Chromosome c2 = population.get(i2);
        return (c1.compareTo(c2) <= 0) ? c1 : c2;
    }

    // ── Two-point crossover ───────────────────────────────────────────────────
    /**
     * Two-point crossover with probability pc = 0.9.
     *
     * Two cut points divide the chromosome into 3 segments.
     * Child 1 gets: [p1 segment1] + [p2 segment2] + [p1 segment3]
     * Child 2 gets: [p2 segment1] + [p1 segment2] + [p2 segment3]
     *
     * Applied to BOTH assignment[] and dcAssignment[] arrays.
     *
     * Why two-point crossover?
     * Single-point crossover preserves only one contiguous block.
     * Two-point preserves middle segments, which often represent
     * clusters of tasks that perform well when co-located on same DC.
     */
    private List<Chromosome> crossover(Chromosome p1, Chromosome p2) {
        Chromosome c1 = p1.copy();
        Chromosome c2 = p2.copy();

        if (rng.nextDouble() < SimulationConfig.CROSSOVER_RATE) {
            int pt1 = rng.nextInt(numTasks);
            int pt2 = rng.nextInt(numTasks);

            if (pt1 > pt2) {
                int tmp = pt1; pt1 = pt2; pt2 = tmp;
            }

            for (int i = pt1; i <= pt2; i++) {
                // Swap assignment
                int tmpA = c1.assignment[i];
                c1.assignment[i] = c2.assignment[i];
                c2.assignment[i] = tmpA;

                // Swap DC assignment
                int tmpDC = c1.dcAssignment[i];
                c1.dcAssignment[i] = c2.dcAssignment[i];
                c2.dcAssignment[i] = tmpDC;
            }
        }

        return Arrays.asList(c1, c2);
    }

    // ── Bitwise mutation ──────────────────────────────────────────────────────
    /**
     * Mutation probability pm = 1/N (N = number of tasks).
     *
     * For each gene: with probability pm, assign a new random VM
     * and a new random DC.
     *
     * Why pm = 1/N?
     * This ensures on average ONE gene mutates per chromosome
     * per generation — enough to explore new regions without
     * destroying good solutions (which higher rates would do).
     */
    private void mutate(Chromosome c) {
        double pm = 1.0 / numTasks;
        for (int i = 0; i < numTasks; i++) {
            if (rng.nextDouble() < pm) {
                c.assignment[i]   = rng.nextInt(numVmsPerDC);
                c.dcAssignment[i] = rng.nextInt(numDCs);
            }
        }
    }

    // ── Select best from Pareto front ─────────────────────────────────────────
    /**
     * From the first Pareto front, select the solution with
     * lowest carbon emissions as the final scheduling decision.
     *
     * This reflects C-NSGA-II's primary sustainability goal:
     * when multiple equally Pareto-optimal solutions exist,
     * prefer the one that emits least carbon.
     *
     * An operator could equally pick minimum makespan or minimum
     * energy — the front provides all options.
     */
    private Chromosome selectBestFromFront(List<Chromosome> front) {
        Chromosome best = front.get(0);
        for (Chromosome c : front) {
            if (c.f3Carbon < best.f3Carbon) {
                best = c;
            }
        }
        return best;
    }

    // ── Getters for sensitivity analysis ─────────────────────────────────────
    public void setCurrentTimeSec(double t) {
        this.currentTimeSec = t;
    }

    /**
     * ASCSF extension: sets the real-world global DC index this optimizer's
     * local dcAssignment=0 corresponds to. Only meaningful when numDCs=1
     * (a single autonomous CloudProvider's local scope). Never called by
     * the 5 pre-existing schedulers (CASPER, C-NSGA-II, EAS, MinMin, RR),
     * so their behaviour is unaffected (offset stays at its default of 0).
     */
    public void setGlobalDcOffset(int offset) {
        this.globalDcOffset = offset;
    }
}