package com.robot.solar

import com.robot.solar.network.http.AuthenticationRetryPolicy
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationRetryPolicyTest {

    @Test
    fun `redirect before first unauthorized is not treated as authentication retry`() {
        val redirect = response(308)
        val unauthorized = response(401, redirect)

        assertFalse(AuthenticationRetryPolicy.hasRepeatedUnauthorized(unauthorized))
    }

    @Test
    fun `second unauthorized response stops authentication retry`() {
        val redirect = response(308)
        val firstUnauthorized = response(401, redirect)
        val secondUnauthorized = response(401, firstUnauthorized)

        assertTrue(AuthenticationRetryPolicy.hasRepeatedUnauthorized(secondUnauthorized))
    }

    private fun response(code: Int, prior: Response? = null): Response =
        Response.Builder()
            .request(Request.Builder().url("https://example.test/api/devices").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .priorResponse(prior)
            .build()
}
