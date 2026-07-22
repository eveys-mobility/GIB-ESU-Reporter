package dev.eveys.gibesu.desktop;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pkcs11AliasScannerTest {
    @Test
    void configuredSlotIsTriedFirstAndAutomaticRangeHasNoDuplicates() {
        assertEquals(List.of(4, 0, 1, 2, 3, 5, 6, 7, 8, 9), Pkcs11AliasScanner.candidateSlots(4));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), Pkcs11AliasScanner.candidateSlots(0));
    }

    @Test
    void retriesPkcs11SlotFailuresButDoesNotHideUnrelatedFailures() {
        assertTrue(Pkcs11AliasScanner.shouldTryOtherSlots(
                new IllegalStateException("PKCS#11 KeyStore service bulunamadi")));
        assertTrue(Pkcs11AliasScanner.shouldTryOtherSlots(
                new IllegalStateException("PKCS#11 provider ayarlanamadi. slotListIndex: 0")));
        assertTrue(Pkcs11AliasScanner.shouldTryOtherSlots(
                new IllegalStateException("PKCS#11 token oturumu acilamadi",
                        new IOException("CKR_TOKEN_NOT_PRESENT"))));
        assertFalse(Pkcs11AliasScanner.shouldTryOtherSlots(
                new IllegalStateException("PKCS#11 token oturumu acilamadi",
                        new IOException("CKR_PIN_INCORRECT"))));
        assertFalse(Pkcs11AliasScanner.shouldTryOtherSlots(
                new IllegalArgumentException("PIN bos olamaz")));
    }
}
