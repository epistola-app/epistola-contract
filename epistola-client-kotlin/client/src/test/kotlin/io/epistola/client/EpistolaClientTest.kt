package io.epistola.client

import kotlin.test.Test
import kotlin.test.assertNotNull

class EpistolaClientTest {
    @Test
    fun `client can be instantiated`() {
        val client = EpistolaClient()
        assertNotNull(client)
    }
}
