package xsamples.ah.sniper;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AH Sniper - alles in EINER Datei.
 * /snipe oeffnet das Menue: Item + Preis eingeben, "Start" druecken.
 * Die Mod konzentriert sich nur auf dieses eine Item, kauft das erste Angebot
 * bis zu deinem Preis und stoppt danach automatisch.
 */
public class AhSniperClient implements ClientModInitializer {

    private static boolean openRequested = false;
    private int cooldown = 0;
    private int pendingStop = 0;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("snipe").executes(ctx -> {
                    openRequested = true;
                    return 1;
                })));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ------------------------------------------------------------------ Logik

    private void tick(Minecraft mc) {
        if (openRequested) {
            openRequested = false;
            mc.setScreen(new SniperScreen());
            return;
        }
        Config cfg = Config.INSTANCE;
        if (!cfg.active || cfg.item == null || mc.player == null || mc.gameMode == null) return;

        // Nach dem Klick auf ein Angebot: kurz warten, dann stoppen
        if (pendingStop > 0) {
            pendingStop--;
            if (pendingStop == 0) finish(mc, "Kauf ausgelöst - Sniper gestoppt.");
        }

        if (!(mc.screen instanceof AbstractContainerScreen<?> scr)) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        String title = scr.getTitle().getString().toLowerCase();
        AbstractContainerMenu menu = scr.getMenu();

        // Bestaetigungsfenster: Slot mit "confirm" oder gruenem Item klicken
        if (title.contains("confirm")) {
            for (Slot s : menu.slots) {
                ItemStack st = s.getItem();
                if (st.isEmpty()) continue;
                String name = st.getHoverName().getString().toLowerCase();
                String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
                if (name.contains("confirm") || id.contains("lime")) {
                    click(mc, menu, s.index);
                    finish(mc, "Gekauft - Sniper gestoppt.");
                    return;
                }
            }
            return;
        }

        if (!title.contains("auction") || pendingStop > 0) return;

        // Nur Angebote mit dem Ziel-Item und Preis <= Limit
        for (Slot s : menu.slots) {
            if (s.index >= 45) break; // 5 Angebotsreihen
            ItemStack st = s.getItem();
            if (st.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
            if (!id.equals(cfg.item)) continue;
            double price = readPrice(st);
            if (price >= 0 && price <= cfg.maxPrice) {
                mc.player.displayClientMessage(Component.literal(
                        "[AH Sniper] Kaufe " + st.getHoverName().getString()
                                + " für " + Config.formatPrice(price)), false);
                click(mc, menu, s.index);
                cooldown = 5;
                pendingStop = 20; // falls keine Bestaetigung kommt, nach 1 Sekunde stoppen
                return;
            }
        }
    }

    private static void finish(Minecraft mc, String msg) {
        Config.INSTANCE.active = false;
        Config.INSTANCE.save();
        mc.player.displayClientMessage(Component.literal("[AH Sniper] " + msg), false);
    }

    private static void click(Minecraft mc, AbstractContainerMenu menu, int slot) {
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
    }

    /** Liest den Preis aus der Beschreibung (Zeile mit "$"). -1 = nichts gefunden. */
    private static double readPrice(ItemStack st) {
        ItemLore lore = st.get(DataComponents.LORE);
        if (lore == null) return -1;
        for (Component line : lore.lines()) {
            String text = line.getString();
            if (text.contains("$")) {
                double p = Config.parsePrice(text);
                if (p >= 0) return p;
            }
        }
        return -1;
    }

    // ----------------------------------------------------------------- Config

    public static class Entry {
        public String item;
        public double maxPrice;

        public Entry() {
        }

        public Entry(String item, double maxPrice) {
            this.item = item;
            this.maxPrice = maxPrice;
        }
    }

    public static class Config {
        public boolean active = false;
        public String item = null;
        public double maxPrice = 0;
        public List<Entry> history = new ArrayList<>();

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ahsniper.json");
        public static final Config INSTANCE = load();

        private static Config load() {
            try {
                if (Files.exists(FILE)) {
                    Config c = GSON.fromJson(Files.readString(FILE), Config.class);
                    if (c != null) {
                        if (c.history == null) c.history = new ArrayList<>();
                        c.active = false; // beim Spielstart nie automatisch aktiv
                        return c;
                    }
                }
            } catch (Exception ignored) {
            }
            return new Config();
        }

        public void save() {
            try {
                Files.writeString(FILE, GSON.toJson(this));
            } catch (Exception ignored) {
            }
        }

        public void addHistory(Entry e) {
            history.removeIf(h -> h.item.equals(e.item) && h.maxPrice == e.maxPrice);
            history.add(0, e);
            while (history.size() > 10) history.remove(history.size() - 1);
        }

        private static final Pattern PRICE =
                Pattern.compile("\\$?\\s*([0-9][0-9,]*\\.?[0-9]*)\\s*([kKmMbB]?)");

        /** "84M", "$ 1.5K", "250,000" -> Zahl. -1 wenn nichts erkannt wurde. */
        public static double parsePrice(String s) {
            if (s == null) return -1;
            Matcher m = PRICE.matcher(s);
            if (!m.find()) return -1;
            try {
                double v = Double.parseDouble(m.group(1).replace(",", ""));
                switch (m.group(2).toLowerCase()) {
                    case "k" -> v *= 1_000;
                    case "m" -> v *= 1_000_000;
                    case "b" -> v *= 1_000_000_000;
                    default -> {
                    }
                }
                return v;
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        public static String formatPrice(double v) {
            if (v >= 1_000_000_000) return trim(v / 1_000_000_000) + "B";
            if (v >= 1_000_000) return trim(v / 1_000_000) + "M";
            if (v >= 1_000) return trim(v / 1_000) + "K";
            return trim(v);
        }

        private static String trim(double v) {
            return v == Math.floor(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.2f", v);
        }
    }

    // ------------------------------------------------------------------- Menue

    public static class SniperScreen extends Screen {
        private EditBox search;
        private EditBox price;
        private final Button[] sugButtons = new Button[5];
        private final List<String> suggestions = new ArrayList<>();
        private String status = "";
        private String keepSearch = "";
        private String keepPrice = "";

        public SniperScreen() {
            super(Component.literal("AH Sniper"));
        }

        @Override
        protected void init() {
            Config cfg = Config.INSTANCE;
            int cx = width / 2;
            int y = 30;

            search = new EditBox(font, cx - 100, y, 200, 20, Component.literal("Item"));
            search.setHint(Component.literal("Item (z.B. crystal)"));
            search.setMaxLength(40);
            search.setResponder(this::onSearch);
            addRenderableWidget(search);

            addRenderableWidget(Button.builder(Component.literal("Alle Items"), b -> {
                        keepSearch = search.getValue();
                        keepPrice = price.getValue();
                        minecraft.setScreen(new ItemPickerScreen(this, picked -> keepSearch = picked));
                    })
                    .bounds(cx + 104, y, 80, 20).build());

            for (int i = 0; i < sugButtons.length; i++) {
                final int idx = i;
                sugButtons[i] = Button.builder(Component.literal(""), b -> {
                            if (idx < suggestions.size()) search.setValue(suggestions.get(idx));
                        })
                        .bounds(cx - 100, y + 22 + i * 17, 200, 16).build();
                sugButtons[i].visible = false;
                addRenderableWidget(sugButtons[i]);
            }

            int py = y + 22 + 5 * 17 + 6;
            price = new EditBox(font, cx - 100, py, 200, 20, Component.literal("Preis"));
            price.setHint(Component.literal("Maximaler Preis (z.B. 84M)"));
            price.setMaxLength(15);
            addRenderableWidget(price);

            addRenderableWidget(Button.builder(Component.literal("Start"), b -> start())
                    .bounds(cx - 100, py + 24, 98, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Stop"), b -> {
                        cfg.active = false;
                        cfg.save();
                        status = "Sniper gestoppt.";
                    })
                    .bounds(cx + 2, py + 24, 98, 20).build());

            int hy = py + 74;
            for (Entry h : new ArrayList<>(cfg.history)) {
                addRenderableWidget(Button.builder(
                                Component.literal(h.item + " ≤ " + Config.formatPrice(h.maxPrice)),
                                b -> {
                                    search.setValue(h.item);
                                    price.setValue(Config.formatPrice(h.maxPrice));
                                })
                        .bounds(cx - 100, hy, 200, 14).build());
                hy += 15;
            }

            search.setValue(keepSearch);
            price.setValue(keepPrice);
        }

        private void onSearch(String text) {
            suggestions.clear();
            String q = normalize(text);
            if (!q.isEmpty()) {
                collect(q);
                if (suggestions.isEmpty() && q.endsWith("s")) collect(q.substring(0, q.length() - 1));
            }
            for (int i = 0; i < sugButtons.length; i++) {
                if (i < suggestions.size()) {
                    sugButtons[i].setMessage(Component.literal(displayName(suggestions.get(i))));
                    sugButtons[i].visible = true;
                } else {
                    sugButtons[i].visible = false;
                }
            }
        }

        private static String normalize(String s) {
            return s.trim().toLowerCase().replace(' ', '_');
        }

        private void collect(String q) {
            List<String> starts = new ArrayList<>();
            List<String> contains = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) continue;
                String path = BuiltInRegistries.ITEM.getKey(item).getPath();
                String name = normalize(new ItemStack(item).getHoverName().getString());
                if (path.startsWith(q) || name.startsWith(q)) starts.add(path);
                else if (path.contains(q) || name.contains(q)) contains.add(path);
            }
            suggestions.addAll(starts);
            suggestions.addAll(contains);
            while (suggestions.size() > 5) suggestions.remove(suggestions.size() - 1);
        }

        private static String displayName(String path) {
            for (Item item : BuiltInRegistries.ITEM) {
                if (BuiltInRegistries.ITEM.getKey(item).getPath().equals(path)) {
                    return new ItemStack(item).getHoverName().getString();
                }
            }
            return path;
        }

        private void start() {
            String q = normalize(search.getValue());
            String item = null;
            for (Item i : BuiltInRegistries.ITEM) {
                if (BuiltInRegistries.ITEM.getKey(i).getPath().equals(q)) {
                    item = q;
                    break;
                }
            }
            if (item == null && !suggestions.isEmpty()) item = suggestions.get(0);
            double p = Config.parsePrice(price.getValue());
            if (item == null || p <= 0) {
                status = "Item oder Preis ungültig";
                return;
            }
            Config cfg = Config.INSTANCE;
            cfg.item = item;
            cfg.maxPrice = p;
            cfg.active = true;
            cfg.addHistory(new Entry(item, p));
            cfg.save();
            Minecraft.getInstance().player.displayClientMessage(Component.literal(
                    "[AH Sniper] Suche nur " + displayName(item) + " bis " + Config.formatPrice(p)
                            + ". Öffne das AH (Recently Listed)."), false);
            onClose();
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int cx = width / 2;
            Config cfg = Config.INSTANCE;
            g.drawCenteredString(font, title, cx, 10, 0xFFFFFFFF);
            String state = cfg.active && cfg.item != null
                    ? "AKTIV: " + displayName(cfg.item) + " ≤ " + Config.formatPrice(cfg.maxPrice)
                    : "Nicht aktiv";
            g.drawCenteredString(font, state, cx, 20, cfg.active ? 0xFF55FF55 : 0xFFAAAAAA);
            int py = 30 + 22 + 5 * 17 + 6;
            g.drawString(font, "Letzte 10 Suchen:", cx - 100, py + 60, 0xFFAAAAAA);
            if (!status.isEmpty()) g.drawCenteredString(font, status, cx, py + 50, 0xFFFF5555);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

    // -------------------------------------------------- Item-Auswahl (alle Items)

    public static class ItemPickerScreen extends Screen {
        private static final int COLS = 12, ROWS = 6, CELL = 20, TOP = 40;
        private final Screen parent;
        private final java.util.function.Consumer<String> onPick;
        private final List<ItemStack> all = new ArrayList<>();
        private List<ItemStack> filtered;
        private final Button[] cells = new Button[COLS * ROWS];
        private Button prev;
        private Button next;
        private int page = 0;

        public ItemPickerScreen(Screen parent, java.util.function.Consumer<String> onPick) {
            super(Component.literal("Item wählen"));
            this.parent = parent;
            this.onPick = onPick;
            for (Item item : BuiltInRegistries.ITEM) {
                if (item != Items.AIR) all.add(new ItemStack(item));
            }
            filtered = new ArrayList<>(all);
        }

        private static String pathOf(ItemStack st) {
            return BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        }

        @Override
        protected void init() {
            int cx = width / 2;
            int left = cx - COLS * CELL / 2;

            EditBox filter = new EditBox(font, cx - 100, 12, 200, 20, Component.literal("Filter"));
            filter.setHint(Component.literal("Filter (z.B. diamond)"));
            filter.setResponder(this::applyFilter);
            addRenderableWidget(filter);

            for (int i = 0; i < cells.length; i++) {
                final int idx = i;
                cells[i] = Button.builder(Component.literal(""), b -> {
                            int n = page * cells.length + idx;
                            if (n < filtered.size()) {
                                onPick.accept(pathOf(filtered.get(n)));
                                minecraft.setScreen(parent);
                            }
                        })
                        .bounds(left + (i % COLS) * CELL, TOP + (i / COLS) * CELL, CELL, CELL).build();
                addRenderableWidget(cells[i]);
            }

            int by = TOP + ROWS * CELL + 6;
            prev = Button.builder(Component.literal("◀"), b -> {
                page--;
                refresh();
            }).bounds(cx - 70, by, 40, 20).build();
            next = Button.builder(Component.literal("▶"), b -> {
                page++;
                refresh();
            }).bounds(cx + 30, by, 40, 20).build();
            addRenderableWidget(prev);
            addRenderableWidget(next);
            addRenderableWidget(Button.builder(Component.literal("Zurück"), b -> minecraft.setScreen(parent))
                    .bounds(cx - 40, by + 26, 80, 20).build());
            refresh();
        }

        private void applyFilter(String text) {
            String q = text.trim().toLowerCase().replace(' ', '_');
            filtered = new ArrayList<>();
            for (ItemStack st : all) {
                String name = st.getHoverName().getString().toLowerCase().replace(' ', '_');
                if (q.isEmpty() || pathOf(st).contains(q) || name.contains(q)) filtered.add(st);
            }
            page = 0;
            refresh();
        }

        private int pages() {
            return Math.max(1, (filtered.size() + cells.length - 1) / cells.length);
        }

        private void refresh() {
            page = Math.max(0, Math.min(page, pages() - 1));
            for (int i = 0; i < cells.length; i++) {
                cells[i].visible = page * cells.length + i < filtered.size();
            }
            prev.active = page > 0;
            next.active = page < pages() - 1;
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.render(g, mouseX, mouseY, partialTick);
            int cx = width / 2;
            int left = cx - COLS * CELL / 2;
            String hovered = null;
            for (int i = 0; i < cells.length; i++) {
                int n = page * cells.length + i;
                if (n >= filtered.size()) break;
                int x = left + (i % COLS) * CELL;
                int y = TOP + (i / COLS) * CELL;
                g.renderItem(filtered.get(n), x + 2, y + 2);
                if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                    hovered = filtered.get(n).getHoverName().getString();
                }
            }
            int by = TOP + ROWS * CELL + 6;
            g.drawCenteredString(font, "Seite " + (page + 1) + "/" + pages() + "  (" + filtered.size() + " Items)",
                    cx, by + 6, 0xFFAAAAAA);
            if (hovered != null) g.drawCenteredString(font, hovered, cx, by + 54, 0xFFFFFFFF);
        }
    }
}
