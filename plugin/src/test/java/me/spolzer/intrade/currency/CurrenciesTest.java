package me.spolzer.intrade.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CurrenciesTest {

    @ParameterizedTest
    @CsvSource({
            "1500, 0, 1500",
            "2.5k, 2, 2500.00",
            "1m, 0, 1000000",
            "3к, 0, 3000",
            "'1,5', 2, 1.50",
            "1 000, 0, 1000",
            "1.239, 2, 1.23",
            "0.9, 0, 0",
            "'', 2, 0"
    })
    void parsesPlayerInput(String input, int scale, String expected) {
        assertEquals(new BigDecimal(expected), Currencies.parse(input, scale));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "-5", "1e5", "1.2.3", "k", "1234567890123456789"})
    void rejectsAnythingElse(String input) {
        assertNull(Currencies.parse(input, 2));
    }

    @ParameterizedTest
    @CsvSource({
            "100, 10, 0, 10",
            "101, 10, 0, 11",
            "1, 10, 0, 1",
            "5, 50, 0, 3",
            "1.05, 10, 2, 0.11",
            "1000, 2.5, 2, 25.00",
            "0.01, 0.1, 2, 0.01",
            "7, 100, 0, 7",
            "7, 0, 0, 0"
    })
    void taxIsRoundedUpToTheCurrencyScale(String amount, String percent, int scale, String expected) {
        assertEquals(new BigDecimal(expected), Currencies.tax(new BigDecimal(amount), new BigDecimal(percent), scale));
    }
}
