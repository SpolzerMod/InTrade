package me.spolzer.intrade.api;

import java.util.UUID;

public record TraderStats(UUID id, String name, int trades) {
}
