package me.spolzer.intrade.config;

import java.util.Locale;

public enum TradeSound {
    REQUEST("block.note_block.bell 1.3"),
    OPEN("block.chest.open 1.2"),
    PUT("entity.item_frame.add_item 1.0"),
    TAKE("entity.item_frame.remove_item 1.0"),
    CURRENCY("entity.experience_orb.pickup 1.2"),
    CHANGED("block.note_block.pling 0.7"),
    READY("block.note_block.chime 1.6"),
    UNREADY("block.note_block.bass 1.0"),
    COUNTDOWN("block.note_block.hat 1.4"),
    ERROR("block.note_block.bass 0.6"),
    CANCELLED("entity.villager.no 1.0"),
    COMPLETED("entity.player.levelup 1.2");

    final String fallback;

    TradeSound(String fallback) {
        this.fallback = fallback;
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
