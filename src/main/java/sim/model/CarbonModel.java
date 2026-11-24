package sim.model;

import sim.SimulationConfig;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Carbon Intensity Model for 3 geo-distributed data centers.
 *
 * Reads carbon.csv which contains 3 rows (one per DC),
 * each row has 24 comma-separated hourly values in gCO2e/kWh.
 *
 * DC0 = Nordic grid     (low carbon ~0.10-0.22)
 * DC1 = Central Europe  (mixed     ~0.34-0.52)
 * DC2 = Fossil-heavy    (high      ~0.67-0.85)
 *
 * Used by C-NSGA-II to make carbon-aware placement decisions.
 */
public class CarbonModel {

    // carbonProfiles[dcIndex][hourIndex] = gCO2e/kWh
    private double[][] carbonProfiles;
    private final int numDCs;
    private final Random noise;
    private double minForecast = 0.05;   // floor of the noisy forecast (kg/kWh); lowered for real low-carbon grids
    public void setProfiles(double[][] p) { this.carbonProfiles = p; }
    public void setMinForecast(double v) { this.minForecast = v; }

    public CarbonModel(String filename) {
        noise = new Random(42); // fixed seed for reproducibility
        carbonProfiles = loadProfiles(filename);
        numDCs = carbonProfiles.length;
        System.out.println("[CarbonModel] Loaded " + numDCs
            + " DC profiles, " + carbonProfiles[0].length
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
                    "carbon.csv not found in resources.");
            }

            BufferedReader reader =
                new BufferedReader(new InputStreamReader(is));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // skip blank lines and comment lines
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
            System.out.println("[CarbonModel] Error: " + e.getMessage());
            System.out.println("[CarbonModel] Using fallback profiles.");
            // Fallback: 3 DCs, 24 hours each
            profiles.add(buildFallback(0.15)); // Nordic
            profiles.add(buildFallback(0.42)); // Europe
            profiles.add(buildFallback(0.74)); // Fossil
        }

        return profiles.toArray(new double[0][]);
    }

    // ── Build a flat fallback profile ────────────────────────────────────────
    private double[] buildFallback(double baseValue) {
        double[] profile = new double[24];
        for (int i = 0; i < 24; i++) {
            profile[i] = baseValue;
        }
        return profile;
    }

    // ── Get actual carbon intensity ──────────────────────────────────────────
    /**
     * Returns the actual carbon intensity for a given DC at a given
     * simulation time (in seconds).
     *
     * @param dcIndex  0=Nordic, 1=Europe, 2=Fossil
     * @param timeSec  simulation time in seconds
     * @return carbon intensity in gCO2e/kWh
     */
    public double getCarbonIntensity(int dcIndex, double timeSec) {
        int hour = getHourIndex(timeSec);
        return carbonProfiles[dcIndex][hour];
    }

    // ── Get forecast carbon intensity ────────────────────────────────────────
    /**
     * Returns the FORECAST carbon intensity for a given DC,
     * looking ahead by FORECAST_HORIZON_HOURS from current time.
     * Adds small Gaussian noise to simulate forecast error (MAPE ~8.3%).
     *
     * This is the key value used in objective f3 of NSGA-II —
     * we optimise against the predicted future CI, not the current CI.
     * This is what separates C-NSGA-II from reactive schedulers.
     *
     * @param dcIndex  0=Nordic, 1=Europe, 2=Fossil
     * @param timeSec  current simulation time in seconds
     * @return forecast carbon intensity in gCO2e/kWh
     */
    public double getForecastCarbonIntensity(int dcIndex, double timeSec) {
        // Look ahead by forecast horizon
        double futureTimeSec = timeSec
            + (SimulationConfig.FORECAST_HORIZON_HOURS * 3600.0);
        int futureHour = getHourIndex(futureTimeSec);
        double actual = carbonProfiles[dcIndex][futureHour];

        // Add Gaussian noise: simulates LSTM/ARIMA forecast error
        // Noise std = 8.3% of actual value (matches paper MAPE)
        double noiseVal = noise.nextGaussian()
            * SimulationConfig.NOISE_STD_DEV * actual;

        // Clamp to realistic range [0.05, 1.0]
        return Math.max(minForecast, Math.min(1.0, actual + noiseVal));
    }

    // ── Utility: convert simulation seconds to hour index ────────────────────
    private int getHourIndex(double timeSec) {
        // Simulate a 24-hour day cycle
        int totalSeconds = (int)(timeSec % (24 * 3600));
        int hour = totalSeconds / 3600;
        return Math.min(hour, carbonProfiles[0].length - 1);
    }

    // ── Getters ──────────────────────────────────────────────────────────────
    /** Re-seed the forecast-noise stream (used so every scheduler in a trial sees identical noise). */
    public void reseed(long seed) { noise.setSeed(seed); }

    public int getNumDCs() {
        return numDCs;
    }

    /**
     * Returns the average carbon intensity across the full day
     * for a given DC. Used for reporting in MetricsCollector.
     */
    public double getDailyAverageCarbonIntensity(int dcIndex) {
        double sum = 0;
        for (double v : carbonProfiles[dcIndex]) sum += v;
        return sum / carbonProfiles[dcIndex].length;
    }
}