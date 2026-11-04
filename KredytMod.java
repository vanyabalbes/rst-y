package ua.kredyt;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.component.type.ConsumableComponents;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.mob.PhantomEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Function;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class KredytMod implements ModInitializer {
    public static final String MOD_ID = "kredyt";
    public static Item PHONE, SHAWARMA, MIC;

    private static final int APARTMENT_PRICE = 5000;
    private static final int APARTMENT_BILL = 50;
    private static final long MAX_CREDIT = 20000;
    private static final long MAX_MICRO = 3000;

    private static final Style MONO = Style.EMPTY.withColor(TextColor.fromRgb(0xFF5D5D)).withBold(true);
    private static final Map<UUID, String> LAST_ZONE = new HashMap<>();
    private static final float[] MELODY = {0.8f, 1.0f, 1.2f, 1.0f, 1.5f, 1.2f, 1.0f, 0.8f};
    private static int melodyStep = 0;

    // ---------------------------------------------------------------- init

    private static Item register(String name, Function<Item.Settings, Item> factory, Item.Settings settings) {
        RegistryKey<Item> key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, name));
        return Registry.register(Registries.ITEM, key, factory.apply(settings.registryKey(key)));
    }

    @Override
    public void onInitialize() {
        PHONE = register("phone", PhoneItem::new, new Item.Settings().maxCount(1));
        MIC = register("mic", MicItem::new, new Item.Settings().maxCount(1));
        SHAWARMA = register("shawarma", Item::new, new Item.Settings().food(
                new FoodComponent.Builder().nutrition(8).saturationModifier(0.9f).build(),
                ConsumableComponents.food().build()));

        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(e -> {
            e.add(PHONE);
            e.add(MIC);
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.FOOD_AND_DRINK).register(e -> e.add(SHAWARMA));

        ServerLifecycleEvents.SERVER_STARTED.register(s -> Bank.load(s.getSavePath(WorldSavePath.ROOT).resolve("kredyt_bank.json")));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> Bank.save());

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity p = handler.getPlayer();
            Bank.Account a = Bank.acc(p);
            if (!a.gotPhone) {
                a.gotPhone = true;
                p.giveItemStack(new ItemStack(PHONE));
                p.sendMessage(Text.literal("Тобі видали телефон з додатком mono. ПКМ — меню банку.").formatted(Formatting.GRAY), false);
            }
        });

        // Случайные здания при генерации новых чанков
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newlyGenerated) -> {
            if (!newlyGenerated || world.getRegistryKey() != World.OVERWORLD) return;
            ChunkPos cp = chunk.getPos();
            Random r = new Random(world.getSeed() ^ (cp.x * 341873128712L) ^ (cp.z * 132897987541L));
            if (r.nextInt(40) != 0) return;
            Buildings.Type type = Buildings.Type.values()[r.nextInt(Buildings.Type.values().length)];
            if (Buildings.build(chunk, type)) {
                Bank.INSTANCE.buildings.put(String.valueOf(cp.toLong()), type.name());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(KredytMod::tick);

        // Шахед не сбивается без Фіді Міді поблизости
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!entity.getCommandTags().contains("shahed")) return true;
            if (!(entity.getEntityWorld() instanceof ServerWorld w)) return true;
            boolean fidi = !w.getEntitiesByClass(VillagerEntity.class, entity.getBoundingBox().expand(24),
                    v -> v.getCommandTags().contains("fidimidi") && !v.isRemoved()).isEmpty();
            if (!fidi && source.getAttacker() instanceof ServerPlayerEntity p) {
                p.sendMessage(Text.literal("Шахед не збивається, поки поруч не співає Фіді Міді!").formatted(Formatting.RED), true);
            }
            return fidi;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity p)) return;
            Bank.Account a = Bank.acc(p);
            if (entity.getCommandTags().contains("shahed")) {
                a.balance += 500;
                p.sendMessage(Text.literal("Шахед збито! +500 ₴ (донат на ЗСУ від мера)").formatted(Formatting.GREEN), false);
            } else if (entity instanceof Monster) {
                a.balance += 5 + p.getRandom().nextInt(11);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("bank")
                        .executes(c -> { menu(c.getSource().getPlayerOrThrow()); return 1; })
                        .then(literal("balance").executes(c -> { menu(c.getSource().getPlayerOrThrow()); return 1; }))
                        .then(literal("credit").then(argument("sum", LongArgumentType.longArg(100, MAX_CREDIT))
                                .executes(c -> { takeCredit(c.getSource().getPlayerOrThrow(), LongArgumentType.getLong(c, "sum")); return 1; })))
                        .then(literal("micro").then(argument("sum", LongArgumentType.longArg(50, 1000))
                                .executes(c -> { takeMicro(c.getSource().getPlayerOrThrow(), LongArgumentType.getLong(c, "sum")); return 1; })))
                        .then(literal("repay").executes(c -> { repay(c.getSource().getPlayerOrThrow()); return 1; }))
                        .then(literal("apartment").executes(c -> { buyApartment(c.getSource().getPlayerOrThrow()); return 1; }))
                        .then(literal("shop").executes(c -> { shop(c.getSource().getPlayerOrThrow()); return 1; }))
                        .then(literal("buy").then(argument("id", StringArgumentType.word())
                                .executes(c -> { buy(c.getSource().getPlayerOrThrow(), StringArgumentType.getString(c, "id")); return 1; })))
        ));
    }

    // ---------------------------------------------------------------- UI

    private static MutableText btn(String label, String cmd) {
        return Text.literal("[" + label + "] ").setStyle(Style.EMPTY.withColor(Formatting.WHITE).withClickEvent(new ClickEvent.RunCommand(cmd)));
    }

    public static void menu(ServerPlayerEntity p) {
        Bank.Account a = Bank.acc(p);
        p.sendMessage(Text.literal("▌mono  ").setStyle(MONO).append(Text.literal("₴ " + a.balance).formatted(Formatting.WHITE)), false);
        p.sendMessage(Text.literal("Кредит: " + a.credit + " ₴ (5%/день) | Мікрозайм: " + a.micro + " ₴ (30%/день)").formatted(Formatting.GRAY), false);
        p.sendMessage(Text.literal("Квартира: " + (a.apartment ? "є (комуналка " + APARTMENT_BILL + " ₴/день)" : "немає, ціна " + APARTMENT_PRICE + " ₴")).formatted(Formatting.GRAY), false);
        p.sendMessage(btn("Кредит 1000", "/bank credit 1000").append(btn("Мікрозайм 500", "/bank micro 500")).append(btn("Погасити", "/bank repay")), false);
        p.sendMessage(btn("Квартира", "/bank apartment").append(btn("Магазин", "/bank shop")), false);
    }

    private static void msg(ServerPlayerEntity p, String s, Formatting f) {
        p.sendMessage(Text.literal(s).formatted(f), false);
    }

    // ---------------------------------------------------------------- bank

    private static void takeCredit(ServerPlayerEntity p, long sum) {
        Bank.Account a = Bank.acc(p);
        if (a.credit + sum > MAX_CREDIT) { msg(p, "Ліміт кредиту " + MAX_CREDIT + " ₴.", Formatting.RED); return; }
        a.credit += sum;
        a.balance += sum;
        msg(p, "Кредит схвалено: +" + sum + " ₴. Борг росте на 5% щодня.", Formatting.GREEN);
    }

    private static void takeMicro(ServerPlayerEntity p, long sum) {
        Bank.Account a = Bank.acc(p);
        if (a.micro + sum > MAX_MICRO) { msg(p, "Ліміт мікрозаймів " + MAX_MICRO + " ₴. Ти й так у боргах.", Formatting.RED); return; }
        a.micro += sum;
        a.balance += sum;
        msg(p, "Мікрозайм за 1 хвилину: +" + sum + " ₴. Борг росте на 30% щодня. Колектори вже в курсі.", Formatting.YELLOW);
    }

    private static void repay(ServerPlayerEntity p) {
        Bank.Account a = Bank.acc(p);
        long pay = Math.min(a.balance, a.micro);
        a.balance -= pay; a.micro -= pay;
        long pay2 = Math.min(a.balance, a.credit);
        a.balance -= pay2; a.credit -= pay2;
        msg(p, "Погашено: " + (pay + pay2) + " ₴. Залишилось боргу: " + (a.micro + a.credit) + " ₴.", Formatting.GREEN);
    }

    private static void buyApartment(ServerPlayerEntity p) {
        Bank.Account a = Bank.acc(p);
        if (a.apartment) { msg(p, "Квартира вже є. Спи в ліжку — це твій дім.", Formatting.GRAY); return; }
        if (a.balance < APARTMENT_PRICE) { msg(p, "Не вистачає грошей: треба " + APARTMENT_PRICE + " ₴. Візьми кредит :)", Formatting.RED); return; }
        if (!(p.getEntityWorld() instanceof ServerWorld w)) return;
        a.balance -= APARTMENT_PRICE;
        a.apartment = true;
        buildApartment(w, p.getBlockPos().add(2, 0, 2));
        msg(p, "Вітаємо з квартирою! Лягай спати в ліжко — це буде твоя точка відродження.", Formatting.GREEN);
    }

    private static void buildApartment(ServerWorld w, BlockPos o) {
        var wall = net.minecraft.block.Blocks.WHITE_CONCRETE.getDefaultState();
        var air = net.minecraft.block.Blocks.AIR.getDefaultState();
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) for (int y = -1; y <= 4; y++) {
            boolean edge = x == 0 || x == 6 || z == 0 || z == 6;
            BlockPos pos = o.add(x, y, z);
            if (y == -1) w.setBlockState(pos, net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
            else if (y == 4) w.setBlockState(pos, wall, Block.NOTIFY_ALL);
            else if (edge) {
                boolean win = (y == 1 || y == 2) && ((x == 3 && (z == 0 || z == 6)) || (z == 3 && (x == 0 || x == 6)));
                w.setBlockState(pos, win ? net.minecraft.block.Blocks.GLASS.getDefaultState() : wall, Block.NOTIFY_ALL);
            } else w.setBlockState(pos, air, Block.NOTIFY_ALL);
        }
        w.setBlockState(o.add(3, 4, 3), net.minecraft.block.Blocks.SEA_LANTERN.getDefaultState(), Block.NOTIFY_ALL);
        w.setBlockState(o.add(1, 0, 1), net.minecraft.block.Blocks.RED_BED.getDefaultState()
                .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.FOOT)
                .with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.SOUTH), Block.NOTIFY_ALL);
        w.setBlockState(o.add(1, 0, 2), net.minecraft.block.Blocks.RED_BED.getDefaultState()
                .with(net.minecraft.block.BedBlock.PART, net.minecraft.block.enums.BedPart.HEAD)
                .with(net.minecraft.block.BedBlock.FACING, net.minecraft.util.math.Direction.SOUTH), Block.NOTIFY_ALL);
        w.setBlockState(o.add(5, 0, 1), net.minecraft.block.Blocks.CRAFTING_TABLE.getDefaultState(), Block.NOTIFY_ALL);
        w.setBlockState(o.add(5, 0, 2), net.minecraft.block.Blocks.FURNACE.getDefaultState(), Block.NOTIFY_ALL);
        var door = net.minecraft.block.Blocks.OAK_DOOR.getDefaultState().with(net.minecraft.block.DoorBlock.FACING, net.minecraft.util.math.Direction.SOUTH);
        w.setBlockState(o.add(3, 0, 6), door.with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.LOWER), Block.NOTIFY_ALL);
        w.setBlockState(o.add(3, 1, 6), door.with(net.minecraft.block.DoorBlock.HALF, net.minecraft.block.enums.DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
    }

    // ---------------------------------------------------------------- shops

    private static Buildings.Type zoneOf(ServerPlayerEntity p) {
        String t = Bank.INSTANCE.buildings.get(String.valueOf(p.getChunkPos().toLong()));
        if (t == null) return null;
        int lx = p.getBlockX() & 15, lz = p.getBlockZ() & 15;
        if (lx < 3 || lx > 11 || lz < 3 || lz > 12) return null;
        return Buildings.Type.valueOf(t);
    }

    private static void shop(ServerPlayerEntity p) {
        Buildings.Type z = zoneOf(p);
        if (z == null) { msg(p, "Тут немає магазину. Шукай ATB, шаурмічну, Аврору або Нову Пошту.", Formatting.GRAY); return; }
        p.sendMessage(Text.literal("▌" + z.title).setStyle(MONO), false);
        MutableText line = Text.empty();
        for (Buildings.Offer o : Buildings.offers(z)) line.append(btn(o.name() + " — " + o.price() + " ₴", "/bank buy " + o.id()));
        p.sendMessage(line, false);
    }

    private static void buy(ServerPlayerEntity p, String id) {
        Buildings.Type z = zoneOf(p);
        if (z == null) { msg(p, "Ти не в магазині.", Formatting.RED); return; }
        Buildings.Offer offer = Buildings.offers(z).stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
        if (offer == null) { msg(p, "Такого товару тут нема.", Formatting.RED); return; }
        Bank.Account a = Bank.acc(p);
        if (a.balance < offer.price()) { msg(p, "Недостатньо коштів на картці.", Formatting.RED); return; }
        a.balance -= offer.price();
        switch (id) {
            case "bread" -> p.giveItemStack(new ItemStack(Items.BREAD, 3));
            case "kovbasa" -> p.giveItemStack(new ItemStack(Items.COOKED_PORKCHOP, 2));
            case "milk" -> p.giveItemStack(new ItemStack(Items.MILK_BUCKET));
            case "shawarma" -> p.giveItemStack(new ItemStack(SHAWARMA));
            case "shawarma3" -> p.giveItemStack(new ItemStack(SHAWARMA, 3));
            case "pickaxe" -> p.giveItemStack(new ItemStack(Items.IRON_PICKAXE));
            case "shield" -> p.giveItemStack(new ItemStack(Items.SHIELD));
            case "mic" -> p.giveItemStack(new ItemStack(MIC));
            case "parcel" -> {
                Item[] loot = {Items.DIAMOND, Items.EMERALD, Items.GOLDEN_APPLE, Items.ENDER_PEARL, Items.IRON_INGOT, Items.COAL, Items.DIRT};
                Item it = loot[p.getRandom().nextInt(loot.length)];
                p.giveItemStack(new ItemStack(it, it == Items.DIRT || it == Items.COAL ? 16 : 2));
                msg(p, "Нова Пошта: «Вам посилка!» Відкриваєш...", Formatting.YELLOW);
            }
            default -> {}
        }
        msg(p, "Оплата пройшла: -" + offer.price() + " ₴", Formatting.GRAY);
    }

    // ---------------------------------------------------------------- tick

    private static void tick(MinecraftServer server) {
        int t = server.getTicks();
        if (t % 20 != 0) return;
        ServerWorld ow = server.getOverworld();
        long day = ow.getTimeOfDay() / 24000L;

        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            Bank.Account a = Bank.acc(p);
            if (a.lastDay < 0 || a.lastDay > day) a.lastDay = day;
            int n = 0;
            while (a.lastDay < day && n++ < 5) { a.lastDay++; dailyCycle(p, a); }

            // вход в магазин
            Buildings.Type z = zoneOf(p);
            String zn = z == null ? "" : z.name();
            if (!zn.equals(LAST_ZONE.getOrDefault(p.getUuid(), ""))) {
                LAST_ZONE.put(p.getUuid(), zn);
                if (z != null) p.sendMessage(Text.literal("Ласкаво просимо: " + z.title + "  (/bank shop)").formatted(Formatting.GOLD), true);
            }

            if (!(p.getEntityWorld() instanceof ServerWorld w)) continue;

            // Шахеды: раз в ~10 минут на игрока
            if (w.getRegistryKey() == World.OVERWORLD && w.random.nextInt(600) == 0) spawnShahed(w, p);

            // таймеры сущностей рядом
            for (MobEntity m : w.getEntitiesByClass(MobEntity.class, p.getBoundingBox().expand(64), e -> !e.isRemoved())) {
                if (m.getCommandTags().contains("shahed") && m.age > 1200) detonate(w, m);
                if (m.getCommandTags().contains("fidimidi") && m.age > 3600) m.discard();
            }
        }

        // пение Фіді Міді
        if (t % 80 == 0) {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                if (!(p.getEntityWorld() instanceof ServerWorld w)) continue;
                for (VillagerEntity v : w.getEntitiesByClass(VillagerEntity.class, p.getBoundingBox().expand(32),
                        e -> e.getCommandTags().contains("fidimidi") && !e.isRemoved())) {
                    p.sendMessage(Text.literal("🎤 Фіді Міді: «Я как Федерико Феллини»").formatted(Formatting.LIGHT_PURPLE), true);
                    w.playSound(null, v.getX(), v.getY(), v.getZ(), SoundEvents.BLOCK_NOTE_BLOCK_HARP.value(),
                            SoundCategory.NEUTRAL, 3.0f, MELODY[melodyStep++ % MELODY.length]);
                }
            }
        }
        if (t % 6000 == 0) Bank.save();
    }

    private static void dailyCycle(ServerPlayerEntity p, Bank.Account a) {
        if (a.apartment) {
            if (a.balance >= APARTMENT_BILL) a.balance -= APARTMENT_BILL;
            else { a.micro += APARTMENT_BILL - a.balance; a.balance = 0; }
            msg(p, "Комуналка: -" + APARTMENT_BILL + " ₴", Formatting.GRAY);
        }
        if (a.micro > 0) a.micro = Math.round(a.micro * 1.30);
        if (a.credit > 0) a.credit = Math.round(a.credit * 1.05);
        if (a.micro > 0 || a.credit > 0) {
            msg(p, "mono: нараховано відсотки. Борг: " + (a.micro + a.credit) + " ₴", Formatting.YELLOW);
        }
        if (a.micro > 1500 && p.getEntityWorld() instanceof ServerWorld w) {
            ZombieEntity z = EntityType.ZOMBIE.create(w, SpawnReason.EVENT);
            if (z != null) {
                z.setPosition(p.getX() + 4, p.getY(), p.getZ() + 4);
                z.setCustomName(Text.literal("Колектор").formatted(Formatting.DARK_RED));
                z.setCustomNameVisible(true);
                z.addCommandTag("collector");
                z.setPersistent();
                z.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                w.spawnEntity(z);
                msg(p, "До тебе прийшов колектор. Погаси борг!", Formatting.RED);
            }
        }
    }

    // ---------------------------------------------------------------- mobs

    private static void spawnShahed(ServerWorld w, ServerPlayerEntity p) {
        PhantomEntity ph = EntityType.PHANTOM.create(w, SpawnReason.EVENT);
        if (ph == null) return;
        double ang = w.random.nextDouble() * Math.PI * 2;
        ph.setPosition(p.getX() + Math.cos(ang) * 35, p.getY() + 20, p.getZ() + Math.sin(ang) * 35);
        ph.setCustomName(Text.literal("Шахед").formatted(Formatting.RED));
        ph.setCustomNameVisible(true);
        ph.addCommandTag("shahed");
        ph.setPersistent();
        ph.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(6.0);
        ph.setHealth(6.0f);
        ph.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, StatusEffectInstance.INFINITE, 0, false, false));
        w.spawnEntity(ph);
        p.sendMessage(Text.literal("🚨 Повітряна тривога! Шахед! Збити можна тільки поруч із Фіді Міді.").formatted(Formatting.RED), false);
        if (w.random.nextInt(100) < 40) spawnFidimidi(w, p.getX() + 3, p.getY(), p.getZ() + 3);
    }

    private static void detonate(ServerWorld w, MobEntity shahed) {
        w.createExplosion(shahed, shahed.getX(), shahed.getY(), shahed.getZ(), 2.0f, World.ExplosionSourceType.NONE);
        for (ServerPlayerEntity p : w.getPlayers(pl -> pl.squaredDistanceTo(shahed) < 64)) {
            Bank.Account a = Bank.acc(p);
            long fine = Math.min(a.balance, 200);
            a.balance -= fine;
            msg(p, "Шахед долетів. Збитки: -" + fine + " ₴", Formatting.RED);
        }
        shahed.discard();
    }

    public static void spawnFidimidi(ServerWorld w, double x, double y, double z) {
        VillagerEntity v = EntityType.VILLAGER.create(w, SpawnReason.EVENT);
        if (v == null) return;
        v.setPosition(x, y, z);
        v.setCustomName(Text.literal("Фіді Міді").formatted(Formatting.LIGHT_PURPLE));
        v.setCustomNameVisible(true);
        v.addCommandTag("fidimidi");
        v.setInvulnerable(true);
        v.setPersistent();
        w.spawnEntity(v);
    }
}
