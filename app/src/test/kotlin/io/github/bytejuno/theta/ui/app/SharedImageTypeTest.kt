package io.github.bytejuno.theta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImageTypeTest {
    @Test
    fun opaqueUrisUseTheBatchImageMimeType() {
        listOf("123", "456").forEach { path ->
            assertTrue(isSharedImageType(null, path, "image/*"))
            assertTrue(isSharedImageType("application/octet-stream", path, "image/jpeg"))
            assertTrue(isSharedImageType("*/*", path, "image/*"))
        }
    }

    @Test
    fun concreteFileTypeOverridesImageExtensionAndBatchType() {
        assertFalse(isSharedImageType("application/pdf", "scan.jpg", "image/*"))
        assertFalse(isSharedImageType("video/mp4", "123", "image/*"))
        assertTrue(isSharedImageType("image/png", "123", "application/octet-stream"))
    }

    @Test
    fun genericTypesFallBackToImageExtensions() {
        assertTrue(isSharedImageType(null, "PHOTO.HEIC", "*/*"))
        assertTrue(isSharedImageType("application/octet-stream", "photo.png", null))
        assertFalse(isSharedImageType(null, "report.pdf", "*/*"))
        assertFalse(isSharedImageType(null, "123", null))
    }

    @Test
    fun normalizesWhitespaceCaseAndMimeParameters() {
        assertTrue(isSharedImageType(" APPLICATION/OCTET-STREAM ; charset=binary", "123", " IMAGE/JPEG "))
        assertTrue(isSharedImageType(" ", "photo.jpg", null))
        assertTrue(isSharedImageType(" Image/PNG ", "123", null))
        assertFalse(isSharedImageType(" Application/PDF ; charset=binary", "photo.jpg", "image/*"))
    }
}
