package sim.model;

import sim.SimulationConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Renewable Energy Availability Model for 3 geo-distributed data centers.
 *
 * Reads renewable.csv which contains 3 rows (one per DC),
 * each row has 24 comma-separated hourly values [0.0 - 1.0]
 * representing fraction of power from renewable sources.
 *
 * DC0 = Nordic     (wind-heavy,  high RE: 0.74 - 0.86)
 * DC1 = Europe     (solar+wind,  mid  RE: 0.37 - 0.73)
 * DC2 = Fossil     (low RE:      0.16 - 0.25)
 *
 * The forecast method simulates what C-NSGA-II sees when making
 * proactive scheduling decisions for delay-tolerant tasks.
 */
public class RenewableModel {

    // renewableProfiles[dcIndex][hourIndex] = fraction [0.0-1.0]
    private double[][] renewableProfiles;
    private final int numDCs;
    private final Random noise;
    public void setProfiles(double[][] p) { this.renewableProfiles = p; }

    public RenewableModel(String filename) {
        noise = new Random(99); // different seed from CarbonModel
        renewableProfiles = loadProfiles(filename);
        numDCs = renewableProfiles.length;
        System.out.println("[RenewableModel] Loaded " + numDCs
            + " DC profiles, " + renewableProfiles[0].length
            + " hourly values each.");
    }

    // ── Load CSV ─────────────────────────────────────────────────────────────
    private double[][] loadProfiles(String filename) {

        List<double[]> profiles = new ArrayList<>();

        try {
            InputStream is = getClass()
                .getClassLoader()
                .getResourceAsStream(filename);

            if (is == null) {
                throw new RuntimeException(
                    "renewable.csv not found in resources.");
            }

            BufferedReader reader =
                new BufferedReader(new InputStreamReader(is));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                String[] parts = line.split(",");
                double[] row = new double[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    row[i] = Double.parseDouble(parts[i].trim());
                }
                profiles.add(row);
            }
            reader.close();

        } catch (Exception e) {
            System.out.println("[RenewableModel] Error: " + e.getMessage());
            System.out.println("[RenewableModel] Using fallback profiles.");
            profiles.add(buildFallback(0.82)); // Nordic
            profiles.add(buildFallback(0.55)); // Europe
            profiles.add(buildFallback(0.20)); // Fossil
        }

        return profiles.toArray(new double[0][]);
    }

    // ── Flat fallback profile ─────────────────────────────────────────────────
    private double[] buildFallback(double baseValue) {
        double[] profile = new double[24];
        for (int i = 0; i < 24; i++) {
            profile[i] = baseValue;
        }
        return profile;
    }

    // ── Get actual renewable fraction ─────────────────────────────────────────
    /**
     * Returns the actual renewable fraction for a DC at a given
     * simulation time (seconds). Used when computing actual
     * renewable utilization after task execution.
     *
     * @param dcIndex  0=Nordic, 1=Europe, 2=Fossil
     * @param timeSec  simulation time in seconds
     * @return renewable fraction [0.0 - 1.0]
     */
    public double getRenewableFraction(int dcIndex, double timeSec) {
        int hour = getHourIndex(timeSec);
        return renewableProfiles[dcIndex][hour];
    }

    // ── Get forecast renewable fraction ──────────────────────────────────────
    /**
     * Returns the FORECAST renewable fraction for a DC,
     * looking ahead by FORECAST_HORIZON_HOURS.
     * Adds Gaussian noise simulating RF forecast MAPE of ~11.2%.
     *
     * C-NSGA-II uses this forecast (not current value) when scoring
     * data centers for delay-tolerant task placement.
     * This allows the scheduler to defer DT tasks to upcoming
     * high-renewable windows — the core mechanism of C-NSGA-II.
     *
     * @param dcIndex  0=Nordic, 1=Europe, 2=Fossil
     * @param timeSec  current simulation time in seconds
     * @return forecast renewable fraction [0.0 - 1.0]
     */
    public double getForecastRenewableFraction(int dcIndex, double timeSec) {
        double futureTimeSec = timeSec
            + (SimulationConfig.FORECAST_HORIZON_HOURS * 3600.0);
        int futureHour = getHourIndex(futureTimeSec);
        double actual = renewableProfiles[dcIndex][futureHour];

        // RF forecast noise is slightly higher than CI forecast
        // (11.2% MAPE vs 8.3% for carbon) — weather is harder to predict
        double noiseFactor = SimulationConfig.NOISE_STD_DEV * 1.35;
        double noiseVal = noise.nextGaussian() * noiseFactor * actual;

        // Clamp to valid range [0.0, 1.0]
        return Math.max(0.0, Math.min(1.0, actual + noiseVal));
    }

    // ── Compute utility score component ──────────────────────────────────────
    /**
     * Computes the renewable component of C-NSGA-II utility score.
     * Higher renewable fraction → higher score → prefer this DC/time.
     *
     * Part of: U(k,t) = alpha*RF - beta*CI + gamma*W
     *
     * @param dcIndex  data center index
     * @param timeSec  current simulation time in seconds
     * @param useForecast  true = use forecast value, false = use actual
     * @return renewable availability score [0.0 - 1.0]
     */
    public double getRenewableScore(int dcIndex, double timeSec,
                                    boolean useForecast) {
        if (useForecast) {
            return getForecastRenewableFraction(dcIndex, timeSec);
        } else {
            return getRenewableFraction(dcIndex, timeSec);
        }
    }

    // ── Utility: seconds to hour index ───────────────────────────────────────
    private int getHourIndex(double timeSec) {
        int totalSeconds = (int)(timeSec % (24 * 3600));
        int hour = totalSeconds / 3600;
        return Math.min(hour, renewableProfiles[0].length - 1);
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    /** Re-seed the forecast-noise stream (used so every scheduler in a trial sees identical noise). */
    public void reseed(long seed) { noise.setSeed(seed); }

    public int getNumDCs() {
        return numDCs;
    }

    /**
     * Returns daily average renewable fraction for a DC.
     * Used in results reporting.
     */
    public double getDailyAverageRenewable(int dcIndex) {
        double sum = 0;
        for (double v : renewableProfiles[dcIndex]) sum += v;
        return sum / renewableProfiles[dcIndex].length;
    }

    /**
     * Returns the peak renewable hour for a DC.
     * C-NSGA-II uses this to identify the best deferral window
     * for delay-tolerant tasks.
     */
    public int getPeakRenewableHour(int dcIndex) {
        double max = -1;
        int peakHour = 0;
        for (int i = 0; i < renewableProfiles[dcIndex].length; i++) {
            if (renewableProfiles[dcIndex][i] > max) {
                max = renewableProfiles[dcIndex][i];
                peakHour = i;
            }
        }
        return peakHour;
    }
}