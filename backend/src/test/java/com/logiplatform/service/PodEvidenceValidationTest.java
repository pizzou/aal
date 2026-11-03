package com.logiplatform.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PodEvidenceValidationTest {
    @Test
    void acceptsKnownDocumentSignaturesOnlyWhenMimeMatches() {
        byte[] png = new byte[] { (byte)0x89, 'P','N','G',13,10,26,10,0 };
        byte[] jpeg = new byte[] { (byte)0xff, (byte)0xd8, (byte)0xff, 0 };
        byte[] pdf = new byte[] { '%','P','D','F','-', '1' };
        assertTrue(PodEvidenceService.matchesSignature(png, "image/png"));
        assertTrue(PodEvidenceService.matchesSignature(jpeg, "image/jpeg"));
        assertTrue(PodEvidenceService.matchesSignature(pdf, "application/pdf"));
        assertFalse(PodEvidenceService.matchesSignature(pdf, "image/jpeg"));
        assertFalse(PodEvidenceService.matchesSignature(new byte[]{1,2,3}, "image/png"));
        assertFalse(PodEvidenceService.matchesSignature(pdf, "text/html"));
    }

    @Test
    void stripsUntrustedFilenameDirectories() {
        assertEquals("pod.png", PodEvidenceService.safeFilename("../../pod.png"));
        assertEquals("evidence.pdf", PodEvidenceService.safeFilename("C:/tmp/evidence.pdf"));
        assertEquals("delivery-evidence", PodEvidenceService.safeFilename(""));
    }
}
