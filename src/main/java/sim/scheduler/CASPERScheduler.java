package sim.scheduler;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.vms.Vm;
import sim.SimulationConfig;
import sim.model.CarbonModel;

import java.util.*;

public class CASPERScheduler {

    private static final double W_LATENCY = 0.5;
    private static final double W_CARBON  = 0.5;

    private final CarbonModel carbonModel;

    public CASPERScheduler(CarbonModel carbonModel) {
        this.carbonModel = carbonModel;
    }

    public void scheduleAndBind(
            DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms,
            int numDCs,
            double currentTimeSec) {

        if (vms == null || vms.isEmpty()) {
            System.out.println("[CASPER] No VMs available.");
            return;
        }

        // Track VM finish times
        Map<Vm, Double> vmFinishTime = new HashMap<>();
        for (Vm vm : vms) {
            vmFinishTime.put(vm, 0.0);
        }

        for (Cloudlet cl : cloudlets) {

            Vm    bestVm    = null;
            double bestScore = Double.MAX_VALUE;

            // Pre-calculate max values for normalisation
            double maxExecTime = 0;
            double maxCarbon   = 0;

            for (int idx = 0; idx < vms.size(); idx++) {
                Vm vm = vms.get(idx);
                int dc = idx % numDCs;

                double execTime = cl.getLength() / vm.getMips();
                double ci       = carbonModel
                    .getCarbonIntensity(dc, currentTimeSec);
                int tier        = getVmTier(vm.getMips());
                double energy   = computeEnergy(
                    cl.getLength(), vm.getMips(), tier);
                double carbon   = energy * ci;

                if (execTime > maxExecTime) maxExecTime = execTime;
                if (carbon   > maxCarbon)   maxCarbon   = carbon;
            }

            if (maxExecTime == 0) maxExecTime = 1;
            if (maxCarbon   == 0) maxCarbon   = 1;

            // Score each VM — flat iteration, no nested DC loop
            for (int idx = 0; idx < vms.size(); idx++) {
                Vm vm = vms.get(idx);
                int dc = idx % numDCs;

                double execTime = cl.getLength() / vm.getMips();
                double ci       = carbonModel
                    .getCarbonIntensity(dc, currentTimeSec);
                int tier        = getVmTier(vm.getMips());
                double energy   = computeEnergy(
                    cl.getLength(), vm.getMips(), tier);
                double carbon   = energy * ci;

                double normLatency = execTime / maxExecTime;
                double normCarbon  = carbon   / maxCarbon;
                double score = W_LATENCY * normLatency
                             + W_CARBON  * normCarbon;

                // Add load penalty for busy VMs
                score += vmFinishTime.get(vm) * 0.01;

                if (score < bestScore) {
                    bestScore = score;
                    bestVm    = vm;
                }
            }

            if (bestVm == null) bestVm = vms.get(0);

            // Update finish time
            double execTime = cl.getLength() / bestVm.getMips();
            vmFinishTime.put(bestVm,
                vmFinishTime.get(bestVm) + execTime);

            cl.setVm(bestVm);
        }

        System.out.println("[CASPER] Scheduling applied to "
            + cloudlets.size() + " tasks.");
    }

    private double computeEnergy(double lengthMI,
                                  double mips, int tier) {
        double execTime    = lengthMI / mips;
        double utilisation = Math.min(1.0,
            lengthMI / (mips * execTime));
        double pIdle = SimulationConfig.VM_POWER_IDLE[tier];
        double pPeak = SimulationConfig.VM_POWER_PEAK[tier];
        double powerW = pIdle + (pPeak - pIdle) * utilisation;
        return (powerW * execTime) / 3_600_000.0;
    }

    private static int getVmTier(double mips) {
        if (mips <= 500)  return 0;
        if (mips <= 1000) return 1;
        if (mips <= 2000) return 2;
        return 3;
    }
}