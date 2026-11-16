package com.proyector.sender;

import com.proyector.shared.Wire;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

final class WindowsControl {
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(12), task -> daemon(task, "windows-control"));
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            task -> daemon(task, "windows-control-timeout"));
    private volatile Process process;
    private BufferedReader replies;
    private BufferedWriter commands;
    private Path executable;

    WindowsControl() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process running = process;
            if (running != null) running.destroyForcibly();
        }, "stop-windows-control"));
    }

    void request(int operation, int value, BooleanSupplier active, BiConsumer<Boolean, String> result) {
        String command = switch (operation) {
            case Wire.STATUS -> "state";
            case Wire.VOLUME_SET -> "set " + value;
            case Wire.VOLUME_UP -> "up";
            case Wire.VOLUME_DOWN -> "down";
            case Wire.MUTE -> "mute";
            case Wire.LOCK -> "lock";
            case Wire.SLEEP -> "sleep";
            case Wire.RESTART -> "restart";
            case Wire.SHUTDOWN -> "shutdown";
            default -> throw new IllegalArgumentException("Orden desconocida");
        };
        try {
            worker.execute(() -> {
                if (!active.getAsBoolean()) return;
                try {
                    ensureProcess();
                    if (!active.getAsBoolean()) return;
                    Process running = process;
                    ScheduledFuture<?> timeout = watchdog.schedule(running::destroyForcibly, 6, TimeUnit.SECONDS);
                    String line;
                    try {
                        commands.write(command);
                        commands.newLine();
                        commands.flush();
                        line = replies.readLine();
                    } finally { timeout.cancel(false); }
                    if (line == null) throw new IOException("Canal de Windows cerrado");
                    if (line.startsWith("OK ")) result.accept(true, line.substring(3));
                    else if (line.startsWith("ERROR ")) result.accept(false, line.substring(6));
                    else throw new IOException("Respuesta de Windows inesperada");
                } catch (IOException error) {
                    if (process != null) process.destroyForcibly();
                    process = null;
                    result.accept(false, "No se pudo consultar el control de Windows. Vuelve a intentarlo.");
                }
            });
        } catch (RejectedExecutionException busy) {
            result.accept(false, "Windows está procesando varias órdenes. Intenta en un momento.");
        }
    }

    private void ensureProcess() throws IOException {
        if (process != null && process.isAlive()) return;
        if (executable == null) {
            try (InputStream source = WindowsControl.class.getResourceAsStream("/native/WindowsControls.exe")) {
                if (source == null) throw new IOException("Falta el componente de Windows");
                Path directory = Files.createTempDirectory("control-pc-");
                directory.toFile().deleteOnExit();
                Path target = directory.resolve("WindowsControls.exe");
                Files.copy(source, target);
                target.toFile().deleteOnExit();
                executable = target;
            }
        }
        process = new ProcessBuilder(executable.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        replies = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        commands = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII));
    }

    static Thread daemon(Runnable run, String name) {
        Thread thread = new Thread(run, name);
        thread.setDaemon(true);
        return thread;
    }
}
