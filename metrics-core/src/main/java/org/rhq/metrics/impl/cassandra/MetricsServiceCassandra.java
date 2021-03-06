/*
 * Copyright 2014 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rhq.metrics.impl.cassandra;

import static org.joda.time.Hours.hours;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import org.joda.time.Duration;
import org.joda.time.Hours;
import org.rhq.metrics.core.Availability;
import org.rhq.metrics.core.AvailabilityMetric;
import org.rhq.metrics.core.Counter;
import org.rhq.metrics.core.Interval;
import org.rhq.metrics.core.Metric;
import org.rhq.metrics.core.MetricAlreadyExistsException;
import org.rhq.metrics.core.MetricData;
import org.rhq.metrics.core.MetricId;
import org.rhq.metrics.core.MetricType;
import org.rhq.metrics.core.MetricsService;
import org.rhq.metrics.core.MetricsThreadFactory;
import org.rhq.metrics.core.NumericData;
import org.rhq.metrics.core.NumericMetric;
import org.rhq.metrics.core.Retention;
import org.rhq.metrics.core.RetentionSettings;
import org.rhq.metrics.core.SchemaManager;
import org.rhq.metrics.core.Tenant;
import org.rhq.metrics.core.TenantAlreadyExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.ResultSetFuture;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.RateLimiter;

/**
 * @author John Sanda
 */
public class MetricsServiceCassandra implements MetricsService {

    private static final Logger logger = LoggerFactory.getLogger(MetricsServiceCassandra.class);

    public static final String REQUEST_LIMIT = "rhq.metrics.request.limit";

    public static final int DEFAULT_TTL = Duration.standardDays(7).toStandardSeconds().getSeconds();

    private static final Function<ResultSet, Void> RESULT_SET_TO_VOID = resultSet -> null;

    private static final Function<List<ResultSet>, Void> RESULT_SETS_TO_VOID = resultSets -> null;

    private static class DataRetentionKey {
        private final String tenantId;
        private final MetricId metricId;
        private final MetricType type;

        public DataRetentionKey(String tenantId, MetricType type) {
            this.tenantId = tenantId;
            this.type = type;
            metricId = new MetricId("[" + type.getText() + "]");
        }

        public DataRetentionKey(String tenantId, MetricId metricId, MetricType type) {
            this.tenantId = tenantId;
            this.metricId = metricId;
            this.type = type;
        }

        public DataRetentionKey(Metric metric) {
            this.tenantId = metric.getTenantId();
            this.metricId = metric.getId();
            this.type = metric.getType();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            DataRetentionKey that = (DataRetentionKey) o;

            if (!metricId.equals(that.metricId)) return false;
            if (!tenantId.equals(that.tenantId)) return false;
            return type == that.type;

        }

        @Override
        public int hashCode() {
            int result = tenantId.hashCode();
            result = 31 * result + metricId.hashCode();
            result = 31 * result + type.hashCode();
            return result;
        }
    }

    private final RateLimiter permits = RateLimiter.create(Double.parseDouble(
        System.getProperty(REQUEST_LIMIT, "30000")), 3, TimeUnit.MINUTES);

    private Optional<Session> session;

    private DataAccess dataAccess;

    private final ListeningExecutorService metricsTasks = MoreExecutors
        .listeningDecorator(Executors.newFixedThreadPool(4, new MetricsThreadFactory()));

    /**
     * Note that while user specifies the durations in hours, we store them in seconds.
     */
    private final Map<DataRetentionKey, Integer> dataRetentions = new ConcurrentHashMap<>();

    private final Boolean embeddedCassandraServer;

    public MetricsServiceCassandra() {
        this(false);
    }

    public MetricsServiceCassandra(boolean embeddedCassandraServer) {
        this.embeddedCassandraServer = embeddedCassandraServer;
    }

    @Override
    public void startUp(Session s) {
        // the session is managed externally
        this.session = Optional.absent();
        this.dataAccess = new DataAccessImpl(s);
        loadDataRetentions();
    }

    @Override
    public void startUp(Map<String, String> params) {

        String tmp = params.get("cqlport");
        int port = 9042;
        try {
            port = Integer.parseInt(tmp);
        } catch (NumberFormatException nfe) {
            logger.warn("Invalid context param 'cqlport', not a number. Will use a default of 9042");
        }

        String[] nodes;
        if (params.containsKey("nodes")) {
            nodes = params.get("nodes").split(",");
        } else {
            nodes = new String[] {"127.0.0.1"};
        }

        // TODO: extend this to remote C* by using C* defaults
        if (embeddedCassandraServer) {
            verifyNodeIsUp(nodes[0], 9990, 10, 1000);
        }

        Cluster cluster = new Cluster.Builder()
            .addContactPoints(nodes)
            .withPort(port)
            .build();

        String keyspace = params.get("keyspace");
        if (keyspace==null||keyspace.isEmpty()) {
            logger.debug("No keyspace given in params, checking system properties ...");
            keyspace = System.getProperty("cassandra.keyspace");
        }

        if (keyspace==null||keyspace.isEmpty()) {
            logger.debug("No explicit keyspace given, will default to 'rhq'");
            keyspace="rhq";
        }

        logger.info("Using a key space of '" + keyspace + "'");

        session = Optional.of(cluster.connect("system"));

        if (System.getProperty("cassandra.resetdb")!=null) {
            // We want a fresh DB -- mostly used for tests
            dropKeyspace(keyspace);
        }
        // This creates/updates the keyspace + tables if needed
        updateSchemaIfNecessary(keyspace);
        session.get().execute("USE " + keyspace);

        dataAccess = new DataAccessImpl(session.get());
        loadDataRetentions();
    }

    void loadDataRetentions() {
        DataRetentionsMapper mapper = new DataRetentionsMapper();
        List<String> tenantIds = loadTenantIds();
        CountDownLatch latch = new CountDownLatch(tenantIds.size() * 2);
        for (String tenantId : tenantIds) {
            ResultSetFuture numericFuture = dataAccess.findDataRetentions(tenantId, MetricType.NUMERIC);
            ResultSetFuture availabilityFuture = dataAccess.findDataRetentions(tenantId, MetricType.AVAILABILITY);
            ListenableFuture<Set<Retention>> numericRetentions = Futures.transform(numericFuture, mapper, metricsTasks);
            ListenableFuture<Set<Retention>> availabilityRetentions = Futures.transform(availabilityFuture, mapper,
                    metricsTasks);
            Futures.addCallback(numericRetentions,
                    new DataRetentionsLoadedCallback(tenantId, MetricType.NUMERIC, latch));
            Futures.addCallback(availabilityRetentions, new DataRetentionsLoadedCallback(tenantId,
                    MetricType.AVAILABILITY, latch));
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    void unloadDataRetentions() {
        dataRetentions.clear();
    }

    boolean verifyNodeIsUp(String address, int jmxPort, int retries, long timeout) {
        Boolean nativeTransportRunning = false;
        for (int i = 0; i < retries || nativeTransportRunning; ++i) {
            try {
                MBeanServerConnection serverConnection = this.getMbeanServerConnection(address, jmxPort);
                ObjectName storageService = new ObjectName("org.apache.cassandra.db:type=StorageService");
                nativeTransportRunning = (Boolean) serverConnection.getAttribute(storageService,
                        "NativeTransportRunning");

                return nativeTransportRunning;
            } catch (Exception e) {
                if (i < retries) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("The storage node is not up.", e);
                    } else {
                        Throwable rootCause = e.getCause();
                        logger.error("The storage node is not up.", rootCause);
                    }
                    logger.error("Checking storage node status again in " + (timeout * (i + 1)) + " ms...");
                }
                try {
                    Thread.sleep(timeout * (i + 1));
                } catch (InterruptedException e1) {
                    logger.error("Failed to connect to Cassandra cluster.", e1);
                }
            }
        }
        return false;
    }

    private MBeanServerConnection getMbeanServerConnection(String address, int jmxPort) throws IOException {
        if (embeddedCassandraServer) {
            return ManagementFactory.getPlatformMBeanServer();
        }

        String url = "service:jmx:rmi:///jndi/rmi://" + address + ":" + jmxPort + "/jmxrmi";
        JMXServiceURL serviceURL;
        try {
            serviceURL = new JMXServiceURL(url);
        } catch (MalformedURLException ex) {
            logger.error("Failed to verify that Cassandra cluster is running", ex);
            throw ex;
        }

        // Sleep a few seconds to work around
        // https://issues.apache.org/jira/browse/CASSANDRA-5467
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
        }

        Map<String, String> env = new HashMap<String, String>();
        JMXConnector connector = JMXConnectorFactory.connect(serviceURL, env);
        return connector.getMBeanServerConnection();
    }

    private class DataRetentionsLoadedCallback implements FutureCallback<Set<Retention>> {

        private final String tenantId;

        private final MetricType type;

        private final CountDownLatch latch;

        public DataRetentionsLoadedCallback(String tenantId, MetricType type, CountDownLatch latch) {
            this.tenantId = tenantId;
            this.type = type;
            this.latch = latch;
        }

        @Override
        public void onSuccess(Set<Retention> dataRetentionsSet) {
            for (Retention r : dataRetentionsSet) {
                dataRetentions.put(new DataRetentionKey(tenantId, r.getId(), type), r.getValue());
            }
            latch.countDown();
        }

        @Override
        public void onFailure(Throwable t) {
            logger.warn("Failed to load data retentions for {tenantId: " + tenantId + ", metricType: " +
                    type.getText() + "}", t);
            latch.countDown();
            // TODO We probably should not let initialization proceed on this error
        }
    }

    @Override
    public void shutdown() {
        if(session.isPresent()) {
            Session s = session.get();
            s.close();
            s.getCluster().close();
        }
    }

    /**
     * This is a test hook.
     */
    DataAccess getDataAccess() {
        return dataAccess;
    }

    /**
     * This is a test hook.
     */
    void setDataAccess(DataAccess dataAccess) {
        this.dataAccess = dataAccess;
    }

    @Override
    public ListenableFuture<Void> createTenant(final Tenant tenant) {
        ResultSetFuture future = dataAccess.insertTenant(tenant);
        return Futures.transform(future, new AsyncFunction<ResultSet, Void>() {
            @Override
            public ListenableFuture<Void> apply(ResultSet resultSet) {
                if (!resultSet.wasApplied()) {
                    throw new TenantAlreadyExistsException(tenant.getId());
                }
                Map<MetricType, Set<Retention>> retentionsMap = new HashMap<>();
                for (RetentionSettings.RetentionKey key : tenant.getRetentionSettings().keySet()) {
                    Set<Retention> retentions = retentionsMap.get(key.metricType);
                    if (retentions == null) {
                        retentions = new HashSet<>();
                    }
                    Interval interval = key.interval == null ? Interval.NONE : key.interval;
                    Hours hours = hours(tenant.getRetentionSettings().get(key));
                    retentions.add(new Retention(new MetricId("[" + key.metricType.getText() + "]", interval),
                        hours.toStandardSeconds().getSeconds()));
                    retentionsMap.put(key.metricType, retentions);
                }
                if (retentionsMap.isEmpty()) {
                    return Futures.immediateFuture(null);
                } else {
                    List<ResultSetFuture> updateRetentionFutures = new ArrayList<>();
                    for (MetricType type : retentionsMap.keySet()) {
                        updateRetentionFutures.add(dataAccess.updateRetentionsIndex(tenant.getId(), type,
                            retentionsMap.get(type)));
                        for (Retention r : retentionsMap.get(type)) {
                            dataRetentions.put(new DataRetentionKey(tenant.getId(), type), r.getValue());
                        }
                    }
                    ListenableFuture<List<ResultSet>> updateRetentionsFuture = Futures
                            .allAsList(updateRetentionFutures);
                    return Futures.transform(updateRetentionsFuture, RESULT_SETS_TO_VOID, metricsTasks);
                }
            }
        }, metricsTasks);
    }

    @Override
    public ListenableFuture<List<Tenant>> getTenants() {
        TenantMapper mapper = new TenantMapper();
        List<String> ids = loadTenantIds();
        List<ListenableFuture<Tenant>> tenantFutures = new ArrayList<>(ids.size());
        for (String id : ids) {
            ResultSetFuture queryFuture = dataAccess.findTenant(id);
            ListenableFuture<Tenant> tenantFuture = Futures.transform(queryFuture, mapper, metricsTasks);
            tenantFutures.add(tenantFuture);
        }
        return Futures.allAsList(tenantFutures);
    }

    private List<String> loadTenantIds() {
        ResultSet resultSet = dataAccess.findAllTenantIds().getUninterruptibly();
        List<String> ids = new ArrayList<>();
        for (Row row : resultSet) {
            ids.add(row.getString(0));
        }
        return ids;
    }

    @Override
    public ListenableFuture<Void> createMetric(final Metric metric) {
        ResultSetFuture future = dataAccess.insertMetricInMetricsIndex(metric);
        return Futures.transform(future, new AsyncFunction<ResultSet, Void>() {
            @Override
            public ListenableFuture<Void> apply(ResultSet resultSet) {
                if (!resultSet.wasApplied()) {
                    throw new MetricAlreadyExistsException(metric);
                }
                // TODO Need error handling if either of the following updates fail
                // If adding meta data fails, then we want to report the error to the
                // client. Updating the retentions_idx table could also fail. We need to
                // report that failure as well.
                ResultSetFuture metadataFuture = dataAccess.addMetadata(metric);
                if (metric.getDataRetention() == null) {
                    return Futures.transform(metadataFuture, RESULT_SET_TO_VOID);
                }
                ResultSetFuture dataRetentionFuture = dataAccess.updateRetentionsIndex(metric);
                ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(metadataFuture,
                    dataRetentionFuture);
                dataRetentions.put(new DataRetentionKey(metric), metric.getDataRetention());
                return Futures.transform(insertsFuture, RESULT_SETS_TO_VOID);
            }
        }, metricsTasks);
    }

    @Override
    public ListenableFuture<Metric> findMetric(final String tenantId, final MetricType type, final MetricId id) {
        if (type == MetricType.LOG_EVENT) {
            throw new IllegalArgumentException(MetricType.LOG_EVENT + " is not yet supported");
        }
        ResultSetFuture future = dataAccess.findMetric(tenantId, type, id, Metric.DPART);
        return Futures.transform(future, new Function<ResultSet, Metric>() {
            @Override
            public Metric apply(ResultSet resultSet) {
                if (resultSet.isExhausted()) {
                    return null;
                }
                Row row = resultSet.one();
                if (type == MetricType.NUMERIC) {
                    return new NumericMetric(tenantId, id, row.getMap(5, String.class, String.class), row.getInt(6));
                } else {
                    return new AvailabilityMetric(tenantId, id, row.getMap(5, String.class, String.class),
                        row.getInt(6));
                }
            }
        }, metricsTasks);
    }

    @Override
    public ListenableFuture<List<Metric>> findMetrics(String tenantId, MetricType type) {
        ResultSetFuture future = dataAccess.findMetricsInMetricsIndex(tenantId, type);
        return Futures.transform(future, new MetricsIndexMapper(tenantId, type), metricsTasks);
    }

    @Override
    public ListenableFuture<Void> updateMetadata(Metric metric, Map<String, String> metadata, Set<String> deletions) {
        ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(
            dataAccess.updateMetadata(metric, metadata, deletions),
            dataAccess.updateMetadataInMetricsIndex(metric, metadata, deletions)
        );
        return Futures.transform(insertsFuture, RESULT_SETS_TO_VOID, metricsTasks);
    }

    @Override
    public ListenableFuture<Void> addNumericData(List<NumericMetric> metrics) {
        List<ResultSetFuture> insertFutures = new ArrayList<>(metrics.size());
        for (NumericMetric metric : metrics) {
            if (metric.getData().isEmpty()) {
                logger.warn("There is no data to insert for {}", metric);
            } else {
                int ttl = getTTL(metric);
                insertFutures.add(dataAccess.insertData(metric, ttl));
            }
        }
        insertFutures.add(dataAccess.updateMetricsIndex(metrics));
        ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(insertFutures);
        return Futures.transform(insertsFuture, RESULT_SETS_TO_VOID, metricsTasks);
    }

    @Override
    public ListenableFuture<Void> addAvailabilityData(List<AvailabilityMetric> metrics) {
        List<ResultSetFuture> insertFutures = new ArrayList<>(metrics.size());
        for (AvailabilityMetric metric : metrics) {
            if (metric.getData().isEmpty()) {
                logger.warn("There is no data to insert for {}", metric);
            } else {
                int ttl = getTTL(metric);
                insertFutures.add(dataAccess.insertData(metric, ttl));
            }
        }
        insertFutures.add(dataAccess.updateMetricsIndex(metrics));
        ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(insertFutures);
        return Futures.transform(insertsFuture, RESULT_SETS_TO_VOID, metricsTasks);
    }

    @Override
    public ListenableFuture<Void> updateCounter(Counter counter) {
//        return Futures.transform(dataAccess.updateCounter(counter), TO_VOID);
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<Void> updateCounters(Collection<Counter> counters) {
//        ResultSetFuture future = dataAccess.updateCounters(counters);
//        return Futures.transform(future, TO_VOID);
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<List<Counter>> findCounters(String group) {
//        ResultSetFuture future = dataAccess.findCounters(group);
//        return Futures.transform(future, mapCounters, metricsTasks);
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<List<Counter>> findCounters(String group, List<String> counterNames) {
//        ResultSetFuture future = dataAccess.findCounters(group, counterNames);
//        return Futures.transform(future, mapCounters, metricsTasks);
        throw new UnsupportedOperationException();
    }

    @Override
    public ListenableFuture<NumericMetric> findNumericData(NumericMetric metric, long start, long end) {
        // When we implement date partitioning, dpart will have to be determined based on
        // the start and end params. And it is possible the the date range spans multiple
        // date partitions.
        metric.setDpart(Metric.DPART);
        ResultSetFuture queryFuture = dataAccess.findData(metric, start, end);
        return Futures.transform(queryFuture, new NumericMetricMapper(), metricsTasks);
    }

    @Override
    public ListenableFuture<AvailabilityMetric> findAvailabilityData(AvailabilityMetric metric, long start, long end) {
        ResultSetFuture queryFuture = dataAccess.findAvailabilityData(metric, start, end);
        return Futures.transform(queryFuture, new AvailabilityMetricMapper(), metricsTasks);
    }

    @Override
    public ListenableFuture<List<NumericData>> findData(NumericMetric metric, long start, long end) {
        ResultSetFuture future = dataAccess.findData(metric, start, end);
        return Futures.transform(future, new NumericDataMapper(), metricsTasks);
    }

    @Override
    public ListenableFuture<Boolean> idExists(final String id) {
        ResultSetFuture future = dataAccess.findAllNumericMetrics();
        return Futures.transform(future, new Function<ResultSet, Boolean>() {
            @Override
            public Boolean apply(ResultSet resultSet) {
                for (Row row : resultSet) {
                    if (id.equals(row.getString(2))) {
                        return true;
                    }
                }
                return false;
            }
        }, metricsTasks);
    }

    private void dropKeyspace(String keyspace) {
        session.get().execute("DROP KEYSPACE IF EXISTS " + keyspace);
    }

    @Override
    // TODO refactor to support multiple metrics
    // Data for different metrics and for the same tag are stored within the same partition
    // in the tags table; therefore, it makes sense for the API to support tagging multiple
    // metrics since they could efficiently be inserted in a single batch statement.
    public ListenableFuture<List<NumericData>> tagNumericData(NumericMetric metric, final Set<String> tags, long start,
        long end) {
        ResultSetFuture queryFuture = dataAccess.findData(metric, start, end, true);
        ListenableFuture<List<NumericData>> dataFuture = Futures.transform(queryFuture, new NumericDataMapper(true),
            metricsTasks);
        int ttl = getTTL(metric);
        ListenableFuture<List<NumericData>> updatedDataFuture = Futures.transform(dataFuture, new ComputeTTL<>(ttl));
        return Futures.transform(updatedDataFuture, new AsyncFunction<List<NumericData>, List<NumericData>>() {
            @Override
            public ListenableFuture<List<NumericData>> apply(final List<NumericData> taggedData) {
                List<ResultSetFuture> insertFutures = new ArrayList<>(tags.size());
                for (String tag : tags) {
                    insertFutures.add(dataAccess.insertNumericTag(tag, taggedData));
                }
                for (NumericData d : taggedData) {
                    insertFutures.add(dataAccess.updateDataWithTag(d, tags));
                }
                ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(insertFutures);
                return Futures.transform(insertsFuture, (List<ResultSet> resultSets) -> taggedData);
            }
        });
    }

    @Override
    public ListenableFuture<List<Availability>> tagAvailabilityData(AvailabilityMetric metric, final Set<String> tags,
        long start, long end) {
        ResultSetFuture queryFuture = dataAccess.findData(metric, start, end, true);
        ListenableFuture<List<Availability>> dataFuture = Futures.transform(queryFuture,
            new AvailabilityDataMapper(true), metricsTasks);
        int ttl = getTTL(metric);
        ListenableFuture<List<Availability>> updatedDataFuture = Futures.transform(dataFuture, new ComputeTTL<>(ttl));
        return Futures.transform(updatedDataFuture, new AsyncFunction<List<Availability>, List<Availability>>() {
            @Override
            public ListenableFuture<List<Availability>> apply(final List<Availability> taggedData) throws Exception {
                List<ResultSetFuture> insertFutures = new ArrayList<>(taggedData.size());
                for (String tag : tags) {
                    insertFutures.add(dataAccess.insertAvailabilityTag(tag, taggedData));
                }
                for (Availability a : taggedData) {
                    insertFutures.add(dataAccess.updateDataWithTag(a, tags));
                }
                ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(insertFutures);
                return Futures.transform(insertsFuture, (List<ResultSet> resultSets) -> taggedData);
            }
        });
    }

    @Override
    public ListenableFuture<List<NumericData>> tagNumericData(NumericMetric metric, final Set<String> tags,
        long timestamp) {
        ListenableFuture<ResultSet> queryFuture = dataAccess.findData(metric, timestamp, true);
        ListenableFuture<List<NumericData>> dataFuture = Futures.transform(queryFuture, new NumericDataMapper(true),
            metricsTasks);
        int ttl = getTTL(metric);
        ListenableFuture<List<NumericData>> updatedDataFuture = Futures.transform(dataFuture, new ComputeTTL<>(ttl));
        return Futures.transform(updatedDataFuture, new AsyncFunction<List<NumericData>, List<NumericData>>() {
            @Override
            public ListenableFuture<List<NumericData>> apply(final List<NumericData> data) throws Exception {
                if (data.isEmpty()) {
                    List<NumericData> results = Collections.emptyList();
                    return Futures.immediateFuture(results);
                }
                List<ResultSetFuture> insertFutures = new ArrayList<>(tags.size());
                for (String tag : tags) {
                    insertFutures.add(dataAccess.insertNumericTag(tag, data));
                }
                for (NumericData d : data) {
                    insertFutures.add(dataAccess.updateDataWithTag(d, tags));
                }
                ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(insertFutures);
                return Futures.transform(insertsFuture, (List<ResultSet> resultSets) -> data);
            }
        });
    }

    @Override
    public ListenableFuture<List<Availability>> tagAvailabilityData(AvailabilityMetric metric, final Set<String> tags,
        long timestamp) {
        ListenableFuture<ResultSet> queryFuture = dataAccess.findData(metric, timestamp);
        ListenableFuture<List<Availability>> dataFuture = Futures.transform(queryFuture,
            new AvailabilityDataMapper(true), metricsTasks);
        int ttl = getTTL(metric);
        ListenableFuture<List<Availability>> updatedDataFuture = Futures.transform(dataFuture, new ComputeTTL<>(ttl));
        return Futures.transform(updatedDataFuture, new AsyncFunction<List<Availability>, List<Availability>>() {
            @Override
            public ListenableFuture<List<Availability>> apply(final List<Availability> data) throws Exception {
                if (data.isEmpty()) {
                    List<Availability> results = Collections.emptyList();
                    return Futures.immediateFuture(results);
                }
                List<ResultSetFuture> insertFutures = new ArrayList<>();
                for (String tag : tags) {
                    insertFutures.add(dataAccess.insertAvailabilityTag(tag, data));
                }
                for (Availability a : data) {
                    insertFutures.add(dataAccess.updateDataWithTag(a, tags));
                }
                ListenableFuture<List<ResultSet>> insertsFuture = Futures.allAsList(insertFutures);
                return Futures.transform(insertsFuture, (List<ResultSet> resultSets) -> data);
            }
        });
    }

    @Override
    public ListenableFuture<Map<MetricId, Set<NumericData>>> findNumericDataByTags(String tenantId, Set<String> tags) {
        List<ListenableFuture<Map<MetricId, Set<NumericData>>>> queryFutures = new ArrayList<>(tags.size());
        for (String tag : tags) {
            queryFutures.add(Futures.transform(dataAccess.findNumericDataByTag(tenantId, tag),
                new TaggedNumericDataMapper(), metricsTasks));
        }
        ListenableFuture<List<Map<MetricId, Set<NumericData>>>> queriesFuture = Futures.allAsList(queryFutures);
        return Futures.transform(queriesFuture,
                new Function<List<Map<MetricId, Set<NumericData>>>, Map<MetricId, Set<NumericData>>>() {
            @Override
            public Map<MetricId, Set<NumericData>> apply(List<Map<MetricId, Set<NumericData>>> taggedDataMaps) {
                if (taggedDataMaps.isEmpty()) {
                    return Collections.emptyMap();
                }
                if (taggedDataMaps.size() == 1) {
                    return taggedDataMaps.get(0);
                }

                Set<MetricId> ids = new HashSet<>(taggedDataMaps.get(0).keySet());
                for (int i = 1; i < taggedDataMaps.size(); ++i) {
                    ids.retainAll(taggedDataMaps.get(i).keySet());
                }

                Map<MetricId, Set<NumericData>> mergedDataMap = new HashMap<>();
                for (MetricId id : ids) {
                    TreeSet<NumericData> set = new TreeSet<>(MetricData.TIME_UUID_COMPARATOR);
                    for (Map<MetricId, Set<NumericData>> taggedDataMap : taggedDataMaps) {
                        set.addAll(taggedDataMap.get(id));
                    }
                    mergedDataMap.put(id, set);
                }

                return mergedDataMap;
            }
        });

    }

    @Override
    public ListenableFuture<Map<MetricId, Set<Availability>>> findAvailabilityByTags(String tenantId,
        Set<String> tags) {
        List<ListenableFuture<Map<MetricId, Set<Availability>>>> queryFutures = new ArrayList<>(tags.size());
        for (String tag : tags) {
            queryFutures.add(Futures.transform(dataAccess.findAvailabilityByTag(tenantId, tag),
                new TaggedAvailabilityMappper(), metricsTasks));
        }
        ListenableFuture<List<Map<MetricId, Set<Availability>>>> queriesFuture = Futures.allAsList(queryFutures);
        return Futures.transform(queriesFuture,
                new Function<List<Map<MetricId, Set<Availability>>>, Map<MetricId, Set<Availability>>>() {
            @Override
            public Map<MetricId, Set<Availability>> apply(List<Map<MetricId, Set<Availability>>> taggedDataMaps) {
                if (taggedDataMaps.isEmpty()) {
                    return Collections.emptyMap();
                }
                if (taggedDataMaps.size() == 1) {
                    return taggedDataMaps.get(0);
                }

                Set<MetricId> ids = new HashSet<>(taggedDataMaps.get(0).keySet());
                for (int i = 1; i < taggedDataMaps.size(); ++i) {
                    ids.retainAll(taggedDataMaps.get(i).keySet());
                }

                Map<MetricId, Set<Availability>> mergedDataMap = new HashMap<>();
                for (MetricId id : ids) {
                    TreeSet<Availability> set = new TreeSet<>(MetricData.TIME_UUID_COMPARATOR);
                    for (Map<MetricId, Set<Availability>> taggedDataMap : taggedDataMaps) {
                        set.addAll(taggedDataMap.get(id));
                    }
                    mergedDataMap.put(id, set);
                }

                return mergedDataMap;
            }
        });
    }

    private int getTTL(Metric metric) {
        Integer ttl = dataRetentions.get(new DataRetentionKey(metric.getTenantId(), metric.getId(), metric.getType()));
        if (ttl == null) {
            ttl = dataRetentions.get(new DataRetentionKey(metric.getTenantId(), metric.getType()));
            if (ttl == null) {
                ttl = DEFAULT_TTL;
            }
        }
        return ttl;
    }

    private void updateSchemaIfNecessary(String schemaName) {
        try {
            SchemaManager schemaManager = new SchemaManager(session.get());
            schemaManager.createSchema(schemaName);
        } catch (IOException e) {
            throw new RuntimeException("Schema creation failed", e);
        }
    }

}