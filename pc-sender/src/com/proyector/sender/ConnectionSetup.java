package com.proyector.sender;

import com.proyector.shared.Wire;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Local setup actions, invoked from the sender window only. */
final class ConnectionSetup {
    static final String DRIVER_PAGE = "https://github.com/VirtualDrivers/Virtual-Display-Driver/releases/latest";

    private ConnectionSetup() { }

    static Path windowsTool(String file) {
        return Path.of(System.getenv("SystemRoot"), "System32", file);
    }

    private static Path adb() throws IOException {
        String[] roots = {System.getenv("ANDROID_SDK_ROOT"), System.getenv("ANDROID_HOME"),
                Path.of(System.getenv("LOCALAPPDATA"), "Android", "Sdk").toString()};
        for (String root : roots) {
            if (root == null || root.isBlank()) continue;
            Path file = Path.of(root, "platform-tools", "adb.exe");
            if (file.isAbsolute() && Files.isRegularFile(file)) return file;
        }
        for (String dir : System.getenv().getOrDefault("PATH", "").split(";")) {
            if (dir.isBlank()) continue;
            Path file = Path.of(dir.replace("\"", ""), "adb.exe");
            if (file.isAbsolute() && Files.isRegularFile(file)) return file;
        }
        throw new IOException("Falta Android Platform Tools. Usa Compartir por USB o instala ADB en la PC.");
    }

    static String prepareUsb(int hostPort) throws IOException, InterruptedException {
        Path tool = adb();
        String source = "tcp:" + Wire.PORT;
        String target = "tcp:" + hostPort;
        String existing = output(tool.toString(), "-d", "reverse", "--list");
        for (String line : existing.split("\\R")) {
            String[] spec = line.trim().split("\\s+");
            if (spec.length >= 2 && spec[spec.length - 2].equals(source)) {
                if (spec[spec.length - 1].equals(target)) return "Cable listo. En Android pulsa Usar USB directo (ADB).";
                throw new IOException("El puerto USB ya se usa para otra conexión. Usa Compartir por USB.");
            }
        }
        output(tool.toString(), "-d", "reverse", "--no-rebind", source, target);
        return "Cable listo. En Android elige USB directo e introduce el código.";
    }

    private static String output(String... args) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) throw new IOException("ADB tardó demasiado. Revisa la autorización del teléfono.");
            String result = new String(process.getInputStream().readNBytes(16384), java.nio.charset.StandardCharsets.UTF_8);
            if (process.exitValue() == 0) return result;
            if (result.contains("unauthorized")) throw new IOException("Desbloquea Android, autoriza esta PC y vuelve a pulsar Preparar cable ADB.");
            if (result.contains("more than one")) throw new IOException("Conecta un solo dispositivo Android por USB para prepararlo.");
            throw new IOException("ADB no encontró un teléfono autorizado. Conecta un cable de datos y activa Depuración USB.");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    static Path virtualControl() {
        Path links = Path.of(System.getenv("LOCALAPPDATA"), "Microsoft", "WinGet", "Links", "VDD Control.exe");
        if (Files.isRegularFile(links)) return links;
        Path global = Path.of(System.getenv("ProgramFiles"), "WinGet", "Links", "VDD Control.exe");
        return Files.isRegularFile(global) ? global : null;
    }

    static Path installControl() throws IOException, InterruptedException {
        Path installed = virtualControl();
        if (installed != null) return installed;
        Path winget = Path.of(System.getenv("LOCALAPPDATA"), "Microsoft", "WindowsApps", "winget.exe");
        Process process = new ProcessBuilder(winget.toString(), "install", "--id", "VirtualDrivers.Virtual-Display-Driver",
                "--exact", "--source", "winget", "--scope", "user", "--disable-interactivity")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(8, TimeUnit.MINUTES)) throw new IOException("La descarga tardó demasiado.");
            installed = virtualControl();
            if (installed == null) throw new IOException("WinGet no pudo preparar Virtual Driver Control.");
            return installed;
        } finally { if (process.isAlive()) process.destroy(); }
    }
}
