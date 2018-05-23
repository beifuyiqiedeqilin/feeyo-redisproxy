package com.feeyo.kafka.net.backend.broker.offset;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.feeyo.kafka.config.KafkaPoolCfg;
import com.feeyo.kafka.config.OffsetManageCfg;
import com.feeyo.kafka.config.TopicCfg;
import com.feeyo.kafka.net.backend.broker.BrokerPartition;
import com.feeyo.kafka.net.backend.broker.BrokerPartitionOffset;
import com.feeyo.kafka.net.backend.broker.ConsumerOffset;
import com.feeyo.kafka.net.backend.broker.zk.ZkClientx;
import com.feeyo.kafka.net.backend.broker.zk.ZkPathUtil;
import com.feeyo.kafka.util.JsonUtils;
import com.feeyo.redis.config.PoolCfg;
import com.feeyo.redis.config.UserCfg;
import com.feeyo.redis.engine.RedisEngineCtx;

/**
 * 管理 topic offset
 * 
 * @author yangtao
 */
public class RunningOffsetAdmin {
	private static Logger LOGGER = LoggerFactory.getLogger(RunningOffsetAdmin.class);

	private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);
	
	private OffsetManageCfg offsetManageCfg;
	private ZkPathUtil zkPathUtil;

	private boolean isRunning = false;

	public RunningOffsetAdmin(OffsetManageCfg offsetManageCfg) {
		this.offsetManageCfg = offsetManageCfg;
		this.zkPathUtil = new ZkPathUtil(offsetManageCfg.getPath());
	}

	private void savePartitionOffsets(String topic, Map<Integer, BrokerPartitionOffset> partitionOffsetMap,
			int poolId) {

		ZkClientx zkclientx = ZkClientx.getZkClient(offsetManageCfg.getServer());
		try {
			for (Entry<Integer, BrokerPartitionOffset> entry : partitionOffsetMap.entrySet()) {
				int partition = entry.getKey();
				BrokerPartitionOffset partitionOffset = entry.getValue();
				
				// log_start_offset
				String partitionLogStartOffsetPath = zkPathUtil.getPartitionLogStartOffsetPath(poolId, topic, partition);
				sendDataToNode(zkclientx, partitionLogStartOffsetPath, String.valueOf(partitionOffset.getLogStartOffset()));
				
				// produce_offset
				String partitionProducerOffsetPath = zkPathUtil.getPartitionProducerOffsetPath(poolId, topic, partition);
				sendDataToNode(zkclientx, partitionProducerOffsetPath, String.valueOf(partitionOffset.getProducerOffset()));
				
				// 消费者
				Map<String, ConsumerOffset> consumerOffsets = partitionOffset.getConsumerOffsets();
				for (Entry<String, ConsumerOffset> consumerOffsetEntry : consumerOffsets.entrySet()) {
					String consumer = consumerOffsetEntry.getKey();
					ConsumerOffset co = consumerOffsetEntry.getValue();
					
					// 消费者点位
					String partitionConsumerOffsetPath = zkPathUtil.getPartitionConsumerOffsetPath(poolId, topic, partition, consumer);
					sendDataToNode(zkclientx, partitionConsumerOffsetPath, String.valueOf(co.getCurrentOffset()));
					
					// 消费者回退点位
					String partitionConsumerRollbackOffsetPath = zkPathUtil.getPartitionConsumerRollbackOffsetPath(poolId, topic, partition, consumer);
					sendDataToNode(zkclientx, partitionConsumerRollbackOffsetPath, co.getOldOffsetQueue().toString());

				}
			}
		} catch (Exception e) {
			LOGGER.warn("kafka cmd offset commit err:", e);
		}
	}

	private void loadPartitionOffsetByPoolId(Map<String, TopicCfg> topicCfgMap, int poolId) {
		ZkClientx zkclientx = ZkClientx.getZkClient(offsetManageCfg.getServer());
		for (TopicCfg topicCfg : topicCfgMap.values()) {
			String topic = topicCfg.getName();
			
			ConcurrentHashMap<Integer, BrokerPartitionOffset> partitionOffsetMap = new ConcurrentHashMap<Integer, BrokerPartitionOffset>();
			try {
				for (BrokerPartition partition : topicCfg.getRunningOffset().getBrokerPartitions()) {
					// log_start_offset
					String partitionLogStartOffsetPath = zkPathUtil.getPartitionLogStartOffsetPath(poolId, topic, partition.getPartition());
					String data = getNodeData(zkclientx, partitionLogStartOffsetPath);
					long logStartOffset = isNull(data) ? 0 : Long.parseLong(data);
					
					// produce_offset
					String partitionProducerOffsetPath = zkPathUtil.getPartitionProducerOffsetPath(poolId, topic, partition.getPartition());
					data = getNodeData(zkclientx, partitionProducerOffsetPath);
					long producerOffset = isNull(data) ? 0 : Long.parseLong(data);
					
					BrokerPartitionOffset partitionOffset = new BrokerPartitionOffset(partition.getPartition(), producerOffset, logStartOffset);
					partitionOffsetMap.put(partition.getPartition(), partitionOffset);
					String partitionConsumerPath = zkPathUtil.getPartitionConsumerPath(poolId, topic, partition.getPartition());
					if (!zkclientx.exists(partitionConsumerPath)) {
						continue;
					}
					
					List<String> childrenPath = zkclientx.getChildren(partitionConsumerPath);
					for (String consumer : childrenPath) {
						// consumer_offset
						String partitionConsumerOffsetPath = zkPathUtil.getPartitionConsumerOffsetPath(poolId, topic, partition.getPartition(), consumer);
						data = getNodeData(zkclientx, partitionConsumerOffsetPath);
						long consumerOffset = isNull(data) ? 0 : Long.parseLong(data);
						
						ConsumerOffset co = new ConsumerOffset(consumer, consumerOffset);
						partitionOffset.getConsumerOffsets().put(consumer, co);
						
						// consumer_offset
						String partitionConsumerRollbackOffsetPath = zkPathUtil.getPartitionConsumerRollbackOffsetPath(poolId, topic, partition.getPartition(), consumer);
						data = getNodeData(zkclientx, partitionConsumerRollbackOffsetPath);
						if (!isNull(data)) {
							ConcurrentLinkedQueue<?> offsets = JsonUtils.unmarshalFromString(data, ConcurrentLinkedQueue.class);
							Object object = offsets.poll();
							while (object != null) {
								if (object instanceof Integer) {
									co.getOldOffsetQueue().offer( Long.parseLong(object.toString()) );
								} else if (object instanceof Long) {
									co.getOldOffsetQueue().offer( (long) object );
								}
								object = offsets.poll();
							}
						}
					}
				}

				topicCfg.getRunningOffset().setPartitionOffsets(partitionOffsetMap);

			} catch (Exception e) {
				LOGGER.warn("", e);
			}
		}
	}

	public void startup() {
		if (isRunning) {
			return;
		}

		final Map<Integer, PoolCfg> poolCfgMap = RedisEngineCtx.INSTANCE().getPoolCfgMap();
		for (Entry<Integer, PoolCfg> entry : poolCfgMap.entrySet()) {
			PoolCfg poolCfg = entry.getValue();
			if (poolCfg instanceof KafkaPoolCfg) {
				Map<String, TopicCfg> topicCfgMap = ((KafkaPoolCfg) poolCfg).getTopicCfgMap();

				// 加载offset
				loadPartitionOffsetByPoolId(topicCfgMap, poolCfg.getId());
			}
		}

		// 定时持久化offset
		executorService.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				try {
					// offset 数据持久化
					saveAll();

				} catch (Exception e) {
					LOGGER.warn("offsetAdmin err: ", e);
				}

			}
		}, 30, 30, TimeUnit.SECONDS);

		isRunning = true;
	}

	/**
	 * 关闭
	 */
	public void close() {
		if (!isRunning) {
			return;
		}

		isRunning = false;

		try {
			// 关闭定时任务
			executorService.shutdown();

			// 提交本地剩余offset
			saveAll();
		} catch (Exception e) {
			isRunning = true;
		}

	}

	/**
	 * offsets 持久化
	 */
	private void saveAll() {
		final Map<Integer, PoolCfg> poolCfgMap = RedisEngineCtx.INSTANCE().getPoolCfgMap();
		for (Entry<Integer, PoolCfg> poolEntry : poolCfgMap.entrySet()) {
			PoolCfg poolCfg = poolEntry.getValue();
			if (poolCfg instanceof KafkaPoolCfg) {
				Map<String, TopicCfg> topicCfgMap = ((KafkaPoolCfg) poolCfg).getTopicCfgMap();

				for (Entry<String, TopicCfg> topicEntry : topicCfgMap.entrySet()) {
					TopicCfg topicCfg = topicEntry.getValue();
					savePartitionOffsets(topicCfg.getName(), topicCfg.getRunningOffset().getPartitionOffsets(),
							poolCfg.getId());
				}
			}
		}
	}

	private boolean isNull(String str) {
		if (str == null) {
			return true;
		}

		if ("".equals(str) || "null".equals(str) || "NULL".equals(str)) {
			return true;
		}

		return false;
	}
	
	private String zkObject2String(Object obj) {
		String result = null;
		if (obj == null) {
			return result;
		}
		
		if (obj instanceof String) {
			result = (String) obj;
		} else {
			result = new String((byte[]) obj);
		}
		
		return result;
	}

	// 获取offset
	public long getOffset(TopicCfg topicCfg, String user, int partition) {
		BrokerPartitionOffset partitionOffset = topicCfg.getRunningOffset().getPartitionOffset(partition);
		ConsumerOffset cOffset = partitionOffset.getConsumerOffsetByConsumer(user);
		long offset = cOffset.getNewOffset();
		return offset;
	}

	// 回收offset
	public void rollbackConsumerOffset(String user, String topic, int partition, long offset) {

		UserCfg userCfg = RedisEngineCtx.INSTANCE().getUserMap().get(user);
		if (userCfg == null) {
			return;
		}
		KafkaPoolCfg kafkaPoolCfg = (KafkaPoolCfg) RedisEngineCtx.INSTANCE().getPoolCfgMap().get(userCfg.getPoolId());
		if (kafkaPoolCfg == null) {
			return;
		}
		TopicCfg topicCfg = kafkaPoolCfg.getTopicCfgMap().get(topic);
		if (topicCfg == null) {
			return;
		}
		BrokerPartitionOffset partitionOffset = topicCfg.getRunningOffset().getPartitionOffset(partition);
		if (partitionOffset == null) {
			return;
		}

		partitionOffset.rollbackConsumerOffset(user, offset);

	}

	// 更新生产offset
	public void updateProducerOffset(String user, String topic, int partition, long offset, long logStartOffset) {

		UserCfg userCfg = RedisEngineCtx.INSTANCE().getUserMap().get(user);
		if (userCfg == null) {
			return;
		}
		KafkaPoolCfg kafkaPoolCfg = (KafkaPoolCfg) RedisEngineCtx.INSTANCE().getPoolCfgMap().get(userCfg.getPoolId());
		if (kafkaPoolCfg == null) {
			return;
		}
		TopicCfg topicCfg = kafkaPoolCfg.getTopicCfgMap().get(topic);
		if (topicCfg == null) {
			return;
		}
		BrokerPartitionOffset partitionOffset = topicCfg.getRunningOffset().getPartitionOffset(partition);
		if (partitionOffset == null) {
			return;
		}
		partitionOffset.setProducerOffset(offset, logStartOffset);

	}
	
	private String getNodeData(ZkClientx zkclientx, String path) {
		if (!zkclientx.exists(path)) {
			zkclientx.createPersistent(path, null, true);
		}
		Object obj = zkclientx.readData(path);
		return zkObject2String(obj);
	}
	
	private void sendDataToNode(ZkClientx zkclientx, String path, String value) {
		if (!zkclientx.exists(path)) {
			zkclientx.createPersistent(path, null, true);
		}
		zkclientx.writeData(path, value);
	}
}