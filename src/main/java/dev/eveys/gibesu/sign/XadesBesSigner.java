package dev.eveys.gibesu.sign;

import java.nio.file.Path;

/**
 * GIB canli gonderim icin gereken imza katmani burada tamamlanacak.
 *
 * Notlar:
 * - GIB kilavuzundaki imza profili XAdES-BES olarak ele alinmalidir.
 * - En saglam entegrasyon, Eveys adina alinacak TUBITAK MA3/ESYA lisansi ile yapilmalidir.
 * - Acik kaynak xades4j/DSS ile alternatif yazilabilir; ancak canliya alinmadan once GIB test ortaminda kesin dogrulama gerekir.
 */
public class XadesBesSigner implements Signer {
    @Override
    public Path sign(Path unsignedXml, Path signedXml) {
        throw new UnsupportedOperationException("XAdES-BES imza adapteri henuz baglanmadi. Once MA3/ESYA lisansi veya resmi imza kutuphanesi secilmeli.");
    }
}
