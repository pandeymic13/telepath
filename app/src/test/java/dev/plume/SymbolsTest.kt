package dev.plume

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SymbolsTest {
    @Test fun symbolsAreBoundedAndNotUrls() {
        assertEquals(listOf("RELIANCE.NS", "^NSEI", "AAPL"), parseSymbols("reliance.ns, ^NSEI, AAPL,AAPL"))
        assertTrue(parseSymbols("").isEmpty())
        assertEquals(listOf("AAPL", "^NSEI"), parseSymbols("AAPL, NIFTY"))
        assertEquals(listOf("^NSEI", "^BSESN"), parseSymbols("nifty, Sensex, ^NSEI"))
        assertEquals(5, parseSymbols("A,B,C,D,E").size)
        assertThrows(IllegalArgumentException::class.java) { parseSymbols("https://example.com") }
        assertThrows(IllegalArgumentException::class.java) { parseSymbols("A,B,C,D,E,F") }
    }

    /** Search results as Yahoo Finance ranks them (October 2026). */
    @Test fun sharesTypedWithoutTheirExchangeGetOnlyASuffix() {
        assertEquals("RELIANCE.NS", listingFor("RELIANCE",
            listOf("RELIANCE.NS", "RS", "RPOWER.NS", "EZRA", "RELIANCE.BO", "RIGD.IL", "RELINFRA.NS")))
        assertEquals("TCS.NS", listingFor("TCS", listOf("TCS.NS", "TCS.BO", "TCS.TO", "TCSEX")))
        assertEquals("BMW.DE", listingFor("BMW", listOf("bmw.de", "BMWKY", "BMW.BO")))
        // Another company's ticker, a longer one, an option or a nonsense suffix never matches.
        assertNull(listingFor("INDUSTRIES", listOf("TEVA", "OLOX", "ALM", "DY")))
        assertNull(listingFor("HDFC", listOf("HDFCBANK.NS", "HDFCLIFE.NS")))
        assertNull(listingFor("SHOP", listOf("SHOP261023C00177500", "SHOP.TO.X", "SHOP.1")))
        assertNull(listingFor("RELIANCE", emptyList()))
        // Symbols that already have an exchange, index caret or currency mark aren't looked up.
        assertNull(listingFor("RELIANCE.NS", listOf("RELIANCE.NS.BO")))
        assertNull(listingFor("^NSEI", listOf("^NSEI.NS")))
        assertNull(listingFor("EURUSD=X", listOf("EURUSD=X.L")))
        // A listing too long to save is skipped.
        assertNull(listingFor("A".repeat(23), listOf("A".repeat(23) + ".NS")))
        assertTrue(isBareTicker("BRK-B"))
        assertFalse(isBareTicker("RELIANCE.NS"))
        assertFalse(isBareTicker("^BSESN"))
    }
}
