package com.proyector.sender;

import com.proyector.shared.Wire;

import java.awt.AWTException;
import java.awt.GraphicsDevice;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class StreamSession {
    private static final int IN_FLIGHT_LIMIT = 2;
    private static final int CONTROL_QUEUE_LIMIT = 24;
    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Supplier<Displays.Settings> settings;
    private final WindowsControl controls;
    private final Consumer<String> onVideo;
    private final Object wake = new Object();
    private final Object pointerLock = new Object();
    private final ArrayDeque<Message> messages = new ArrayDeque<>();
    private final Map<Integer, SentFrame> inFlight = new LinkedHashMap<>();
    private final AdaptiveVideo video = new AdaptiveVideo();
    private volatile ViewRequest view = new ViewRequest(0, Wire.PAUSED, 2, 30, true);
    private Viewport viewport;
    private Robot pointer;
    private boolean mouseDown;

    StreamSession(Socket socket, DataInputStream input, DataOutputStream output,
                  Supplier<Displays.Settings> settings, WindowsControl controls, Consumer<String> onVideo) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.settings = settings;
        this.controls = controls;
        this.onVideo = onVideo;
    }

    void run() {
        WindowsControl.daemon(this::readCommands, "pc-command-reader").start();
        queryWindows(Wire.STATUS, 0);
        Displays displays = null;
        DisplayCapture capture = null;
        ViewRequest previous = null;
        Displays.Settings previousSettings = null;
        String previousDisplayInfo = null;
        long nextTopologyCheck = 0;
        long nextFrame = 0;
        long meterStarted = System.nanoTime();
        int sent = 0;
        int frames = 0;
        try (FrameEncoder encoder = new FrameEncoder()) {
            while (!socket.isClosed()) {
                drainMessages();
                ViewRequest request = view;
                Displays.Settings selected = settings.get();
                long now = System.nanoTime();
                boolean topologyDue = now >= nextTopologyCheck;
                if (topologyDue) {
                    displays = Displays.read();
                    nextTopologyCheck = now + TimeUnit.SECONDS.toNanos(1);
                }
                if (topologyDue || !selected.equals(previousSettings) || request != previous) {
                    Displays.Choice choice = displays.choose(request.mode, selected);
                    Displays.Choice second = displays.secondary(selected.secondary());
                    String secondary = second == null ? "" : second.name();
                    String displayInfo = displays.choices().size() + ":" + secondary;
                    if (!displayInfo.equals(previousDisplayInfo)) {
                        output.writeInt(Wire.DISPLAYS);
                        output.writeInt(displays.choices().size());
                        output.writeUTF(secondary);
                        output.flush();
                        previousDisplayInfo = displayInfo;
                    }
                    if (request != previous || !DisplayCapture.matches(capture, choice)) {
                        clearViewport();
                        capture = choice == null ? null : new DisplayCapture(choice);
                        if (capture == null) {
                            onVideo.accept("Imagen en pausa");
                            if (request.mode != Wire.PAUSED && request == view) {
                                output.writeInt(Wire.NOTICE);
                                output.writeBoolean(false);
                                output.writeUTF(request.mode == Wire.SECOND_DISPLAY ? "Windows no tiene una segunda pantalla activa."
                                        : "La pantalla elegida ya no está disponible en Windows.");
                                output.flush();
                            }
                        }
                        meterStarted = now;
                        frames = 0;
                        nextFrame = 0;
                    }
                    synchronized (wake) {
                        video.configure(Math.min(selected.quality(), request.quality),
                                Math.min(selected.fps(), request.fps), request.automatic);
                    }
                    previous = request;
                    previousSettings = selected;
                }
                int resolution;
                int fps;
                synchronized (wake) {
                    if (inFlight.size() > 0 && now - inFlight.values().iterator().next().started > TimeUnit.SECONDS.toNanos(10))
                        throw new IOException("El receptor dejó de confirmar imágenes");
                    if (capture == null || inFlight.size() >= IN_FLIGHT_LIMIT || now < nextFrame) {
                        if (messages.isEmpty() && view == request) {
                            long delay = nextFrame > now ? Math.max(1, TimeUnit.NANOSECONDS.toMillis(nextFrame - now)) : 100;
                            wake.wait(Math.min(delay, 100));
                        }
                        continue;
                    }
                    resolution = video.resolution();
                    fps = video.fps();
                }

                long started = System.nanoTime();
                BufferedImage image = capture.read(resolution);
                int[] size = FrameEncoder.fit(image.getWidth(), image.getHeight(), resolution);
                try { encoder.encode(image, resolution, request.automatic); }
                finally { image.flush(); }
                nextFrame = started + 1_000_000_000L / fps;
                if (view != request || encoder.size() > Wire.MAX_FRAME) continue;
                drainMessages();
                int sequence = ++sent;
                synchronized (wake) {
                    inFlight.put(sequence, new SentFrame(started, elapsedMs(started)));
                }
                output.writeInt(Wire.FRAME);
                output.writeInt(request.revision);
                output.writeInt(sequence);
                output.writeInt(encoder.size());
                synchronized (pointerLock) {
                    if (view == request) {
                        int first = viewport == null ? sequence : viewport.firstSequence;
                        viewport = new Viewport(request.revision, first, sequence, capture.choice.bounds(), capture.choice.device());
                    }
                }
                encoder.writeTo(output);
                output.flush();
                frames++;
                if (elapsedMs(meterStarted) >= 1000) {
                    long actualFps = Math.round(frames * 1000.0 / elapsedMs(meterStarted));
                    onVideo.accept(size[0] + " × " + size[1] + "  ·  " + actualFps + " FPS");
                    meterStarted = System.nanoTime();
                    frames = 0;
                }
            }
        } catch (IOException | AWTException | RuntimeException error) {
            if (!socket.isClosed()) onVideo.accept("Sesión de imagen interrumpida");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } finally { close(); }
    }

    private void readCommands() {
        try {
            while (!socket.isClosed()) {
                int operation = input.readInt();
                if (Wire.isTouch(operation)) {
                    readPointer(operation);
                    continue;
                }
                switch (operation) {
                    case Wire.PING -> {
                        long time = input.readLong();
                        enqueue(out -> { out.writeInt(Wire.PONG); out.writeLong(time); });
                    }
                    case Wire.VIEW -> readView();
                    case Wire.FRAME_ACK -> acknowledge(input.readInt());
                    case Wire.VOLUME_SET -> {
                        int percent = input.readInt();
                        if (percent < 0 || percent > 100) throw new IOException("Volumen inválido");
                        queryWindows(operation, percent);
                    }
                    case Wire.STATUS, Wire.MUTE, Wire.VOLUME_UP, Wire.VOLUME_DOWN,
                         Wire.LOCK, Wire.SLEEP, Wire.SHUTDOWN, Wire.RESTART -> queryWindows(operation, 0);
                    default -> throw new IOException("Orden desconocida");
                }
            }
        } catch (IOException | AWTException | RuntimeException ignored) {
            // A malformed or interrupted connection is closed and any drag is released.
        } finally { close(); }
    }

    private void readView() throws IOException {
        int revision = input.readInt();
        int mode = input.readInt();
        int quality = input.readInt();
        int fps = input.readInt();
        boolean automatic = input.readBoolean();
        if (revision <= view.revision || mode < Wire.PAUSED || mode > Wire.SECOND_DISPLAY
                || quality < 0 || quality >= Wire.RESOLUTIONS.length || fps < 12 || fps > 60)
            throw new IOException("Configuración de imagen inválida");
        synchronized (pointerLock) {
            releaseMouse();
            viewport = null;
            view = new ViewRequest(revision, mode, quality, fps, automatic);
        }
        synchronized (wake) { wake.notifyAll(); }
    }

    private void readPointer(int operation) throws IOException, AWTException {
        int revision = input.readInt();
        int frame = input.readInt();
        int x = 0;
        int y = 0;
        String text = null;
        if (operation == Wire.TEXT) {
            text = input.readUTF();
            if (text.length() > 512) throw new IOException("Texto demasiado largo");
        } else if (operation != Wire.RIGHT_CLICK) {
            x = input.readInt();
            if (operation != Wire.SCROLL) y = input.readInt();
        }
        synchronized (pointerLock) {
            if (viewport == null || viewport.revision != revision || frame < viewport.firstSequence
                    || frame > viewport.lastSequence || socket.isClosed()) return;
            if (pointer == null) pointer = viewport.device == null ? new Robot() : new Robot(viewport.device);
            Rectangle area = viewport.area;
            if (operation == Wire.RELATIVE) {
                if (x < -1000 || x > 1000 || y < -1000 || y > 1000) throw new IOException("Movimiento inválido");
                var info = MouseInfo.getPointerInfo();
                if (info == null) return;
                Point current = info.getLocation();
                pointer.mouseMove(clamp(current.x + x, area.x, area.x + area.width - 1),
                        clamp(current.y + y, area.y, area.y + area.height - 1));
            } else if (operation == Wire.SCROLL) {
                if (x < -12 || x > 12) throw new IOException("Desplazamiento inválido");
                pointer.mouseWheel(x);
            } else if (operation == Wire.TEXT) {
                if (text != null && !text.isEmpty()) paste(text);
            } else if (operation == Wire.RIGHT_CLICK) {
                pointer.mousePress(InputEvent.BUTTON3_DOWN_MASK);
                pointer.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
            } else {
                Point target = pointOnDisplay(area, x, y);
                if (target != null) pointer.mouseMove(target.x, target.y);
                if (operation == Wire.DOWN && !mouseDown) {
                    pointer.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    mouseDown = true;
                }
                if (operation == Wire.UP) releaseMouse();
            }
        }
    }

    static Point pointOnDisplay(Rectangle area, int x, int y) throws IOException {
        if (x == -1 && y == -1) return null;
        if (x < 0 || x > Wire.COORDINATE_MAX || y < 0 || y > Wire.COORDINATE_MAX)
            throw new IOException("Coordenadas inválidas");
        return new Point(area.x + (int) Math.round((area.width - 1) * x / (double) Wire.COORDINATE_MAX),
                area.y + (int) Math.round((area.height - 1) * y / (double) Wire.COORDINATE_MAX));
    }

    private void paste(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        try {
            Transferable previous = clipboard.getContents(null);
            StringSelection selection = new StringSelection(text);
            clipboard.setContents(selection, null);
            try {
                pointer.keyPress(KeyEvent.VK_CONTROL);
                try { pointer.keyPress(KeyEvent.VK_V); pointer.keyRelease(KeyEvent.VK_V); }
                finally { pointer.keyRelease(KeyEvent.VK_CONTROL); }
                pointer.delay(100);
            } finally {
                if (previous != null && clipboard.getContents(null) == selection) clipboard.setContents(previous, null);
            }
        } catch (IllegalStateException busy) { notice(false, "El portapapeles de Windows está ocupado."); }
    }

    private void acknowledge(int sequence) {
        synchronized (wake) {
            SentFrame frame = inFlight.remove(sequence);
            if (frame != null) video.feedback(elapsedMs(frame.started), frame.processingMs);
            wake.notifyAll();
        }
    }

    private void queryWindows(int operation, int percent) {
        controls.request(operation, percent, () -> !socket.isClosed(), this::notice);
    }

    private void notice(boolean ok, String message) {
        enqueue(out -> { out.writeInt(Wire.NOTICE); out.writeBoolean(ok); out.writeUTF(message); });
    }

    private void enqueue(Message message) {
        synchronized (wake) {
            if (socket.isClosed()) return;
            if (messages.size() < CONTROL_QUEUE_LIMIT) messages.addLast(message);
            wake.notifyAll();
        }
    }

    private void drainMessages() throws IOException {
        while (true) {
            Message message;
            synchronized (wake) { message = messages.pollFirst(); }
            if (message == null) break;
            message.write(output);
        }
        output.flush();
    }

    void close() {
        try { socket.close(); } catch (IOException ignored) { }
        clearViewport();
        synchronized (wake) { wake.notifyAll(); }
    }

    boolean uses(Socket candidate) { return socket == candidate; }

    private void clearViewport() {
        synchronized (pointerLock) { viewport = null; releaseMouse(); pointer = null; }
    }

    private void releaseMouse() {
        if (!mouseDown) return;
        mouseDown = false;
        pointer.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    private static long elapsedMs(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static int clamp(int number, int min, int max) { return Math.max(min, Math.min(max, number)); }

    private record ViewRequest(int revision, int mode, int quality, int fps, boolean automatic) { }
    private record Viewport(int revision, int firstSequence, int lastSequence, Rectangle area, GraphicsDevice device) { }
    private record SentFrame(long started, long processingMs) { }
    @FunctionalInterface private interface Message { void write(DataOutputStream output) throws IOException; }

    private static final class DisplayCapture {
        private final Displays.Choice choice;
        private final Robot robot;

        DisplayCapture(Displays.Choice choice) throws AWTException {
            this.choice = choice;
            robot = choice.device() == null ? new Robot() : new Robot(choice.device());
        }

        static boolean matches(DisplayCapture existing, Displays.Choice next) {
            if (existing == null || next == null) return existing == null && next == null;
            return existing.choice.id().equals(next.id()) && existing.choice.bounds().equals(next.bounds())
                    && existing.choice.name().equals(next.name());
        }

        BufferedImage read(int resolution) {
            Rectangle bounds = choice.bounds();
            if (choice.device() != null && resolution == 3) {
                var configuration = choice.device().getDefaultConfiguration();
                if (configuration.getDefaultTransform().getScaleX() > 1.0
                        && bounds.width < Wire.WIDTHS[resolution]) {
                    var variants = robot.createMultiResolutionScreenCapture(bounds).getResolutionVariants();
                    BufferedImage largest = (BufferedImage) variants.get(variants.size() - 1);
                    for (var image : variants) if (image != largest) image.flush();
                    return largest;
                }
            }
            return robot.createScreenCapture(bounds);
        }
    }
}
