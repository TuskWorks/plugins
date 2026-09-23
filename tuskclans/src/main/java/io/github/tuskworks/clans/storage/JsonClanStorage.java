package io.github.tuskworks.clans.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.tuskworks.clans.model.Clan;
import io.github.tuskworks.clans.model.ClanHome;
import io.github.tuskworks.clans.model.ClanMember;
import io.github.tuskworks.clans.model.ClanRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One JSON file per clan, written atomically on a single background thread so writes
 * for the same clan are applied in order.
 */
public final class JsonClanStorage implements ClanStorage {

    private static final int FORMAT_VERSION = 1;

    private final Path dir;
    private final Logger logger;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TuskClans-storage");
        t.setDaemon(true);
        return t;
    });

    public JsonClanStorage(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
    }

    @Override
    public List<Clan> loadAll() throws IOException {
        Files.createDirectories(dir);
        List<Clan> clans = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                try {
                    ClanData data = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClanData.class);
                    clans.add(data.toClan());
                } catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException e) {
                    logger.log(Level.SEVERE, "Skipping unreadable clan file " + file.getFileName(), e);
                }
            }
        }
        return clans;
    }

    @Override
    public void save(Clan clan) {
        String json = gson.toJson(ClanData.of(clan));
        UUID id = clan.id();
        submit(() -> {
            Path target = file(id);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.createDirectories(dir);
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    @Override
    public void delete(UUID clanId) {
        submit(() -> Files.deleteIfExists(file(clanId)));
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.severe("Timed out waiting for clan data to be written");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Path file(UUID clanId) {
        return dir.resolve(clanId + ".json");
    }

    private void submit(IoTask task) {
        writer.execute(() -> {
            try {
                task.run();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to write clan data", e);
            }
        });
    }

    @FunctionalInterface
    private interface IoTask {
        void run() throws IOException;
    }

    // Plain DTOs keep the on-disk format independent from the domain classes.
    @SuppressWarnings("unused")
    private static final class ClanData {
        int format = FORMAT_VERSION;
        String id;
        String tag;
        String name;
        long createdAt;
        boolean friendlyFire;
        boolean open;
        int kills;
        int deaths;
        HomeData home;
        List<MemberData> members = new ArrayList<>();
        List<String> allies = new ArrayList<>();

        static ClanData of(Clan clan) {
            ClanData d = new ClanData();
            d.id = clan.id().toString();
            d.tag = clan.tag();
            d.name = clan.name();
            d.createdAt = clan.createdAt();
            d.friendlyFire = clan.friendlyFire();
            d.open = clan.open();
            d.kills = clan.kills();
            d.deaths = clan.deaths();
            d.home = clan.home().map(HomeData::of).orElse(null);
            for (ClanMember m : clan.members()) {
                d.members.add(MemberData.of(m));
            }
            for (UUID ally : clan.allies()) {
                d.allies.add(ally.toString());
            }
            return d;
        }

        Clan toClan() {
            Clan clan = new Clan(UUID.fromString(id), tag, name, createdAt);
            clan.friendlyFire(friendlyFire);
            clan.open(open);
            clan.kills(kills);
            clan.deaths(deaths);
            if (home != null) {
                clan.home(new ClanHome(home.world, home.x, home.y, home.z, home.yaw, home.pitch));
            }
            for (MemberData m : members) {
                clan.putMember(new ClanMember(UUID.fromString(m.id), m.name, ClanRole.valueOf(m.role), m.joinedAt));
            }
            for (String ally : allies) {
                clan.addAlly(UUID.fromString(ally));
            }
            clan.leader(); // fail fast on corrupt files without a leader
            return clan;
        }
    }

    private static final class MemberData {
        String id;
        String name;
        String role;
        long joinedAt;

        static MemberData of(ClanMember m) {
            MemberData d = new MemberData();
            d.id = m.id().toString();
            d.name = m.name();
            d.role = m.role().name();
            d.joinedAt = m.joinedAt();
            return d;
        }
    }

    private static final class HomeData {
        String world;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;

        static HomeData of(ClanHome h) {
            HomeData d = new HomeData();
            d.world = h.world();
            d.x = h.x();
            d.y = h.y();
            d.z = h.z();
            d.yaw = h.yaw();
            d.pitch = h.pitch();
            return d;
        }
    }
}
