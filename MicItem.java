package ua.kredyt;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/** Микрофон: ПКМ вызывает Фіді Міді на 3 минуты. */
public class MicItem extends Item {
    public MicItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (user instanceof ServerPlayerEntity sp && sp.getEntityWorld() instanceof ServerWorld sw) {
            KredytMod.spawnFidimidi(sw, sp.getX() + 2, sp.getY(), sp.getZ() + 2);
            user.getStackInHand(hand).decrementUnlessCreative(1, user);
        }
        return ActionResult.SUCCESS;
    }
}
