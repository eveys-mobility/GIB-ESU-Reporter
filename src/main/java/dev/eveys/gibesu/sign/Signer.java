package dev.eveys.gibesu.sign;

import java.nio.file.Path;

public interface Signer {
    Path sign(Path unsignedXml, Path signedXml) throws Exception;
}
