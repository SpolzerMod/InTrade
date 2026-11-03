package me.spolzer.intrade.currency;

/** A currency of another plugin failed to answer, so the amounts of a trade cannot be checked or shown. */
public final class CurrencyException extends RuntimeException {

    CurrencyException(ExternalCurrency currency, String call, Throwable cause) {
        super(call + "() of the currency " + currency.id() + " of " + currency.owner()
                + (cause == null ? " returned null" : " failed"), cause);
    }
}
