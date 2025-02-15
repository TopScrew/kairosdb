package org.kairosdb.core.http.rest;


import com.google.inject.*;
import com.google.inject.matcher.Matchers;
import com.google.inject.name.Names;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.kairosdb.core.GuiceKairosDataPointFactory;
import org.kairosdb.core.KairosDataPointFactory;
import org.kairosdb.core.KairosFeatureProcessor;
import org.kairosdb.core.KairosRootConfig;
import org.kairosdb.core.aggregator.TestAggregatorFactory;
import org.kairosdb.core.datapoints.*;
import org.kairosdb.core.datastore.*;
import org.kairosdb.core.exception.DatastoreException;
import org.kairosdb.core.exception.KairosDBException;
import org.kairosdb.core.groupby.TestGroupByFactory;
import org.kairosdb.core.http.WebServer;
import org.kairosdb.core.http.WebServletModule;
import org.kairosdb.core.http.rest.json.QueryParser;
import org.kairosdb.core.http.rest.json.TestQueryPluginFactory;
import org.kairosdb.core.processingstage.FeatureProcessingFactory;
import org.kairosdb.core.processingstage.FeatureProcessor;
import org.kairosdb.core.scheduler.KairosDBScheduler;
import org.kairosdb.core.scheduler.KairosDBSchedulerImpl;
import org.kairosdb.eventbus.EventBusConfiguration;
import org.kairosdb.eventbus.FilterEventBus;
import org.kairosdb.plugin.Aggregator;
import org.kairosdb.plugin.GroupBy;
import org.kairosdb.testing.Client;
import org.kairosdb.util.SimpleStatsReporter;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Trigger;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.kairosdb.core.CoreModule.bindConfiguration;

public abstract class ResourceBase
{
    private static final FilterEventBus eventBus = new FilterEventBus(new EventBusConfiguration(new KairosRootConfig()));
    private static WebServer server;

    static QueryQueuingManager queuingManager;
    static Client client;
    static TestDatastore datastore;
    static MetricsResource resource;

    @BeforeClass
    public static void startup() throws Exception
    {
        //This sends jersey java util logging to logback
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

        datastore = new TestDatastore();
        queuingManager = new QueryQueuingManager(3, "localhost");

        Injector injector = Guice.createInjector(new WebServletModule(new KairosRootConfig()), new AbstractModule()
        {
            @Override
            protected void configure()
            {
                bind(FilterEventBus.class).toInstance(eventBus);
                //Need to register an exception handler
                bindListener(Matchers.any(), new TypeListener()
                {
                    public <I> void hear(TypeLiteral<I> typeLiteral, TypeEncounter<I> typeEncounter)
                    {
                        typeEncounter.register((InjectionListener<I>) i -> eventBus.register(i));
                    }
                });

                KairosRootConfig props = new KairosRootConfig();
                String configFileName = "kairosdb.conf";
                InputStream is = getClass().getClassLoader().getResourceAsStream(configFileName);
                try
                {
                    props.load(is, KairosRootConfig.ConfigFormat.fromFileName(configFileName));
                    is.close();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }

                //Names.bindProperties(binder(), props);
                bind(KairosRootConfig.class).toInstance(props);

                Map<String, String> testProperties = new HashMap<>();

                testProperties.put(WebServer.JETTY_ADDRESS_PROPERTY, "0.0.0.0");
                testProperties.put(WebServer.JETTY_PORT_PROPERTY, "9001");
                testProperties.put(WebServer.JETTY_WEB_ROOT_PROPERTY, "bogus");
                testProperties.put(WebServer.JETTY_SHOW_STACKTRACE, "false");
                testProperties.put("kairosdb.datastore.concurrentQueryThreads", "1");
                testProperties.put("kairosdb.query_cache.keep_cache_files", "false");
                testProperties.put("kairosdb.server.type", "ALL");

                props.load(testProperties);
                bindConfiguration(props, binder());

                bind(Datastore.class).toInstance(datastore);
                bind(ServiceKeyStore.class).toInstance(datastore);
                bind(KairosDatastore.class).in(Singleton.class);
                bind(FeaturesResource.class).in(Singleton.class);
                bind(FeatureProcessor.class).to(KairosFeatureProcessor.class);
                bind(KairosDBScheduler.class).toInstance(new FakeScheduler());
                bind(new TypeLiteral<FeatureProcessingFactory<Aggregator>>() {}).to(TestAggregatorFactory.class);
                bind(new TypeLiteral<FeatureProcessingFactory<GroupBy>>() {}).to(TestGroupByFactory.class);                bind(QueryParser.class).in(Singleton.class);
                bind(QueryQueuingManager.class).toInstance(queuingManager);
                bindConstant().annotatedWith(Names.named("HOSTNAME")).to("HOST");
                bind(KairosDataPointFactory.class).to(GuiceKairosDataPointFactory.class);
                bind(QueryPluginFactory.class).to(TestQueryPluginFactory.class);
                bind(SimpleStatsReporter.class);


                bind(DoubleDataPointFactory.class)
                        .to(DoubleDataPointFactoryImpl.class).in(Singleton.class);
                bind(DoubleDataPointFactoryImpl.class).in(Singleton.class);

                bind(LongDataPointFactory.class)
                        .to(LongDataPointFactoryImpl.class).in(Singleton.class);
                bind(LongDataPointFactoryImpl.class).in(Singleton.class);

                bind(LegacyDataPointFactory.class).in(Singleton.class);
                bind(StringDataPointFactory.class).in(Singleton.class);

                bind(QueryPreProcessorContainer.class).to(GuiceQueryPreProcessor.class).in(javax.inject.Singleton.class);
            }
        });
        KairosDatastore kairosDatastore = injector.getInstance(KairosDatastore.class);
        kairosDatastore.init();

        server = injector.getInstance(WebServer.class);
        server.start();

        client = new Client();
        resource = injector.getInstance(MetricsResource.class);
    }

    @AfterClass
    public static void tearDown()
    {
        if (server != null)
        {
            server.stop();
        }
    }

    public static class TestDatastore implements Datastore, ServiceKeyStore
    {
        private DatastoreException m_toThrow = null;
        private Map<String, String> metadata = new TreeMap<>();

        TestDatastore()
        {
        }

        void throwException(DatastoreException toThrow)
        {
            m_toThrow = toThrow;
        }

        @Override
        public void close()
        {
        }

        @Override
        public Iterable<String> getMetricNames(String prefix)
        {
            return Arrays.asList("cpu", "memory", "disk", "network");
        }

        @Override
        public Iterable<String> getTagNames()
        {
            return Arrays.asList("server1", "server2", "server3");
        }

        @Override
        public Iterable<String> getTagValues()
        {
            return Arrays.asList("larry", "moe", "curly");
        }

        @Override
        public void queryDatabase(DatastoreMetricQuery query, QueryCallback queryCallback) throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;

            try
            {
                SortedMap<String, String> tags = new TreeMap<>();
                tags.put("server", "server1");

                QueryCallback.DataPointWriter dataPointWriter = queryCallback.startDataPointSet(LongDataPointFactoryImpl.DST_LONG, tags);
                dataPointWriter.addDataPoint(new LongDataPoint(1, 10));
                dataPointWriter.addDataPoint(new LongDataPoint(1, 20));
                dataPointWriter.addDataPoint(new LongDataPoint(2, 10));
                dataPointWriter.addDataPoint(new LongDataPoint(2, 5));
                dataPointWriter.addDataPoint(new LongDataPoint(3, 10));
                dataPointWriter.close();

                tags = new TreeMap<>();
                tags.put("server", "server2");

                dataPointWriter = queryCallback.startDataPointSet(DoubleDataPointFactoryImpl.DST_DOUBLE, tags);
                dataPointWriter.addDataPoint(new DoubleDataPoint(1, 10.1));
                dataPointWriter.addDataPoint(new DoubleDataPoint(1, 20.1));
                dataPointWriter.addDataPoint(new DoubleDataPoint(2, 10.1));
                dataPointWriter.addDataPoint(new DoubleDataPoint(2, 5.1));
                dataPointWriter.addDataPoint(new DoubleDataPoint(3, 10.1));

                dataPointWriter.close();
            }
            catch (IOException e)
            {
                throw new DatastoreException(e);
            }
        }

        @Override
        public void deleteDataPoints(DatastoreMetricQuery deleteQuery)
        {
        }

        @Override
        public TagSet queryMetricTags(DatastoreMetricQuery query)
        {
            return null;
        }

        @Override
        public void indexMetricTags(DatastoreMetricQuery query) throws DatastoreException
        {
        }

        @Override
        public long getMinTimeValue()
        {
            return Long.MIN_VALUE;
        }

        @Override
        public long getMaxTimeValue()
        {
            return Long.MAX_VALUE;
        }

        @Override
        public void setValue(String service, String serviceKey, String key, String value) throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;
            metadata.put(service + "/" + serviceKey + "/" + key, value);
        }

        @Override
        public ServiceKeyValue getValue(String service, String serviceKey, String key) throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;
            return new ServiceKeyValue(metadata.get(service + "/" + serviceKey + "/" + key), new Date());
        }

        @Override
        public Iterable<String> listServiceKeys(String service)
                throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;

            Set<String> keys = new HashSet<>();
            for (String key : metadata.keySet()) {
                if (key.startsWith(service))
                {
                    keys.add(key.split("/")[1]);
                }
            }
            return keys;
        }

        @Override
        public Iterable<String> listKeys(String service, String serviceKey) throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;

            List<String> keys = new ArrayList<>();
            for (String key : metadata.keySet()) {
                if (key.startsWith(service + "/" + serviceKey))
                {
                    keys.add(key.split("/")[2]);
                }
            }
            return keys;
        }

        @Override
        public Iterable<String> listKeys(String service, String serviceKey, String keyStartsWith) throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;

            List<String> keys = new ArrayList<>();
            for (String key : metadata.keySet()) {
                if (key.startsWith(service + "/" + serviceKey +  "/" + keyStartsWith))
                {
                    keys.add(key.split("/")[2]);
                }
            }
            return keys;
        }

        @Override
        public void deleteKey(String service, String serviceKey, String key)
                throws DatastoreException
        {
            if (m_toThrow != null)
                throw m_toThrow;

            metadata.remove(service + "/" + serviceKey + "/" + key);
        }

        @Override
        public Date getServiceKeyLastModifiedTime(String service, String serviceKey)
        {
            return null;
        }
    }

    private static class FakeScheduler implements KairosDBScheduler
    {
        @Override
        public void start() throws KairosDBException
        {

        }

        @Override
        public void stop()
        {

        }

        @Override
        public void schedule(JobDetail jobDetail, Trigger trigger) throws KairosDBException
        {

        }

        @Override
        public void cancel(JobKey jobKey) throws KairosDBException
        {

        }

        @Override
        public Set<String> getScheduledJobIds() throws KairosDBException
        {
            return Collections.emptySet();
        }
    }
}
