package com.aliyun.openservices.log.flink;

import com.aliyun.openservices.log.common.Shard;
import com.aliyun.openservices.log.flink.model.CheckpointMode;
import com.aliyun.openservices.log.flink.model.LogDataFetcher;
import com.aliyun.openservices.log.flink.model.LogDeserializationSchema;
import com.aliyun.openservices.log.flink.model.LogstoreShardMeta;
import com.aliyun.openservices.log.flink.model.LogstoreShardState;
import com.aliyun.openservices.log.flink.util.Consts;
import com.aliyun.openservices.log.flink.util.LogClientProxy;
import com.aliyun.openservices.log.flink.util.LogUtil;
import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.api.java.typeutils.TupleTypeInfo;
import org.apache.flink.runtime.state.CheckpointListener;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class FlinkLogConsumer<T> extends RichParallelSourceFunction<T> implements ResultTypeQueryable<T>,
        CheckpointedFunction, CheckpointListener {
    private static final Logger LOG = LoggerFactory.getLogger(FlinkLogConsumer.class);
    private static final long serialVersionUID = 7835636734161627680L;

    private static final String CURSOR_STATE_STORE_NAME = "LogStore-Shard-State";

    private final Properties configProps;
    private transient LogDataFetcher<T> fetcher;
    private volatile boolean running = true;
    private transient ListState<Tuple2<LogstoreShardMeta, String>> cursorStateForCheckpoint;
    private final LogDeserializationSchema<T> deserializer;
    private transient HashMap<LogstoreShardMeta, String> cursorsToRestore;
    private final String consumerGroup;
    private LogClientProxy logClient;
    private final String logProject;
    private final String logstore;
    private final CheckpointMode checkpointMode;

    public FlinkLogConsumer(LogDeserializationSchema<T> deserializer, Properties configProps) {
        this.configProps = configProps;
        this.deserializer = deserializer;
        this.consumerGroup = configProps.getProperty(ConfigConstants.LOG_CONSUMERGROUP);
        this.logProject = configProps.getProperty(ConfigConstants.LOG_PROJECT);
        this.logstore = configProps.getProperty(ConfigConstants.LOG_LOGSTORE);
        this.checkpointMode = LogUtil.parseCheckpointMode(configProps);
    }

    private void createClient() {
        final String userAgent = configProps.getProperty(ConfigConstants.LOG_USER_AGENT,
                Consts.LOG_CONNECTOR_USER_AGENT);
        this.logClient = new LogClientProxy(
                configProps.getProperty(ConfigConstants.LOG_ENDPOINT),
                configProps.getProperty(ConfigConstants.LOG_ACCESSSKEYID),
                configProps.getProperty(ConfigConstants.LOG_ACCESSKEY),
                userAgent);
    }

    @Override
    public void run(SourceContext<T> sourceContext) throws Exception {
        createClient();
        final RuntimeContext ctx = getRuntimeContext();
        LOG.debug("NumberOfTotalTask={}, IndexOfThisSubtask={}", ctx.getNumberOfParallelSubtasks(), ctx.getIndexOfThisSubtask());
        LogDataFetcher<T> fetcher = new LogDataFetcher<T>(sourceContext, ctx, configProps, deserializer, logClient, checkpointMode);
        if (consumerGroup != null) {
            logClient.createConsumerGroup(fetcher.getProject(), fetcher.getLogstore(), consumerGroup);
        }
        List<LogstoreShardMeta> newShards = fetcher.discoverNewShardsToSubscribe();
        for (LogstoreShardMeta shard : newShards) {
            String checkpoint = null;
            if (cursorsToRestore != null && cursorsToRestore.containsKey(shard)) {
                checkpoint = cursorsToRestore.get(shard);
            }
            fetcher.registerNewSubscribedShardState(new LogstoreShardState(shard, checkpoint));
        }
        if (!running) {
            return;
        }
        this.fetcher = fetcher;
        fetcher.runFetcher();
        fetcher.awaitTermination();
        sourceContext.close();
    }

    @Override
    public void cancel() {
        running = false;

        LogDataFetcher<T> fetcher = this.fetcher;
        this.fetcher = null;

        // this method might be called before the subtask actually starts running,
        // so we must check if the fetcher is actually created
        if (fetcher != null) {
            try {
                // interrupt the fetcher of any work
                fetcher.shutdownFetcher();
                fetcher.awaitTermination();
            } catch (Exception e) {
                LOG.warn("Error while closing log data fetcher", e);
            }
        }
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        if (!running) {
            LOG.info("snapshotState() called on closed source");
        } else {
            LOG.info("Snapshotting state ...");
            cursorStateForCheckpoint.clear();
            if (fetcher == null) {
                if (cursorsToRestore != null) {
                    for (Map.Entry<LogstoreShardMeta, String> entry : cursorsToRestore.entrySet()) {
                        // cursorsToRestore is the restored global union state;
                        // should only snapshot shards that actually belong to us
                        if (LogDataFetcher.isThisSubtaskShouldSubscribeTo(
                                entry.getKey(),
                                getRuntimeContext().getNumberOfParallelSubtasks(),
                                getRuntimeContext().getIndexOfThisSubtask())) {
                            updateCursorState(entry.getKey(), entry.getValue());
                        }
                    }
                }
            } else {
                Map<LogstoreShardMeta, String> lastStateSnapshot = fetcher.snapshotState();
                if (LOG.isDebugEnabled()) {
                    StringBuilder strb = new StringBuilder();
                    for (Map.Entry<LogstoreShardMeta, String> entry : lastStateSnapshot.entrySet()) {
                        strb.append("shard: ").append(entry.getKey().getShardId()).append(", cursor: ").append(entry.getValue());
                    }
                    LOG.debug("Snapshotted state, last processed cursor: {}, checkpoint id: {}, timestamp: {}",
                            strb, context.getCheckpointId(), context.getCheckpointTimestamp());
                }
                for (Map.Entry<LogstoreShardMeta, String> entry : lastStateSnapshot.entrySet()) {
                    updateCursorState(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private void updateCursorState(LogstoreShardMeta shardMeta, String cursor) throws Exception {
        cursorStateForCheckpoint.add(Tuple2.of(shardMeta, cursor));
        if (cursor != null && checkpointMode == CheckpointMode.ON_CHECKPOINTS) {
            // NOTE: The cursor of new discovered shard maybe null
            updateCheckpointIfNeeded(shardMeta.getShardId(), cursor);
        }
    }

    private void updateCheckpointIfNeeded(int shardId, String cursor) throws Exception {
        if (consumerGroup != null && logClient != null) {
            LOG.debug("Updating checkpoint of shard {} to {}, project {}, logstore {}", shardId, cursor, logProject, logstore);
            logClient.updateCheckpoint(logProject, logstore, consumerGroup, shardId, cursor);
        }
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        LOG.debug("initializeState...");
        TypeInformation<Tuple2<LogstoreShardMeta, String>> shardsStateTypeInfo = new TupleTypeInfo<Tuple2<LogstoreShardMeta, String>>(
                TypeInformation.of(LogstoreShardMeta.class),
                TypeInformation.of(String.class));
        cursorStateForCheckpoint = context.getOperatorStateStore().getUnionListState(
                new ListStateDescriptor<Tuple2<LogstoreShardMeta, String>>(CURSOR_STATE_STORE_NAME, shardsStateTypeInfo));
        if (!context.isRestored()) {
            LOG.info("No state restored for FlinkLogConsumer.");
            return;
        }
        if (cursorsToRestore == null) {
            cursorsToRestore = new HashMap<LogstoreShardMeta, String>();
            List<Integer> shardIds = fetchLatestShards(logProject, logstore);
            for (Tuple2<LogstoreShardMeta, String> cursor : cursorStateForCheckpoint.get()) {
                final LogstoreShardMeta shardMeta = cursor.f0;
                final String checkpoint = cursor.f1;
                LOG.info("initializeState, project: {}, logstore: {}, shard: {}, checkpoint: {}", logProject, logstore, shardMeta, checkpoint);
                int shardId = shardMeta.getShardId();
                if (shardIds != null && !shardIds.contains(shardId)) {
                    LOG.warn("The shard {} already not exist", shardId);
                    continue;
                }
                cursorsToRestore.put(shardMeta, checkpoint);
                try {
                    updateCheckpointIfNeeded(shardId, checkpoint);
                } catch (Exception ex) {
                    LOG.warn("Unable to update restored checkpoint to server", ex);
                }
            }
            LOG.info("The following offsets have been restored from Flink state: {}", cursorsToRestore);
        }
    }

    private List<Integer> fetchLatestShards(String project, String logstore) {
        try {
            List<Shard> shards = logClient.listShards(project, logstore);
            List<Integer> shardIds = new ArrayList<Integer>(shards.size());
            for (Shard shard : shards) {
                shardIds.add(shard.GetShardId());
            }
            return shardIds;
        } catch (Exception ex) {
            LOG.warn("Unable to sync shard list from server", ex);
        }
        return null;
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return deserializer.getProducedType();
    }

    @Override
    public void notifyCheckpointComplete(long l) {
    }

    @Override
    public void close() throws Exception {
        cancel();
        super.close();
    }
}
