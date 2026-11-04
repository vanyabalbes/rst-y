package ua.kredyt;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;

/** Телефон с "mono"-приложением: ПКМ открывает меню банка в чате. */
public class PhoneItem extends Item {
    public PhoneItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        if (user instanceof ServerPlayerEntity sp) {
            KredytMod.menu(sp);
        }
        return ActionResult.SUCCESS;
    }
}
