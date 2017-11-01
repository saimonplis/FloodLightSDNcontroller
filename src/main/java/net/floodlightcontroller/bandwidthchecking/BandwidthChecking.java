package net.floodlightcontroller.bandwidthchecking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.OFPort;
//import org.projectfloodlight.openflow.types.int;


import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.statistics.IStatisticsService;
import net.floodlightcontroller.statistics.StatisticsCollector;
import net.floodlightcontroller.threadpool.IThreadPoolService;

public class BandwidthChecking implements IFloodlightModule, IOFMessageListener {
	protected IFloodlightProviderService floodlightProvider;
	protected IStatisticsService statisticscollector;
	private static IThreadPoolService threadPoolService;
	protected int val=0;
	private ArrayList<Long> array;
	private int i=0;
	protected HashMap<OFPort, Map<Integer,ArrayList<Long>>> PortTime; //for each port, I catch packet xid and arrival time
	protected Executor executor;
	@Override
	public String getName() {
		// TODO Auto-generated method stub
		return "BandwidthChecking";
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-enerated method stub
	//	return (type.equals(OFType.PACKET_IN) && (name.equals("statistics"))); //statistics have been collected before the execution of my module
	//	if (val==0) return ((type.equals(OFType.PACKET_IN))&&((name.equals("forwarding"))||(name.equals("statistics"))));
		//else
return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
	/*	if (val==0 )return false; // the first time forwarding has to be executed
		else
		return (type.equals(OFType.PACKET_IN) && (name.equals("forwarding")));//module forwarding has to execute after my module
	*/
		return type.equals(OFType.PACKET_IN)&&name.equals("learningswitch");
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		//array.add(i, System.currentTimeMillis()) ;
		OFPacketIn pi;
		Map<Integer,ArrayList<Long>> aux;

		pi=(OFPacketIn)msg;
		Long [] anArray=new Long[10] ;
		executor.execute(new CatchPacketThread(PortTime,pi,i));
/*		val=1;
	//	pi.get
	    long bandwidth;
		// vlan = pi.getMatch().get(MatchField.VLAN_VID).getint().ge;*/
		pi=(OFPacketIn)msg;
		int nbyte=pi.getTotalLen();
		//System.out.println("Size of pkt: "+nbyte);
		System.out.println("SUgnu u main: "+i);
	/*	try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}*/
		
		if((PortTime.get(pi.getInPort())!=null)&&PortTime.get(pi.getInPort()).get(1).size()>=3) //we have collected at least two pkts let s start to calculate the bandwidth
		{
			//for port 1
			Iterator it=PortTime.keySet().iterator();
			
			for(;it.hasNext();)
			{
				System.out.println("1");
			  synchronized(this){
				  aux=PortTime.get((OFPort)it.next());
			  }
				  Iterator it1=aux.keySet().iterator();
				  for(;it1.hasNext();)
				  {
						System.out.println("2");

						  System.out.println("For VLAN: "+(int)it1.next());
						  ArrayList<Long> aux2=aux.get(1);
						  
						  int z=0;
						  for(;aux2.iterator().hasNext();){
								System.out.println("3");
								if ((z+1)>=aux2.size()) break;
							  System.out.println("Size:" +aux2.size());
							  synchronized(this){
								  System.out.println("valore tempo 1: "+aux2.get(z+1));
								  System.out.println("valore tempo 2: "+aux2.get(z));
								  System.out.println("byte: "+nbyte);
								  long valore=nbyte/((aux2.get(z+1)+1)-aux2.get(z));
								 
								  long bdwdt=statisticscollector.getBandwidthConsumption(sw.getId(),pi.getInPort()).getBitsPerSecondRx().getValue();
								  
							  System.out.println("valore: "+valore);
							  System.out.println("banda: "+bdwdt);
							  if(bdwdt+valore>100){
								  System.out.println("pacchetto stoppato");
								  return Command.STOP;
							  }
							  //aux2.remove(z);
							  }
							  z++;
							  
							  
						  }
					/*	  anArray=(Long[])aux2.toArray();
						  for(int j=0;j<(anArray.length-1);i++){
							  System.out.println("primo: "+anArray[j]+"secondo"+anArray[j+1]);
						  }*
			/*	  Iterator t2=aux.get(it.next()).iterator();
						  for(;t2.hasNext();)
						  {
							  
						  }*/
						  	
				  }
					  
			  
			}
		}
		
	//  statisticscollector.getBandwidthConsumption(sw.getId(), pi.getMatch().get(MatchField.IN_PORT)).getBitsPerSecondRx().getValue();
	    //while(statisticscollector.getBandwidthConsumption(sw.getId(), pi.getMatch().get(MatchField.IN_PORT))==null){
	     //System.out.println("Mbaruccio sugnu null");
	    //}
	/*	System.out.println("Banda: "+statisticscollector.getBandwidthConsumption(sw.getId(), pi.getMatch().get(MatchField.IN_PORT)).getBitsPerSecondRx().getValue());
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
	*/
		return Command.CONTINUE;
		
	}
	protected class CatchPacketThread implements Runnable{
		private HashMap<OFPort, Map<Integer,ArrayList<Long>>> PortTime;
		private OFPacketIn pi;
		private int i;
		public CatchPacketThread(HashMap<OFPort, Map<Integer,ArrayList<Long>>> PortTime,OFPacketIn pi,Integer i){
			this.PortTime=PortTime;
			this.pi=pi;
			this.i=i;
			
		}
		@Override
		public void run() {
			// TODO Auto-generated meHthod stub
			if (PortTime.isEmpty()||(PortTime.containsKey(pi.getInPort())==false)){ // if the map is empty or we have never seen a packet from that port
				HashMap<Integer,ArrayList<Long>> aux=new HashMap<>();
				ArrayList<Long> aux1=new ArrayList<>();
				aux1.add(System.currentTimeMillis());
			//	System.out.println("VLanID: "+pi.getMatch().get(MatchField.VLAN_VID).getint());
			//	aux.put(pi.getMatch().get(MatchField.VLAN_VID).getint(),aux1);
				aux.put((int)1,aux1);
				synchronized(this){
				PortTime.put(pi.getInPort(), aux);}
			}
		/*	else if((PortTime.containsKey(pi.getInPort()))&&((PortTime.get(pi.getInPort())).isEmpty())) // if map field is empty
			{
				HashMap<int,Long> aux=new HashMap<>();
				aux.put(pi.getMatch().get(MatchField.VLAN_VID).getint(), System.currentTimeMillis());
				synchronized(this){
				PortTime.put(pi.getInPort(),aux);}
			}*/
			else{

				
				synchronized(this){
					System.out.println("Sei vuoto?");
					System.out.println("Vediamo: "+PortTime.get(pi.getInPort()).get(1).toString());
					System.out.println("NOn sei vuoti");
					
			//	PortTime.get(PortTime.get(pi.getInPort())).get(pi.getMatch().get(MatchField.VLAN_VID).getint()).add(System.currentTimeMillis());}
					PortTime.get((pi.getInPort())).get(1).add(System.currentTimeMillis());
					}
				System.out.println("Tempo di arrivo aggiunto");
				}
			i++;

			
			System.out.println("Sugnu u thread: "+i);
			
		}
		

	}
	
	protected class  BandwidthThread implements Runnable{
		OFPacketIn pi;
		IOFSwitch sw;
		ArrayList<Long> array;
		protected long difference;
		protected long bandwidth_consumed;
		protected long bandwidth_in;
		protected BandwidthThread(OFPacketIn pi,IOFSwitch sw,ArrayList<Long> array){
			this.pi=pi;
			this.sw=sw;
			this.array=array;
		}

		@Override
		public void run() {
			// TODO Auto-generated method stub
			if(statisticscollector.getBandwidthConsumption(sw.getId(), pi.getMatch().get(MatchField.IN_PORT))==null){
				System.out.println("Banda nulla");
			}
			
				else
					synchronized(this){
					
					difference=array.get(0)-(array.get(1)-10);
					System.out.println("The time interval: "+difference);
					bandwidth_consumed=statisticscollector.getBandwidthConsumption(sw.getId(), pi.getMatch().get(MatchField.IN_PORT)).getBitsPerSecondRx().getValue();
				
				//	bandwidth_in=/difference;
					System.out.println("Bandwi: "+bandwidth_in);
					//	System.out.println("Banda: "+statisticscollector.getBandwidthConsumption(sw.getId(), pi.getMatch().get(MatchField.IN_PORT)).getBitsPerSecondRx().getValue());
					}
				
			
		}
		
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		 Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		    l.add(IFloodlightProviderService.class);
			l.add(IThreadPoolService.class);

		    return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		statisticscollector=context.getServiceImpl(IStatisticsService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		array=new ArrayList<>();
		 executor= Executors.newFixedThreadPool(1500);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		 floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		 PortTime=new HashMap<OFPort, Map<Integer,ArrayList<Long>>>();
		 System.out.println("BandwidthCheckStartUp");
		 //statisticscollector.collectStatistics(true);
		

	}

}
