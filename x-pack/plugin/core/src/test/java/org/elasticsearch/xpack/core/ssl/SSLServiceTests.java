/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.core.ssl;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.CheckedRunnable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.junit.annotations.Network;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.ssl.cert.CertificateInfo;
import org.joda.time.DateTime;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.security.cert.X509Certificate;
import java.nio.file.Path;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.iterableWithSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SSLServiceTests extends ESTestCase {

    private Path testnodeStore;
    private String testnodeStoreType;
    private Path testclientStore;
    private Path testnodeCert;
    private Path testnodeKey;
    private Environment env;

    @Before
    public void setup() throws Exception {
        // Randomise the keystore type (jks/PKCS#12)
        if (randomBoolean()) {
            testnodeStore = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks");
            // The default is to use JKS. Randomly test with explicit and with the default value.
            testnodeStoreType = "jks";
        } else {
            testnodeStore = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.p12");
            testnodeStoreType = randomBoolean() ? "PKCS12" : null;
        }
        logger.info("Using [{}] key/truststore [{}]", testnodeStoreType, testnodeStore);
        testnodeCert = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt");
        testnodeKey = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.pem");
        testclientStore = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testclient.jks");
        env = TestEnvironment.newEnvironment(Settings.builder().put("path.home", createTempDir()).build());
    }

    public void testThatCustomTruststoreCanBeSpecified() throws Exception {
        assumeFalse("Can't run in a FIPS JVM", inFipsJvm());
        Path testClientStore = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testclient.jks");
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.truststore.secure_password", "testnode");
        secureSettings.setString("transport.profiles.foo.xpack.security.ssl.truststore.secure_password", "testclient");
        Settings settings = Settings.builder()
                .put("xpack.ssl.truststore.path", testnodeStore)
                .put("xpack.ssl.truststore.type", testnodeStoreType)
                .setSecureSettings(secureSettings)
                .put("transport.profiles.foo.xpack.security.ssl.truststore.path", testClientStore)
                .build();
        SSLService sslService = new SSLService(settings, env);

        MockSecureSettings secureCustomSettings = new MockSecureSettings();
        secureCustomSettings.setString("truststore.secure_password", "testclient");
        Settings customTruststoreSettings = Settings.builder()
                .put("truststore.path", testClientStore)
                .setSecureSettings(secureCustomSettings)
                .build();

        SSLConfiguration configuration = new SSLConfiguration(customTruststoreSettings, globalConfiguration(sslService));
        SSLEngine sslEngineWithTruststore = sslService.createSSLEngine(configuration, null, -1);
        assertThat(sslEngineWithTruststore, is(not(nullValue())));

        SSLConfiguration globalConfig = globalConfiguration(sslService);
        SSLEngine sslEngine = sslService.createSSLEngine(globalConfig, null, -1);
        assertThat(sslEngineWithTruststore, is(not(sameInstance(sslEngine))));

        final SSLConfiguration profileConfiguration = sslService.getSSLConfiguration("transport.profiles.foo.xpack.security.ssl");
        assertThat(profileConfiguration, notNullValue());
        assertThat(profileConfiguration.trustConfig(), instanceOf(StoreTrustConfig.class));
        assertThat(((StoreTrustConfig) profileConfiguration.trustConfig()).trustStorePath, equalTo(testClientStore.toString()));
    }

    public void testThatSslContextCachingWorks() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
                .setSecureSettings(secureSettings)
                .build();
        SSLService sslService = new SSLService(settings, env);

        SSLContext sslContext = sslService.sslContext();
        SSLContext cachedSslContext = sslService.sslContext();

        assertThat(sslContext, is(sameInstance(cachedSslContext)));

        final SSLConfiguration configuration = sslService.getSSLConfiguration("xpack.ssl");
        final SSLContext configContext = sslService.sslContext(configuration);
        assertThat(configContext, is(sameInstance(sslContext)));
    }

    public void testThatKeyStoreAndKeyCanHaveDifferentPasswords() throws Exception {
        assumeFalse("Can't run in a FIPS JVM", inFipsJvm());
        Path differentPasswordsStore =
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode-different-passwords.jks");
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.keystore.secure_password", "testnode");
        secureSettings.setString("xpack.ssl.keystore.secure_key_password", "testnode1");
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", differentPasswordsStore)
                .setSecureSettings(secureSettings)
                .build();
        final SSLService sslService = new SSLService(settings, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        sslService.createSSLEngine(configuration, null, -1);
    }

    public void testIncorrectKeyPasswordThrowsException() throws Exception {
        assumeFalse("Can't run in a FIPS JVM", inFipsJvm());
        Path differentPasswordsStore =
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode-different-passwords.jks");
        try {
            MockSecureSettings secureSettings = new MockSecureSettings();
            secureSettings.setString("xpack.ssl.keystore.secure_password", "testnode");
            Settings settings = Settings.builder()
                    .put("xpack.ssl.keystore.path", differentPasswordsStore)
                    .setSecureSettings(secureSettings)
                    .build();
            final SSLService sslService = new SSLService(settings, env);
            SSLConfiguration configuration = globalConfiguration(sslService);
            sslService.createSSLEngine(configuration, null, -1);
            fail("expected an exception");
        } catch (ElasticsearchException e) {
            assertThat(e.getMessage(), containsString("failed to initialize a KeyManagerFactory"));
        }
    }

    public void testThatSSLv3IsNotEnabled() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
            .setSecureSettings(secureSettings)
            .build();
        SSLService sslService = new SSLService(settings, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        SSLEngine engine = sslService.createSSLEngine(configuration, null, -1);
        assertThat(Arrays.asList(engine.getEnabledProtocols()), not(hasItem("SSLv3")));
    }

    public void testThatCreateClientSSLEngineWithoutAnySettingsWorks() throws Exception {
        SSLService sslService = new SSLService(Settings.EMPTY, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        SSLEngine sslEngine = sslService.createSSLEngine(configuration, null, -1);
        assertThat(sslEngine, notNullValue());
    }

    public void testThatCreateSSLEngineWithOnlyTruststoreWorks() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.truststore.secure_password", "testclient");
        Settings settings = Settings.builder()
                .put("xpack.ssl.truststore.path", testclientStore)
                .setSecureSettings(secureSettings)
                .build();
        SSLService sslService = new SSLService(settings, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        SSLEngine sslEngine = sslService.createSSLEngine(configuration, null, -1);
        assertThat(sslEngine, notNullValue());
    }


    public void testCreateWithKeystoreIsValidForServer() throws Exception {
        assumeFalse("Can't run in a FIPS JVM, JKS keystores can't be used", inFipsJvm());
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.keystore.secure_password", "testnode");
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", testnodeStore)
                .put("xpack.ssl.keystore.type", testnodeStoreType)
                .setSecureSettings(secureSettings)
                .build();
        SSLService sslService = new SSLService(settings, env);

        assertTrue(sslService.isConfigurationValidForServerUsage(globalConfiguration(sslService)));
    }

    public void testValidForServerWithFallback() throws Exception {
        assumeFalse("Can't run in a FIPS JVM, JKS keystores can't be used", inFipsJvm());
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.truststore.secure_password", "testnode");
        Settings settings = Settings.builder()
                .put("xpack.ssl.truststore.path", testnodeStore)
                .put("xpack.ssl.truststore.type", testnodeStoreType)
                .setSecureSettings(secureSettings)
                .build();
        SSLService sslService = new SSLService(settings, env);
        assertFalse(sslService.isConfigurationValidForServerUsage(globalConfiguration(sslService)));

        secureSettings.setString("xpack.security.transport.ssl.keystore.secure_password", "testnode");
        settings = Settings.builder()
                .put("xpack.ssl.truststore.path", testnodeStore)
                .put("xpack.ssl.truststore.type", testnodeStoreType)
                .setSecureSettings(secureSettings)
                .put("xpack.security.transport.ssl.keystore.path", testnodeStore)
                .put("xpack.security.transport.ssl.keystore.type", testnodeStoreType)
                .build();
        sslService = new SSLService(settings, env);
        assertFalse(sslService.isConfigurationValidForServerUsage(globalConfiguration(sslService)));
        assertTrue(sslService.isConfigurationValidForServerUsage(sslService.getSSLConfiguration("xpack.security.transport.ssl")));
    }

    public void testGetVerificationMode() throws Exception {
        assumeFalse("Can't run in a FIPS JVM, TrustAllConfig is not a SunJSSE TrustManagers", inFipsJvm());
        SSLService sslService = new SSLService(Settings.EMPTY, env);
        assertThat(globalConfiguration(sslService).verificationMode(), is(XPackSettings.VERIFICATION_MODE_DEFAULT));

        Settings settings = Settings.builder()
                .put("xpack.ssl.verification_mode", "none")
                .put("xpack.security.transport.ssl.verification_mode", "certificate")
                .put("transport.profiles.foo.xpack.security.ssl.verification_mode", "full")
                .build();
        sslService = new SSLService(settings, env);
        assertThat(globalConfiguration(sslService).verificationMode(), is(VerificationMode.NONE));
        assertThat(sslService.getSSLConfiguration("xpack.security.transport.ssl.").verificationMode(), is(VerificationMode.CERTIFICATE));
        assertThat(sslService.getSSLConfiguration("transport.profiles.foo.xpack.security.ssl.").verificationMode(),
            is(VerificationMode.FULL));
    }

    public void testIsSSLClientAuthEnabled() throws Exception {
        SSLService sslService = new SSLService(Settings.EMPTY, env);
        assertTrue(globalConfiguration(sslService).sslClientAuth().enabled());

        Settings settings = Settings.builder()
                .put("xpack.ssl.client_authentication", "none")
                .put("xpack.security.transport.ssl.client_authentication", "optional")
            .put("transport.profiles.foo.port", "9400-9410")
                .build();
        sslService = new SSLService(settings, env);
        assertFalse(sslService.isSSLClientAuthEnabled(globalConfiguration(sslService)));
        assertTrue(sslService.isSSLClientAuthEnabled(sslService.getSSLConfiguration("xpack.security.transport.ssl")));
        assertTrue(sslService.isSSLClientAuthEnabled(sslService.getSSLConfiguration("transport.profiles.foo.xpack.security.ssl")));
    }

    public void testThatHttpClientAuthDefaultsToNone() throws Exception {
        final Settings globalSettings = Settings.builder()
                .put("xpack.security.http.ssl.enabled", true)
                .put("xpack.ssl.client_authentication", SSLClientAuth.OPTIONAL.name())
                .build();
        final SSLService sslService = new SSLService(globalSettings, env);

        final SSLConfiguration globalConfig = globalConfiguration(sslService);
        assertThat(globalConfig.sslClientAuth(), is(SSLClientAuth.OPTIONAL));

        final SSLConfiguration httpConfig = sslService.getHttpTransportSSLConfiguration();
        assertThat(httpConfig.sslClientAuth(), is(SSLClientAuth.NONE));
    }

    public void testThatTruststorePasswordIsRequired() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.keystore.secure_password", "testnode");
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", testnodeStore)
                .put("xpack.ssl.keystore.type", testnodeStoreType)
                .setSecureSettings(secureSettings)
                .put("xpack.ssl.truststore.path", testnodeStore)
                .put("xpack.ssl.truststore.type", testnodeStoreType)
                .build();
        ElasticsearchException e =
                expectThrows(ElasticsearchException.class, () -> new SSLService(settings, env));
        assertThat(e.getMessage(), is("failed to initialize a TrustManagerFactory"));
    }

    public void testThatKeystorePasswordIsRequired() throws Exception {
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", testnodeStore)
                .put("xpack.ssl.keystore.type", testnodeStoreType)
                .build();
        ElasticsearchException e =
                expectThrows(ElasticsearchException.class, () -> new SSLService(settings, env));
        assertThat(e.getMessage(), is("failed to create trust manager"));
    }

    public void testCiphersAndInvalidCiphersWork() throws Exception {
        List<String> ciphers = new ArrayList<>(XPackSettings.DEFAULT_CIPHERS);
        ciphers.add("foo");
        ciphers.add("bar");
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
            .setSecureSettings(secureSettings)
            .build();
        SSLService sslService = new SSLService(settings, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        SSLEngine engine = sslService.createSSLEngine(configuration, null, -1);
        assertThat(engine, is(notNullValue()));
        String[] enabledCiphers = engine.getEnabledCipherSuites();
        assertThat(Arrays.asList(enabledCiphers), not(contains("foo", "bar")));
    }

    public void testInvalidCiphersOnlyThrowsException() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
            .putList("xpack.ssl.cipher_suites", new String[]{"foo", "bar"})
            .setSecureSettings(secureSettings)
            .build();

        IllegalArgumentException e =
                expectThrows(IllegalArgumentException.class, () -> new SSLService(settings, env));
        assertThat(e.getMessage(), is("none of the ciphers [foo, bar] are supported by this JVM"));
    }

    public void testThatSSLEngineHasCipherSuitesOrderSet() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
            .setSecureSettings(secureSettings)
            .build();
        SSLService sslService = new SSLService(settings, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        SSLEngine engine = sslService.createSSLEngine(configuration, null, -1);
        assertThat(engine, is(notNullValue()));
        assertTrue(engine.getSSLParameters().getUseCipherSuitesOrder());
    }

    public void testThatSSLSocketFactoryHasProperCiphersAndProtocols() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
            .setSecureSettings(secureSettings)
            .build();
        SSLService sslService = new SSLService(settings, env);
        SSLConfiguration config = globalConfiguration(sslService);
        final SSLSocketFactory factory = sslService.sslSocketFactory(config);
        final String[] ciphers = sslService.supportedCiphers(factory.getSupportedCipherSuites(), config.cipherSuites(), false);
        assertThat(factory.getDefaultCipherSuites(), is(ciphers));

        final String[] supportedProtocols = config.supportedProtocols().toArray(Strings.EMPTY_ARRAY);
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            assertThat(socket.getEnabledCipherSuites(), is(ciphers));
            // the order we set the protocols in is not going to be what is returned as internally the JDK may sort the versions
            assertThat(socket.getEnabledProtocols(), arrayContainingInAnyOrder(supportedProtocols));
            assertArrayEquals(ciphers, socket.getSSLParameters().getCipherSuites());
            assertThat(socket.getSSLParameters().getProtocols(), arrayContainingInAnyOrder(supportedProtocols));
            assertTrue(socket.getSSLParameters().getUseCipherSuitesOrder());
        }
    }

    public void testThatSSLEngineHasProperCiphersAndProtocols() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.certificate", testnodeCert)
            .put("xpack.ssl.key", testnodeKey)
            .setSecureSettings(secureSettings)
            .build();
        SSLService sslService = new SSLService(settings, env);
        SSLConfiguration configuration = globalConfiguration(sslService);
        SSLEngine engine = sslService.createSSLEngine(configuration, null, -1);
        final String[] ciphers = sslService.supportedCiphers(engine.getSupportedCipherSuites(), configuration.cipherSuites(), false);
        final String[] supportedProtocols = configuration.supportedProtocols().toArray(Strings.EMPTY_ARRAY);
        assertThat(engine.getEnabledCipherSuites(), is(ciphers));
        assertArrayEquals(ciphers, engine.getSSLParameters().getCipherSuites());
        // the order we set the protocols in is not going to be what is returned as internally the JDK may sort the versions
        assertThat(engine.getEnabledProtocols(), arrayContainingInAnyOrder(supportedProtocols));
        assertThat(engine.getSSLParameters().getProtocols(), arrayContainingInAnyOrder(supportedProtocols));
    }

    public void testSSLStrategy() {
        // this just exhaustively verifies that the right things are called and that it uses the right parameters
        VerificationMode mode = randomFrom(VerificationMode.values());
        Settings settings = Settings.builder()
                .put("supported_protocols", "protocols")
                .put("cipher_suites", "")
                .put("verification_mode", mode.name())
                .build();
        SSLService sslService = mock(SSLService.class);
        SSLConfiguration sslConfig = new SSLConfiguration(settings);
        SSLParameters sslParameters = mock(SSLParameters.class);
        SSLContext sslContext = mock(SSLContext.class);
        String[] protocols = new String[] { "protocols" };
        String[] ciphers = new String[] { "ciphers!!!" };
        String[] supportedCiphers = new String[] { "supported ciphers" };
        List<String> requestedCiphers = new ArrayList<>(0);
        ArgumentCaptor<HostnameVerifier> verifier = ArgumentCaptor.forClass(HostnameVerifier.class);
        SSLIOSessionStrategy sslStrategy = mock(SSLIOSessionStrategy.class);

        when(sslService.sslConfiguration(settings)).thenReturn(sslConfig);
        when(sslService.sslContext(sslConfig)).thenReturn(sslContext);
        when(sslService.supportedCiphers(supportedCiphers, requestedCiphers, false)).thenReturn(ciphers);
        when(sslService.sslParameters(sslContext)).thenReturn(sslParameters);
        when(sslParameters.getCipherSuites()).thenReturn(supportedCiphers);
        when(sslService.sslIOSessionStrategy(eq(sslContext), eq(protocols), eq(ciphers), verifier.capture())).thenReturn(sslStrategy);

        // ensure it actually goes through and calls the real method
        when(sslService.sslIOSessionStrategy(settings)).thenCallRealMethod();
        when(sslService.sslIOSessionStrategy(sslConfig)).thenCallRealMethod();

        assertThat(sslService.sslIOSessionStrategy(settings), sameInstance(sslStrategy));

        if (mode.isHostnameVerificationEnabled()) {
            assertThat(verifier.getValue(), instanceOf(DefaultHostnameVerifier.class));
        } else {
            assertThat(verifier.getValue(), sameInstance(NoopHostnameVerifier.INSTANCE));
        }
    }

    public void testEmptyTrustManager() throws Exception {
        Settings settings = Settings.builder().build();
        final SSLService sslService = new SSLService(settings, env);
        SSLConfiguration sslConfig = new SSLConfiguration(settings);
        X509ExtendedTrustManager trustManager = sslService.sslContextHolder(sslConfig).getEmptyTrustManager();
        assertThat(trustManager.getAcceptedIssuers(), emptyArray());
    }

    public void testGetConfigurationByContextName() throws Exception {
        assumeFalse("Can't run in a FIPS JVM, JKS keystores can't be used", inFipsJvm());
        final SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
        sslContext.init(null, null, null);
        final String[] cipherSuites = sslContext.getSupportedSSLParameters().getCipherSuites();

        final String[] contextNames = {
            "xpack.ssl",
            "xpack.http.ssl",
            "xpack.security.http.ssl",
            "xpack.security.transport.ssl",
            "transport.profiles.prof1.xpack.security.ssl",
            "transport.profiles.prof2.xpack.security.ssl",
            "transport.profiles.prof3.xpack.security.ssl",
            "xpack.security.authc.realms.realm1.ssl",
            "xpack.security.authc.realms.realm2.ssl",
            "xpack.monitoring.exporters.mon1.ssl",
            "xpack.monitoring.exporters.mon2.ssl"
        };

        assumeTrue("Not enough cipher suites are available to support this test", cipherSuites.length >= contextNames.length);

        // Here we use a different ciphers for each context, so we can check that the returned SSLConfiguration matches the
        // provided settings
        final Iterator<String> cipher = Arrays.asList(cipherSuites).iterator();

        final MockSecureSettings secureSettings = new MockSecureSettings();
        final Settings.Builder builder = Settings.builder();
        for (String prefix : contextNames) {
            secureSettings.setString(prefix + ".keystore.secure_password", "testnode");
            builder.put(prefix + ".keystore.path", testnodeStore)
                .putList(prefix + ".cipher_suites", cipher.next());
        }

        final Settings settings = builder
            // Add a realm without SSL settings. This context name should be mapped to the global configuration
            .put("xpack.security.authc.realms.realm3.type", "file")
            // Add an exporter without SSL settings. This context name should be mapped to the global configuration
            .put("xpack.monitoring.exporters.mon3.type", "http")
            .setSecureSettings(secureSettings)
            .build();
        SSLService sslService = new SSLService(settings, env);

        for (int i = 0; i < contextNames.length; i++) {
            final String name = contextNames[i];
            final SSLConfiguration configuration = sslService.getSSLConfiguration(name);
            assertThat("Configuration for " + name, configuration, notNullValue());
            assertThat("KeyStore for " + name, configuration.keyConfig(), instanceOf(StoreKeyConfig.class));
            final StoreKeyConfig keyConfig = (StoreKeyConfig) configuration.keyConfig();
            assertThat("KeyStore Path for " + name, keyConfig.keyStorePath, equalTo(testnodeStore.toString()));
            assertThat("Cipher for " + name, configuration.cipherSuites(), contains(cipherSuites[i]));
            assertThat("Configuration for " + name + ".", sslService.getSSLConfiguration(name + "."), sameInstance(configuration));
        }

        // These contexts have no SSL settings, but for convenience we want those components to be able to access their context
        // by name, and get back the global configuration
        final SSLConfiguration realm3Config = sslService.getSSLConfiguration("xpack.security.authc.realms.realm3.ssl");
        final SSLConfiguration mon3Config = sslService.getSSLConfiguration("xpack.monitoring.exporters.mon3.ssl.");
        final SSLConfiguration global = globalConfiguration(sslService);
        assertThat(realm3Config, sameInstance(global));
        assertThat(mon3Config, sameInstance(global));
    }

    public void testReadCertificateInformation() throws Exception {
        assumeFalse("Can't run in a FIPS JVM, JKS keystores can't be used", inFipsJvm());
        final Path jksPath = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks");
        final Path p12Path = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.p12");
        final Path pemPath = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/active-directory-ca.crt");

        final MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.keystore.secure_password", "testnode");
        secureSettings.setString("xpack.ssl.truststore.secure_password", "testnode");
        secureSettings.setString("xpack.http.ssl.keystore.secure_password", "testnode");

        final Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", jksPath)
                .put("xpack.ssl.truststore.path", jksPath)
                .put("xpack.http.ssl.keystore.path", p12Path)
                .put("xpack.security.authc.realms.ad.type", "ad")
                .put("xpack.security.authc.realms.ad.ssl.certificate_authorities", pemPath)
                .setSecureSettings(secureSettings)
                .build();

        final SSLService sslService = new SSLService(settings, env);
        final List<CertificateInfo> certificates = new ArrayList<>(sslService.getLoadedCertificates());
        assertThat(certificates, iterableWithSize(10));
        Collections.sort(certificates,
                Comparator.comparing((CertificateInfo c) -> c.alias() == null ? "" : c.alias()).thenComparing(CertificateInfo::path));

        final Iterator<CertificateInfo> iterator = certificates.iterator();
        CertificateInfo cert = iterator.next();
        assertThat(cert.alias(), nullValue());
        assertThat(cert.path(), equalTo(pemPath.toString()));
        assertThat(cert.format(), equalTo("PEM"));
        assertThat(cert.serialNumber(), equalTo("580db8ad52bb168a4080e1df122a3f56"));
        assertThat(cert.subjectDn(), equalTo("CN=ad-ELASTICSEARCHAD-CA, DC=ad, DC=test, DC=elasticsearch, DC=com"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2029-08-27T16:32:42Z")));
        assertThat(cert.hasPrivateKey(), equalTo(false));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("activedir"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("580db8ad52bb168a4080e1df122a3f56"));
        assertThat(cert.subjectDn(), equalTo("CN=ad-ELASTICSEARCHAD-CA, DC=ad, DC=test, DC=elasticsearch, DC=com"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2029-08-27T16:32:42Z")));
        assertThat(cert.hasPrivateKey(), equalTo(false));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("mykey"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("3151a81eec8d4e34c56a8466a8510bcfbe63cc31"));
        assertThat(cert.subjectDn(), equalTo("CN=samba4"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2021-02-14T17:49:11.000Z")));
        assertThat(cert.hasPrivateKey(), equalTo(false));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("openldap"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("d3850b2b1995ad5f"));
        assertThat(cert.subjectDn(), equalTo("CN=OpenLDAP, OU=Elasticsearch, O=Elastic, L=Mountain View, ST=CA, C=US"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2027-07-23T16:41:14Z")));
        assertThat(cert.hasPrivateKey(), equalTo(false));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("testclient"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("b9d497f2924bbe29"));
        assertThat(cert.subjectDn(), equalTo("CN=Elasticsearch Test Client, OU=elasticsearch, O=org"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2019-09-22T18:52:55Z")));
        assertThat(cert.hasPrivateKey(), equalTo(false));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("testnode-client-profile"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("c0ea4216e8ff0fd8"));
        assertThat(cert.subjectDn(), equalTo("CN=testnode-client-profile"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2019-09-22T18:52:56Z")));
        assertThat(cert.hasPrivateKey(), equalTo(false));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("testnode_dsa"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("223c736a"));
        assertThat(cert.subjectDn(), equalTo("CN=Elasticsearch Test Node"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2045-10-02T09:43:18.000Z")));
        assertThat(cert.hasPrivateKey(), equalTo(true));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("testnode_ec"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("7268203b"));
        assertThat(cert.subjectDn(), equalTo("CN=Elasticsearch Test Node"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2045-10-02T09:36:10.000Z")));
        assertThat(cert.hasPrivateKey(), equalTo(true));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("testnode_rsa"));
        assertThat(cert.path(), equalTo(jksPath.toString()));
        assertThat(cert.format(), equalTo("jks"));
        assertThat(cert.serialNumber(), equalTo("b8b96c37e332cccb"));
        assertThat(cert.subjectDn(), equalTo("CN=Elasticsearch Test Node, OU=elasticsearch, O=org"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2019-09-22T18:52:57.000Z")));
        assertThat(cert.hasPrivateKey(), equalTo(true));

        cert = iterator.next();
        assertThat(cert.alias(), equalTo("testnode_rsa"));
        assertThat(cert.path(), equalTo(p12Path.toString()));
        assertThat(cert.format(), equalTo("PKCS12"));
        assertThat(cert.serialNumber(), equalTo("b8b96c37e332cccb"));
        assertThat(cert.subjectDn(), equalTo("CN=Elasticsearch Test Node, OU=elasticsearch, O=org"));
        assertThat(cert.expiry(), equalTo(DateTime.parse("2019-09-22T18:52:57Z")));
        assertThat(cert.hasPrivateKey(), equalTo(true));

        assertFalse(iterator.hasNext());
    }

    public void testSSLSessionInvalidationHandlesNullSessions() {
        final int numEntries = randomIntBetween(1, 32);
        final AtomicInteger invalidationCounter = new AtomicInteger();
        int numNull = 0;
        final Map<byte[], SSLSession> sessionMap = new HashMap<>();
        for (int i = 0; i < numEntries; i++) {
            final byte[] id = randomByteArrayOfLength(2);
            final SSLSession sslSession;
            if (rarely()) {
                sslSession = null;
                numNull++;
            } else {
                sslSession = new MockSSLSession(id, invalidationCounter::incrementAndGet);
            }
            sessionMap.put(id, sslSession);
        }

        SSLSessionContext sslSessionContext = new SSLSessionContext() {
            @Override
            public SSLSession getSession(byte[] sessionId) {
                return sessionMap.get(sessionId);
            }

            @Override
            public Enumeration<byte[]> getIds() {
                return Collections.enumeration(sessionMap.keySet());
            }

            @Override
            public void setSessionTimeout(int seconds) throws IllegalArgumentException {
            }

            @Override
            public int getSessionTimeout() {
                return 0;
            }

            @Override
            public void setSessionCacheSize(int size) throws IllegalArgumentException {
            }

            @Override
            public int getSessionCacheSize() {
                return 0;
            }
        };

        SSLService.invalidateSessions(sslSessionContext);
        assertEquals(numEntries - numNull, invalidationCounter.get());
    }

    @Network
    public void testThatSSLContextWithoutSettingsWorks() throws Exception {
        SSLService sslService = new SSLService(Settings.EMPTY, env);
        SSLContext sslContext = sslService.sslContext();
        try (CloseableHttpClient client = HttpClients.custom().setSSLContext(sslContext).build()) {
            // Execute a GET on a site known to have a valid certificate signed by a trusted public CA
            // This will result in a SSLHandshakeException if the SSLContext does not trust the CA, but the default
            // truststore trusts all common public CAs so the handshake will succeed
            privilegedConnect(() -> client.execute(new HttpGet("https://www.elastic.co/")).close());
        }
    }

    @Network
    public void testThatSSLContextTrustsJDKTrustedCAs() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.keystore.secure_password", "testclient");
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", testclientStore)
                .setSecureSettings(secureSettings)
                .build();
        SSLContext sslContext = new SSLService(settings, env).sslContext();
        try (CloseableHttpClient client = HttpClients.custom().setSSLContext(sslContext).build()) {
            // Execute a GET on a site known to have a valid certificate signed by a trusted public CA which will succeed because the JDK
            // certs are trusted by default
            privilegedConnect(() -> client.execute(new HttpGet("https://www.elastic.co/")).close());
        }
    }

    @Network
    public void testThatSSLIOSessionStrategyWithoutSettingsWorks() throws Exception {
        SSLService sslService = new SSLService(Settings.EMPTY, env);
        SSLConfiguration sslConfiguration = globalConfiguration(sslService);
        logger.info("SSL Configuration: {}", sslConfiguration);
        SSLIOSessionStrategy sslStrategy = sslService.sslIOSessionStrategy(sslConfiguration);
        try (CloseableHttpAsyncClient client = getAsyncHttpClient(sslStrategy)) {
            client.start();

            // Execute a GET on a site known to have a valid certificate signed by a trusted public CA
            // This will result in a SSLHandshakeException if the SSLContext does not trust the CA, but the default
            // truststore trusts all common public CAs so the handshake will succeed
            client.execute(new HttpHost("elastic.co", 443, "https"), new HttpGet("/"), new AssertionCallback()).get();
        }
    }

    @Network
    public void testThatSSLIOSessionStrategyTrustsJDKTrustedCAs() throws Exception {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.keystore.secure_password", "testclient");
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", testclientStore)
                .setSecureSettings(secureSettings)
                .build();
        final SSLService sslService = new SSLService(settings, env);
        SSLIOSessionStrategy sslStrategy = sslService.sslIOSessionStrategy(globalConfiguration(sslService));
        try (CloseableHttpAsyncClient client = getAsyncHttpClient(sslStrategy)) {
            client.start();

            // Execute a GET on a site known to have a valid certificate signed by a trusted public CA which will succeed because the JDK
            // certs are trusted by default
            client.execute(new HttpHost("elastic.co", 443, "https"), new HttpGet("/"), new AssertionCallback()).get();
        }
    }

    private static SSLConfiguration globalConfiguration(SSLService sslService) {
        return sslService.getSSLConfiguration("xpack.ssl");
    }

    class AssertionCallback implements FutureCallback<HttpResponse> {

        @Override
        public void completed(HttpResponse result) {
            assertThat(result.getStatusLine().getStatusCode(), lessThan(300));
        }

        @Override
        public void failed(Exception ex) {
            logger.error(ex);

            fail(ex.toString());
        }

        @Override
        public void cancelled() {
            fail("The request was cancelled for some reason");
        }
    }

    private CloseableHttpAsyncClient getAsyncHttpClient(SSLIOSessionStrategy sslStrategy) throws Exception {
        try {
            return AccessController.doPrivileged((PrivilegedExceptionAction<CloseableHttpAsyncClient>)
                    () -> HttpAsyncClientBuilder.create().setSSLStrategy(sslStrategy).build());
        } catch (PrivilegedActionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void privilegedConnect(CheckedRunnable<Exception> runnable) throws Exception {
        try {
            AccessController.doPrivileged((PrivilegedExceptionAction<Void>) () -> {
                runnable.run();
                return null;
            });
        } catch (PrivilegedActionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static final class MockSSLSession implements SSLSession {

        private final byte[] id;
        private final Runnable invalidation;

        private MockSSLSession(byte[] id, Runnable invalidation) {
            this.id = id;
            this.invalidation = invalidation;
        }

        @Override
        public byte[] getId() {
            return id;
        }

        @Override
        public SSLSessionContext getSessionContext() {
            return null;
        }

        @Override
        public long getCreationTime() {
            return 0;
        }

        @Override
        public long getLastAccessedTime() {
            return 0;
        }

        @Override
        public void invalidate() {
            invalidation.run();
        }

        @Override
        public boolean isValid() {
            return false;
        }

        @Override
        public void putValue(String name, Object value) {

        }

        @Override
        public Object getValue(String name) {
            return null;
        }

        @Override
        public void removeValue(String name) {

        }

        @Override
        public String[] getValueNames() {
            return new String[0];
        }

        @Override
        public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
            return new Certificate[0];
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return new Certificate[0];
        }

        @SuppressForbidden(reason = "need to reference deprecated class to implement JDK interface")
        @Override
        public X509Certificate[] getPeerCertificateChain() throws SSLPeerUnverifiedException {
            return new X509Certificate[0];
        }

        @Override
        public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
            return null;
        }

        @Override
        public Principal getLocalPrincipal() {
            return null;
        }

        @Override
        public String getCipherSuite() {
            return null;
        }

        @Override
        public String getProtocol() {
            return null;
        }

        @Override
        public String getPeerHost() {
            return null;
        }

        @Override
        public int getPeerPort() {
            return 0;
        }

        @Override
        public int getPacketBufferSize() {
            return 0;
        }

        @Override
        public int getApplicationBufferSize() {
            return 0;
        }
    }
}
