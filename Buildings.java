package ua.kredyt;

import net.minecraft.block.*;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;

import java.util.List;

/** Процедурные магазины: ATB, шаурмичная, Аврора, Нова Пошта. Кольори умовні. */
public class Buildings {
    public enum Type {
        ATB("ATB-Маркет", Blocks.BLUE_CONCRETE, Blocks.ORANGE_CONCRETE),
        SHAWARMA("Шаурмічна", Blocks.YELLOW_CONCRETE, Blocks.BROWN_CONCRETE),
        AURORA("Аврора", Blocks.LIGHT_BLUE_CONCRETE, Blocks.PURPLE_CONCRETE),
        POSHTA("Нова Пошта", Blocks.RED_CONCRETE, Blocks.WHITE_CONCRETE);

        public final String title;
        public final Block wall, accent;

        Type(String title, Block wall, Block accent) {
            this.title = title;
            this.wall = wall;
            this.accent = accent;
        }
    }

    public record Offer(String id, String name, int price) {}

    public static List<Offer> offers(Type t) {
        return switch (t) {
            case ATB -> List.of(new Offer("bread", "Хліб", 15), new Offer("kovbasa", "Ковбаса", 40), new Offer("milk", "Молоко", 20));
            case SHAWARMA -> List.of(new Offer("shawarma", "Шаурма", 60), new Offer("shawarma3", "Шаурма x3", 150));
            case AURORA -> List.of(new Offer("pickaxe", "Залізна кирка", 250), new Offer("shield", "Щит", 200), new Offer("mic", "Мікрофон Фіді Міді", 500));
            case POSHTA -> List.of(new Offer("parcel", "Посилка (сюрприз)", 150));
        };
    }

    private static void set(Chunk c, int x, int y, int z, BlockState s) {
        c.setBlockState(new BlockPos(x, y, z), s, 0);
    }

    /** Строит здание 9x9 в границах чанка (локальные x/z 3..11). Возвращает false, если место не подходит. */
    public static boolean build(Chunk chunk, Type type) {
        int[][] pts = {{3, 3}, {11, 3}, {3, 11}, {11, 11}, {7, 7}};
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int[] p : pts) {
            int floor = chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR_WG, p[0], p[1]);
            int surf = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE_WG, p[0], p[1]);
            if (floor != surf) return false; // вода
            min = Math.min(min, floor);
            max = Math.max(max, floor);
        }
        if (max - min > 4 || max < 50 || max > 200) return false;

        int baseY = max; // первый свободный блок над землёй
        int x0 = chunk.getPos().getStartX() + 3;
        int z0 = chunk.getPos().getStartZ() + 3;
        BlockState wall = type.wall.getDefaultState();
        BlockState accent = type.accent.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState glass = Blocks.GLASS.getDefaultState();
        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState floorB = Blocks.POLISHED_ANDESITE.getDefaultState();

        for (int dx = 0; dx < 9; dx++) {
            for (int dz = 0; dz < 9; dz++) {
                int x = x0 + dx, z = z0 + dz;
                int colFloor = chunk.sampleHeightmap(Heightmap.Type.OCEAN_FLOOR_WG, 3 + dx, 3 + dz);
                for (int y = colFloor - 1; y < baseY - 1; y++) set(chunk, x, y, z, stone);
                set(chunk, x, baseY - 1, z, floorB);
                for (int y = baseY; y <= baseY + 6; y++) set(chunk, x, y, z, air);

                boolean edge = dx == 0 || dx == 8 || dz == 0 || dz == 8;
                if (edge) {
                    for (int h = 0; h < 4; h++) {
                        BlockState s = h == 3 ? accent : wall;
                        boolean window = (h == 1 || h == 2) && ((dz == 0 || dz == 8) ? (dx >= 2 && dx <= 6 && dx != 4) : (dz >= 2 && dz <= 6 && dz != 4));
                        set(chunk, x, baseY + h, z, window ? glass : s);
                    }
                }
                set(chunk, x, baseY + 4, z, wall);
            }
        }
        // свет
        set(chunk, x0 + 4, baseY + 4, z0 + 4, Blocks.SEA_LANTERN.getDefaultState());
        set(chunk, x0 + 2, baseY + 4, z0 + 2, Blocks.SEA_LANTERN.getDefaultState());
        set(chunk, x0 + 6, baseY + 4, z0 + 6, Blocks.SEA_LANTERN.getDefaultState());
        // дверь (южная стена, dz=8)
        BlockState door = Blocks.IRON_DOOR.getDefaultState().with(DoorBlock.FACING, Direction.SOUTH);
        set(chunk, x0 + 4, baseY, z0 + 8, door.with(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(chunk, x0 + 4, baseY + 1, z0 + 8, door.with(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        set(chunk, x0 + 4, baseY - 1, z0 + 9, floorB);
        // прилавок и бочки
        for (int dx = 2; dx <= 6; dx++) set(chunk, x0 + dx, baseY, z0 + 3, accent);
        set(chunk, x0 + 1, baseY, z0 + 1, Blocks.BARREL.getDefaultState());
        set(chunk, x0 + 7, baseY, z0 + 1, Blocks.BARREL.getDefaultState());
        return true;
    }
}
