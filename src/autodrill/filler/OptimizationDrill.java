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

        if (useConveyors) {
            placeCompactConveyorLanes(selection, rotations, laneCoordinates, outputConveyor,
                direction, team, allPlans);
        }

        for (Tile t : selection) {
            BuildPlan plan = new BuildPlan(t.x, t.y, rotations.get(t, 0), drill);
            if (Util.canPlaceWithoutPlanCollision(plan, team, allPlans)) allPlans.add(plan);
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

    /** Merge all active drill lanes into one short collector and one outlet. */
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
        int exit = spans.get(spans.size / 2).coordinate;

        if (verticalLanes) {
            int merge = towardPositive
                ? spans.max(s -> s.maxFeed).maxFeed + 1
                : spans.min(s -> s.minFeed).minFeed - 1;

            // Extend every active lane to the same merge row. Stopping each
            // lane at its own last drill leaves shorter lanes disconnected.
            for (LaneSpan span : spans) {
                int start = towardPositive ? span.minFeed : merge + 1;
                int end = towardPositive ? merge - 1 : span.maxFeed;
                for (int y = start; y <= end; y++) {
                    addConveyor(span.coordinate, y, direction.r, conveyor, team, plans);
                }
            }

            // One cross-belt merges the lane outputs. It flows toward the
            // middle lane, which becomes the single north/south outlet.
            int minLane = spans.first().coordinate;
            int maxLane = spans.peek().coordinate;
            for (int x = minLane; x <= maxLane; x++) {
                int rotation = x == exit ? direction.r : (x < exit ? Direction.RIGHT.r : Direction.LEFT.r);
                addConveyor(x, merge, rotation, conveyor, team, plans);
            }
            int outletX = exit;
            int outletY = merge + direction.p.y;
            addConveyor(outletX, outletY, direction.r, conveyor, team, plans);
        } else {
            int merge = towardPositive
                ? spans.max(s -> s.maxFeed).maxFeed + 1
                : spans.min(s -> s.minFeed).minFeed - 1;

            // Extend every active lane to the same merge column so no output
            // is left stranded before reaching the shared collector.
            for (LaneSpan span : spans) {
                int start = towardPositive ? span.minFeed : merge + 1;
                int end = towardPositive ? merge - 1 : span.maxFeed;
                for (int x = start; x <= end; x++) {
                    addConveyor(x, span.coordinate, direction.r, conveyor, team, plans);
                }
            }

            // A single vertical cross-belt collects every row and exits in
            // the chosen left/right direction at the center row.
            int minLane = spans.first().coordinate;
            int maxLane = spans.peek().coordinate;
            for (int y = minLane; y <= maxLane; y++) {
                int rotation = y == exit ? direction.r : (y < exit ? Direction.UP.r : Direction.DOWN.r);
                addConveyor(merge, y, rotation, conveyor, team, plans);
            }
            int outletX = merge + direction.p.x;
            addConveyor(outletX, exit, direction.r, conveyor, team, plans);
        }
    }

    private static void addConveyor(int x, int y, int rotation, Block conveyor,
            Team team, Seq<BuildPlan> plans) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null || tile.block() == conveyor) return;
        BuildPlan plan = new BuildPlan(x, y, rotation, conveyor);
        if (Util.canPlaceWithoutPlanCollision(plan, team, plans)) plans.add(plan);
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
