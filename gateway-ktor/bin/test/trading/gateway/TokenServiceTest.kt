package trading.gateway

import trading.gateway.auth.TokenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TokenServiceTest {
    private val tokens = TokenService(Config(jwtSecret = "test-secret"))

    @Test
    fun `access token verifies and carries user`() {
        val token = tokens.issueAccess(42, "alice")
        val verified = tokens.verifyAccess(token)
        assertNotNull(verified)
        assertEquals(42L, verified.first)
        assertEquals("alice", verified.second)
    }

    @Test
    fun `refresh token is not a valid access token`() {
        val refresh = tokens.issueRefresh(42, "alice")
        assertNull(tokens.verifyAccess(refresh))
        assertNotNull(tokens.verifyRefresh(refresh))
    }

    @Test
    fun `access token is not a valid refresh token`() {
        val access = tokens.issueAccess(42, "alice")
        assertNull(tokens.verifyRefresh(access))
    }

    @Test
    fun `token signed with another secret is rejected`() {
        val other = TokenService(Config(jwtSecret = "another-secret"))
        val token = other.issueAccess(42, "alice")
        assertNull(tokens.verifyAccess(token))
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(tokens.verifyAccess("not-a-jwt"))
        assertNull(tokens.verifyRefresh(""))
    }
}
