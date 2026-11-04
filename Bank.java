package ua.kredyt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Простое хранилище: JSON-файл в папке мира (kredyt_bank.json). */
public class Bank {
    public static class Account {
        public long balance = 100;
        public long credit = 0;   // кредит, 5% в день
        public long micro = 0;    // мікрозайм, 30% в день
        public long lastDay = -1;
        public boolean apartment = false;
        public boolean gotPhone = false;
    }

    public Map<String, Account> accounts = new HashMap<>();
    /** ключ = ChunkPos.toLong() строкой, значение = имя Buildings.Type */
    public Map<String, String> buildings = new HashMap<>();

    public static Bank INSTANCE = new Bank();
    private static Path file;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Account acc(ServerPlayerEntity p) {
        return INSTANCE.accounts.computeIfAbsent(p.getUuidAsString(), k -> new Account());
    }

    public static void load(Path path) {
        file = path;
        INSTANCE = new Bank();
        if (Files.exists(path)) {
            try (Reader r = Files.newBufferedReader(path)) {
                Bank b = GSON.fromJson(r, Bank.class);
                if (b != null) {
                    if (b.accounts == null) b.accounts = new HashMap<>();
                    if (b.buildings == null) b.buildings = new HashMap<>();
                    INSTANCE = b;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void save() {
        if (file == null) return;
        try (Writer w = Files.newBufferedWriter(file)) {
            GSON.toJson(INSTANCE, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
