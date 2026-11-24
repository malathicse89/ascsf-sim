package sim.scheduler;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.vms.Vm;

import java.util.List;

public class RoundRobinScheduler {

	public static void scheduleAndBind(DatacenterBrokerSimple broker,
            List<Cloudlet> cloudlets,
            List<Vm> vms) {

	    

	    if (vms == null || vms.isEmpty()) {
	        System.out.println("VMs not ready!");
	        return;
	    }

	    int vmIndex = 0;

	    for (Cloudlet cl : cloudlets) {
	        Vm vm = vms.get(vmIndex);
	        cl.setVm(vm);

	        vmIndex = (vmIndex + 1) % vms.size();
	    }
	}
}