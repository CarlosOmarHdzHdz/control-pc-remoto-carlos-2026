package com.proyector.sender;

import com.proyector.shared.Wire;

import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

final class Displays {
    record Choice(String id, String name, boolean primary, Rectangle bounds, GraphicsDevice device) {
        @Override public String toString() { return name; }
    }

    record Settings(String desktop, String secondary, int quality, int fps) { }

    private final List<Choice> choices;

    Displays(List<Choice> choices) { this.choices = List.copyOf(choices); }

    static Displays read() {
        GraphicsEnvironment graphics = GraphicsEnvironment.getLocalGraphicsEnvironment();
        String primary = graphics.getDefaultScreenDevice().getIDstring();
        List<Choice> options = new ArrayList<>();
        for (GraphicsDevice device : graphics.getScreenDevices()) {
            String id = device.getIDstring();
            Rectangle area = device.getDefaultConfiguration().getBounds();
            boolean main = primary.equals(id);
            var mode = device.getDisplayMode();
            String name = (main ? "Principal" : "Pantalla adicional " + (options.stream().filter(d -> !d.primary).count() + 1))
                    + " · " + mode.getWidth() + " × " + mode.getHeight();
            Choice choice = new Choice(id, name, main, new Rectangle(area), device);
            if (main) options.add(0, choice);
            else options.add(choice);
        }
        return new Displays(options);
    }

    List<Choice> choices() { return choices; }

    Choice desktop(String selection) {
        if ("*".equals(selection)) {
            Rectangle area = new Rectangle(choices.get(0).bounds);
            for (Choice choice : choices) area = area.union(choice.bounds);
            return new Choice("*", "Escritorio completo", true, area, null);
        }
        return choices.stream().filter(display -> display.id.equals(selection)).findFirst().orElse(null);
    }

    Choice secondary(String selection) {
        if (selection != null && !selection.isEmpty()) {
            Choice selected = choices.stream().filter(display -> !display.primary && display.id.equals(selection)).findFirst().orElse(null);
            if (selected != null) return selected;
            // Replugging can change the Windows device ID. Only substitute an unambiguous display.
            List<Choice> available = choices.stream().filter(display -> !display.primary).toList();
            return available.size() == 1 ? available.get(0) : null;
        }
        return choices.stream().filter(display -> !display.primary).findFirst().orElse(null);
    }

    Choice choose(int mode, Settings settings) {
        if (mode == Wire.PAUSED) return null;
        if (mode == Wire.SECOND_DISPLAY) return secondary(settings.secondary);
        if (settings.desktop == null || settings.desktop.isEmpty()) return choices.get(0);
        return desktop(settings.desktop);
    }
}
