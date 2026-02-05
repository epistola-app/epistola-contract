package com.example.lib

import kotlin.test.Test
import kotlin.test.assertEquals

class ExampleTest {

    @Test
    fun `greet returns hello message`() {
        val example = Example()
        assertEquals("Hello, World!", example.greet("World"))
    }
}
