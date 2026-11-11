package com.cinefile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.TreeSet;

public final class Trajectory {
    private final String uuid;
    private final String name;
    private int totalTicks;
    private String playerUuid;
    private List<Trajectory> players = List.of(this);
    final TreeMap<Integer, Pose> poses = new TreeMap<>();
    final List<Integer> cuts = new ArrayList<>();
    final List<Integer> dimensionChanges = new ArrayList<>();
    final TreeSet<Integer> presenceBreaks = new TreeSet<>();

    Trajectory(String uuid, String name, int totalTicks) {
        this.uuid = uuid;
        this.name = name;
        this.totalTicks = totalTicks;
    }

    void setTotalTicks(int n) {
        totalTicks = n;
    }

    /** Pose at the tick, else the nearest earlier one, else null. */
    public Pose sample(int tick) {
        Map.Entry<Integer, Pose> e = poses.floorEntry(tick);
        return e == null ? null : e.getValue();
    }

    public NavigableMap<Integer, Pose> poses() {
        return poses;
    }

    public int totalTicks() {
        return totalTicks;
    }

    public String uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public String playerUuid() {
        return playerUuid;
    }

    void setPlayerUuid(String id) {
        playerUuid = id;
    }

    void setPlayers(List<Trajectory> all) {
        players = List.copyOf(all);
    }

    public List<Trajectory> players() {
        return players;
    }

    public Trajectory player(String id) {
        for (Trajectory t : players) if (id != null && id.equals(t.playerUuid)) return t;
        return null;
    }

    public List<Integer> presenceBreaks() {
        return List.copyOf(presenceBreaks);
    }

    /** Ticks where a teleport starts, ascending. */
    public List<Integer> cuts() {
        return cuts;
    }

    /** Ticks where the tracked player's level changes, ascending. */
    public List<Integer> dimensionChanges() {
        return dimensionChanges;
    }
}
