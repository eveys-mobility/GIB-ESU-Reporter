package dev.eveys.gibesu.gib;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

public final class GibSslTrustStore {
    private static final char[] TRUSTSTORE_PASSWORD = "changeit".toCharArray();
    private static final String TEST_HOST = "okctest.gib.gov.tr";
    private static final String PROD_HOST = "okc.gib.gov.tr";

    private GibSslTrustStore() {
    }

    public static SSLContext sslContextForEndpoint(URI endpoint) throws Exception {
        String host = endpoint.getHost();
        if (host == null || (!TEST_HOST.equalsIgnoreCase(host) && !PROD_HOST.equalsIgnoreCase(host))) {
            return null;
        }

        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 443;
        String fileName = PROD_HOST.equalsIgnoreCase(host) ? "gib-prod-truststore.jks" : "gib-test-truststore.jks";
        Path trustStore = ensureTrustStore(fileName, host, port);
        return loadSslContext(trustStore);
    }

    private static Path ensureTrustStore(String fileName, String host, int port) throws Exception {
        Path certDir = Path.of(System.getProperty("user.home"), ".eveys-gib-esu", "certs");
        Files.createDirectories(certDir);
        Path trustStorePath = certDir.resolve(fileName);

        if (Files.exists(trustStorePath) && Files.size(trustStorePath) > 0) {
            return trustStorePath;
        }

        Certificate[] chain = fetchServerCertificateChain(host, port);
        if (chain == null || chain.length == 0) {
            throw new IllegalStateException("GIB SSL sertifika zinciri alinamadi: " + host);
        }

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, TRUSTSTORE_PASSWORD);
        for (int i = 0; i < chain.length; i++) {
            keyStore.setCertificateEntry(host + "-" + (i + 1), chain[i]);
        }

        try (OutputStream out = Files.newOutputStream(trustStorePath)) {
            keyStore.store(out, TRUSTSTORE_PASSWORD);
        }
        System.out.println("GIB SSL truststore hazir: " + trustStorePath.toAbsolutePath());
        return trustStorePath;
    }

    private static SSLContext loadSslContext(Path trustStorePath) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(trustStorePath)) {
            trustStore.load(in, TRUSTSTORE_PASSWORD);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    private static Certificate[] fetchServerCertificateChain(String host, int port) throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustAll, new SecureRandom());

        try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
            SSLParameters params = socket.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.connect(new InetSocketAddress(host, port), 15000);
            socket.setSoTimeout(15000);
            socket.startHandshake();
            return socket.getSession().getPeerCertificates();
        }
    }
}
