package id.kantah.cetakantrian;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.UUID;

/** Kirim perintah ESC/POS ke printer thermal lewat Bluetooth (profil SPP). */
public class BluetoothPrinter {

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final Charset ASCII = Charset.forName("US-ASCII");

    /** Susun isi tiket dalam format ESC/POS. */
    public static byte[] tiket(String header, String loket, String nomor, String waktu, String footer) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        write(b, 0x1B, 0x40);             // reset
        write(b, 0x1B, 0x61, 0x01);       // rata tengah
        write(b, 0x1B, 0x45, 0x01);       // tebal
        text(b, header + "\n");
        write(b, 0x1B, 0x45, 0x00);
        text(b, "\n" + loket + "\n\n");
        write(b, 0x1D, 0x21, 0x33);       // huruf 4x
        text(b, nomor + "\n");
        write(b, 0x1D, 0x21, 0x00);       // normal
        text(b, "\n" + waktu + "\n");
        if (footer != null && !footer.trim().isEmpty()) text(b, footer + "\n");
        write(b, 0x1B, 0x64, 0x04);       // maju 4 baris
        write(b, 0x1D, 0x56, 0x01);       // potong kertas (diabaikan jika tanpa cutter)
        return b.toByteArray();
    }

    /** Kirim data ke printer. Melempar exception berisi pesan yang bisa ditampilkan. */
    public static void kirim(String mac, byte[] data) throws Exception {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) throw new Exception("Tablet tidak punya Bluetooth");
        if (!adapter.isEnabled()) throw new Exception("Bluetooth belum dinyalakan");

        BluetoothDevice dev = adapter.getRemoteDevice(mac);
        try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }

        BluetoothSocket socket = null;
        try {
            try {
                socket = dev.createRfcommSocketToServiceRecord(SPP);
                socket.connect();
            } catch (IOException e) {
                // beberapa printer murah hanya mau koneksi "insecure"
                tutup(socket);
                socket = dev.createInsecureRfcommSocketToServiceRecord(SPP);
                socket.connect();
            }
            OutputStream os = socket.getOutputStream();
            os.write(data);
            os.flush();
            Thread.sleep(400); // beri waktu printer menerima sebelum koneksi ditutup
        } catch (IOException e) {
            throw new Exception("Printer tidak terhubung. Pastikan printer menyala dan dekat tablet.");
        } finally {
            tutup(socket);
        }
    }

    private static void tutup(BluetoothSocket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }

    private static void write(ByteArrayOutputStream b, int... bytes) {
        for (int x : bytes) b.write(x);
    }

    private static void text(ByteArrayOutputStream b, String s) {
        byte[] d = s.getBytes(ASCII);
        b.write(d, 0, d.length);
    }
}
