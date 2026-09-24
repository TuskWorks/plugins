package io.github.tuskworks.clans.model;

import java.util.UUID;

public record ClanMember(UUID id, String name, ClanRole role, long joinedAt) {

    public ClanMember withRole(ClanRole newRole) {
        return new ClanMember(id, name, newRole, joinedAt);
    }

    public ClanMember withName(String newName) {
        return new ClanMember(id, newName, role, joinedAt);
    }
}
