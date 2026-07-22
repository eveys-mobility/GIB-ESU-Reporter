package dev.eveys.gibesu.desktop;

import dev.eveys.gibesu.config.AppConfig;
import dev.eveys.gibesu.gib.GibClient;
import dev.eveys.gibesu.verify.XmlSignatureVerifier;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Muhasebe kullanımı için sade tek ekran JavaFX arayüzü.
 *
 * Tasarım hedefi:
 * - Endpoint alanları kullanıcıya gösterilmez; sadece TEST / CANLI geçişi vardır.
 * - GİB EŞÜ XSD proje içine gömülüdür; harici dosya seçimi gerektirmez.
 * - AKİS PKCS#11 kütüphanesi bilinen yollardan otomatik bulunmaya çalışılır.
 * - Önce test success30 alınır, sonra canlı gönderim açılır.
 * - PIN kaydedilmez ve loglanmaz.
 */
public class DesktopApp extends Application {
    private static final String TEST_ENDPOINT = "https://okctest.gib.gov.tr/okcesu/services/EArsivWsPort/earsiv";
    private static final String PROD_ENDPOINT = "https://okc.gib.gov.tr/okcesu/services/EArsivWsPort/earsiv";
    private static final String TEST_TRUSTSTORE = "certs/gib-test-truststore.jks";
    private static final String PROD_TRUSTSTORE = "certs/gib-prod-truststore.jks";
    private static final String TRUSTSTORE_PASSWORD = "changeit";
    private static final int STATUS_RETRIES = 5;
    private static final int STATUS_WAIT_SECONDS = 10;
    private static final Path INTERNAL_CONFIG = Path.of(System.getProperty("user.home"), ".eveys-gib-esu", "application.yml");

    private final DesktopWorkflow workflow = new DesktopWorkflow();
    private final DecimalFormat numberFormat = new DecimalFormat("#,##0.00");

    private Stage stage;
    private TextArea logArea;

    // Config dosyası kullanıcıya seçtirilmez; arka planda ~/.eveys-gib-esu/application.yml kullanılır.
    private TextField vknField;
    private TextField unvanField;
    private TextField epdkField;
    private TextField pkcs11LibraryField;
    private TextField aliasField;
    private TextField slotField;
    private PasswordField pinField;
    private TextField excelPathField;
    private TextField periodField;
    private TextField outDirField;

    private Label environmentLabel;
    private Label summaryLabel;
    private Label packageLabel;
    private Label testStatusLabel;
    private Label prodStatusLabel;

    private Path unsignedXml;
    private Path signedXml;
    private Path zipFile;
    private String paketId;
    private boolean lastTestSuccess = false;
    private boolean lastProdSuccess = false;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        primaryStage.setTitle("Eveys GİB EŞÜ Raporlama Uygulaması");

        VBox left = new VBox(12,
                section("Şirket ve Mali Mühür", companyAndSealPane()),
                section("Rapor", reportPane()),
                actionPane()
        );
        left.setPrefWidth(620);

        VBox right = new VBox(12,
                section("Durum", statusPane()),
                section("İşlem Günlüğü", logPane())
        );
        right.setPrefWidth(520);

        HBox content = new HBox(12, left, right);
        content.setPadding(new Insets(14));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);

        Scene scene = new Scene(scroll, 1180, 760);
        primaryStage.setScene(scene);
        primaryStage.show();

        loadDefaultValues();
        log("Uygulama hazır. Config dosyası seçmeniz gerekmez; ayarlar arka planda kullanıcı klasöründe tutulur.");
        log("Endpoint alanları gizlidir; TEST ve CANLI ortamlar sabit GİB adresleriyle çalışır.");
        log("GİB EŞÜ XSD ve gerekli import şemaları proje içine eklidir; harici gib-esu-paket seçmenize gerek yoktur.");
    }

    private Node companyAndSealPane() {
        vknField = new TextField();
        unvanField = new TextField();
        epdkField = new TextField();
        pkcs11LibraryField = new TextField(defaultPkcs11Path());
        slotField = new TextField("0");
        aliasField = new TextField();
        pinField = new PasswordField();

        GridPane grid = baseGrid();
        int r = 0;
        addRow(grid, r++, "VKN", vknField, null, null);
        addRow(grid, r++, "Unvan", unvanField, null, null);
        addRow(grid, r++, "EPDK Lisans No", epdkField, null, null);
        addRow(grid, r++, "AKİS PKCS#11", pkcs11LibraryField, button("Otomatik Bul", this::autoFillPkcs11), button("Seç", () -> chooseFile(pkcs11LibraryField, "PKCS#11 library", "*.dylib", "*.dll", "*.so")));
        addRow(grid, r++, "Slot", slotField, null, null);
        addRow(grid, r++, "Sertifika Alias", aliasField, button("Alias Tara", this::scanAliases), button("SIGN0 Öner", this::suggestSign0));
        addRow(grid, r++, "Mali Mühür PIN", pinField, null, null);

        Label help = infoBox("AKİS PKCS#11, mali mühür sürücüsünün bilgisayardaki kütüphane dosyasıdır. Uygulama macOS/Windows/Linux bilinen yolları otomatik dener. Bulamazsa AKİS kurulumu sonrası libakisp11.dylib / akisp11.dll / libakisp11.so dosyasını bir kez seçmeniz yeterlidir. Bu yol arka planda kaydedilir; PIN kaydedilmez.");
        return new VBox(6, grid, help);
    }

    private GridPane reportPane() {
        excelPathField = new TextField();
        periodField = new TextField("2026-05");
        outDirField = new TextField("out-desktop");

        GridPane grid = baseGrid();
        int r = 0;
        addRow(grid, r++, "Excel", excelPathField, button("Seç", () -> chooseFile(excelPathField, "Excel", "*.xlsx", "*.xls")), null);
        addRow(grid, r++, "Dönem", periodField, null, null);
        addRow(grid, r++, "Çıktı Klasörü", outDirField, button("Seç", () -> chooseDirectory(outDirField)), null);
        return grid;
    }

    private VBox actionPane() {
        Button prepareTest = new Button("1) Hazırla ve Test Et");
        prepareTest.setMaxWidth(Double.MAX_VALUE);
        prepareTest.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        prepareTest.setOnAction(e -> prepareAndTest());

        Button sendProd = new Button("2) Canlıya Gönder");
        sendProd.setMaxWidth(Double.MAX_VALUE);
        sendProd.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        sendProd.setOnAction(e -> sendProdAndStatus());

        Button archive = new Button("Başarılı Paketi Arşivle");
        archive.setMaxWidth(Double.MAX_VALUE);
        archive.setOnAction(e -> archiveCurrentPackage());

        Label note = infoBox("Kullanım: Excel seç → PIN gir → Hazırla ve Test Et. Test success30 olmadan canlı gönderim engellenir. Canlı gönderimde ayrıca CANLI GÖNDER yazılı onayı istenir.");
        return section("İşlem", new VBox(10, note, prepareTest, sendProd, archive));
    }

    private VBox statusPane() {
        environmentLabel = valueLabel("Beklemede");
        summaryLabel = valueLabel("Henüz rapor hazırlanmadı.");
        packageLabel = valueLabel("Paket yok.");
        testStatusLabel = valueLabel("Test yapılmadı.");
        prodStatusLabel = valueLabel("Canlı gönderilmedi.");

        GridPane grid = baseGrid();
        int r = 0;
        addStatusRow(grid, r++, "Ortam", environmentLabel);
        addStatusRow(grid, r++, "Rapor Özeti", summaryLabel);
        addStatusRow(grid, r++, "Paket", packageLabel);
        addStatusRow(grid, r++, "Test Durumu", testStatusLabel);
        addStatusRow(grid, r++, "Canlı Durumu", prodStatusLabel);
        return new VBox(8, grid);
    }

    private TextArea logPane() {
        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(24);
        return logArea;
    }

    private void loadDefaultValues() {
        String detectedPkcs11 = defaultPkcs11Path();
        if (pkcs11LibraryField.getText() == null || pkcs11LibraryField.getText().isBlank()) {
            pkcs11LibraryField.setText(detectedPkcs11);
        }
        if (Files.exists(INTERNAL_CONFIG)) {
            loadConfigFromDisk();
        } else {
            suggestSign0();
            log("AKİS PKCS#11 yolu otomatik önerildi: " + detectedPkcs11);
            log("Arka plan config henüz yok. İlk başarılı işlemden sonra kaydedilecek: " + INTERNAL_CONFIG);
        }
    }

    private AppConfig buildConfigFromFields(String environment) {
        AppConfig config = new AppConfig();
        config.company.vkn = vknField.getText().trim();
        config.company.unvan = unvanField.getText().trim();
        config.company.epdkLisansNo = epdkField.getText().trim();
        config.report.xsdPath = "gib-esu-paket/Esu_GIB_Paket_V3/esuRapor.xsd";
        config.report.namespaceUri = "http://earsiv.efatura.gov.tr";
        config.report.decimalScale = 2;
        config.excel.sheetName = "";
        config.excel.headerRow = 1;
        config.excel.plateColumn = "";
        config.excel.kwhColumn = "";
        config.excel.amountColumn = "";
        config.signing.mode = "pkcs11-xades-bes";
        config.signing.pkcs11Library = pkcs11LibraryField.getText().trim();
        config.signing.slotListIndex = parseInt(slotField.getText(), 0);
        config.signing.keyAlias = aliasField.getText().trim();
        config.signing.pinEnv = "MALI_MUHUR_PIN";
        config.client.environment = environment;
        config.client.testEndpoint = TEST_ENDPOINT;
        config.client.prodEndpoint = PROD_ENDPOINT;
        config.client.connectTimeoutSeconds = 30;
        config.client.readTimeoutSeconds = 120;
        return config;
    }

    private void loadConfigFromDisk() {
        runTask("Arka plan ayarları yükleniyor", () -> {
            AppConfig config = AppConfig.load(INTERNAL_CONFIG);
            Platform.runLater(() -> applyConfigToFields(config));
            return "Ayarlar yüklendi: " + INTERNAL_CONFIG.toAbsolutePath();
        });
    }

    private void saveConfigToDisk() {
        try {
            AppConfig config = buildConfigFromFields("test");
            config.save(INTERNAL_CONFIG);
            log("Ayarlar arka planda kaydedildi: " + INTERNAL_CONFIG.toAbsolutePath());
        } catch (Exception e) {
            log("Ayarlar kaydedilemedi: " + e.getMessage());
        }
    }

    private void applyConfigToFields(AppConfig c) {
        if (c.company != null) {
            vknField.setText(nullToBlank(c.company.vkn));
            unvanField.setText(nullToBlank(c.company.unvan));
            epdkField.setText(nullToBlank(c.company.epdkLisansNo));
        }
        if (c.signing != null) {
            pkcs11LibraryField.setText(nullToBlank(c.signing.pkcs11Library).isBlank() ? defaultPkcs11Path() : nullToBlank(c.signing.pkcs11Library));
            slotField.setText(String.valueOf(c.signing.slotListIndex == null ? 0 : c.signing.slotListIndex));
            aliasField.setText(nullToBlank(c.signing.keyAlias));
        }
        log("Ayarlar uygulandı. Endpoint ve config seçimi arayüzde gösterilmez; test/prod geçişi butonlarla yapılır.");
    }

    private void suggestSign0() {
        String vkn = vknField.getText() == null ? "" : vknField.getText().trim();
        if (!vkn.isBlank()) {
            aliasField.setText(vkn + "SIGN0");
            log("Alias önerildi: " + vkn + "SIGN0");
        } else {
            log("SIGN0 önerisi için önce VKN girin.");
        }
    }

    private void scanAliases() {
        char[] pin = pinField.getText().toCharArray();
        runTask("Mali mühür alias taranıyor", () -> {
            try {
                List<Pkcs11AliasScanner.TokenAlias> aliases = Pkcs11AliasScanner.scan(
                        pkcs11LibraryField.getText().trim(),
                        parseInt(slotField.getText(), 0),
                        pin
                );
                Platform.runLater(() -> showAliasDialog(aliases));
                return "Alias tarama tamamlandı. Bulunan alias sayısı: " + aliases.size();
            } finally {
                Arrays.fill(pin, '\0');
            }
        });
    }

    private void showAliasDialog(List<Pkcs11AliasScanner.TokenAlias> aliases) {
        if (aliases.isEmpty()) {
            alert(Alert.AlertType.WARNING, "Alias bulunamadı", "Mali mühürde private key alias bulunamadı.");
            return;
        }
        Pkcs11AliasScanner.TokenAlias defaultAlias = aliases.stream()
                .filter(a -> a.alias().endsWith("SIGN0"))
                .findFirst()
                .orElse(aliases.get(0));
        ChoiceDialog<Pkcs11AliasScanner.TokenAlias> dialog = new ChoiceDialog<>(defaultAlias, aliases);
        dialog.setTitle("Sertifika Alias Seçimi");
        dialog.setHeaderText("Mali mühür sertifika alias seçimi");
        dialog.setContentText("GİB kabul testinde SIGN0 ile başarı alınmıştır:");
        Optional<Pkcs11AliasScanner.TokenAlias> selected = dialog.showAndWait();
        selected.ifPresent(a -> {
            aliasField.setText(a.alias());
            if (a.slotListIndex() >= 0) {
                slotField.setText(String.valueOf(a.slotListIndex()));
                saveConfigToDisk();
            }
        });
    }

    private void prepareAndTest() {
        runTask("Rapor hazırlanıyor ve test ortamına gönderiliyor", () -> {
            validateRequiredInputs();
            applyPinForCurrentOperation();
            resetRunState();
            updateEnvironment("TEST");

            AppConfig config = buildConfigFromFields("test");
            saveConfigToDisk();
            Path excel = Path.of(excelPathField.getText().trim());
            Path out = Path.of(outDirField.getText().trim());
            String period = periodField.getText().trim();

            // GİB EŞÜ XSD, <baslik> içinde ds:Signature bekler. Bu nedenle unsigned XML burada XSD ile doğrulanmaz; XSD doğrulama imzadan sonra yapılır.
            var generated = workflow.generate(config, excel, period, out, false);
            unsignedXml = generated.unsignedXml();
            paketId = generated.raporNo();
            updateSummary(generated);

            signedXml = workflow.sign(config, unsignedXml, out);
            XmlSignatureVerifier.VerificationResult verify = workflow.verify(signedXml);
            if (!verify.coreValid()) {
                throw new IllegalStateException("Yerel XML imza doğrulama başarısız: " + verify.message());
            }
            workflow.validateXml(config, signedXml);

            var packaged = workflow.packageSignedXml(signedXml, out);
            zipFile = packaged.zipPath();
            paketId = packaged.raporNo();
            updatePackage();

            String trustStore = existingTrustStore(TEST_TRUSTSTORE);
            var send = workflow.send(config, zipFile, out, false, trustStore, TRUSTSTORE_PASSWORD);
            var status = statusWithRetry(config, paketId, out, trustStore, TRUSTSTORE_PASSWORD, "TEST");
            lastTestSuccess = isSuccess(status.returnText());
            Platform.runLater(() -> testStatusLabel.setText(lastTestSuccess ? "Başarılı: " + status.returnText() : "Kontrol gerekli: " + status.returnText()));

            saveConfigToDisk();
            return "TEST send: " + send.returnText()
                    + "\nTEST status: " + status.returnText()
                    + "\nYerel imza: BASARILI"
                    + "\nXSD: İmzalı XML BASARILI"
                    + "\nPaket ID: " + paketId;
        });
    }

    private void sendProdAndStatus() {
        if (!lastTestSuccess) {
            alert(Alert.AlertType.WARNING, "Test başarısı yok", "Canlı gönderimden önce aynı oturumda test ortamında success30 alınmalıdır.");
            return;
        }
        if (zipFile == null || paketId == null || paketId.isBlank()) {
            alert(Alert.AlertType.WARNING, "Paket yok", "Önce Hazırla ve Test Et adımını tamamlayın.");
            return;
        }

        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Canlı Gönderim Onayı");
        dialog.setHeaderText("Bu işlem GİB canlı ortamına resmi bildirim gönderir.");
        dialog.setContentText("Devam etmek için CANLI GÖNDER yazın:");
        Optional<String> confirmation = dialog.showAndWait();
        if (confirmation.isEmpty() || !"CANLI GÖNDER".equals(confirmation.get().trim())) {
            log("Canlı gönderim iptal edildi.");
            return;
        }

        runTask("Canlı ortama gönderiliyor", () -> {
            applyPinForCurrentOperation();
            updateEnvironment("CANLI");
            AppConfig config = buildConfigFromFields("prod");
            Path out = Path.of(outDirField.getText().trim());
            String trustStore = existingTrustStore(PROD_TRUSTSTORE);
            var send = workflow.send(config, zipFile, out, false, trustStore, TRUSTSTORE_PASSWORD);
            var status = statusWithRetry(config, paketId, out, trustStore, TRUSTSTORE_PASSWORD, "PROD");
            lastProdSuccess = isSuccess(status.returnText());
            Platform.runLater(() -> prodStatusLabel.setText(lastProdSuccess ? "Başarılı: " + status.returnText() : "Kontrol gerekli: " + status.returnText()));
            return "PROD send: " + send.returnText() + "\nPROD status: " + status.returnText() + "\nPaket ID: " + paketId;
        });
    }

    private GibClient.SendResult statusWithRetry(AppConfig config, String paketId, Path out, String trustStore, String trustStorePassword, String label) throws Exception {
        GibClient.SendResult last = null;
        for (int attempt = 1; attempt <= STATUS_RETRIES; attempt++) {
            if (attempt > 1) {
                log(label + " status tekrar denemesi için bekleniyor: " + STATUS_WAIT_SECONDS + " saniye");
                Thread.sleep(STATUS_WAIT_SECONDS * 1000L);
            }
            log(label + " status denemesi: " + attempt + "/" + STATUS_RETRIES);
            last = workflow.status(config, paketId, out, false, trustStore, trustStorePassword);
            if (isSuccess(last.returnText())) {
                return last;
            }
        }
        return last;
    }

    private void archiveCurrentPackage() {
        runTask("Arşiv oluşturuluyor", () -> {
            if (paketId == null || paketId.isBlank()) throw new IllegalStateException("Paket ID yok.");
            Path out = Path.of(outDirField.getText().trim());
            Path archive = out.resolve("arsiv").resolve(periodField.getText().trim() + "-" + paketId);
            Files.createDirectories(archive);
            copyIfExists(zipFile, archive);
            copyIfExists(unsignedXml, archive);
            copyIfExists(signedXml, archive);
            copyIfExists(out.resolve("esu-rapor-" + periodField.getText().trim() + "-audit.csv"), archive);
            copyIfExists(out.resolve("esu-rapor-" + periodField.getText().trim() + "-audit-summary.txt"), archive);
            copyIfExists(out.resolve(paketId + "-sendDocumentFile-request.xml"), archive);
            copyIfExists(out.resolve(paketId + "-sendDocumentFile-response.xml"), archive);
            copyIfExists(out.resolve(paketId + "-getBatchStatus-request.xml"), archive);
            copyIfExists(out.resolve(paketId + "-getBatchStatus-response.xml"), archive);
            return "Arşiv klasörü hazır: " + archive.toAbsolutePath();
        });
    }

    private void validateRequiredInputs() {
        require(vknField, "VKN");
        require(pkcs11LibraryField, "AKİS PKCS#11 yolu");
        require(aliasField, "Sertifika alias");
        require(pinField, "Mali mühür PIN");
        require(excelPathField, "Excel dosyası");
        require(periodField, "Dönem");
        require(outDirField, "Çıktı klasörü");
    }

    private void require(TextInputControl field, String label) {
        if (field.getText() == null || field.getText().isBlank()) {
            throw new IllegalArgumentException(label + " boş olamaz.");
        }
    }

    private void resetRunState() {
        unsignedXml = null;
        signedXml = null;
        zipFile = null;
        paketId = null;
        lastTestSuccess = false;
        lastProdSuccess = false;
        Platform.runLater(() -> {
            packageLabel.setText("Paket yok.");
            testStatusLabel.setText("Test devam ediyor...");
            prodStatusLabel.setText("Canlı gönderilmedi.");
        });
    }

    private void updateSummary(DesktopWorkflow.GenerateResult result) {
        Platform.runLater(() -> summaryLabel.setText(
                "Satır: " + result.rowCount()
                        + " | Plaka: " + result.plateCount()
                        + "\nkWh: " + numberFormat.format(result.totalKwh())
                        + " | Tutar: " + numberFormat.format(result.totalAmount())
                        + "\nRapor No: " + result.raporNo()
        ));
    }

    private void updatePackage() {
        Platform.runLater(() -> packageLabel.setText("Paket ID: " + paketId + "\nZIP: " + (zipFile == null ? "" : zipFile.toAbsolutePath())));
    }

    private void updateEnvironment(String env) {
        Platform.runLater(() -> environmentLabel.setText(env));
    }

    private String existingTrustStore(String path) {
        if (path != null && Files.exists(Path.of(path))) {
            log("SSL truststore kullanılacak: " + path);
            return path;
        }

        try {
            boolean prod = path != null && path.toLowerCase().contains("prod");
            String host = prod ? "okc.gib.gov.tr" : "okctest.gib.gov.tr";
            String fileName = prod ? "gib-prod-truststore.jks" : "gib-test-truststore.jks";
            log("SSL truststore bulunamadı. GİB sertifika zinciri otomatik hazırlanıyor: " + host);
            Path generated = SslTrustStoreManager.ensureTrustStore(fileName, host, 443, TRUSTSTORE_PASSWORD.toCharArray());
            log("SSL truststore hazır: " + generated.toAbsolutePath());
            return generated.toString();
        } catch (Exception ex) {
            log("SSL truststore otomatik hazırlanamadı: " + ex.getMessage());
            log("Java varsayılan truststore denenecek. PKIX hatası devam ederse certs klasörü manuel oluşturulmalı.");
            return "";
        }
    }

    private void applyPinForCurrentOperation() {
        String pin = pinField.getText();
        if (pin != null && !pin.isBlank()) {
            System.setProperty("MALI_MUHUR_PIN", pin);
        }
    }

    private boolean isSuccess(String text) {
        return text != null && text.toLowerCase().contains("success30");
    }

    private void copyIfExists(Path source, Path archive) throws Exception {
        if (source != null && Files.exists(source)) {
            Files.copy(source, archive.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void chooseFile(TextField target, String title, String... patterns) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(title, patterns));
        var file = chooser.showOpenDialog(stage);
        if (file != null) target.setText(file.toPath().toString());
    }

    private void chooseDirectory(TextField target) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Klasör seç");
        var file = chooser.showDialog(stage);
        if (file != null) target.setText(file.toPath().toString());
    }

    private void runTask(String title, ThrowingSupplier<String> supplier) {
        log("▶ " + title + "...");
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                return supplier.get();
            }
        };
        task.setOnSucceeded(e -> log("✅ " + task.getValue()));
        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            String message = ex == null ? "Bilinmeyen hata" : ex.getMessage();
            log("❌ " + title + " hata: " + message);
            if (ex != null) ex.printStackTrace();
            alert(Alert.AlertType.ERROR, title + " başarısız", message);
        });
        Thread thread = new Thread(task, "desktop-task");
        thread.setDaemon(true);
        thread.start();
    }

    private void log(String text) {
        Platform.runLater(() -> logArea.appendText(text + "\n"));
    }

    private void alert(Alert.AlertType type, String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(type);
            alert.setTitle(title);
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    private GridPane baseGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(10));
        return grid;
    }

    private void addRow(GridPane grid, int row, String label, Control field, Button action1, Button action2) {
        Label l = new Label(label + ":");
        l.setMinWidth(120);
        grid.add(l, 0, row);
        field.setMaxWidth(Double.MAX_VALUE);
        grid.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        HBox actions = new HBox(6);
        if (action1 != null) actions.getChildren().add(action1);
        if (action2 != null) actions.getChildren().add(action2);
        if (!actions.getChildren().isEmpty()) grid.add(actions, 2, row);
    }

    private void addStatusRow(GridPane grid, int row, String label, Label value) {
        Label l = new Label(label + ":");
        l.setMinWidth(100);
        value.setWrapText(true);
        grid.add(l, 0, row);
        grid.add(value, 1, row);
        GridPane.setHgrow(value, Priority.ALWAYS);
    }

    private VBox section(String title, javafx.scene.Node child) {
        Label label = new Label(title);
        label.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        VBox box = new VBox(6, label, child);
        box.setPadding(new Insets(10));
        box.setStyle("-fx-border-color: #d6d6d6; -fx-border-radius: 6; -fx-background-radius: 6; -fx-background-color: #ffffff;");
        return box;
    }

    private Label infoBox(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setPadding(new Insets(8));
        label.setStyle("-fx-background-color: #f7f7f7; -fx-border-color: #dddddd; -fx-border-radius: 4; -fx-background-radius: 4;");
        return label;
    }

    private Label valueLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    private int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value == null ? "" : value.trim()); } catch (Exception e) { return fallback; }
    }

    private String nullToBlank(String value) { return value == null ? "" : value; }

    private void autoFillPkcs11() {
        String path = defaultPkcs11Path();
        pkcs11LibraryField.setText(path);
        if (Files.exists(Path.of(path))) {
            log("AKİS PKCS#11 otomatik bulundu: " + path);
        } else {
            log("AKİS PKCS#11 otomatik bulunamadı. AKİS/KamuSM sürücüsünü kurduktan sonra Seç butonu ile libakisp11.dylib / akisp11.dll / libakisp11.so dosyasını seçin.");
        }
    }

    private String defaultPkcs11Path() {
        String[] candidates = {
                "/usr/local/lib/libakisp11.dylib",
                "/Library/Java/Extensions/libakisp11.dylib",
                "C:\\Windows\\System32\\akisp11.dll",
                "C:\\Windows\\SysWOW64\\akisp11.dll",
                "C:\\Program Files\\AKIS\\akisp11.dll",
                "C:\\Program Files (x86)\\AKIS\\akisp11.dll",
                "/usr/lib/libakisp11.so",
                "/usr/local/lib/libakisp11.so"
        };
        for (String candidate : candidates) {
            try {
                if (Files.exists(Path.of(candidate))) {
                    return candidate;
                }
            } catch (Exception ignored) { }
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "C:\\Windows\\System32\\akisp11.dll";
        if (os.contains("mac")) return "/usr/local/lib/libakisp11.dylib";
        return "/usr/lib/libakisp11.so";
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> { T get() throws Exception; }

    public static void main(String[] args) {
        launch(args);
    }
}
