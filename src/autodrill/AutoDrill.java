package autodrill;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.math.geom.Vec2;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Scaling;
import autodrill.filler.BridgeDrill;
import autodrill.filler.Direction;
import autodrill.filler.OptimizationDrill;
import autodrill.filler.WallDrill;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.graphics.Pal;
import mindustry.mod.Mod;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.SettingsMenuDialog;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.production.BeamDrill;
import mindustry.world.blocks.production.Drill;

import java.util.Arrays;

import static arc.Core.bundle;
import static mindustry.Vars.state;
import static mindustry.Vars.ui;

public class AutoDrill extends Mod {
    private static final int buttonSize = 30;

    private boolean enabled = false;

    private Tile selectedTile;

    private final Table selectTable = new Table(Styles.black3);
    private final Table conveyorTable = new Table(Styles.black3);
    private final Table directionTable = new Table(Styles.black3);
    private ImageButton enableButton;

    private Cons<Direction> directionAction;
    private Drill pendingDrill;
    private Block selectedConveyor;

    private ImageButton mechanicalDrillButton, pneumaticDrillButton, blastDrillButton, laserDrillButton, plasmaBoreButton, largePlasmaBoreButton, impactDrillButton, eruptionDrillButton;
    private final Seq<Drill> customDrills = new Seq<>();
    private final Seq<ImageButton> customDrillButtons = new Seq<>();

    @Override
    public void init() {
        // Tutorial
        if (!Core.settings.getBool("auto-drill")) {
            BaseDialog baseDialog = new BaseDialog(bundle.get("auto-drill-welcome-title"));

            Table t = new Table();

            t.labelWrap(bundle.get("auto-drill-welcome-text")).growX().fillX().padTop(10).row();
            t.labelWrap(bundle.get("auto-drill-tutorial-text")).growX().fillX().padTop(10).row();

            float maxWidth = Math.max(Core.scene.getWidth() * 0.5f, Math.min(1000, Core.scene.getWidth()));

            t.image(new TextureRegionDrawable(Core.atlas.find("auto-drill-tutorial"))).maxWidth(maxWidth).scaling(Scaling.fit).padTop(10).get().setWidth(maxWidth);
            t.row();
            t.labelWrap(bundle.get("auto-drill-tutorial2-text")).growX().fillX().padTop(10).row();
            t.image(new TextureRegionDrawable(Core.atlas.find("auto-drill-tutorial2"))).maxWidth(maxWidth).scaling(Scaling.fit).padTop(10).get().setWidth(maxWidth);
            t.row();
            t.labelWrap(bundle.get("auto-drill-settings-text")).growX().fillX().padTop(10).row();
            t.image(new TextureRegionDrawable(Core.atlas.find("auto-drill-settings"))).maxWidth(maxWidth).scaling(Scaling.fit).padTop(10).get().setWidth(maxWidth);
            t.row();
            t.labelWrap(bundle.get("auto-drill-conclusion-text")).growX().fillX().padTop(10).row();

            ScrollPane p = new ScrollPane(t);
            baseDialog.cont.top().add(p).growX().pad(0, 10, 0, 10).maxWidth(maxWidth);

            baseDialog.addCloseButton();
            baseDialog.show();

            Core.settings.put("auto-drill", true);
        }

        buildSelectTable();
        buildConveyorTable();
        buildDirectionTable();

        // A server map transition may rebuild content and invalidate cached
        // custom block icons. Drop the old selector actors before the next map.
        Events.on(EventType.GameOverEvent.class, event -> resetDrillSelector());
        Events.on(EventType.WorldLoadBeginEvent.class, event -> resetDrillSelector());

        // Settings
        Cons<SettingsMenuDialog.SettingsTable> builder = settingsTable -> {
            SettingsMenuDialog.SettingsTable settings = new SettingsMenuDialog.SettingsTable();

            settings.pref(new DescriptionSetting(bundle.get("auto-drill.settings.activation-desc")));
            settings.textPref(bundle.get("auto-drill.settings.activation-key"), KeyCode.h.name().toUpperCase(), s -> {
                KeyCode keyCode = Arrays.stream(KeyCode.values()).filter(k -> k.value.equalsIgnoreCase(s)).findFirst().orElse(null);
                Core.settings.put(bundle.get("auto-drill.settings.activation-key"), keyCode == null ? KeyCode.h.name().toUpperCase() : keyCode.name().toUpperCase());
            });
            settings.checkPref(bundle.get("auto-drill.settings.display-toggle-button"), true);
            settings.pref(new DividerSetting());

            settings.pref(new DescriptionSetting(bundle.get("auto-drill.settings.drills-desc")));

            settings.pref(new DescriptionSetting("\uF870 " + bundle.get("auto-drill.settings.mechanical-drill")));
            settings.sliderPref("mechanical-drill-max-tiles", 200, 25, 500, 25, value -> value + "").title = bundle.get("auto-drill.settings.max-tiles");
            settings.sliderPref("mechanical-drill-min-ores", 1, 1, 4, 1, value -> value + "").title = bundle.get("auto-drill.settings.min-ores");

            settings.pref(new DescriptionSetting("\uF86F " + bundle.get("auto-drill.settings.pneumatic-drill")));
            settings.sliderPref("pneumatic-drill-max-tiles", 150, 25, 500, 25, value -> value + "").title = bundle.get("auto-drill.settings.max-tiles");
            settings.sliderPref("pneumatic-drill-min-ores", 2, 1, 4, 1, value -> value + "").title = bundle.get("auto-drill.settings.min-ores");

            settings.pref(new DescriptionSetting("\uF86E " + bundle.get("auto-drill.settings.laser-drill")));
            settings.sliderPref("laser-drill-max-tiles", 100, 25, 500, 25, value -> value + "").title = bundle.get("auto-drill.settings.max-tiles");
            settings.sliderPref("laser-drill-min-ores", 5, 1, 9, 1, value -> value + "").title = bundle.get("auto-drill.settings.min-ores");

            settings.pref(new DescriptionSetting("\uF86D " + bundle.get("auto-drill.settings.airblast-drill")));
            settings.sliderPref("airblast-drill-max-tiles", 100, 25, 500, 25, value -> value + "").title = bundle.get("auto-drill.settings.max-tiles");
            settings.sliderPref("airblast-drill-min-ores", 9, 1, 16, 1, value -> value + "").title = bundle.get("auto-drill.settings.min-ores");

            settings.pref(new DividerSetting());
            settings.pref(new DescriptionSetting(bundle.get("auto-drill.settings.optimization-quality-desc")));
            settings.sliderPref(bundle.get("auto-drill.settings.optimization-quality"), 2, 1, 10, 1, i -> i + "");
            settings.checkPref(bundle.get("auto-drill.settings.place-water-extractor-and-power-nodes"), true);

            settingsTable.add(settings);
        };
        ui.settings.getCategories().add(new SettingsMenuDialog.SettingsCategory(bundle.get("auto-drill.settings.title"), new TextureRegionDrawable(Core.atlas.find("auto-drill-logo")), builder));

        // Activation
        Core.scene.addListener(new InputListener() {
            @Override
            public boolean keyDown(InputEvent event, KeyCode keyCode) {
                if (!state.isMenu() &&
                        !ui.chatfrag.shown() &&
                        !ui.schematics.isShown() &&
                        !ui.database.isShown() &&
                        !ui.consolefrag.shown() &&
                        !ui.content.isShown() &&
                        !Core.scene.hasKeyboard()) {
                    if (Core.settings.getString(bundle.get("auto-drill.settings.activation-key")).equalsIgnoreCase(keyCode.value)) {
                        enabled = !enabled;
                        selectTable.visible = false;
                        conveyorTable.visible = false;
                        directionTable.visible = false;
                        enableButton.setChecked(enabled);
                    }
                }

                return super.keyDown(event, keyCode);
            }
        });

        // Handling
        Events.on(EventType.TapEvent.class, event -> {
            if (enabled) {
                selectTable.visible = true;
                selectedTile = event.tile;

                updateSelectTable();

                Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize, (selectedTile.centerY() + 1) * Vars.tilesize);
                selectTable.setPosition(v.x, v.y, Align.bottom);
                conveyorTable.setPosition(v.x, v.y, Align.bottom);
                directionTable.setPosition(v.x, v.y, Align.bottom);

                Fx.tapBlock.at(selectedTile.getX(), selectedTile.getY());
            }
        });

        ui.hudGroup.fill(t -> {
            enableButton = t.button(new TextureRegionDrawable(Core.atlas.find("auto-drill-logo")), Styles.emptyTogglei, () -> {
                enabled = !enabled;
                selectTable.visible = false;
                conveyorTable.visible = false;
                directionTable.visible = false;
            }).get();
            enableButton.resizeImage(buttonSize);
            enableButton.visible(() -> Core.settings.getBool(bundle.get("auto-drill.settings.display-toggle-button")));

            t.margin(5f);
            t.marginRight(140f + 15f);
            t.top().right();
        });
    }

    private void buildSelectTable() {
        selectTable.update(() -> {
            if (Vars.state.isMenu()) {
                selectTable.visible = false;
                return;
            }
            Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize, (selectedTile.centerY() + 1) * Vars.tilesize);
            selectTable.setPosition(v.x, v.y, Align.bottom);
        });

        mechanicalDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-mechanical-drill-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            directionTable.visible = true;
            directionAction = direction -> BridgeDrill.fill(selectedTile, (Drill) Blocks.mechanicalDrill, direction);
        }).get();
        mechanicalDrillButton.resizeImage(buttonSize);

        pneumaticDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-pneumatic-drill-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            directionTable.visible = true;
            directionAction = direction -> BridgeDrill.fill(selectedTile, (Drill) Blocks.pneumaticDrill, direction);
        }).get();
        pneumaticDrillButton.resizeImage(buttonSize);

        laserDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-laser-drill-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            OptimizationDrill.fill(selectedTile, (Drill) Blocks.laserDrill);
        }).get();
        laserDrillButton.resizeImage(buttonSize);

        blastDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-blast-drill-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            OptimizationDrill.fill(selectedTile, (Drill) Blocks.blastDrill);
        }).get();
        blastDrillButton.resizeImage(buttonSize);

        plasmaBoreButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-plasma-bore-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            directionTable.visible = true;
            directionAction = direction -> WallDrill.fill(selectedTile, (BeamDrill) Blocks.plasmaBore, direction);
        }).get();
        plasmaBoreButton.resizeImage(buttonSize);

        /*largePlasmaBoreButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-large-plasma-bore-full")), Styles.defaulti, () -> {
            selectTable.visible = false;
            directionTable.visible = true;
            directionAction = direction -> WallDrill.fill(selectedTile, (BeamDrill) Blocks.largePlasmaBore, direction);
        }).get();
        largePlasmaBoreButton.resizeImage(buttonSize);*/

        impactDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-impact-drill-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            OptimizationDrill.fill(selectedTile, (Drill) Blocks.impactDrill, false);
        }).get();
        impactDrillButton.resizeImage(buttonSize);

        eruptionDrillButton = selectTable.button(new TextureRegionDrawable(Core.atlas.find("block-eruption-drill-full")), Styles.defaulti, () -> {
            enabled = false;
            selectTable.visible = false;
            OptimizationDrill.fill(selectedTile, (Drill) Blocks.eruptionDrill, false);
        }).get();
        eruptionDrillButton.resizeImage(buttonSize);

        Core.input.addProcessor(new InputProcessor() {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
                if (!selectTable.hasMouse()) selectTable.visible = false;

                return InputProcessor.super.touchDown(screenX, screenY, pointer, button);
            }
        });

        selectTable.pack();
        selectTable.act(0);
        Core.scene.root.addChildAt(0, selectTable);
    }

    private void updateSelectTable() {
        addNewCustomDrills();

        // Rebuild the button cells as well as their actors. Removing only
        // actors left empty Table cells behind, which appeared as black boxes
        // every time the selector was toggled.
        selectTable.clearChildren();
        if (Blocks.mechanicalDrill.environmentBuildable() && ((Drill) Blocks.mechanicalDrill).canMine(selectedTile)) {
            selectTable.add(mechanicalDrillButton);
        }

        if (Blocks.pneumaticDrill.environmentBuildable() && ((Drill) Blocks.pneumaticDrill).canMine(selectedTile)) {
            selectTable.add(pneumaticDrillButton);
        }

        if (Blocks.laserDrill.environmentBuildable() && ((Drill) Blocks.laserDrill).canMine(selectedTile)) {
            selectTable.add(laserDrillButton);
        }

        if (Blocks.blastDrill.environmentBuildable() && ((Drill) Blocks.blastDrill).canMine(selectedTile)) {
            selectTable.add(blastDrillButton);
        }

        if (Blocks.plasmaBore.environmentBuildable() && selectedTile.wallDrop() != null && selectedTile.wallDrop().hardness <= ((BeamDrill) Blocks.plasmaBore).tier) {
            selectTable.add(plasmaBoreButton);
        }

        /*selectTable.removeChild(largePlasmaBoreButton);
        if (Blocks.largePlasmaBore.environmentBuildable() && selectedTile.wallDrop() != null && selectedTile.wallDrop().hardness <= ((BeamDrill)Blocks.largePlasmaBore).tier) {
            selectTable.add(largePlasmaBoreButton);
        }*/

        if (Blocks.impactDrill.environmentBuildable() && ((Drill) Blocks.impactDrill).canMine(selectedTile)) {
            selectTable.add(impactDrillButton);
        }

        if (Blocks.eruptionDrill.environmentBuildable() && ((Drill) Blocks.eruptionDrill).canMine(selectedTile)) {
            selectTable.add(eruptionDrillButton);
        }

        for (int i = 0; i < customDrills.size; i++) {
            Drill drill = customDrills.get(i);
            ImageButton button = customDrillButtons.get(i);
            if (drill.environmentBuildable() && canMineNearby(drill, selectedTile)) {
                selectTable.add(button);
            }
        }

        selectTable.pack();
    }

    private void addNewCustomDrills() {
        for (Block block : Vars.content.blocks()) {
            if (!(block instanceof Drill)) continue;
            Drill drill = (Drill) block;
            if (isVanillaDrill(drill) || customDrills.contains(drill, true)) continue;
            if (drill.uiIcon == null || !drill.uiIcon.found()) continue;

            customDrills.add(drill);
            ImageButton button = selectTable.button(new TextureRegionDrawable(drill.uiIcon), Styles.defaulti, () -> {
                enabled = false;
                selectTable.visible = false;
                if (drill.size == 1) {
                    pendingDrill = drill;
                    conveyorTable.visible = true;
                    directionTable.visible = false;
                    Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize,
                        (selectedTile.centerY() + 1) * Vars.tilesize);
                    conveyorTable.setPosition(v.x, v.y, Align.bottom);
                } else {
                    OptimizationDrill.fill(selectedTile, drill);
                }
            }).get();
            button.resizeImage(buttonSize);
            customDrillButtons.add(button);
        }
    }

    private void resetDrillSelector() {
        enabled = false;
        selectedTile = null;
        pendingDrill = null;
        selectedConveyor = null;
        selectTable.visible = false;
        conveyorTable.visible = false;
        directionTable.visible = false;
        customDrills.clear();
        customDrillButtons.clear();
        selectTable.clearChildren();
        if (enableButton != null) enableButton.setChecked(false);
    }

    private boolean canMineNearby(Drill drill, Tile tile) {
        if (drill.canMine(tile)) return true;
        for (Tile linked : tile.getLinkedTilesAs(drill, new Seq<>())) {
            if (drill.canMine(linked)) return true;
        }
        return false;
    }

    private boolean isVanillaDrill(Drill drill) {
        return drill == Blocks.mechanicalDrill || drill == Blocks.pneumaticDrill
            || drill == Blocks.laserDrill || drill == Blocks.blastDrill
            || drill == Blocks.impactDrill || drill == Blocks.eruptionDrill
            || drill == Blocks.plasmaBore || drill == Blocks.largePlasmaBore;
    }

    private void buildConveyorTable() {
        conveyorTable.update(() -> {
            if (Vars.state.isMenu() || selectedTile == null) {
                conveyorTable.visible = false;
                return;
            }
            Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize,
                (selectedTile.centerY() + 1) * Vars.tilesize);
            conveyorTable.setPosition(v.x, v.y, Align.bottom);
        });

        conveyorTable.button(new TextureRegionDrawable(Blocks.conveyor.uiIcon), Styles.defaulti, () -> {
            chooseConveyor(Blocks.conveyor);
        }).get().resizeImage(buttonSize);

        Block titaniumConveyor = Vars.content.block("titanium-conveyor");
        if (titaniumConveyor != null) {
            conveyorTable.button(new TextureRegionDrawable(titaniumConveyor.uiIcon), Styles.defaulti, () -> {
                chooseConveyor(titaniumConveyor);
            }).get().resizeImage(buttonSize);
        }

        conveyorTable.button(Icon.cancel, Styles.defaulti, () -> {
            conveyorTable.visible = false;
            pendingDrill = null;
        }).get().resizeImage(buttonSize);

        Core.input.addProcessor(new InputProcessor() {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
                if (!conveyorTable.hasMouse()) conveyorTable.visible = false;
                return InputProcessor.super.touchDown(screenX, screenY, pointer, button);
            }
        });

        conveyorTable.pack();
        conveyorTable.act(0);
        conveyorTable.visible = false;
        Core.scene.root.addChildAt(0, conveyorTable);
    }

    private void chooseConveyor(Block conveyor) {
        if (pendingDrill == null || selectedTile == null) {
            conveyorTable.visible = false;
            return;
        }
        selectedConveyor = conveyor;
        conveyorTable.visible = false;
        directionAction = direction -> OptimizationDrill.fill(
            selectedTile, pendingDrill, selectedConveyor, direction);
        directionTable.visible = true;
        Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize,
            (selectedTile.centerY() + 1) * Vars.tilesize);
        directionTable.setPosition(v.x, v.y, Align.bottom);
    }

    private void buildDirectionTable() {
        directionTable.update(() -> {
            if (Vars.state.isMenu()) {
                directionTable.visible = false;
                return;
            }
            Vec2 v = Core.camera.project(selectedTile.centerX() * Vars.tilesize, (selectedTile.centerY() + 1) * Vars.tilesize);
            directionTable.setPosition(v.x, v.y, Align.bottom);
        });

        directionTable.table().get().button(Icon.up, Styles.defaulti, () -> {
            directionAction.get(Direction.UP);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);

        directionTable.row();

        Table row2 = directionTable.table().get();

        row2.button(Icon.left, Styles.defaulti, () -> {
            directionAction.get(Direction.LEFT);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);
        row2.button(Icon.cancel, Styles.defaulti, () -> {
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);
        row2.button(Icon.right, Styles.defaulti, () -> {
            directionAction.get(Direction.RIGHT);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);

        directionTable.row();

        directionTable.table().get().button(Icon.down, Styles.defaulti, () -> {
            directionAction.get(Direction.DOWN);
            directionTable.visible = false;
        }).get().resizeImage(buttonSize);

        Core.input.addProcessor(new InputProcessor() {
            @Override
            public boolean touchDown(int screenX, int screenY, int pointer, KeyCode button) {
                if (!directionTable.hasMouse()) directionTable.visible = false;

                return InputProcessor.super.touchDown(screenX, screenY, pointer, button);
            }
        });

        directionTable.pack();
        directionTable.act(0);
        Core.scene.root.addChildAt(0, directionTable);
    }

    private static class DescriptionSetting extends SettingsMenuDialog.SettingsTable.Setting {
        String desc;

        public DescriptionSetting(String desc) {
            super(null);
            this.desc = desc;
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            table.labelWrap(this.desc).fillX().get().setWrap(true);
            table.row();
        }
    }

    private static class DividerSetting extends SettingsMenuDialog.SettingsTable.Setting {
        public DividerSetting() {
            super(null);
        }

        @Override
        public void add(SettingsMenuDialog.SettingsTable table) {
            table.image().growX().pad(10, 0, 10, 0).color(Pal.gray);
            table.row();
        }
    }
}
