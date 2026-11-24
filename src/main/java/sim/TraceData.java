package sim;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Real-trace inputs for the trace-driven study (REVIEW_SET = "TRACE"):
 *  - trace_workload.csv : per trial, 2000 tasks sampled from the labelled Azure VMs (length in MI, ds flag)
 *  - trace_days.csv     : per trial, one real 2025 day of hourly grid data for SE, DE, PL
 *                         (carbon intensity in kg CO2e/kWh, renewable share as a fraction), flow-traced
 * Files are read from the folder "trace_data" in the Eclipse project root (the working directory).
 */
public class TraceData {
    private final Map<Integer, int[]> lengths = new HashMap<>();
    private final Map<Integer, boolean[]> ds = new HashMap<>();
    private final Map<Integer, double[][]> carbon = new HashMap<>();
    private final Map<Integer, double[][]> renew = new HashMap<>();
    private final Map<Integer, Integer> startHour = new HashMap<>();

    public static TraceData load(String dir) throws Exception {
        TraceData t = new TraceData();
        try (BufferedReader br = new BufferedReader(new FileReader(dir + "/trace_workload.csv"))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split(",");
                int trial = Integer.parseInt(p[0]), idx = Integer.parseInt(p[1]);
                t.lengths.computeIfAbsent(trial, k -> new int[2000])[idx] = Integer.parseInt(p[2]);
                t.ds.computeIfAbsent(trial, k -> new boolean[2000])[idx] = p[3].equals("1");
            }
        }
        try (BufferedReader br = new BufferedReader(new FileReader(dir + "/trace_days.csv"))) {
            br.readLine();
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.trim().split(",");
                int trial = Integer.parseInt(p[0]), dc = Integer.parseInt(p[1]);
                t.startHour.put(trial, Integer.parseInt(p[4]));
                double[][] c = t.carbon.computeIfAbsent(trial, k -> new double[3][24]);
                double[][] r = t.renew.computeIfAbsent(trial, k -> new double[3][24]);
                for (int h = 0; h < 24; h++) {
                    c[dc][h] = Double.parseDouble(p[5 + h]);
                    r[dc][h] = Double.parseDouble(p[29 + h]);
                }
            }
        }
        return t;
    }
    public int[] lengths(int trial, int n) { return java.util.Arrays.copyOf(lengths.get(trial), n); }
    public boolean[] ds(int trial, int n)  { return java.util.Arrays.copyOf(ds.get(trial), n); }
    public double[][] carbon(int trial)    { return carbon.get(trial); }
    public double[][] renew(int trial)     { return renew.get(trial); }
    public int startHour(int trial)        { return startHour.get(trial); }
}
