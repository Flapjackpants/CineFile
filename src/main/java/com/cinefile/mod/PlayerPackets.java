package com.cinefile.mod;

import com.cinefile.core.PlayerPacketIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.world.entity.EntityTypes;

/** Resolves clientbound player packet ids for the running game version. */
public final class PlayerPackets {
    private PlayerPackets() {}

    public static PlayerPacketIds current() {
        int[] ids = {-1, -1};
        GameProtocols.CLIENTBOUND_TEMPLATE.details().listPackets((type, id) -> {
            if (type.equals(GamePacketTypes.CLIENTBOUND_ADD_ENTITY)) ids[0] = id;
            if (type.equals(GamePacketTypes.CLIENTBOUND_REMOVE_ENTITIES)) ids[1] = id;
        });
        return new PlayerPacketIds(ids[0], ids[1], BuiltInRegistries.ENTITY_TYPE.getId(EntityTypes.PLAYER));
    }
}
