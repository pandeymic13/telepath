package dev.plume

import java.util.Locale

/** A Yahoo Finance symbol: AAPL, RELIANCE.NS, ^NSEI, EURUSD=X. */
private val SYMBOL = Regex("[A-Z0-9^][A-Z0-9.^=\\-]{0,23}")

/** A share's ticker without an exchange suffix, index caret or currency mark: RELIANCE, BRK-B. */
private val BARE_TICKER = Regex("[A-Z0-9][A-Z0-9\\-]*")

/** A Yahoo Finance exchange suffix: NS, BO, L, DE, SAU. */
private val EXCHANGE = Regex("[A-Z]{1,3}")

/** Index names that aren't Yahoo symbols, and the symbols they mean. */
private val INDEX_SHORTCUTS = mapOf("NIFTY" to "^NSEI", "SENSEX" to "^BSESN")

/** Watchlist symbols as typed on the remote: Yahoo tickers, at most five; "NIFTY" and "SENSEX" mean those indices. */
internal fun parseSymbols(value: String): List<String> {
    val symbols = value.uppercase(Locale.ROOT).split(',', ' ', '\n', '\t')
        .map { it.trim() }.filter { it.isNotEmpty() }.map { INDEX_SHORTCUTS[it] ?: it }.distinct()
    require(symbols.size <= 5) { "Use at most five symbols to keep requests and the widget small." }
    require(symbols.all { it.matches(SYMBOL) }) {
        "Use Yahoo symbols, for example RELIANCE.NS, TCS.NS, ^NSEI or AAPL."
    }
    return symbols
}

/** True for a ticker typed without its exchange (RELIANCE), which Yahoo may know only with one (RELIANCE.NS). */
internal fun isBareTicker(symbol: String) = symbol.matches(BARE_TICKER)

/**
 * The listing meant by a share typed without its exchange ([typed]: RELIANCE): the first of Yahoo's search results
 * ([found], best match first) that is the same ticker with an exchange suffix (RELIANCE.NS); null if there is none.
 * Only a suffix is ever added, so a typo never turns into another company.
 */
internal fun listingFor(typed: String, found: List<String>): String? {
    if (!isBareTicker(typed)) return null
    return found.map { it.trim().uppercase(Locale.ROOT) }.firstOrNull {
        it.startsWith("$typed.") && it.substring(typed.length + 1).matches(EXCHANGE) && it.matches(SYMBOL)
    }
}
