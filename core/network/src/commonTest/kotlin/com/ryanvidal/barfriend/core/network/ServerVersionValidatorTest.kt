package com.ryanvidal.barfriend.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerVersionValidatorTest {
    @Test
    fun `accepts v6 and newer versions`() {
        assertIs<ServerCompatibility.Supported>(ServerVersionValidator.validate("v6.12.1"))
        assertIs<ServerCompatibility.Supported>(ServerVersionValidator.validate("6.0.0"))
        assertIs<ServerCompatibility.Supported>(ServerVersionValidator.validate("7.1.0-beta1"))
        assertIs<ServerCompatibility.Supported>(ServerVersionValidator.validate("develop"))
    }

    @Test
    fun `rejects v5 and older versions`() {
        val v5 = ServerVersionValidator.validate("v5.11.0")
        assertIs<ServerCompatibility.Unsupported>(v5)
        assertEquals("v5.11.0", v5.version)
        assertEquals("6.0.0", v5.minimumRequired)

        assertIs<ServerCompatibility.Unsupported>(ServerVersionValidator.validate("4.4.1"))
    }

    @Test
    fun `flags unparseable versions as unknown`() {
        assertIs<ServerCompatibility.Unknown>(ServerVersionValidator.validate("custom-build"))
        assertIs<ServerCompatibility.Unknown>(ServerVersionValidator.validate(""))
    }

    @Test
    fun `normalizes server urls consistently`() {
        assertEquals("https://bar.example.com", ServerUrlNormalizer.normalize("bar.example.com/"))
        assertEquals("https://bar.example.com/bar", ServerUrlNormalizer.normalize("bar.example.com/bar/"))
        assertEquals("http://192.168.1.10:8080", ServerUrlNormalizer.normalize("http://192.168.1.10:8080/api/"))
        assertEquals("http://192.168.1.20:8080", ServerUrlNormalizer.normalize("192.168.1.20:8080"))
        assertEquals("http://172.16.0.5", ServerUrlNormalizer.normalize("172.16.0.5/"))
        assertEquals("https://172.32.0.5", ServerUrlNormalizer.normalize("172.32.0.5"))
        assertEquals("http://nas.local:3000", ServerUrlNormalizer.normalize("nas.local:3000"))
        assertEquals("http://localhost:8080", ServerUrlNormalizer.normalize("localhost:8080"))
        assertEquals("https://bar.example.com/api", ServerUrlNormalizer.apiBaseUrl("https://bar.example.com/api"))
    }

    @Test
    fun `preserves host named api, normalizes ipv6, strips query and fragment, and rejects bad schemes`() {
        assertEquals("http://api", ServerUrlNormalizer.normalize("http://api"))
        assertEquals("http://api", ServerUrlNormalizer.normalize("http://api/"))
        assertEquals("http://api", ServerUrlNormalizer.normalize("api"))
        assertEquals("http://[::1]", ServerUrlNormalizer.normalize("::1"))
        assertEquals("http://[::1]/bar", ServerUrlNormalizer.normalize("::1/bar/"))
        assertEquals("http://[::1]:8080", ServerUrlNormalizer.normalize("[::1]:8080/api/"))
        assertEquals(
            "https://bar.example.com/bar",
            ServerUrlNormalizer.normalize("https://bar.example.com/bar/api/?foo=1#frag"),
        )
        assertEquals(
            "https://127.0.0.1@bar.example.com",
            ServerUrlNormalizer.normalize("127.0.0.1@bar.example.com"),
        )
        assertFailsWith<IllegalArgumentException> {
            ServerUrlNormalizer.normalize("ftp://bar.example.com")
        }
        assertFailsWith<IllegalArgumentException> {
            ServerUrlNormalizer.normalize("   ")
        }
    }

    @Test
    fun `classifies local host and cleartext public urls accurately`() {
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("localhost:8080"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("127.0.0.1"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("10.0.0.5"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("192.168.1.20:8080"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("172.16.0.5"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("169.254.1.10"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("100.64.0.1"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("100.127.255.1"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("nas.local"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("server.lan"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("bar.home.arpa"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("bar-assistant"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("::1"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("fd12:3456::1"))
        assertTrue(ServerUrlNormalizer.isLocalHostUrl("fe80::1"))

        assertFalse(ServerUrlNormalizer.isLocalHostUrl(""))
        assertFalse(ServerUrlNormalizer.isLocalHostUrl("bar.example.com"))
        assertFalse(ServerUrlNormalizer.isLocalHostUrl("100.128.0.1"))
        assertFalse(ServerUrlNormalizer.isLocalHostUrl("172.32.0.5"))
        assertFalse(ServerUrlNormalizer.isLocalHostUrl("127.0.0.1@bar.example.com"))

        assertTrue(ServerUrlNormalizer.isCleartextPublicUrl("http://bar.example.com"))
        assertTrue(ServerUrlNormalizer.isCleartextPublicUrl("HTTP://172.32.0.5:8080"))
        assertFalse(ServerUrlNormalizer.isCleartextPublicUrl("https://bar.example.com"))
        assertFalse(ServerUrlNormalizer.isCleartextPublicUrl("bar.example.com"))
        assertFalse(ServerUrlNormalizer.isCleartextPublicUrl("http://192.168.1.20:8080"))
        assertFalse(ServerUrlNormalizer.isCleartextPublicUrl("http://bar-assistant"))
        assertFalse(ServerUrlNormalizer.isCleartextPublicUrl("http://[::1]:8080"))
        assertFalse(ServerUrlNormalizer.isCleartextPublicUrl(""))
    }

    @Test
    fun `extracts host cleanly across schemes, subpaths, and ipv6`() {
        assertEquals("bar.example.com", ServerUrlNormalizer.extractHost("https://bar.example.com/bar/api/"))
        assertEquals("192.168.1.20", ServerUrlNormalizer.extractHost("192.168.1.20:8080/bar"))
        assertEquals("::1", ServerUrlNormalizer.extractHost("::1/bar"))
        assertEquals("bar.example.com", ServerUrlNormalizer.extractHost("user@bar.example.com:8443"))
        assertEquals(null, ServerUrlNormalizer.extractHost("   "))
        assertEquals(null, ServerUrlNormalizer.extractHost("ftp://bar.example.com"))
    }
}
