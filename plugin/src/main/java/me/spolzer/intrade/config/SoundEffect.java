package me.spolzer.intrade.config;

import org.bukkit.entity.Player;

public record SoundEffect(String id, float volume, float pitch) {
    static final SoundEffect NONE = new SoundEffect("", 0, 0);

    // "block.note_block.bell 1.3": sound id and optional pitch, an empty value turns the sound off
    static SoundEffect parse(String value, float volume) {
        String[] parts = value.trim().split("\s+");
        if (parts[0].isEmpty()) return NONE;
        float pitch = 1;
        if (parts.length > 1) {
            try {
                pitch = Float.parseFloat(parts[1]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return new SoundEffect(parts[0], volume, Math.clamp(pitch, 0.5f, 2.0f));
    }

    public void play(Player player) {
        if (this != NONE && player.isOnline()) player.playSound(player.getLocation(), id, volume, pitch);
    }
}
