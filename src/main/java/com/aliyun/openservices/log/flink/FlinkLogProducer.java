package com.aliyun.openservices.log.flink;

import com.aliyun.openservices.log.common.LogItem;
import com.aliyun.openservices.log.flink.model.LogSerializationSchema;
import com.aliyun.openservices.log.producer.LogProducer;
import com.aliyun.openservices.log.producer.ProducerConfig;
import com.aliyun.openservices.log.producer.ProjectConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import com.aliyun.openservices.log.flink.data.RawLog;
import com.aliyun.openservices.log.flink.data.RawLogGroup;
import com.aliyun.openservices.log.flink.util.Consts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class FlinkLogProducer<T> extends RichSinkFunction<T> implements CheckpointedFunction {

    private static final Logger LOG = LoggerFactory.getLogger(FlinkLogProducer.class);
    private final Properties configProps;
    private final LogSerializationSchema<T> schema;
    private LogPartitioner<T> customPartitioner = null;
    private transient LogProducer logProducer;
    private transient ProducerCallback callback;
    private final String logProject;
    private final String logStore;

    public FlinkLogProducer(final LogSerializationSchema<T> schema, Properties configProps) {
        if (schema == null) {
            throw new IllegalArgumentException("schema cannot be null");
        }
        if (configProps == null) {
            throw new IllegalArgumentException("configProps cannot be null");
        }
        this.configProps = configProps;
        this.schema = schema;
        this.logProject = configProps.getProperty(ConfigConstants.LOG_PROJECT);
        this.logStore = configProps.getProperty(ConfigConstants.LOG_LOGSTORE);
    }

    public Properties getConfigProps() {
        return configProps;
    }

    public LogPartitioner<T> getCustomPartitioner() {
        return customPartitioner;
    }

    public void setCustomPartitioner(LogPartitioner<T> customPartitioner) {
        this.customPartitioner = customPartitioner;
    }

    public LogSerializationSchema<T> getSchema() {
        return schema;
    }

    public ProducerCallback getCallback() {
        return callback;
    }

    public void setCallback(ProducerCallback callback) {
        this.callback = callback;
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        if (callback == null) {
            callback = new ProducerCallback();
        }
        if (customPartitioner != null) {
            customPartitioner.initialize(getRuntimeContext().getIndexOfThisSubtask(), getRuntimeContext().getNumberOfParallelSubtasks());
        }
        ProducerConfig producerConfig = new ProducerConfig();
        producerConfig.userAgent = configProps.getProperty(ConfigConstants.LOG_USER_AGENT, Consts.LOG_CONNECTOR_USER_AGENT);
        if (configProps.containsKey(ConfigConstants.LOG_SENDER_IO_THREAD_COUNT))
            producerConfig.maxIOThreadSizeInPool = Integer.parseInt(configProps.getProperty(ConfigConstants.LOG_SENDER_IO_THREAD_COUNT));
        if (configProps.containsKey(ConfigConstants.LOG_PACKAGE_TIMEOUT_MILLIS))
            producerConfig.packageTimeoutInMS = Integer.parseInt(configProps.getProperty(ConfigConstants.LOG_PACKAGE_TIMEOUT_MILLIS));
        if (configProps.containsKey(ConfigConstants.LOG_LOGS_COUNT_PER_PACKAGE))
            producerConfig.logsCountPerPackage = Integer.parseInt(configProps.getProperty(ConfigConstants.LOG_LOGS_COUNT_PER_PACKAGE));
        if (configProps.containsKey(ConfigConstants.LOG_LOGS_BYTES_PER_PACKAGE))
            producerConfig.logsBytesPerPackage = Integer.parseInt(configProps.getProperty(ConfigConstants.LOG_LOGS_BYTES_PER_PACKAGE));
        if (configProps.containsKey(ConfigConstants.LOG_MEM_POOL_BYTES))
            producerConfig.memPoolSizeInByte = Integer.parseInt(configProps.getProperty(ConfigConstants.LOG_MEM_POOL_BYTES));
        logProducer = new LogProducer(producerConfig);
        logProducer.setProjectConfig(new ProjectConfig(logProject, configProps.getProperty(ConfigConstants.LOG_ENDPOINT), configProps.getProperty(ConfigConstants.LOG_ACCESSSKEYID), configProps.getProperty(ConfigConstants.LOG_ACCESSKEY)));
        LOG.info("Started log producer instance");
    }

    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        if (logProducer != null) {
            logProducer.flush();
            Thread.sleep(logProducer.getProducerConfig().packageTimeoutInMS);
        }
    }

    public void initializeState(FunctionInitializationContext context) throws Exception {
        // do nothing
    }

    @Override
    public void invoke(T value, Context context) {
        if (this.logProducer == null) {
            throw new IllegalStateException("Flink log producer has not been initialized yet!");
        }
        RawLogGroup logGroup = schema.serialize(value);
        if (logGroup == null) {
            LOG.info("The serialized log group is null, will not send any data to log service");
            return;
        }
        String shardHashKey = null;
        if (customPartitioner != null) {
            shardHashKey = customPartitioner.getHashKey(value);
        }
        List<LogItem> logs = new ArrayList<LogItem>();
        for (RawLog rawLog : logGroup.getLogs()) {
            if (rawLog == null) {
                continue;
            }
            LogItem record = new LogItem(rawLog.getTime());
            for (Map.Entry<String, String> kv : rawLog.getContents().entrySet()) {
                record.PushBack(kv.getKey(), kv.getValue());
            }
            logs.add(record);
        }
        if (logs.isEmpty()) {
            return;
        }
        ProducerCallback cloneCallback = callback.clone();
        cloneCallback.init(logProducer, logProject, logStore, logGroup.getTopic(), shardHashKey, logGroup.getSource(), logs);
        logProducer.send(logProject, logStore, logGroup.getTopic(), shardHashKey, logGroup.getSource(), logs, cloneCallback);
    }

    @Override
    public void close() throws Exception {
        if (logProducer != null) {
            logProducer.flush();
            Thread.sleep(logProducer.getProducerConfig().packageTimeoutInMS);
            logProducer.close();
            logProducer = null;
        }
        super.close();
        LOG.info("Flink log producer has been closed");
    }
}
