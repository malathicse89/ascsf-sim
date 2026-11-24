package sim.scheduler;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.vms.Vm;

import java.util.*;

public class MinMinScheduler {

	public static void scheduleAndBind(DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms) {

        

        if (vms == null || vms.isEmpty()) {
            System.out.println("VMs not ready for Min-Min scheduling!");
            return;
        }

        // Copy cloudlets so we can remove as we schedule
        List<Cloudlet> unscheduled = new ArrayList<>(cloudlets);

        // Track when each VM becomes free
        Map<Vm, Double> vmReadyTime = new HashMap<>();
        for (Vm vm : vms) {
            vmReadyTime.put(vm, 0.0);
        }

        while (!unscheduled.isEmpty()) {

            Cloudlet bestCloudlet = null;
            Vm bestVm = null;
            double minCompletionTime = Double.MAX_VALUE;

            // 🔍 Find best (cloudlet, VM) pair
            for (Cloudlet cl : unscheduled) {

                for (Vm vm : vms) {

                    double execTime = cl.getLength() / vm.getMips();
                    double completionTime = vmReadyTime.get(vm) + execTime;

                    if (completionTime < minCompletionTime) {
                        minCompletionTime = completionTime;
                        bestCloudlet = cl;
                        bestVm = vm;
                    }
                }
            }

            // 🔗 Bind selected cloudlet to VM
            bestCloudlet.setVm(bestVm);

            // ⏱ Update VM ready time
            double updatedTime = vmReadyTime.get(bestVm)
                    + (bestCloudlet.getLength() / bestVm.getMips());

            vmReadyTime.put(bestVm, updatedTime);

            // ❌ Remove scheduled cloudlet
            unscheduled.remove(bestCloudlet);
        }

        System.out.println("Min-Min Scheduling Applied");
    }
}