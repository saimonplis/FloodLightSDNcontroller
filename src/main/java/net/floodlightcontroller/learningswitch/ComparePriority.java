package net.floodlightcontroller.learningswitch;

import java.util.Comparator;

import org.projectfloodlight.openflow.protocol.OFFlowStatsEntry;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.VlanPcp;

public class ComparePriority implements Comparator<OFFlowStatsEntry>{

	@Override
	public int compare(OFFlowStatsEntry o1, OFFlowStatsEntry o2) {
		// TODO Auto-generated method stub
		VlanPcp pcp1=o1.getMatch().get(MatchField.VLAN_PCP);
		VlanPcp pcp2=o1.getMatch().get(MatchField.VLAN_PCP);

		if(pcp1.getValue()<pcp2.getValue()){
			return 1;
		}
		else if(pcp1.getValue()>pcp2.getValue()){
			return -1;
		}
		else{
		return 0;
		}
	}
	

}
