package io.github.jasondorjeshort.dystopo.model

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UuidTest {
    @Test
    fun formatsCanonicalLayout() {
        val bytes = ByteArray(16) { (it * 17).toByte() }
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", Uuid.format(bytes))
    }

    @Test
    fun version4AndVariantBits() {
        val gen = UuidGenerator(Random(7))
        repeat(200) {
            val id = gen.next()
            assertTrue(Uuid.isValid(id), id)
            assertEquals(4, Uuid.version(id))
            assertTrue(id[19] in "89ab", "variant nibble of $id")
            assertEquals(id.lowercase(), id)
        }
    }

    @Test
    fun seededGeneratorsRepeat() {
        val a = UuidGenerator(Random(42))
        val b = UuidGenerator(Random(42))
        repeat(5) { assertEquals(a.next(), b.next()) }
        assertNotEquals(UuidGenerator(Random(1)).next(), UuidGenerator(Random(2)).next())
    }

    @Test
    fun manyAreDistinct() {
        val gen = UuidGenerator(Random(3))
        val ids = HashSet<String>()
        repeat(10_000) { ids += gen.next() }
        assertEquals(10_000, ids.size)
        assertTrue(Uuid.isValid(Uuid.random()))
    }

    @Test
    fun validation() {
        assertTrue(Uuid.isValid("123E4567-E89B-12D3-A456-426614174000"))
        assertFalse(Uuid.isValid("123e4567e89b12d3a456426614174000"))
        assertFalse(Uuid.isValid("123e4567-e89b-12d3-a456-42661417400g"))
        assertFalse(Uuid.isValid("123e4567-e89b-12d3-a456-4266141740000"))
    }
}
