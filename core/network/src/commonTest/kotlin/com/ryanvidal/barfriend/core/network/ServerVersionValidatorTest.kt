package com.ryanvidal.barfriend.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
        assertEquals("https://bar.thevidals.com/bar", ServerUrlNormalizer.normalize("bar.thevidals.com/bar/"))
        assertEquals("http://192.168.1.10:8080", ServerUrlNormalizer.normalize("http://192.168.1.10:8080/api/"))
        assertEquals("http://192.168.50.198:8080", ServerUrlNormalizer.normalize("192.168.50.198:8080"))
        assertEquals("http://172.16.0.5", ServerUrlNormalizer.normalize("172.16.0.5/"))
        assertEquals("https://172.32.0.5", ServerUrlNormalizer.normalize("172.32.0.5"))
        assertEquals("http://nas.local:3000", ServerUrlNormalizer.normalize("nas.local:3000"))
        assertEquals("http://localhost:8080", ServerUrlNormalizer.normalize("localhost:8080"))
        assertEquals("https://bar.example.com/api", ServerUrlNormalizer.apiBaseUrl("https://bar.example.com/api"))
    }
}
