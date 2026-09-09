package com.rommmobile.app

import com.google.common.truth.Truth.assertThat
import com.rommmobile.app.core.network.UrlNormalizer
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `adds scheme and strips trailing slash`() {
        assertThat(UrlNormalizer.normalize("192.168.50.11:8080/")).isEqualTo("http://192.168.50.11:8080")
        assertThat(UrlNormalizer.normalize("  https://romm.example.com/  ")).isEqualTo("https://romm.example.com")
    }

    @Test
    fun `keeps a path prefix and drops query`() {
        assertThat(UrlNormalizer.normalize("http://host/romm/?x=1#frag")).isEqualTo("http://host/romm")
    }

    @Test
    fun `rejects garbage`() {
        assertThat(UrlNormalizer.normalize("")).isNull()
        assertThat(UrlNormalizer.normalize("http://")).isNull()
    }

    @Test
    fun `private hosts are recognised`() {
        assertThat(UrlNormalizer.isPrivateHost("192.168.50.11")).isTrue()
        assertThat(UrlNormalizer.isPrivateHost("10.0.0.5")).isTrue()
        assertThat(UrlNormalizer.isPrivateHost("172.20.1.1")).isTrue()
        assertThat(UrlNormalizer.isPrivateHost("172.32.1.1")).isFalse()
        assertThat(UrlNormalizer.isPrivateHost("romm.local")).isTrue()
        assertThat(UrlNormalizer.isPrivateHost("romm.example.com")).isFalse()
        assertThat(UrlNormalizer.isPrivateHost("8.8.8.8")).isFalse()
    }

    @Test
    fun `join never doubles slashes`() {
        assertThat(UrlNormalizer.join("http://h:1/", "/api/x")).isEqualTo("http://h:1/api/x")
        assertThat(UrlNormalizer.join("http://h:1", "api/x")).isEqualTo("http://h:1/api/x")
    }
}
