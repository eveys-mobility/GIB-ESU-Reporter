package dev.eveys.gibesu.desktop;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * GIB test/prod SSL zincirini otomatik alıp kullanıcı klasöründe JKS truststore üretir.
 *
 * Not: İlk bağlantıda sertifika zinciri sadece resmi GIB hostlarından okunur
 * (okctest.gib.gov.tr / okc.gib.gov.tr). Sonraki SOAP çağrıları normal Java
 * PKIX doğrulamasıyla bu lokal truststore üzerinden yapılır.
 */
public final class SslTrustStoreManager {
    private SslTrustStoreManager() {
    }

    public static Path ensureTrustStore(String fileName, String host, int port, char[] password) throws Exception {
        if (!"okctest.gib.gov.tr".equalsIgnoreCase(host) && !"okc.gib.gov.tr".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("Sadece resmi GIB hostları için otomatik truststore üretilebilir: " + host);
        }

        Path certDir = Path.of(System.getProperty("user.home"), ".eveys-gib-esu", "certs");
        Files.createDirectories(certDir);
        Path trustStorePath = certDir.resolve(fileName);

        if (Files.exists(trustStorePath) && Files.size(trustStorePath) > 0) {
            return trustStorePath;
        }

        Certificate[] chain = fetchServerCertificateChain(host, port);
        if (chain == null || chain.length == 0) {
            throw new IllegalStateException("GIB SSL sertifika zinciri alınamadı: " + host);
        }

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, password);
        for (int i = 0; i < chain.length; i++) {
            keyStore.setCertificateEntry(host + "-" + (i + 1), chain[i]);
        }

        try (OutputStream out = Files.newOutputStream(trustStorePath)) {
            keyStore.store(out, password);
        }

        return trustStorePath;
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
            // Hostname kontrolü açık kalır; sadece CA zinciri ilk indirme anında bypass edilir.
            params.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(params);
            socket.connect(new InetSocketAddress(host, port), 15000);
            socket.setSoTimeout(15000);
            socket.startHandshake();
            return socket.getSession().getPeerCertificates();
        }
    }
}
