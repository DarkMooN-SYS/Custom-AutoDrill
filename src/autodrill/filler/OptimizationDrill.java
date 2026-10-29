package autodrill.filler;

import arc.Core;
import arc.math.geom.Rect;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.production.Drill;

import static arc.Core.bundle;

public class OptimizationDrill {
    public static void fill(Tile tile, Drill drill) {
        fill(tile, drill, true, Direction.UP);
    }

    public static void fill(Tile tile, Drill drill, Direction direction) {
        fill(tile, drill, true, direction);
    }

    public static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes) {
        fill(tile, drill, waterExtractorsAndPowerNodes, Direction.UP);
    }

    public static void fill(Tile tile, Drill drill, Block conveyor, Direction direction) {
        fill(tile, drill, true, direction, conveyor);
    }

    private static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes,
            Direction direction) {
        fill(tile, drill, waterExtractorsAndPowerNodes, direction, null);
    }

    private static void fill(Tile tile, Drill drill, boolean waterExtractorsAndPowerNodes,
            Direction direction, Block conveyorOverride) {
        Team team = Vars.player.team();
        String drillPrefix = getDrillSettingsPrefix(drill);
        int maxTiles = Core.settings.getInt(drillPrefix + "-drill-max-tiles");

        Seq<Tile> tiles = Util.getConnectedTiles(tile, maxTiles);
        Util.expandArea(tiles, drill.size / 2);
        Seq<Tile> originalCandidates = tiles.copy();
        int minOresPerDrill = Core.settings.getInt(drillPrefix + "-drill-min-ores");
        Floor floor = tile.overlay() != Blocks.air ? tile.overlay() : tile.floor();
        Block conveyor = drill.size == 1
            ? (conveyorOverride != null ? conveyorOverride : Vars.content.block("titanium-conveyor"))
            : null;
        if (drill.size == 1 && conveyor == null) conveyor = Blocks.conveyor;
        final Block outputConveyor = conveyor;
        boolean useConveyors = conveyor != null;

        Seq<Integer> laneCoordinates = new Seq<>();
        if (useConveyors && tiles.size > 0) {
            boolean verticalLanes = direction == Direction.UP || direction == Direction.DOWN;
            int minAcross = verticalLanes ? tiles.min(t -> t.x).x : tiles.min(t -> t.y).y;
            int maxAcross = verticalLanes ? tiles.max(t -> t.x).x : tiles.max(t -> t.y).y;
            for (int across = minAcross; across <= maxAcross; across += 3) {
                laneCoordinates.add(across);
            }
        }

        ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount = new ObjectMap<>();
        ObjectIntMap<Tile> rotations = new ObjectIntMap<>();
        final boolean routeCandidateMode = useConveyors;
        for (Tile t : tiles) {
            tilesItemAndCount.put(t, Util.countOre(t, drill));
        }

        tiles.retainAll(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            if (itemAndCount == null || itemAndCount.key != floor.itemDrop
                || itemAndCount.value < minOresPerDrill) return false;
            int rotation = 0;
            if (routeCandidateMode) {
                boolean verticalLanes = direction == Direction.UP || direction == Direction.DOWN;
                Tile negativeSide = verticalLanes ? Vars.world.tile(t.x - 1, t.y) : Vars.world.tile(t.x, t.y - 1);
                Tile positiveSide = verticalLanes ? Vars.world.tile(t.x + 1, t.y) : Vars.world.tile(t.x, t.y + 1);
                int negativeAcross = verticalLanes ? t.x - 1 : t.y - 1;
                int positiveAcross = verticalLanes ? t.x + 1 : t.y + 1;
                if (negativeSide != null && laneCoordinates.contains(negativeAcross)
                    && canUseConveyorTile(negativeSide, outputConveyor, team)) {
                    rotation = verticalLanes ? 2 : 3;
                } else if (positiveSide != null && laneCoordinates.contains(positiveAcross)
                    && canUseConveyorTile(positiveSide, outputConveyor, team)) {
                    rotation = verticalLanes ? 0 : 1;
                } else {
                    return false;
                }
            }
            if (!Util.canPlaceBlock(drill, team, t.x, t.y, rotation)) return false;
            rotations.put(t, rotation);
            return true;
        }).sort(t -> {
            ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
            return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
        });

        // If the deposit is too narrow/blocked for a shared belt lane, keep
        // the previously working drill placement behavior for that deposit.
        if (useConveyors && tiles.isEmpty()) {
            useConveyors = false;
            rotations.clear();
            laneCoordinates.clear();
            tiles.addAll(originalCandidates);
            tiles.retainAll(t -> {
                ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
                if (itemAndCount == null || itemAndCount.key != floor.itemDrop
                    || itemAndCount.value < minOresPerDrill
                    || !Util.canPlaceBlock(drill, team, t.x, t.y, 0)) return false;
                rotations.put(t, 0);
                return true;
            }).sort(t -> {
                ObjectIntMap.Entry<Item> itemAndCount = tilesItemAndCount.get(t);
                return itemAndCount == null ? Integer.MIN_VALUE : -itemAndCount.value;
            });
        }

        Seq<BuildPlan> allPlans = new Seq<>();
        Seq<Tile> selection = new Seq<>();
        int maxTries = Core.settings.getInt(bundle.get("auto-drill.settings.optimization-quality")) * 1000;

        recursiveMaxSearch(tiles, drill, team, tilesItemAndCount, selection,
            new Seq<>(), 0, new Seq<>(), maxTries, 0);

        // Reserve drill footprints first. Previously conveyor plans were added
        // first, so a collector lane could occupy a selected drill tile and
        // silently prevent that drill from being queued.
        for (Tile t : selection) {
            BuildPlan plan = new BuildPlan(t.x, t.y, rotations.get(t, 0), drill);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) allPlans.add(plan);
        }

        if (useConveyors) {
            placeCompactConveyorLanes(selection, rotations, laneCoordinates, outputConveyor,
                direction, team, allPlans);
        }

        if (waterExtractorsAndPowerNodes
            && Core.settings.getBool(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes"))) {
            placeWaterExtractorsAndPowerNodes(selection, drill, team, allPlans);
        }

        Util.commitPlans(allPlans);
        if (Util.DEBUG) {
            Log.info("[AutoDrill] OptimizationDrill: committed " + allPlans.size
                + " plans for " + drill.name);
        }
    }

    private static boolean canUseConveyorTile(Tile tile, Block conveyor, Team team) {
        return tile.block() == conveyor || Util.canPlaceBlock(conveyor, team, tile.x, tile.y, 0);
    }

    /**
     * Extend each active drill lane only as far as needed. If a conveyor
     * already exists nearby, connect to that network using a short L-shaped
     * route instead of forcing every lane all the way to one distant outlet.
     * Otherwise use a compact collector with belt directions matching each
     * segment's actual flow.
     */
    private static void placeCompactConveyorLanes(Seq<Tile> selection,
            ObjectIntMap<Tile> rotations, Seq<Integer> laneCoordinates, Block conveyor,
            Direction direction, Team team, Seq<BuildPlan> plans) {
        boolean verticalLanes = direction == Direction.UP || direction == Direction.DOWN;
        boolean towardPositive = direction == Direction.UP || direction == Direction.RIGHT;
        Seq<LaneSpan> spans = new Seq<>();

        for (int lane : laneCoordinates) {
            int minFeed = Integer.MAX_VALUE;
            int maxFeed = Integer.MIN_VALUE;
            for (Tile drill : selection) {
                int rotation = rotations.get(drill, 0);
                int feedLane = verticalLanes
                    ? drill.x + (rotation == 2 ? -1 : 1)
                    : drill.y + (rotation == 3 ? -1 : 1);
                if (feedLane != lane) continue;
                int along = verticalLanes ? drill.y : drill.x;
                minFeed = Math.min(minFeed, along);
                maxFeed = Math.max(maxFeed, along);
            }
            if (minFeed != Integer.MAX_VALUE) spans.add(new LaneSpan(lane, minFeed, maxFeed));
        }
        if (spans.isEmpty()) return;
        spans.sort(s -> s.coordinate);

        // Resolve targets before adding plans. Only replace the shared collector
        // when every lane can reach an existing belt; otherwise keep the safe
        // collector fallback so no lane is left disconnected.
        int[][] targets = new int[spans.size][];
        int connectedCount = 0;
        for (int i = 0; i < spans.size; i++) {
            LaneSpan span = spans.get(i);
            int edge = towardPositive ? span.maxFeed : span.minFeed;
            targets[i] = findNearbyConveyor(span.coordinate, edge, verticalLanes,
                towardPositive, conveyor, 8);
            if (targets[i] != null) connectedCount++;
        }
        // Route each lane independently when it can reach an existing belt.
        // Previously this was all-or-nothing: a single lane without a nearby
        // target forced every lane back into one shared collector, leaving
        // nearby outlets unused and creating long, unnecessary runs.
        if (connectedCount > 0) {
            Seq<Integer> unresolvedLanes = new Seq<>();
            for (int i = 0; i < spans.size; i++) {
                LaneSpan span = spans.get(i);
                int[] target = targets[i];
                if (target == null) {
                    unresolvedLanes.add(span.coordinate);
                    continue;
                }

                int edge = towardPositive ? span.maxFeed : span.minFeed;
                if (verticalLanes) {
                    addConveyorLine(span.coordinate, span.minFeed, span.coordinate, span.maxFeed,
                        direction.r, conveyor, team, plans);
                    addConveyorPath(span.coordinate, edge, target[0], target[1],
                        direction, true, conveyor, team, plans);
                } else {
                    addConveyorLine(span.minFeed, span.coordinate, span.maxFeed, span.coordinate,
                        direction.r, conveyor, team, plans);
                    addConveyorPath(edge, span.coordinate, target[0], target[1],
                        direction, false, conveyor, team, plans);
                }
            }

            if (unresolvedLanes.isEmpty()) return;

            Seq<Tile> unresolvedDrills = new Seq<>();
            for (Tile drill : selection) {
                int rotation = rotations.get(drill, 0);
                int feedLane = verticalLanes
                    ? drill.x + (rotation == 2 ? -1 : 1)
                    : drill.y + (rotation == 3 ? -1 : 1);
                if (unresolvedLanes.contains(feedLane)) unresolvedDrills.add(drill);
            }
            placeCompactConveyorLanes(unresolvedDrills, rotations, unresolvedLanes,
                conveyor, direction, team, plans);
            return;
        }

        int exit = spans.get(spans.size / 2).coordinate;
        if (verticalLanes) {
            int merge = towardPositive
                ? medianFeed(spans, true) + 1
                : medianFeed(spans, false) - 1;

            for (LaneSpan span : spans) {
                // Keep the main lane aligned to this row's actual drill span.
                // Only add the extra segment needed to reach the shared collector.
                addConveyorLine(span.coordinate, span.minFeed, span.coordinate, span.maxFeed,
                    direction.r, conveyor, team, plans);
                if (towardPositive) {
                    addConveyorLine(span.coordinate, span.maxFeed + 1,
                        span.coordinate, merge - 1, direction.r, conveyor, team, plans);
                } else {
                    addConveyorLine(span.coordinate, merge + 1,
                        span.coordinate, span.minFeed - 1, direction.r, conveyor, team, plans);
                }
            }

            int minLane = spans.first().coordinate;
            int maxLane = spans.peek().coordinate;
            for (int x = minLane; x <= maxLane; x++) {
                int crossRotation = x < exit ? 0 : x > exit ? 2 : direction.r;
                addConveyor(x, merge, crossRotation, conveyor, team, plans);
            }
            addConveyor(exit, merge + direction.p.y, direction.r, conveyor, team, plans);
        } else {
            int merge = towardPositive
                ? medianFeed(spans, true) + 1
                : medianFeed(spans, false) - 1;

            for (LaneSpan span : spans) {
                // Keep the main lane aligned to this row's actual drill span.
                // Only add the extra segment needed to reach the shared collector.
                addConveyorLine(span.minFeed, span.coordinate, span.maxFeed, span.coordinate,
                    direction.r, conveyor, team, plans);
                if (towardPositive) {
                    addConveyorLine(span.maxFeed + 1, span.coordinate,
                        merge - 1, span.coordinate, direction.r, conveyor, team, plans);
                } else {
                    addConveyorLine(merge + 1, span.coordinate,
                        span.minFeed - 1, span.coordinate, direction.r, conveyor, team, plans);
                }
            }

            int minLane = spans.first().coordinate;
            int maxLane = spans.peek().coordinate;
            for (int y = minLane; y <= maxLane; y++) {
                int crossRotation = y < exit ? 1 : y > exit ? 3 : direction.r;
                addConveyor(merge, y, crossRotation, conveyor, team, plans);
            }
            addConveyor(merge + direction.p.x, exit, direction.r, conveyor, team, plans);
        }
    }

    /** Find the closest existing conveyor near a lane's feed edge. */
    private static int[] findNearbyConveyor(int lane, int edge, boolean verticalLanes,
            boolean towardPositive, Block conveyor, int maxDistance) {
        int originX = verticalLanes ? lane : edge;
        int originY = verticalLanes ? edge : lane;
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -maxDistance; dx <= maxDistance; dx++) {
            for (int dy = -maxDistance; dy <= maxDistance; dy++) {
                int distance = Math.abs(dx) + Math.abs(dy);
                if (distance == 0 || distance > maxDistance || distance >= bestDistance) continue;
                Tile tile = Vars.world.tile(originX + dx, originY + dy);
                if (tile != null && tile.block() == conveyor) {
                    best = new int[]{originX + dx, originY + dy};
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    /** Join a lane to a nearby conveyor with a short L-shaped connector. */
    private static void addConveyorPath(int x1, int y1, int x2, int y2,
            Direction outputDirection, boolean verticalLanes, Block conveyor,
            Team team, Seq<BuildPlan> plans) {
        int firstX = x2;
        int firstY = y1;
        if (verticalLanes) {
            // Turn sideways first, then enter the target belt from its side.
            addConveyorLine(x1, y1, x2, y1, x2 >= x1 ? 0 : 2, conveyor, team, plans);
            addConveyorLine(x2, y1, x2, y2,
                y2 >= y1 ? 1 : 3, conveyor, team, plans);
        } else {
            addConveyorLine(x1, y1, x1, y2,
                y2 >= y1 ? 1 : 3, conveyor, team, plans);
            addConveyorLine(x1, y2, x2, y2,
                x2 >= x1 ? 0 : 2, conveyor, team, plans);
        }
    }

    /**
     * Place a straight conveyor run. If a planned drill/building blocks a tile,
     * try to bridge across the blocked tile(s) with an item bridge pair three
     * tiles apart. Bridge endpoints are reserved only when both can be placed.
     */
    private static void addConveyorLine(int x1, int y1, int x2, int y2, int rotation,
            Block conveyor, Team team, Seq<BuildPlan> plans) {
        int dx = Integer.compare(x2, x1);
        int dy = Integer.compare(y2, y1);
        int length = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (length == 0) {
            addConveyor(x1, y1, rotation, conveyor, team, plans);
            return;
        }

        int i = 0;
        while (i <= length) {
            int x = x1 + dx * i;
            int y = y1 + dy * i;
            Tile tile = Vars.world.tile(x, y);
            BuildPlan belt = new BuildPlan(x, y, rotation, conveyor);
            if (tile != null && (tile.block() == conveyor
                    || Util.canPlaceWithoutPlanCollision(belt, team, plans))) {
                if (tile.block() != conveyor) plans.add(belt);
                i++;
                continue;
            }

            // Need a gap of two tiles between bridge endpoints (distance 3).
            // Reuse the previous belt as the first bridge endpoint.
            if (i > 0 && i + 2 <= length) {
                int sx = x1 + dx * (i - 1);
                int sy = y1 + dy * (i - 1);
                int ex = x1 + dx * (i + 2);
                int ey = y1 + dy * (i + 2);
                BuildPlan startBridge = new BuildPlan(sx, sy, 0, Blocks.itemBridge,
                    new arc.math.geom.Point2(ex - sx, ey - sy));
                BuildPlan endBridge = new BuildPlan(ex, ey, 0, Blocks.itemBridge,
                    new arc.math.geom.Point2());
                int previousIndex = -1;
                for (int p = 0; p < plans.size; p++) {
                    BuildPlan existing = plans.get(p);
                    if (existing.x == sx && existing.y == sy && existing.block == conveyor) {
                        previousIndex = p;
                        break;
                    }
                }
                if (previousIndex >= 0) {
                    BuildPlan old = plans.remove(previousIndex);
                    boolean startFree = Util.canPlaceBlock(Blocks.itemBridge, team, sx, sy, 0)
                        && !Util.footprintOverlapsPlanned(Blocks.itemBridge, sx, sy, plans);
                    boolean endFree = Util.canPlaceWithoutPlanCollision(endBridge, team, plans);
                    if (startFree && endFree) {
                        plans.add(startBridge);
                        plans.add(endBridge);
                        i += 3;
                        continue;
                    }
                    plans.add(old);
                }
            }

            // A blocked cell that cannot be bridged is left empty rather than
            // placing a belt over a drill or another planned structure.
            i++;
        }
    }

    private static void addConveyor(int x, int y, int rotation, Block conveyor,
            Team team, Seq<BuildPlan> plans) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null || tile.block() == conveyor) return;
        BuildPlan plan = new BuildPlan(x, y, rotation, conveyor);
        if (Util.canPlaceWithoutPlanCollision(plan, team, plans)) plans.add(plan);
    }

    /** Choose the median row edge so the shared collector does not force every
     * shorter lane to extend all the way to the single longest row. */
    private static int medianFeed(Seq<LaneSpan> spans, boolean maximum) {
        Seq<Integer> edges = new Seq<>();
        for (LaneSpan span : spans) edges.add(maximum ? span.maxFeed : span.minFeed);
        edges.sort(Integer::compare);
        return edges.get(edges.size / 2);
    }

    private static class LaneSpan {
        final int coordinate;
        final int minFeed;
        final int maxFeed;

        LaneSpan(int coordinate, int minFeed, int maxFeed) {
            this.coordinate = coordinate;
            this.minFeed = minFeed;
            this.maxFeed = maxFeed;
        }
    }

    private static int recursiveMaxSearch(
            Seq<Tile> tiles, Drill drill, Team team,
            ObjectMap<Tile, ObjectIntMap.Entry<Item>> tilesItemAndCount,
            Seq<Tile> selection, Seq<Rect> rects, int sum,
            Seq<Integer> triesPerLevel, final int maxTries, final int level) {

        int max = sum;
        Seq<Tile> maxSelection = selection.copy();
        if (triesPerLevel.size < level + 1) {
            triesPerLevel.setSize(level + 1);
            triesPerLevel.set(level, 0);
        }

        for (Tile tile : tiles) {
            Rect rect = Util.getBlockRect(tile, drill);
            if (rects.isEmpty() || rects.find(r -> r.overlaps(rect)) == null) {
                int newSum = sum + tilesItemAndCount.get(tile).value;
                Seq<Tile> newSelection = selection.copy().add(tile);
                Seq<Rect> newRects = rects.copy().add(rect);
                int newMax = recursiveMaxSearch(tiles, drill, team, tilesItemAndCount,
                    newSelection, newRects, newSum, triesPerLevel, maxTries, level + 1);

                if (newMax > max) {
                    max = newMax;
                    maxSelection = newSelection.copy();
                }
                triesPerLevel.set(level, triesPerLevel.get(level) + 1);
                if (triesPerLevel.get(level) >= maxTries / Math.pow(2, level + 1)) break;
            }
        }

        selection.clear();
        selection.addAll(maxSelection);
        return max;
    }

    private static void placeWaterExtractorsAndPowerNodes(
            Seq<Tile> selection, Drill drill, Team team, Seq<BuildPlan> allPlans) {
        if (drill.size > 1 && drill.consumesLiquid(Liquids.water)) {
            for (Tile t : selection) {
                Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.waterExtractor.size);
                for (Tile n : nearby) {
                    BuildPlan plan = new BuildPlan(n.x, n.y, 0, Blocks.waterExtractor);
                    if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                        allPlans.add(plan);
                        break;
                    }
                }
            }
        }

        if (drill.consPower != null) {
            for (Tile t : selection) {
                Seq<Tile> nearby = Util.getNearbyTiles(t.x, t.y, drill.size, Blocks.powerNode.size);
                for (Tile n : nearby) {
                    BuildPlan plan = new BuildPlan(n.x, n.y, 0, Blocks.powerNode);
                    if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) {
                        allPlans.add(plan);
                        break;
                    }
                }
            }
        }
    }

    private static String getDrillSettingsPrefix(Drill drill) {
        if (drill == Blocks.mechanicalDrill) return "mechanical";
        if (drill == Blocks.pneumaticDrill) return "pneumatic";
        if (drill == Blocks.laserDrill) return "laser";
        if (drill == Blocks.blastDrill) return "airblast";
        if (drill == Blocks.impactDrill) return "airblast";
        if (drill == Blocks.eruptionDrill) return "airblast";
        return "mechanical";
    }
}
