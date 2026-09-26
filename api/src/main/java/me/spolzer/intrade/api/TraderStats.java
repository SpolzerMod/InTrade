package me.spolzer.intrade.api;

import java.util.UUID;

/**
 * A row of the trader top.
 *
 * @param id player UUID
 * @param name last known player name
 * @param trades number of completed trades
 */
public record TraderStats(UUID id, String name, int trades) {
}
