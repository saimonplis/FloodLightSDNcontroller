package net.floodlightcontroller.statistics;

import java.lang.Thread.State;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.projectfloodlight.openflow.protocol.OFStatsReply;
import org.projectfloodlight.openflow.protocol.OFStatsRequest;
import org.projectfloodlight.openflow.protocol.OFStatsType;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TableId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ListenableFuture;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.statistics.web.SwitchStatisticsWebRoutable;
import net.floodlightcontroller.threadpool.IThreadPoolService;

public class FlowStatisticscollector implements IFloodlightModule,IStatisticsService{
	
	private static final Logger log = LoggerFactory.getLogger(StatisticsCollector.class);

	public void setValues(List<OFStatsReply> values) {
		this.values = values;
		//log.info("I have set: "+this.getValues());
	}

	private static IOFSwitchService switchService;
	private static IThreadPoolService threadPoolService;
	private List<OFStatsReply> values ;
	public List<OFStatsReply> getValues() {
		log.info("I m in the get function "+ this.getClass().getName());
		return this.values;
	}

	private static boolean isEnabled = false;
	
	private static int flowStatsInterval = 10;
	private static ScheduledFuture<?> flowStatsCollector;
	
	private static final String INTERVAL_FLOW_STATS_STR = "collectionIntervalFLowStatsSeconds";
	private static final String ENABLED_STR = "enable";
	
	private class FlowStatsCollector implements Runnable {

		@Override
		public void run() {
			// TODO Auto-generated method stub
			Map<DatapathId, List<OFStatsReply>> replies = getSwitchStatistics(switchService.getAllSwitchDpids(), OFStatsType.FLOW);
		}
		
	}
	
	private class GetStatisticsThread extends Thread {
		private List<OFStatsReply> statsReply;
		private DatapathId switchId;
		private OFStatsType statType;

		public GetStatisticsThread(DatapathId switchId, OFStatsType statType) {
			this.switchId = switchId;
			this.statType = statType;
			this.statsReply = null;
		}

		public List<OFStatsReply> getStatisticsReply() {
			return statsReply;
		}

		public DatapathId getSwitchId() {
			return switchId;
		}

		@Override
		public void run() {
			statsReply = getSwitchStatistics(switchId, statType);
		}
	}
	

	/*
	 * IFloodlightModule implementation
	 */
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IStatisticsService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IStatisticsService.class, this);

		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IOFSwitchService.class);
		l.add(IThreadPoolService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		switchService = context.getServiceImpl(IOFSwitchService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		
		Map<String, String> config = context.getConfigParams(this);
		if (config.containsKey(ENABLED_STR)) {
			try {
				isEnabled = Boolean.parseBoolean(config.get(ENABLED_STR).trim());
			} catch (Exception e) {
				log.error("Could not parse '{}'. Using default of {}", ENABLED_STR, isEnabled);
			}
		}
		log.info("Statistics collection {}", isEnabled ? "enabled" : "disabled");

		if (config.containsKey(INTERVAL_FLOW_STATS_STR)) {
			try {
				flowStatsInterval = Integer.parseInt(config.get(INTERVAL_FLOW_STATS_STR).trim());
			} catch (Exception e) {
				log.error("Could not parse '{}'. Using default of {}", INTERVAL_FLOW_STATS_STR, flowStatsInterval);
			}
		}
		log.info("Port statistics collection interval set to {}s", flowStatsInterval);
	}

	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException {
		if (isEnabled) {
			startStatisticsCollection();
		}
	}
	
	/**
	 * Start all stats threads.
	 */
	private void startStatisticsCollection() {
		flowStatsCollector = threadPoolService.getScheduledExecutor().scheduleAtFixedRate(new FlowStatsCollector(), flowStatsInterval, flowStatsInterval, TimeUnit.SECONDS);
		//tentativePortStats.clear(); /* must clear out, otherwise might have huge BW result if present and wait a long time before re-enabling stats */
		log.warn("Statistics collection thread(s) started");
	}
	
	/**
	 * Stop all stats threads.
	 */
	private void stopStatisticsCollection() {
		if (!flowStatsCollector.cancel(false)) {
			log.error("Could not cancel port stats thread");
		} else {
			log.warn("Statistics collection thread(s) stopped");
		}
	}
	
	/**
	 * Retrieve the statistics from all switches in parallel.
	 * @param dpids
	 * @param statsType
	 * @return
	 */
	private Map<DatapathId, List<OFStatsReply>> getSwitchStatistics(Set<DatapathId> dpids, OFStatsType statsType) {
		HashMap<DatapathId, List<OFStatsReply>> model = new HashMap<DatapathId, List<OFStatsReply>>();

		List<GetStatisticsThread> activeThreads = new ArrayList<GetStatisticsThread>(dpids.size());
		List<GetStatisticsThread> pendingRemovalThreads = new ArrayList<GetStatisticsThread>();
		GetStatisticsThread t;
		for (DatapathId d : dpids) {
			t = new GetStatisticsThread(d, statsType);
			activeThreads.add(t);
			t.start();
		}

		/* Join all the threads after the timeout. Set a hard timeout
		 * of 12 seconds for the threads to finish. If the thread has not
		 * finished the switch has not replied yet and therefore we won't
		 * add the switch's stats to the reply.
		 */
		for (int iSleepCycles = 0; iSleepCycles < flowStatsInterval; iSleepCycles++) {
			for (GetStatisticsThread curThread : activeThreads) {
				if (curThread.getState() == State.TERMINATED) {
					model.put(curThread.getSwitchId(), curThread.getStatisticsReply());
					pendingRemovalThreads.add(curThread);
				}
			}

			/* remove the threads that have completed the queries to the switches */
			for (GetStatisticsThread curThread : pendingRemovalThreads) {
				activeThreads.remove(curThread);
			}
			
			/* clear the list so we don't try to double remove them */
			pendingRemovalThreads.clear();

			/* if we are done finish early */
			if (activeThreads.isEmpty()) {
				break;
			}

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				log.error("Interrupted while waiting for statistics", e);
			}
		}

		return model;
	}

	@SuppressWarnings("unchecked")
	public List<OFStatsReply> getSwitchStatistics(DatapathId switchId, OFStatsType statsType) {
		
		IOFSwitch sw = switchService.getSwitch(switchId);
		ListenableFuture<?> future;
	//	List<OFStatsReply> values = null;
		Match match;
		OFStatsRequest<?> req = null;
		match = sw.getOFFactory().buildMatch().build();
		req = sw.getOFFactory().buildFlowStatsRequest()
						.setMatch(match)
						.setOutPort(OFPort.ANY)
						.setTableId(TableId.ALL)
						.build();
		try {
			if (req != null) {
				future = sw.writeStatsRequest(req); 
				System.out.println("***"+req);
				this.setValues((List<OFStatsReply>) future.get(flowStatsInterval / 2, TimeUnit.SECONDS));
				System.out.println("***"+values);
			}
		} catch (Exception e) {
				log.error("Failure retrieving statistics from switch {}. {}", sw, e);
		}
	
		return values;
	}

	@Override
	public SwitchPortBandwidth getBandwidthConsumption(DatapathId dpid, OFPort p) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<NodePortTuple, SwitchPortBandwidth> getBandwidthConsumption() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void collectStatistics(boolean collect) {
		// TODO Auto-generated method stub
		
	}

}