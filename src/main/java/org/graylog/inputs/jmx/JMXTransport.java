package org.graylog.inputs.jmx;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.assistedinject.AssistedInject;
import com.googlecode.jmxtrans.jmx.JmxQueryProcessor;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Result;
import com.googlecode.jmxtrans.model.Server;
import org.graylog.inputs.jmx.model.GLAttribute;
import org.graylog.inputs.jmx.model.GLQuery;
import org.graylog.inputs.jmx.model.GLQueryConfig;
import org.graylog2.plugin.ServerStatus;
import org.graylog2.plugin.configuration.Configuration;
import org.graylog2.plugin.configuration.ConfigurationRequest;
import org.graylog2.plugin.configuration.fields.ConfigurationField;
import org.graylog2.plugin.configuration.fields.DropdownField;
import org.graylog2.plugin.configuration.fields.NumberField;
import org.graylog2.plugin.configuration.fields.TextField;
import org.graylog2.plugin.inputs.MessageInput;
import org.graylog2.plugin.inputs.MisfireException;
import org.graylog2.plugin.inputs.annotations.ConfigClass;
import org.graylog2.plugin.inputs.annotations.FactoryClass;
import org.graylog2.plugin.inputs.codecs.CodecAggregator;
import org.graylog2.plugin.inputs.transports.Transport;
import org.graylog2.plugin.journal.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Created on 2/9/15.
 */
public class JMXTransport implements Transport {

    private static Logger LOGGER = LoggerFactory.getLogger(JMXTransport
            .class.getName());
    private final Configuration configuration;
    private final MetricRegistry metricRegistry;
    private ServerStatus serverStatus;
    private List<Server> servers;
    private GLQueryConfig queryConfig;
    private ScheduledExecutorService executorService;
    private List<ScheduledFuture> futures;
    private int executionInterval;
    private TimeUnit getExecutionIntervalTimeUnit;
    private String label;
    private Hashtable<String, MBeanServerConnection> connections;

    private static final String CK_CONFIG_HOSTS = "configHosts";
    private static final String CK_CONFIG_LABEL = "configLabel";
    private static final String CK_CONFIG_PORT = "configPort";
    private static final String CK_CONFIG_USER_NAME = "configUsername";
    private static final String CK_CONFIG_PASSWORD = "configPassword";
    private static final String CK_CONFIG_INTERVAL = "configInterval";
    private static final String CK_CONFIG_INTERVAL_UNIT = "configIntervalUnit";
    private static final String CK_CONFIG_TYPE = "configType";
    private static final String CK_CONFIG_CUSTOM_FILE_PATH = "configCustomFilePath";


    @AssistedInject
    public JMXTransport(@Assisted Configuration configuration,
                        MetricRegistry metricRegistry,
                        ServerStatus serverStatus) {
        this.configuration = configuration;
        this.metricRegistry = metricRegistry;
        this.serverStatus = serverStatus;
    }

    @Override
    public void setMessageAggregator(CodecAggregator codecAggregator) {

    }

    @Override
    public void launch(MessageInput messageInput) throws MisfireException {

        this.executionInterval = configuration.getInt(CK_CONFIG_INTERVAL);
        this.getExecutionIntervalTimeUnit = TimeUnit.valueOf(configuration.getString(CK_CONFIG_INTERVAL_UNIT));
        this.label = configuration.getString(CK_CONFIG_LABEL);
        String hosts[] = configuration.getString(CK_CONFIG_HOSTS).split(",");

        servers = new ArrayList<>(hosts.length);

        for (String host : hosts) {
            Server server = Server.builder().setHost(host)
                    .setPort(String.valueOf(configuration.getInt(CK_CONFIG_PORT)))
                    .setUsername(configuration.getString(CK_CONFIG_USER_NAME))
                    .setPassword(configuration.getString(CK_CONFIG_PASSWORD))
                    .build();
            servers.add(server);
        }

        String jmxObjectType = configuration.getString(CK_CONFIG_TYPE);
        ObjectMapper configMapper = new ObjectMapper();
        String configJson = jmxObjectType;
        boolean error = true;

        try {
            if ("custom".equals(jmxObjectType)) {
                configJson = configuration.getString(CK_CONFIG_CUSTOM_FILE_PATH);
                if (configJson != null && configJson.length() > 0) {
                    if (new File(configJson).exists()) {
                        queryConfig = configMapper.readValue(configJson, GLQueryConfig.class);
                        error = false;
                    } else {
                        LOGGER.error("Custom config file not present." + configJson);
                    }
                } else {
                    LOGGER.error("Custom config file not entered.");
                }
            } else {
                queryConfig = configMapper.readValue(
                        JMXTransport.class.getClassLoader().getResourceAsStream(configJson),
                        GLQueryConfig.class);
                error = false;
            }
        } catch (IOException e) {
            LOGGER.error("Exception while parsing config file", e);
        }

        if (!error) {
            startMonitoring(messageInput);
        }
    }


    private void startMonitoring(MessageInput messageInput) {
        executorService = Executors.newScheduledThreadPool(servers.size());
        futures = new ArrayList<>(servers.size());
        long initalDelayMillis = TimeUnit.MILLISECONDS.convert(Math.round(Math.random() * 60), TimeUnit.SECONDS);
        long executionIntervalMillis = TimeUnit.MILLISECONDS.convert(executionInterval, getExecutionIntervalTimeUnit);
        for (Server server : servers) {
            ScheduledFuture future = executorService.scheduleAtFixedRate(new PollTask(messageInput, server, queryConfig, label),
                    initalDelayMillis, executionIntervalMillis, TimeUnit.MILLISECONDS);
            futures.add(future);
        }

        LOGGER.info("JMX Input Plugin started ...");
    }

    @Override
    public void stop() {
        if (futures != null) {
            for (ScheduledFuture future : futures) {
                future.cancel(true);
            }
        }

        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @Override
    public MetricSet getMetricSet() {
        return null;
    }

    private class PollTask implements Runnable {

        private MessageInput messageInput;
        private Server server;
        private GLQueryConfig queryConfig;
        private JmxQueryProcessor queryProcessor;
        private String label;
        private ObjectMapper mapper;
        private Map<String, GLAttribute> configuredAttributes;
        private List<Query> queries;


        public PollTask(MessageInput messageInput, Server server, GLQueryConfig queryConfig, String label) {
            this.messageInput = messageInput;
            this.server = server;
            this.queryConfig = queryConfig;
            this.label = label;
            queryProcessor = new JmxQueryProcessor();
            mapper = new ObjectMapper();
            connections = new Hashtable<>();
            populateConfiguredAttributes();

        }

        private String getName() {
            return "JMX-Input-" + server.getUrl();
        }

        private void populateConfiguredAttributes() {
            configuredAttributes = new HashMap<>();
            queries = new ArrayList<>();
            for (GLQuery glQuery : queryConfig.getQueries()) {
                Query.Builder queryBuilder = Query.builder().setObj(glQuery.getObject());
                for (GLAttribute attribute : glQuery.getAttributes()) {
                    queryBuilder.addAttr(attribute.getName());
                    String mapKey = glQuery.getObject() + attribute.getName();
                    if (attribute.getKey() != null) {
                        mapKey += attribute.getKey();
                    }
                    configuredAttributes.put(mapKey,
                            attribute);
                }
                queries.add(queryBuilder.build());
            }
        }


        @Override
        public void run() {
            Thread.currentThread().setName(getName());
            fetchData();
        }

        private void fetchData() {
            try {
                MBeanServerConnection connection = getConnection(server);
                if (connection != null) {
                    Map<String, Object> eventData = Maps.newHashMap();
                    eventData.put("version", "1.1");
                    eventData.put("_object", queryConfig.getType());
                    eventData.put("host", server.getHost());
                    eventData.put("_label", label);
                    //graylog needs a short_message as part of every event
                    eventData.put("short_message", "JMX");
                    for (Query query : queries) {
                        HashMultimap<ObjectName, Result> results = queryProcessor.processQuery(connection, query);
                        for (Map.Entry<ObjectName, Result> entry : results.entries()) {
                            processResult(eventData, entry);
                        }
                    }
                    publishToGLServer(eventData);


                } else {
                    LOGGER.debug("Cannot get connection for server " + server);
                }

            } catch (Exception e) {
                LOGGER.error("Exception while querying " + server.getHost(), e);
            }
        }

        private void publishToGLServer(Map<String, Object> eventData) throws IOException {
            synchronized (JMXTransport.this) {
                //publish to graylog server
                ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
                mapper.writeValue(byteStream, eventData);
                messageInput.processRawMessage(new RawMessage(byteStream.toByteArray()));
                byteStream.close();
            }
        }


        //process JMXTrans result object as per configured json
        private void processResult(Map<String, Object> eventData, Map.Entry<ObjectName, Result> entry) {
            Result result = entry.getValue();
            String attrName = result.getAttributeName();
            Set<String> attrKeys = result.getValues().keySet();

            for (String key : attrKeys) {
                String alias = attrName;
                String attrKey = entry.getKey().toString() + attrName;
                if (attrName.equals(key)) {
                    if (configuredAttributes.containsKey(attrKey)) {
                        if (configuredAttributes.get(attrKey).getAlias() != null) {
                            alias = configuredAttributes.get(attrKey).getAlias();
                        }
                        eventData.put("_" + alias, entry.getValue().getValues().get(key));
                    }
                } else {
                    alias = attrName + "." + key;
                    attrKey += key;
                    if (configuredAttributes.containsKey(attrKey)) {
                        if (configuredAttributes.get(attrKey).getAlias() != null) {
                            alias = configuredAttributes.get(attrKey).getAlias();
                        }
                        eventData.put("_" + alias, entry.getValue().getValues().get(key));
                    }

                }

            }
        }
    }

    private MBeanServerConnection getConnection(Server server) {
        MBeanServerConnection connection = null;
        boolean create = false;
        if (connections.containsKey(server.getUrl())) {
            connection = connections.get(server.getUrl());
            try {
                connection.getMBeanCount();
            } catch (IOException e) {
                //Connection not proper. So get a new connection
                LOGGER.debug("Connection not alive for server " + server, e);
                create = true;
            }
        } else {
            create = true;
        }

        if (create) {
            try {
                connection = server.getServerConnection().getMBeanServerConnection();
                connections.put(server.getUrl(), connection);
            } catch (Exception e) {
                //Cannot create new Connection
                LOGGER.debug("Cannot create new connection for server" + server, e);
            }
        }
        return connection;
    }


    @FactoryClass
    public interface Factory extends Transport.Factory<JMXTransport> {
        @Override
        JMXTransport create(Configuration configuration);

        @Override
        Config getConfig();
    }

    @ConfigClass
    public static class Config implements Transport.Config {
        @Override
        public ConfigurationRequest getRequestedConfiguration() {
            final ConfigurationRequest cr = new ConfigurationRequest();
            cr.addField(new TextField(CK_CONFIG_HOSTS,
                    "Servers to monitor",
                    "",
                    "Comma separated IP Address/Host names to monitor"));
            cr.addField(new NumberField(CK_CONFIG_PORT,
                    "Port",
                    1099,
                    "Server JMX port to query",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            cr.addField(new TextField(CK_CONFIG_LABEL,
                    "Label",
                    "",
                    "Label to identify this HTTP monitor"));

            Map<String, String> monitorTypes = new LinkedHashMap<>();
            monitorTypes.put("jvm.json", "JVM");
            monitorTypes.put("custom", "Custom");
            cr.addField(new DropdownField(CK_CONFIG_TYPE,
                    "JMX Object type",
                    "JVM",
                    monitorTypes,
                    "JMX Object type to monitor",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            cr.addField(new TextField(CK_CONFIG_CUSTOM_FILE_PATH,
                    "Config File Path",
                    "",
                    "Absolute path of JSON config file.Applicable for Custom Object type",
                    ConfigurationField.Optional.OPTIONAL
            ));

            cr.addField(new TextField(CK_CONFIG_USER_NAME,
                    "Username",
                    "",
                    "Username for JMX Connection",
                    ConfigurationField.Optional.OPTIONAL));
            cr.addField(new TextField(CK_CONFIG_PASSWORD,
                    "Password",
                    "",
                    "Password for JMX Connection",
                    ConfigurationField.Optional.OPTIONAL,
                    TextField.Attribute.IS_PASSWORD));

            cr.addField(new NumberField(CK_CONFIG_INTERVAL,
                    "Interval",
                    1,
                    "Time between between requests",
                    ConfigurationField.Optional.NOT_OPTIONAL));

            Map<String, String> timeUnits = DropdownField.ValueTemplates.timeUnits();
            //Do not add nano seconds and micro seconds
            timeUnits.remove(TimeUnit.NANOSECONDS.toString());
            timeUnits.remove(TimeUnit.MICROSECONDS.toString());

            cr.addField(new DropdownField(
                    CK_CONFIG_INTERVAL_UNIT,
                    "Interval time unit",
                    TimeUnit.MINUTES.toString(),
                    timeUnits,
                    ConfigurationField.Optional.NOT_OPTIONAL
            ));

            return cr;
        }
    }
}
