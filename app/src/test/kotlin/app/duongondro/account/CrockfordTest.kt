package app.duongondro.account

import org.junit.Assert.assertEquals
import org.junit.Test

class CrockfordTest {
    @Test fun readsLookAlikesAndIgnoresSpacing() {
        assertEquals("7K2MQ9XA", Crockford.normalise("7k2m q9xa", 8))
        assertEquals("1110000", Crockford.normalise("Il1O-oO0", 8))
    }

    @Test fun capsLength() {
        assertEquals("ABCD", Crockford.normalise("abcdefgh", 4))
    }

    @Test fun keepsOnlyTheCodeOfAPastedLink() {
        assertEquals("7K2MQ9XA", Crockford.normalise("https://duongondro.app/signin?code=7K2M-Q9XA", 8))
        assertEquals("7K2MQ9XAH4N8R2CJ6TPW3ZQF", Crockford.normalise("https://duongondro.app/invite/7K2MQ9XAH4N8R2CJ6TPW3ZQF", 24))
    }

    @Test fun groupsByFour() {
        assertEquals(listOf("7K2M", "Q9XA", "H4N8", "R2CJ", "6TPW", "3ZQF", "8D"), Crockford.grouped("7K2MQ9XAH4N8R2CJ6TPW3ZQF8D"))
    }
}
