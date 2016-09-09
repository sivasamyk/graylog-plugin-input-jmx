package com.googlecode.jmxtrans.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableSet;
import com.googlecode.jmxtrans.jmx.JmxQueryProcessor;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import javax.rmi.ssl.SslRMIClientSocketFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.MalformedURLException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;

import static com.fasterxml.jackson.databind.annotation.JsonSerialize.Inclusion.NON_NULL;
import static com.google.common.collect.ImmutableSet.copyOf;
import static com.googlecode.jmxtrans.model.PropertyResolver.resolveProps;
import static java.util.Arrays.asList;
import static javax.management.remote.JMXConnectorFactory.PROTOCOL_PROVIDER_PACKAGES;
import static javax.naming.Context.SECURITY_CREDENTIALS;
import static javax.naming.Context.SECURITY_PRINCIPAL;

/**
 * Represents a jmx server that we want to connect to. This also stores the
 * queries that we want to execute against the server.
 *
 * @author jon
 */
@JsonSerialize(include = NON_NULL)
@JsonPropertyOrder(value = {
        "alias",
        "local",
        "host",
        "port",
        "username",
        "password",
        "cronExpression",
        "numQueryThreads",
        "protocolProviderPackages"
})
public class Server {

    private static final String FRONT = "service:jmx:rmi:///jndi/rmi://";
    private static final String BACK = "/jmxrmi";

    private final String alias;
    private final String host;
    private final String port;
    private final String username;
    private final String password;
    private final String protocolProviderPackages;
    private final String url;
    private final String cronExpression;
    private final Integer numQueryThreads;
    private String trustStorePath;
    private String trustStorePass;

    public String getTrustStorePath() {
        return trustStorePath;
    }

    public void setTrustStorePath(String trustStorePath) {
        this.trustStorePath = trustStorePath;
    }

    public String getTrustStorePass() {
        return trustStorePass;
    }

    public void setTrustStorePass(String trustStorePass) {
        this.trustStorePass = trustStorePass;
    }

    // if using local JMX to embed JmxTrans to query the local MBeanServer
    private final boolean local;

    private final ImmutableSet<Query> queries;

    @JsonCreator
    public Server(
            @JsonProperty("alias") String alias,
            @JsonProperty("host") String host,
            @JsonProperty("port") String port,
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("protocolProviderPackages") String protocolProviderPackages,
            @JsonProperty("url") String url,
            @JsonProperty("cronExpression") String cronExpression,
            @JsonProperty("numQueryThreads") Integer numQueryThreads,
            @JsonProperty("local") boolean local,
            @JsonProperty("queries") List<Query> queries,
            @JsonProperty("trustStorePath") String trustStorePath,
            @JsonProperty("trustStorePass") String trustStorePass) {
        this.alias = resolveProps(alias);
        this.host = resolveProps(host);
        this.port = resolveProps(port);
        this.username = resolveProps(username);
        this.password = resolveProps(password);
        this.protocolProviderPackages = protocolProviderPackages;
        this.url = resolveProps(url);
        this.cronExpression = cronExpression;
        this.numQueryThreads = numQueryThreads;
        this.local = local;
        this.queries = copyOf(queries);
        this.trustStorePath = trustStorePath;
        this.trustStorePass = trustStorePass;
    }

    /**
     * Generates the proper username/password environment for JMX connections.
     */
    @JsonIgnore
    public Map<String, Object> getEnvironment() {
        Map<String, Object> environment = new HashMap<>();
        if (getProtocolProviderPackages() != null && getProtocolProviderPackages().contains("weblogic")) {
            if ((username != null) && (password != null)) {
                environment.put(PROTOCOL_PROVIDER_PACKAGES, getProtocolProviderPackages());
                environment.put(SECURITY_PRINCIPAL, username);
                environment.put(SECURITY_CREDENTIALS, password);
            }
        }

        if ((username != null) && (password != null)) {
            String[] credentials = new String[]{
                    username,
                    password
            };
            environment.put(JMXConnector.CREDENTIALS, credentials);
        }

        if (trustStorePath != null) {
            environment.put("com.sun.jndi.rmi.factory.socket", createSslRMIClientSocketFactory());
        }

        return environment;
    }

    private SslRMIClientSocketFactory createSslRMIClientSocketFactory() {
        return new SslRMIClientSocketFactory() {
            @Override
            public Socket createSocket(String host, int port) throws IOException {
                try {
                    final SocketFactory sslSocketFactory = getSslConetext().getSocketFactory();
                    return sslSocketFactory.createSocket(host, port);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Cannot create socket", e);
                }
            }
        };
    }

    private SSLContext getSslConetext() throws GeneralSecurityException,IOException {
        TrustManager[] myTMs = new TrustManager[]{
                new JMXX509TrustManager(trustStorePath, trustStorePass.toCharArray())};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, myTMs, null);
        return ctx;
    }

    /**
     * Helper method for connecting to a Server. You need to close the resulting
     * connection.
     */
    @JsonIgnore
    public JMXConnector getServerConnection() throws Exception {
        JMXServiceURL url = new JMXServiceURL(getUrl());
        return JMXConnectorFactory.connect(url, this.getEnvironment());
    }

    @JsonIgnore
    public MBeanServer getLocalMBeanServer() {
        // Getting the platform MBean server is cheap (expect for th first call) no need to cache it.
        return ManagementFactory.getPlatformMBeanServer();
    }

    /**
     * Some writers (GraphiteWriter) use the alias in generation of the unique
     * key which references this server.
     */
    public String getAlias() {
        return this.alias;
    }

    public String getHost() {
        if (host == null && url == null) {
            // TODO: shouldn't we just return a null in this case ?
            throw new IllegalStateException("host is null and url is null. Cannot construct host dynamically.");
        }

        if (host != null) {
            return host;
        }

        // removed the caching of the extracted host as it is a very minor
        // optimization we should probably pre compute it in the builder and
        // throw exception at construction if both url and host are set
        // we might also be able to use java.net.URI to parse the URL, but I'm
        // not familiar enough with JMX URLs to think of the test cases ...
        return url.substring(url.lastIndexOf("//") + 2, url.lastIndexOf(':'));
    }

    public String getPort() {
        if (port == null && url == null) {
            throw new IllegalStateException("port is null and url is null.  Cannot construct port dynamically.");
        }
        if (this.port != null) {
            return port;
        }

        return extractPortFromUrl(url);
    }

    private static String extractPortFromUrl(String url) {
        String computedPort = url.substring(url.lastIndexOf(':') + 1);
        if (computedPort.contains("/")) {
            computedPort = computedPort.substring(0, computedPort.indexOf('/'));
        }
        return computedPort;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    /**
     * Whether the current local Java process should be used or not (useful for
     * polling the embedded JVM when using JmxTrans inside a JVM to poll JMX
     * stats and push them remotely)
     */
    public boolean isLocal() {
        return local;
    }

    public ImmutableSet<Query> getQueries() {
        return this.queries;
    }

    /**
     * The jmx url to connect to. If null, it builds this from host/port with a
     * standard configuration. Other JVM's may want to set this value.
     */
    public String getUrl() {
        if (this.url == null) {
            if ((this.host == null) || (this.port == null)) {
                throw new RuntimeException("url is null and host or port is null. cannot construct url dynamically.");
            }
            return FRONT + this.host + ":" + this.port + BACK;
        }
        return this.url;
    }

    @JsonIgnore
    public JMXServiceURL getJmxServiceURL() throws MalformedURLException {
        return new JMXServiceURL(getUrl());
    }

    @JsonIgnore
    public boolean isQueriesMultiThreaded() {
        return (this.numQueryThreads != null) && (this.numQueryThreads > 0);
    }

    /**
     * The number of query threads for this server.
     */
    public Integer getNumQueryThreads() {
        return this.numQueryThreads;
    }

    /**
     * Each server can set a cronExpression for the scheduler. If the
     * cronExpression is null, then the job is run immediately and once.
     * Otherwise, it is added to the scheduler for immediate execution and run
     * according to the cronExpression.
     */
    public String getCronExpression() {
        return this.cronExpression;
    }

    @Override
    public String toString() {
        return "Server [host=" + this.host + ", port=" + this.port + ", url=" + this.url + ", cronExpression=" + this.cronExpression
                + ", numQueryThreads=" + this.numQueryThreads + "]";
    }


    /**
     * This is some obtuse shit for enabling weblogic support.
     * <p/>
     * http://download.oracle.com/docs/cd/E13222_01/wls/docs90/jmx/accessWLS.
     * html
     * <p/>
     * You'd set this to: weblogic.management.remote
     */
    public String getProtocolProviderPackages() {
        return protocolProviderPackages;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(Server server) {
        return new Builder(server);
    }

    public static final class Builder {
        private String alias;
        private String host;
        private String port;
        private String username;
        private String password;
        private String protocolProviderPackages;
        private String url;
        private String cronExpression;
        private Integer numQueryThreads;
        private boolean local;
        private String trustStorePath;

        public Builder setTrustStorePath(String trustStorePath) {
            this.trustStorePath = trustStorePath;
            return this;
        }

        public Builder setTrustStorePass(String trustStorePass) {
            this.trustStorePass = trustStorePass;
            return this;
        }

        private String trustStorePass;
        private final List<Query> queries = new ArrayList<Query>();

        private Builder() {
        }

        private Builder(Server server) {
            this.alias = server.alias;
            this.host = server.host;
            this.port = server.port;
            this.username = server.username;
            this.password = server.password;
            this.protocolProviderPackages = server.protocolProviderPackages;
            this.url = server.url;
            this.cronExpression = server.cronExpression;
            this.numQueryThreads = server.numQueryThreads;
            this.local = server.local;
            this.trustStorePath = server.trustStorePath;
            this.trustStorePass = server.trustStorePass;
            this.queries.addAll(server.queries);
        }

        public Builder setAlias(String alias) {
            this.alias = alias;
            return this;
        }

        public Builder setHost(String host) {
            this.host = host;
            return this;
        }

        public Builder setPort(String port) {
            this.port = port;
            return this;
        }

        public Builder setUsername(String username) {
            this.username = username;
            return this;
        }

        public Builder setPassword(String password) {
            this.password = password;
            return this;
        }

        public Builder setProtocolProviderPackages(String protocolProviderPackages) {
            this.protocolProviderPackages = protocolProviderPackages;
            return this;
        }

        public Builder setUrl(String url) {
            this.url = url;
            return this;
        }

        public Builder setCronExpression(String cronExpression) {
            this.cronExpression = cronExpression;
            return this;
        }

        public Builder setNumQueryThreads(Integer numQueryThreads) {
            this.numQueryThreads = numQueryThreads;
            return this;
        }

        public Builder setLocal(boolean local) {
            this.local = local;
            return this;
        }

        public Builder addQuery(Query query) {
            this.queries.add(query);
            return this;
        }

        public Builder addQueries(Query... queries) {
            this.queries.addAll(asList(queries));
            return this;
        }

        public Builder addQueries(Set<Query> queries) {
            this.queries.addAll(queries);
            return this;
        }

        public Server build() {
            return new Server(
                    alias,
                    host,
                    port,
                    username,
                    password,
                    protocolProviderPackages,
                    url,
                    cronExpression,
                    numQueryThreads,
                    local,
                    queries,
                    trustStorePath,
                    trustStorePass);
        }
    }


    public static void main(String args[]) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
       // Query[] queries = objectMapper.readValue(new File("src/monitors.json"), Query[].class);
        Server.Builder serverBuilder = new Server.Builder();
        serverBuilder.setHost("localhost").setPort("12345");
        //serverBuilder.setTrustStorePath("/home/user/Documents/keys/truststore.ts")
        //.setTrustStorePass("password").setPassword("derby");
        Query query = Query.builder().setObj("java.lang:type=Memory")
                .addAttr("HeapMemoryUsage")
                .addAttr("NonHeapMemoryUsage")
                .build();
        Server server = serverBuilder.build();
        MBeanServerConnection connection = server.getServerConnection().getMBeanServerConnection();
        JmxQueryProcessor queryProcessor = new JmxQueryProcessor();
       /* for (Query query : queries) {*/
            System.out.println(queryProcessor.processQuery(connection, query));
        //}
    }

    class JMXX509TrustManager implements X509TrustManager {

        /*
         * The default PKIX X509TrustManager9.  We'll delegate
         * decisions to it, and fall back to the logic in this class if the
         * default X509TrustManager doesn't trust it.
         */
        X509TrustManager pkixTrustManager;

        JMXX509TrustManager(String tsFile, char[] pass) throws GeneralSecurityException,IOException {
            // create a "default" JSSE X509TrustManager.

            KeyStore ks = KeyStore.getInstance("JKS");
            ks.load(new FileInputStream(tsFile),
                    pass);

            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance("PKIX");
            tmf.init(ks);

            TrustManager tms[] = tmf.getTrustManagers();

         /*
          * Iterate over the returned trustmanagers, look
          * for an instance of X509TrustManager.  If found,
          * use that as our "default" trust manager.
          */
            for (int i = 0; i < tms.length; i++) {
                if (tms[i] instanceof X509TrustManager) {
                    pkixTrustManager = (X509TrustManager) tms[i];
                    return;
                }
            }
        }

        /*
         * Delegate to the default trust manager.
         */
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                pkixTrustManager.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                e.printStackTrace();
            }
        }

        /*
         * Delegate to the default trust manager.
         */
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                pkixTrustManager.checkServerTrusted(chain, authType);
            } catch (CertificateException e) {
                e.printStackTrace();
            }
        }

        /*
         * Merely pass this through.
         */
        public X509Certificate[] getAcceptedIssuers() {
            return pkixTrustManager.getAcceptedIssuers();
        }
    }

}
