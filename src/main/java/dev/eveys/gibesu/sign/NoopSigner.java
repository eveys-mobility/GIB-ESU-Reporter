package dev.eveys.gibesu.sign;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NoopSigner implements Signer {
    @Override
    public Path sign(Path unsignedXml, Path signedXml) throws Exception {
        Files.copy(unsignedXml, signedXml, StandardCopyOption.REPLACE_EXISTING);
        return signedXml;
    }
}
