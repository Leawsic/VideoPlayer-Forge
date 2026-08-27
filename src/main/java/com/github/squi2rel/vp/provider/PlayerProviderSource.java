package com.github.squi2rel.vp.provider;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class PlayerProviderSource implements IProviderSource {
    private final ServerPlayer player;

    public PlayerProviderSource(ServerPlayer entity) {
        player = entity;
    }

    @Override
    public String name() {
        return player.getGameProfile().getName();
    }

    @Override
    public void reply(String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
