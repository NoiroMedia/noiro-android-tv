package tv.noiro.launcher;

import android.content.Context;
import com.cgutman.adblib.AdbConnection;
import com.cgutman.adblib.AdbCrypto;
import com.cgutman.adblib.AdbBase64;
import com.cgutman.adblib.AdbStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Only connects to this TV's loopback interface. No remote host or arbitrary command UI. */
final class LocalAdb implements AutoCloseable, HomeSwitch.Shell {
    private final Socket socket = new Socket();
    private final AdbConnection connection;

    LocalAdb(Context context, int port) throws Exception {
        if (port < 1 || port > 65535) throw new IOException("Invalid debugging port.");
        File directory = new File(context.getFilesDir(), "home-adb");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot store the TV authorization key.");
        File secret = new File(directory, "private.key"), publicKey = new File(directory, "public.key");
        AdbBase64 base64 = data -> android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        AdbCrypto crypto;
        if (secret.isFile() && publicKey.isFile()) crypto = AdbCrypto.loadAdbKeyPair(base64, secret, publicKey);
        else {
            crypto = AdbCrypto.generateAdbKeyPair(base64);
            crypto.saveAdbKeyPair(secret, publicKey);
            secret.setReadable(false, false); secret.setWritable(false, false);
            secret.setReadable(true, true); secret.setWritable(true, true);
        }
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(45000);
            connection = AdbConnection.create(socket, crypto);
            connection.connect();
        } catch (Exception e) {
            socket.close();
            throw new IOException("Cannot connect. Enable TCP/network debugging and approve the TV authorization prompt. Wireless pairing ports are not supported by this method.");
        }
    }

    @Override public String run(String command) throws Exception {
        String marker = "NOIRO_EXIT_" + java.util.UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (AdbStream stream = connection.open("shell:" + command + "; echo " + marker + ":$?")) {
            while (true) {
                try {
                    byte[] data = stream.read();
                    if (output.size() + data.length > 1024 * 1024) throw new IOException("Debugging response was too large.");
                    output.write(data);
                } catch (IOException closed) {
                    String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
                    int end = result.lastIndexOf(marker + ":");
                    if (end < 0) throw new IOException("TV connection ended before the command completed.");
                    String status = result.substring(end + marker.length() + 1).trim();
                    if (!status.equals("0")) throw new IOException("The TV did not accept this Home operation.");
                    return result.substring(0, end).trim();
                }
            }
        }
    }
    @Override public void close() throws IOException { connection.close(); }
}
