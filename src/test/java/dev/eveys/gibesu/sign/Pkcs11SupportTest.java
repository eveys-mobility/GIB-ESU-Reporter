package dev.eveys.gibesu.sign;

import org.junit.jupiter.api.Test;

import javax.security.auth.login.LoginException;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Pkcs11SupportTest {
    @Test
    void lockedPinExplainsRecoveryAndPreservesNestedCause() {
        LoginException login = new LoginException("Login failed");
        login.initCause(new Exception("CKR_PIN_LOCKED"));
        IOException cause = new IOException("load failed", login);

        IllegalStateException result = Pkcs11Support.tokenLoginFailure(cause);

        assertTrue(result.getMessage().contains("PIN'i kilitli (CKR_PIN_LOCKED)"));
        assertTrue(result.getMessage().contains("https://onlineislemler.kamusm.gov.tr"));
        assertFalse(result.getMessage().contains("slotListIndex"));
        assertSame(cause, result.getCause());
    }

    @Test
    void incorrectPinIsNotReportedAsLocked() {
        var result = Pkcs11Support.tokenLoginFailure(new IOException("CKR_PIN_INCORRECT"));
        assertTrue(result.getMessage().contains("PIN'i hatali"));
        assertFalse(result.getMessage().contains("CKR_PIN_LOCKED"));
    }

    @Test
    void missingCardKeepsItsCodeForSlotRetry() {
        var result = Pkcs11Support.tokenLoginFailure(new IOException("CKR_TOKEN_NOT_PRESENT"));
        assertTrue(result.getMessage().contains("kartina ulasilamiyor"));
        assertTrue(result.getMessage().contains("CKR_TOKEN_NOT_PRESENT"));
    }

    @Test
    void unknownFailureDoesNotEchoArbitraryProviderText() {
        var result = Pkcs11Support.tokenLoginFailure(new IOException("private provider details: CKR_DEVICE_ERROR"));
        assertTrue(result.getMessage().contains("CKR_DEVICE_ERROR"));
        assertFalse(result.getMessage().contains("private provider details"));
    }
}
