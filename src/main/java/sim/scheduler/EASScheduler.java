package sim.scheduler;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.vms.Vm;
import sim.SimulationConfig;

import java.util.*;

public class EASScheduler {

    public static void scheduleAndBind(
            DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms) {

        if (vms == null || vms.isEmpty()) {
            System.out.println("[EAS] No VMs available.");
            return;
        }

        // Track VM finish times to avoid overloading one VM
        Map<Vm, Double> vmFinishTime = new HashMap<>();
        for (Vm vm : vms) {
            vmFinishTime.put(vm, 0.0);
        }

        for (Cloudlet cl : cloudlets) {

            Vm bestVm    = null;
            double minEnergy = Double.MAX_VALUE;

            for (Vm vm : vms) {

                double mips     = vm.getMips();
                double lengthMI = cl.getLength();
                double execTime = lengthMI / mips;

                int tier = getVmTier(mips);

                double utilisation = Math.min(1.0,
                    lengthMI / (mips * execTime));

                double pIdle = SimulationConfig.VM_POWER_IDLE[tier];
                double pPeak = SimulationConfig.VM_POWER_PEAK[tier];
                double powerW = pIdle + (pPeak - pIdle) * utilisation;

                double energyKWh =
                    (powerW * execTime) / 3_600_000.0;

                // Prefer VM with lower energy AND lower current load
                double readyTime  = vmFinishTime.get(vm);
                double score = energyKWh + (readyTime * 0.0001);

                if (score < minEnergy) {
                    minEnergy = score;
                    bestVm    = vm;
                }
            }

            if (bestVm == null) bestVm = vms.get(0);

            // Update VM finish time
            double execTime = cl.getLength() / bestVm.getMips();
            double newFinish = vmFinishTime.get(bestVm) + execTime;
            vmFinishTime.put(bestVm, newFinish);

            cl.setVm(bestVm);
        }

        System.out.println("[EAS] Scheduling applied to "
            + cloudlets.size() + " tasks.");
    }

    private static int getVmTier(double mips) {
        if (mips <= 500)  return 0;
        if (mips <= 1000) return 1;
        if (mips <= 2000) return 2;
        return 3;
    }
}