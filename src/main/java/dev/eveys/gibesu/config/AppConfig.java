package dev.eveys.gibesu.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AppConfig {
    public Company company = new Company();
    public Report report = new Report();
    public Excel excel = new Excel();
    public Signing signing = new Signing();
    public Client client = new Client();

    public static AppConfig load(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (var in = Files.newInputStream(path)) {
            return mapper.readValue(in, AppConfig.class);
        }
    }

    public void save(Path path) throws IOException {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        try (var out = Files.newOutputStream(path)) {
            mapper.writerWithDefaultPrettyPrinter().writeValue(out, this);
        }
    }

    public static class Company {
        public String vkn;
        public String unvan;
        public String epdkLisansNo;
    }

    public static class Report {
        public String xsdPath;
        public String namespaceUri = "http://earsiv.efatura.gov.tr";
        public int decimalScale = 2;
    }

    public static class Excel {
        public String sheetName;
        public int headerRow = 1;
        public String plateColumn;
        public String kwhColumn;
        public String amountColumn;
    }

    public static class Signing {
        public String mode = "none";
        public String pkcs11Library;
        public Integer slotListIndex = 0;
        public String keyAlias;
        public String pinEnv = "MALI_MUHUR_PIN";
    }

    public static class Client {
        public String environment = "test";
        public String testEndpoint = "https://okctest.gib.gov.tr/okcesu/services/EArsivWsPort/earsiv";
        public String prodEndpoint = "https://okc.gib.gov.tr/okcesu/services/EArsivWsPort/earsiv";
        public int connectTimeoutSeconds = 30;
        public int readTimeoutSeconds = 120;
    }
}
