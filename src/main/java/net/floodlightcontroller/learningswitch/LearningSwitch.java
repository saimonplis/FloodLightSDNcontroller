/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

/**
 * Floodlight
 * A BSD licensed, Java based OpenFlow controller
 *
 * Floodlight is a Java based OpenFlow controller originally written by David Erickson at Stanford
 * University. It is available under the BSD license.
 *
 * For documentation, forums, issue tracking and more visit:
 *
 * http://www.openflowhub.org/display/Floodlight/Floodlight+Home
 **/

package net.floodlightcontroller.learningswitch;

import net.floodlightcontroller.core.*;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.MacVlanPair;
import net.floodlightcontroller.debugcounter.IDebugCounter;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.debugcounter.IDebugCounterService.MetaData;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.restserver.IRestApiService;
//import net.floodlightcontroller.statistics.FlowStatisticscollector;
//import net.floodlightcontroller.statistics.IStatisticsService;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFMessageUtils;

import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstruction;
import org.projectfloodlight.openflow.protocol.instruction.OFInstructionApplyActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.*;
import org.projectfloodlight.openflow.util.LRULinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.projectfloodlight.openflow.protocol.OFStatsRequest;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;



// paag: with IControllerCompletionListener that logswhen an input event has been consumed
public class LearningSwitch
implements IFloodlightModule, ILearningSwitchService, IOFMessageListener, IControllerCompletionListener {
	protected static Logger log = LoggerFactory.getLogger(LearningSwitch.class);

	// Module dependencies
	protected IFloodlightProviderService floodlightProviderService;
	protected IRestApiService restApiService;
	protected IDebugCounterService debugCounterService;
	private IDebugCounter counterFlowMod;
	private IDebugCounter counterPacketOut;



	// Stores the learned state for each switch
	protected Map<IOFSwitch, Map<MacVlanPair, OFPort>> macVlanToSwitchPortMap;
	//Stored devices linked
	//Store bwd left for each port regarding the match object
	protected Map<IOFSwitch,Map<OFPort,Map<Match,Short>>> BitRateRequestedForMatchPort; //Short field is identification
	//Stores bwd left for each port
	protected Map<IOFSwitch,Map<OFPort,Float>> SwitchUtilizationperPort;
	// flow-mod - for use in the cookie
	public static final int LEARNING_SWITCH_APP_ID = 1;
	public static int countpkt=0;
	// LOOK! This should probably go in some class that encapsulates
	// the app cookie management
	
	public static final int APP_ID_BITS = 12;
	public static final int APP_ID_SHIFT = (64 - APP_ID_BITS);
	public static final long LEARNING_SWITCH_COOKIE = (long) (LEARNING_SWITCH_APP_ID & ((1 << APP_ID_BITS) - 1)) << APP_ID_SHIFT;
	public static  float BW_TOTAL=50; //100 mbit/s
	public static  float NO_UTILIZATION=0;
	public static  float THRESHOLD=(float) 0.8; 
	private static short PRIORITY_ALLOWED=3;
	// more flow-mod defaults
	protected static short FLOWMOD_DEFAULT_IDLE_TIMEOUT = 35; // in seconds
	protected  static short FLOWMOD_DEFAULT_HARD_TIMEOUT = 10; // infinite
	protected static int FLOWMOD_PRIORITY = 100;
	

	// for managing our map sizes
	protected static final short MAX_MACS_PER_SWITCH  = 1000;

	// normally, setup reverse flow as well. Disable only for using cbench for comparison with NOX etc.
	protected static final boolean LEARNING_SWITCH_REVERSE_FLOW = false;

	// set this flag to true if you want to see the completion messages and
	// have the switch flushed
	protected final boolean flushAtCompletion = false;
	

	
	/**
	 * @param floodlightProvider the floodlightProvider to set
	 */
	public void setFloodlightProvider(IFloodlightProviderService floodlightProviderService) {
		this.floodlightProviderService = floodlightProviderService;
	}

	@Override
	public String getName() {
		return "learningswitch";
	}

	/**
	 * Adds a host to the MAC/VLAN->SwitchPort mapping
	 * @param sw The switch to add the mapping to
	 * @param mac The MAC address of the host to add
	 * @param vlan The VLAN that the host is on
	 * @param portVal The switchport that the host is on
	 */
	protected void addToPortMap(IOFSwitch sw, MacAddress mac, VlanVid vlan, OFPort portVal) {
		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		
		if (vlan == VlanVid.FULL_MASK || vlan == null) {
			vlan = VlanVid.ofVlan(0);
		}

		if (swMap == null) {
			// May be accessed by REST API so we need to make it thread safe
			swMap = Collections.synchronizedMap(new LRULinkedHashMap<MacVlanPair, OFPort>(MAX_MACS_PER_SWITCH));
			macVlanToSwitchPortMap.put(sw, swMap);
		}
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(101)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(102)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(103)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(104)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(105)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(106)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(107)), portVal);
			swMap.put(new MacVlanPair(mac, VlanVid.ofVlan(108)), portVal);
	}
	//**Check if the mac address is already in the map
	protected  boolean checkMacAddress(IOFSwitch sw,MacAddress mac,VlanVid vlan,OFPort portVal){
		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		MacVlanPair aux_mac=null;
		if (vlan == VlanVid.FULL_MASK || vlan == null) {
			vlan = VlanVid.ofVlan(0);
		}
		if (swMap == null) //I have never seen this mac address so return false
			return false;
		else
		{
			Iterator it=swMap.keySet().iterator(); //iterator of the keys
			for(;it.hasNext();){
				aux_mac=(MacVlanPair)(it.next());
				if(aux_mac.getMac()==mac) // I have seen this mac check if the port is the same
				{
					if(swMap.get(aux_mac).getPortNumber()==portVal.getPortNumber())// I have seen this mac in this port
					{
						return true;
					}
					else
					{
						log.info("The same device in two different port??");
						return false;
					}
				}
				
			}
		}
		return false;
	}
	/**
	 * Check utilization left in destination port to allow/decline the flow
	 * @param sw
	 * @param pi
	 * @param outport
	 * @param identification
	 */
	protected void checkBW(IOFSwitch sw,Match m,OFPacketIn pi,OFPort outport,short identification){
		Map<OFPort,Float> swMap= SwitchUtilizationperPort.get(sw);
		if(swMap==null){
			swMap = Collections.synchronizedMap(new LinkedHashMap<OFPort, Float>());//thread safe
			SwitchUtilizationperPort.put(sw, swMap);
		}
		//we have the map where of each port we have the utilization, firstly is 0 but it will increase
		
		//outport check its utilization
		if(!swMap.containsKey(outport)){ //if I don t have the value saved 
			swMap.put(outport, NO_UTILIZATION); //initialization of the port with 0(No utilization)
			
		}
		
		float port_utilization=swMap.get(outport); // getting the actual utilization
	    float possible_port_utilization=(float)(port_utilization+((float)identification/(float)BW_TOTAL)); //new possible port utilization
		log.info("Possible port utilization: "+possible_port_utilization );
		if (possible_port_utilization<THRESHOLD){
			log.info("I can add the flow since the possible port utilization is less then "+threshold*100+"%");
			this.pushPacket(sw, m, pi, outport);
			this.writeFlowMod(sw, OFFlowModCommand.ADD, OFBufferId.NO_BUFFER, m, outport);
			this.addMatchIDtoPort(sw, m,  outport, identification);
			swMap.put(outport,(float) possible_port_utilization); //store the actual port utilization
			SwitchUtilizationperPort.put(sw, swMap);
		}
		else if(possible_port_utilization>=THRESHOLD){
			log.info("I m sorry I can t add this flow, because the actual utlization is: "+port_utilization);
			boolean result=this.flowtribunal(m);
			if(result==true&&possible_port_utilization<=(float)1){
				this.pushPacket(sw, m, pi, outport);
				this.writeFlowMod(sw, OFFlowModCommand.ADD, OFBufferId.NO_BUFFER, m, outport);
				this.addMatchIDtoPort(sw, m,  outport, identification);
				swMap.put(outport, (float)possible_port_utilization);
				SwitchUtilizationperPort.put(sw, swMap);
			}
			else if(result==true&&possible_port_utilization>(float)1){
				log.info("The priority is allowed but the flow goes over the badwidth allocated");
			}
			else if(result==false){
				log.info("The priority is less then the priority accepted no I don t add the flow to table");
			}

		}
		
	}
	/**
	 * If the packet's priority is less than priority allowed, the flow is forbidden, instead of the priortiy is equal or greater than priority allowed the flow is accepted
	 * @param sw
	 * @param m
	 * @param pi
	 * @param outport
	 * @param identification
	 */
	protected boolean flowtribunal(Match m){
		//while(possible_port_utilization>0.8){
			//get the flows from FlowStatscollector
			short pcp=m.get(MatchField.VLAN_PCP).getValue();
			if(pcp>=PRIORITY_ALLOWED){
				return true; //if the packet has a priority greater or less then the priority allowed we accept it
			}
			else if(pcp<PRIORITY_ALLOWED){
				return false; // if the packet has a priority less then priority allowed not accept it
			}
			return false; //by default we return false
	}
	
		
	/**
	 * For every switch we have differents port, and for each port every match(identification of SRC,DEST,ETHTYPE,VLAN,PCP) has bit rate requested
	 * @param sw
	 * @param m
	 * @param pi
	 * @param outport
	 * @param identification
	 */
	
	protected void addMatchIDtoPort(IOFSwitch sw,Match m,OFPort outport,short identification){
		log.info("Adding identification");
		Map<OFPort,Map<Match,Short>> PortMatchId=BitRateRequestedForMatchPort.get(sw);
		if(PortMatchId==null){
			
			PortMatchId=new HashMap<OFPort,Map<Match,Short>>();
			BitRateRequestedForMatchPort.put(sw, PortMatchId);
			
		}
		Map<Match,Short> MatchIDMap=PortMatchId.get(outport); //get the map for the port destination
		if(MatchIDMap==null){
			
			MatchIDMap=new HashMap<Match,Short>();
			
			MatchIDMap.put(m, identification);
			PortMatchId.put(outport, MatchIDMap);
			
		}
	
		MatchIDMap.put(m, identification);
		PortMatchId.put(outport, MatchIDMap);
		
	}

	/**
	 * Removes a host from the MAC/VLAN->SwitchPort mapping
	 * @param sw The switch to remove the mapping from
	 * @param mac The MAC address of the host to remove
	 * @param vlan The VLAN that the host is on
	 */
	protected void removeFromPortMap(IOFSwitch sw, MacAddress mac, VlanVid vlan) {
		if (vlan == VlanVid.FULL_MASK) {
			vlan = VlanVid.ofVlan(0);
		}

		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		if (swMap != null) {
			swMap.remove(new MacVlanPair(mac, vlan));
		}
	}

	/**
	 * Get the port that a MAC/VLAN pair is associated with
	 * @param sw The switch to get the mapping from
	 * @param mac The MAC address to get
	 * @param vlan The VLAN number to get
	 * @return The port the host is on
	 */
	public OFPort getFromPortMap(IOFSwitch sw, MacAddress mac, VlanVid vlan) {
		if (vlan == VlanVid.FULL_MASK || vlan == null) {
			vlan = VlanVid.ofVlan(0);
		}
		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		if (swMap != null) {
			return swMap.get(new MacVlanPair(mac, vlan));
		}

		// if none found
		return null;
	}

	/**
	 * Clears the MAC/VLAN -> SwitchPort map for all switches
	 */
	public void clearLearnedTable() {
		macVlanToSwitchPortMap.clear();
	}

	/**
	 * Clears the MAC/VLAN -> SwitchPort map for a single switch
	 * @param sw The switch to clear the mapping for
	 */
	public void clearLearnedTable(IOFSwitch sw) {
		Map<MacVlanPair, OFPort> swMap = macVlanToSwitchPortMap.get(sw);
		if (swMap != null) {
			swMap.clear();
		}
	}

	@Override
	public synchronized Map<IOFSwitch, Map<MacVlanPair, OFPort>> getTable() {
		return macVlanToSwitchPortMap;
	}

	/**
	 * Writes a OFFlowMod to a switch.
	 * @param sw The switch tow rite the flowmod to.
	 * @param command The FlowMod actions (add, delete, etc).
	 * @param bufferId The buffer ID if the switch has buffered the packet.
	 * @param match The OFMatch structure to write.
	 * @param outPort The switch port to output it to.
	 */
	private void writeFlowMod(IOFSwitch sw, OFFlowModCommand command, OFBufferId bufferId,
			Match match, OFPort outPort) {
		// from openflow 1.0 spec - need to set these on a struct ofp_flow_mod:
		// struct ofp_flow_mod {
		//    struct ofp_header header;
		//    struct ofp_match match; /* Fields to match */
		//    uint64_t cookie; /* Opaque controller-issued identifier. */
		//
		//    /* Flow actions. */
		//    uint16_t command; /* One of OFPFC_*. */
		//    uint16_t idle_timeout; /* Idle time before discarding (seconds). */
		//    uint16_t hard_timeout; /* Max time before discarding (seconds). */
		//    uint16_t priority; /* Priority level of flow entry. */
		//    uint32_t buffer_id; /* Buffered packet to apply to (or -1).
		//                           Not meaningful for OFPFC_DELETE*. */
		//    uint16_t out_port; /* For OFPFC_DELETE* commands, require
		//                          matching entries to include this as an
		//                          output port. A value of OFPP_NONE
		//                          indicates no restriction. */
		//    uint16_t flags; /* One of OFPFF_*. */
		//    struct ofp_action_header actions[0]; /* The action length is inferred
		//                                            from the length field in the
		//                                            header. */
		//    };

		OFFlowMod.Builder fmb;
		if (command == OFFlowModCommand.DELETE) {
			fmb = sw.getOFFactory().buildFlowDelete();
		} else {
			fmb = sw.getOFFactory().buildFlowAdd();
		}
		
		fmb.setMatch(match);
		fmb.setCookie((U64.of(LearningSwitch.LEARNING_SWITCH_COOKIE)));
		fmb.setIdleTimeout(LearningSwitch.FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		fmb.setHardTimeout(LearningSwitch.FLOWMOD_DEFAULT_HARD_TIMEOUT);
	    fmb.setPriority(65000);

		
		fmb.setBufferId(bufferId);
		fmb.setOutPort((command == OFFlowModCommand.DELETE) ? OFPort.ANY : outPort);
		Set<OFFlowModFlags> sfmf = new HashSet<OFFlowModFlags>();
	
		if (command != OFFlowModCommand.DELETE) {
			sfmf.add(OFFlowModFlags.SEND_FLOW_REM);
		}
		fmb.setFlags(sfmf);

		log.info("I m adding flow: source " +match.get(MatchField.ETH_SRC)+"dest "+match.get(MatchField.ETH_DST)+
				 " vlan "+match.get(MatchField.VLAN_VID)+" pcp " +match.get(MatchField.VLAN_PCP));
		// set the ofp_action_header/out actions:
			// from the openflow 1.0 spec: need to set these on a struct ofp_action_output:
		// uint16_t type; /* OFPAT_OUTPUT. */
		// uint16_t len; /* Length is 8. */
		// uint16_t port; /* Output port. */
		// uint16_t max_len; /* Max length to send to controller. */
		// type/len are set because it is OFActionOutput,
		// and port, max_len are arguments to this constructor
		List<OFAction> al = new ArrayList<OFAction>();
		al.add(sw.getOFFactory().actions().buildOutput().setPort(outPort).setMaxLen(0xffFFffFF).build());

		FlowModUtils.setActions(fmb, al, sw);

		if (log.isTraceEnabled()) {
			log.trace("{} {} flow mod {}",
					new Object[]{ sw, (command == OFFlowModCommand.DELETE) ? "deleting" : "adding", fmb.build() });
		}

		counterFlowMod.increment();

		// and write it out
		sw.write(fmb.build());
	}

	/**
	 * Pushes a packet-out to a switch.  The assumption here is that
	 * the packet-in was also generated from the same switch.  Thus, if the input
	 * port of the packet-in and the outport are the same, the function will not
	 * push the packet-out.
	 * @param sw        switch that generated the packet-in, and from which packet-out is sent
	 * @param match     OFmatch
	 * @param pi        packet-in
	 * @param outport   output port
	 */
	private void pushPacket(IOFSwitch sw, Match match, OFPacketIn pi, OFPort outport) {
		if (pi == null) {
			return;
		}

		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));

		// The assumption here is (sw) is the switch that generated the
		// packet-in. If the input port is the same as output port, then
		// the packet-out should be ignored.
		if (inPort.equals(outport)) {
			if (log.isDebugEnabled()) {
				log.debug("Attempting to do packet-out to the same " +
						"interface as packet-in. Dropping packet. " +
						" SrcSwitch={}, match = {}, pi={}",
						new Object[]{sw, match, pi});
				return;
			}
		}

		if (log.isTraceEnabled()) {
			log.trace("PacketOut srcSwitch={} match={} pi={}",
					new Object[] {sw, match, pi});
		}

		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();

		// set actions
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(outport).setMaxLen(0xffFFffFF).build());

		pob.setActions(actions);

		// If the switch doens't support buffering set the buffer id to be none
		// otherwise it'll be the the buffer id of the PacketIn
		if (sw.getBuffers() == 0) {
			// We set the PI buffer id here so we don't have to check again below
			pi = pi.createBuilder().setBufferId(OFBufferId.NO_BUFFER).build();
			pob.setBufferId(OFBufferId.NO_BUFFER);
		} else {
			pob.setBufferId(pi.getBufferId());
		}

		pob.setInPort(inPort);

		// If the buffer id is none or the switch doesn's support buffering
		// we send the data with the packet out
		if (pi.getBufferId() == OFBufferId.NO_BUFFER) {
			byte[] packetData = pi.getData();
			pob.setData(packetData);
		}

		counterPacketOut.increment();
		sw.write(pob.build());
	}

	/**
	 * Writes an OFPacketOut message to a switch.
	 * @param sw The switch to write the PacketOut to.
	 * @param packetInMessage The corresponding PacketIn.
	 * @param egressPort The switchport to output the PacketOut.
	 */
	private void writePacketOutForPacketIn(IOFSwitch sw, OFPacketIn packetInMessage, OFPort egressPort) {
		OFMessageUtils.writePacketOutForPacketIn(sw, packetInMessage, egressPort);
		counterPacketOut.increment();
	}

	protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, FloodlightContext cntx) {
		// The packet in match will only contain the port number.
		// We need to add in specifics for the hosts we're routing between.
		
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPv4 ipv4;
		short identification;
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
		VlanPcp pcp=VlanPcp.of(eth.getPriorityCode());
		if(eth.getEtherType()==EthType.IPv4){
			ipv4=(IPv4)eth.getPayload();
			identification=ipv4.getIdentification();
			System.out.println("Identification: "+identification); // bit rate requested from the packet

		}
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, inPort)
		.setExact(MatchField.ETH_SRC, srcMac)
		.setExact(MatchField.ETH_DST, dstMac)
		.setExact(MatchField.ETH_TYPE, eth.getEtherType());
		
		
		 
		if (!vlan.equals(VlanVid.ZERO)) {
			mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
			mb.setExact(MatchField.VLAN_PCP, VlanPcp.of(eth.getPriorityCode()));
			
		}

		return mb.build();
	}

	/**
	 * Processes a OFPacketIn message. If the switch has learned the MAC/VLAN to port mapping
	 * for the pair it will write a FlowMod for. If the mapping has not been learned the
	 * we will flood the packet.
	 * @param sw
	 * @param pi
	 * @param cntx
	 * @return
	 */
	private Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {

		countpkt++;
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		short identification;
		/* Read packet header attributes into Match */
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		IPv4 ipv4=(IPv4)eth.getPayload(); //ipv4 pyload extracted by ethernet frame
		if(ipv4==null) return Command.STOP; //pkt with errors
		Match m = createMatchFromPacket(sw, inPort, cntx);
		MacAddress sourceMac = m.get(MatchField.ETH_SRC);
		MacAddress destMac = m.get(MatchField.ETH_DST);
		VlanVid vlan = m.get(MatchField.VLAN_VID) == null ? VlanVid.ZERO : m.get(MatchField.VLAN_VID).getVlanVid();
		System.out.println("ETH type: "+m.get(MatchField.ETH_TYPE));
		System.out.println("Packet from: "+inPort.getPortNumber()+" source mac: "+sourceMac+ "destination mac: "+destMac+ "Vlan id: "+vlan.getVlan());
		identification=ipv4.getIdentification(); //bit rate requested
		log.info("Bit rte requested: "+identification);

		if (sourceMac == null) {
			sourceMac = MacAddress.NONE;
		}
		if (destMac == null) {
			destMac = MacAddress.NONE;
		}
		if (vlan == null) {
			vlan = VlanVid.ZERO;
		}

		if ((destMac.getLong() & 0xfffffffffff0L) == 0x0180c2000000L) {
			if (log.isTraceEnabled()) {
				log.trace("ignoring packet addressed to 802.1D/Q reserved addr: switch {} vlan {} dest MAC {}",
						new Object[]{ sw, vlan, destMac.toString() });
			}
			return Command.STOP;
		}
		if ((sourceMac.getLong() & 0x010000000000L) == 0) {
			// If source MAC is a unicast address, learn the port for this MAC/VLAN	
			//if((this.checkMacAddress(sw, sourceMac, vlan, portVal))==false)
			OFPort portlearned=getFromPortMap(sw,sourceMac,vlan);
			if(portlearned==null){
				log.info("I learn the port");
				this.addToPortMap(sw, sourceMac, vlan, inPort);
				OFPort portin=this.getFromPortMap(sw, sourceMac, vlan);
				log.info("I know that device is attacched on: "+portin.getPortNumber());
			}
			else{
				log.info("I have already learned mac: "+sourceMac+"that is linked in port: "+portlearned.getPortNumber()+"for vlan: "+vlan.getVlan());
			}
			
		}

		// Now output flow-mod and/or packet
		OFPort outPort = getFromPortMap(sw, destMac, vlan); // check where is linked the dest mac adddress, outport of the flow
		
		if (outPort == null) { // I haven t learnt the port for that device but I will learn it
			// If we haven't learned the port for the dest MAC/VLAN, flood it
			// Don't flood broadcast packets if the broadcast is disabled.
			// XXX For LearningSwitch this doesn't do much. The sourceMac is removed
			//     from port map whenever a flow expires, so you would still see
			//     a lot of floods.
			 
			log.info("I have never seen this device dest mac: "+destMac +"so the action is gonna be flood, that's impossible because at startup time devices send its mac address");
			log.info("I wait for his advertisement: "+destMac);
			 	return Command.STOP; //I stop it
	
		//	this.writePacketOutForPacketIn(sw, pi, OFPort.FLOOD);
		} else if (outPort.equals(inPort)) {
			log.info("ignoring packet that arrived on same port as learned destination:"
					+ " switch {} vlan {} dest MAC {} port {}",
					new Object[]{ sw, vlan, destMac.toString(), outPort.getPortNumber() });
			log.trace("ignoring packet that arrived on same port as learned destination:"
					+ " switch {} vlan {} dest MAC {} port {}",
					new Object[]{ sw, vlan, destMac.toString(), outPort.getPortNumber() });
		} else {
			// Add flow table entry matching source MAC, dest MAC, VLAN and input port
			// that sends to the port we previously learned for the dest MAC/VLAN.  Also
			// add a flow table entry with source and destination MACs reversed, and
			// input and output ports reversed.  When either entry expires due to idle
			// timeout, remove the other one.  This ensures that if a device moves to
			// a different port, a constant stream of packets headed to the device at
			// its former location does not keep the stale entry alive forever.
			// FIXME: current HP switches ignore DL_SRC and DL_DST fields, so we have to match on
			// NW_SRC and NW_DST as well
			// We write FlowMods with Buffer ID none then explicitly PacketOut the buffered packet
			log.info("I push src:"+m.get(MatchField.ETH_SRC )+" dst: "+m.get(MatchField.ETH_DST));
			this.checkBW(sw,m,pi,outPort,identification);
			//this.pushPacket(sw, m, pi, outPort);
		//	this.writeFlowMod(sw, OFFlowModCommand.ADD, OFBufferId.NO_BUFFER, m, outPort);
			
	
			if (LEARNING_SWITCH_REVERSE_FLOW) {
				Match.Builder mb = m.createBuilder();
				mb.setExact(MatchField.ETH_SRC, m.get(MatchField.ETH_DST))                         
				.setExact(MatchField.ETH_DST, m.get(MatchField.ETH_SRC))     
				.setExact(MatchField.IN_PORT, outPort);
				
				if (m.get(MatchField.VLAN_VID) != null) {
					mb.setExact(MatchField.VLAN_VID, m.get(MatchField.VLAN_VID));
				}
				log.info("reverse flow");

				this.writeFlowMod(sw, OFFlowModCommand.ADD, OFBufferId.NO_BUFFER, mb.build(), inPort);
				
			}
		}
		return Command.CONTINUE;
	}

	/**
	 * Processes a flow removed message. We will delete the learned MAC/VLAN mapping from
	 * the switch's table.
	 * @param sw The switch that sent the flow removed message.
	 * @param flowRemovedMessage The flow removed message.
	 * @return Whether to continue processing this message or stop.
	 */
	private Command processFlowRemovedMessage(IOFSwitch sw, OFFlowRemoved flowRemovedMessage) {
		if (!flowRemovedMessage.getCookie().equals(U64.of(LearningSwitch.LEARNING_SWITCH_COOKIE))) {
			return Command.CONTINUE;
		}
		short bitrate=0;

		if (!flowRemovedMessage.getCookie().equals(U64.of(LearningSwitch.LEARNING_SWITCH_COOKIE))) {
			return Command.CONTINUE;
		}
		if (log.isTraceEnabled()) {
			log.trace("{} flow entry removed {}", sw, flowRemovedMessage);
		}
		log.info("{} flow entry removed {}", sw, flowRemovedMessage);

		Match match = flowRemovedMessage.getMatch();
		
		Map<OFPort,Map<Match,Short>> mapPortMatchBitRate=BitRateRequestedForMatchPort.get(sw);
		if(mapPortMatchBitRate==null){
			log.info("Impossible that the map is null because we have already made the map");
		}
		Map<Match,Short> mapMatchBitRate=mapPortMatchBitRate.get(OFPort.ofInt(2));
		log.info("map: "+mapMatchBitRate.toString());
		if(mapMatchBitRate==null){
			log.info("Probably outport is not gettable");
		}
		if(mapMatchBitRate.containsKey(match)){
			bitrate=mapMatchBitRate.get(match);
			log.info("bit rate got from flowremove "+bitrate);
		}
		else 
		{
			log.info("Contains map not work");
		}
		synchronized(this){
		Map<OFPort,Float> swMap= SwitchUtilizationperPort.get(sw);
		float utilization=swMap.get(OFPort.ofInt(2)); // getting the actual utilization
		log.info("utilization before leaving packet: "+utilization);
	    float new_port_utilization=utilization-((float)bitrate/(float)BW_TOTAL); //new possible port utilization
	    log.info("new port utilization: "+new_port_utilization);
		swMap.put(OFPort.ofInt(2), new_port_utilization); //store the actual port utilization
		SwitchUtilizationperPort.put(sw, swMap);
		mapMatchBitRate.remove(match, bitrate);//removing the element of the map because we have deleted from flow so if it will arrive again I will store it in checkbW
		
		}
		return Command.CONTINUE;
	}

	// IOFMessageListener

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			return this.processPacketInMessage(sw, (OFPacketIn) msg, cntx);
		case FLOW_REMOVED:
			return this.processFlowRemovedMessage(sw, (OFFlowRemoved) msg);
		case ERROR:
			log.info("received an error {} from switch {}", msg, sw);
			return Command.CONTINUE;
		default:
			log.error("received an unexpected message {} from switch {}", msg, sw);
			return Command.CONTINUE;
		}
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	// IFloodlightModule

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ILearningSwitchService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>,  IFloodlightService> m = 
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(ILearningSwitchService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IDebugCounterService.class);
		l.add(IRestApiService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		macVlanToSwitchPortMap = new ConcurrentHashMap<IOFSwitch, Map<MacVlanPair, OFPort>>();
		BitRateRequestedForMatchPort=new ConcurrentHashMap<IOFSwitch,Map<OFPort,Map<Match,Short>>>();
		SwitchUtilizationperPort = new ConcurrentHashMap<IOFSwitch,Map<OFPort,Float>>();
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
	//	statisticsService=context.getServiceImpl(IStatisticsService.class);
		

	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		// paag: register the IControllerCompletionListener
		floodlightProviderService.addCompletionListener(this);
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addOFMessageListener(OFType.FLOW_REMOVED, this);
		floodlightProviderService.addOFMessageListener(OFType.ERROR, this);
		floodlightProviderService.addOFMessageListener(OFType.STATS_REPLY, this);
		restApiService.addRestletRoutable(new LearningSwitchWebRoutable());
		
		
		

		// read our config options
		Map<String, String> configOptions = context.getConfigParams(this);
		try {
			String idleTimeout = configOptions.get("idletimeout");
			if (idleTimeout != null) {
				FLOWMOD_DEFAULT_IDLE_TIMEOUT = Short.parseShort(idleTimeout);
			}
		} catch (NumberFormatException e) {
			log.warn("Error parsing flow idle timeout, " +
					"using default of {} seconds", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		}
		try {
			String hardTimeout = configOptions.get("hardtimeout");
			if (hardTimeout != null) {
				FLOWMOD_DEFAULT_HARD_TIMEOUT = Short.parseShort(hardTimeout);
			}
		} catch (NumberFormatException e) {
			log.warn("Error parsing flow hard timeout, " +
					"using default of {} seconds", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		}
		try {
			String priority = configOptions.get("priority");
			if (priority != null) {
				FLOWMOD_PRIORITY = Short.parseShort(priority);
			}
		} catch (NumberFormatException e) {
			log.warn("Error parsing flow priority, " +
					"using default of {}",
					FLOWMOD_PRIORITY);
		}
		try{
			String bwtotal=configOptions.get("bwtotal");
			if(bwtotal!=null){
				BW_TOTAL=Float.parseFloat(bwtotal);
				log.info("BW dedicate: "+BW_TOTAL);
				log.info("value "+(0.443-0.45));
			//	log.info("BW dedicated float: "+(float)BW_TOTAL);
			}
			} catch (NumberFormatException e) {
				log.warn("Error parsing bandwidth dedicated, " +
						"using default of {}",
						BW_TOTAL);
			}
		try{
			String priorityallowed=configOptions.get("priorityallowed");
			if(priorityallowed!=null){
				PRIORITY_ALLOWED=Short.parseShort(priorityallowed);
			}
			}catch (NumberFormatException e) {
				log.warn("Error parsing flow priority allowed, " +
						"using default of {}",
						PRIORITY_ALLOWED);
			}
		try{
			String threshold=configOptions.get("threshold");
			if(threshold!=null){
				THRESHOLD=Float.parseFloat(threshold);
			}
			}catch (NumberFormatException e) {
				log.warn("Error parsing flow threshold, " +
						"using default of {}",
						THRESHOLD);
			}
		log.debug("FlowMod idle timeout set to {} seconds", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		log.debug("FlowMod hard timeout set to {} seconds", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		log.debug("FlowMod priority set to {}", FLOWMOD_PRIORITY);

		debugCounterService.registerModule(this.getName());
		counterFlowMod = debugCounterService.registerCounter(this.getName(), "flow-mods-written", "Flow mods written to switches by LearningSwitch", MetaData.WARN);
		counterPacketOut = debugCounterService.registerCounter(this.getName(), "packet-outs-written", "Packet outs written to switches by LearningSwitch", MetaData.WARN);
	}

	// paag: to show the IControllerCompletion concept
	// CAVEAT: extremely noisy when tracking enabled
	@Override
	public void onMessageConsumed(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		if (this.flushAtCompletion) {
			log.debug("Learning switch: ended processing packet {}",msg.toString());
		}
	}
}
