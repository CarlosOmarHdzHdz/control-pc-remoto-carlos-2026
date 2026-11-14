import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class ProtocolTest {
    private static final class RecordingRobot extends Robot {
        volatile int x = Integer.MIN_VALUE;
        volatile int y = Integer.MIN_VALUE;
        volatile int presses;
        volatile int releases;
        volatile int wheel;

        RecordingRobot() throws AWTException { super(); }
        @Override public void mouseMove(int x, int y) { this.x = x; this.y = y; }
        @Override public void mousePress(int buttons) { presses++; }
        @Override public void mouseRelease(int buttons) { releases++; }
        @Override public void mouseWheel(int amount) { wheel += amount; }
    }

    public static void main(String[] args) throws Exception {
        Class<?> sessionType = Class.forName("com.proyector.sender.Main$StreamSession");
        Method scale = sessionType.getDeclaredMethod("scale", BufferedImage.class, int.class, int.class);
        scale.setAccessible(true);
        BufferedImage large = new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB);
        BufferedImage fullHd = (BufferedImage) scale.invoke(null, large, 1920, 1080);
        if (fullHd.getWidth() != 1920 || fullHd.getHeight() != 1080)
            throw new AssertionError("El perfil Full HD no produce 1920x1080");
        BufferedImage small = new BufferedImage(1366, 768, BufferedImage.TYPE_INT_RGB);
        if (scale.invoke(null, small, 1920, 1080) != small)
            throw new AssertionError("No se debe ampliar una pantalla de menor resolución");
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket sender = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket receiver = listener.accept()) {
            Class<?> listenerType = Class.forName("com.proyector.sender.Main$StatusListener");
            Object status = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                    (proxy, method, values) -> null);
            Constructor<?> constructor = sessionType.getDeclaredConstructor(Socket.class, DataInputStream.class, int.class, listenerType);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(receiver, new DataInputStream(receiver.getInputStream()), 0, status);
            Method read = sessionType.getDeclaredMethod("readCommands", Robot.class, Rectangle.class);
            read.setAccessible(true);
            RecordingRobot robot = new RecordingRobot();
            Thread reader = new Thread(() -> {
                try { read.invoke(session, robot, new Rectangle(-100, 50, 200, 100)); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            reader.start();

            DataOutputStream output = new DataOutputStream(sender.getOutputStream());
            output.writeInt(6); output.writeInt(500000); output.writeInt(250000);
            output.writeInt(7); output.writeInt(500000); output.writeInt(250000);
            output.writeInt(9); output.writeInt(3);
            output.flush();
            long until = System.currentTimeMillis() + 2000;
            while ((robot.presses < 1 || robot.releases < 1 || robot.wheel != 3) && System.currentTimeMillis() < until) Thread.sleep(10);
            if (robot.x != 0 || robot.y != 75 || robot.presses != 1 || robot.releases != 1 || robot.wheel != 3)
                throw new AssertionError("Mapeo táctil incorrecto: " + robot.x + "," + robot.y);

            output.writeInt(6); output.writeInt(-1); output.writeInt(-1);
            output.writeInt(7); output.writeInt(-1); output.writeInt(-1);
            output.flush();
            until = System.currentTimeMillis() + 2000;
            while (robot.presses < 2 && System.currentTimeMillis() < until) Thread.sleep(10);
            if (robot.x != 0 || robot.y != 75 || robot.presses != 2)
                throw new AssertionError("El panel táctil movió el cursor de forma inesperada");

            output.writeInt(6); output.writeInt(-2); output.writeInt(0); output.flush();
            reader.join(2000);
            if (reader.isAlive() || !receiver.isClosed()) throw new AssertionError("No se rechazó el paquete inválido");
        }
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket phone = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
             Socket pc = listener.accept()) {
            phone.setSoTimeout(2000);
            Class<?> listenerType = Class.forName("com.proyector.sender.Main$StatusListener");
            Object status = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                    (proxy, method, values) -> null);
            AtomicInteger received = new AtomicInteger();
            BiConsumer<Integer, BiConsumer<Boolean, String>> fakeActions = (command, result) -> {
                received.set(received.get() | (1 << command));
                result.accept(true, "Orden " + command + " aceptada");
            };
            Constructor<?> constructor = sessionType.getDeclaredConstructor(Socket.class, DataInputStream.class,
                    int.class, listenerType, BiConsumer.class);
            constructor.setAccessible(true);
            Object session = constructor.newInstance(pc, new DataInputStream(pc.getInputStream()), 0, status, fakeActions);
            Field outputField = sessionType.getDeclaredField("output");
            outputField.setAccessible(true);
            outputField.set(session, new DataOutputStream(pc.getOutputStream()));
            Method read = sessionType.getDeclaredMethod("readCommands", Robot.class, Rectangle.class);
            read.setAccessible(true);
            Thread reader = new Thread(() -> {
                try { read.invoke(session, new RecordingRobot(), new Rectangle(0, 0, 200, 100)); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            reader.start();
            DataOutputStream commands = new DataOutputStream(phone.getOutputStream());
            DataInputStream responses = new DataInputStream(phone.getInputStream());
            for (int command : new int[]{10, 1, 2}) {
                commands.writeInt(command);
                commands.flush();
                for (int response = 0; response < 2; response++) {
                    if (responses.readInt() != -2 || !responses.readBoolean() || responses.readUTF().isEmpty())
                        throw new AssertionError("Falta confirmación de la orden " + command);
                }
            }
            if (received.get() != ((1 << 10) | (1 << 1) | (1 << 2)))
                throw new AssertionError("Bloquear, suspender o apagar no llegó al emisor");
            phone.close();
            reader.join(2000);
            if (reader.isAlive()) throw new AssertionError("El lector de controles no terminó");
        }
        System.out.println("Protocolo táctil, órdenes y 1080p: correcto");
    }
}
