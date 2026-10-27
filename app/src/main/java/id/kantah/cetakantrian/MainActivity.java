package id.kantah.cetakantrian;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String[][] LOKET = {
            {"I",  "Informasi dan Pengaduan"},
            {"B",  "Pembayaran"},
            {"P",  "Prioritas"},
            {"A",  "Pendaftaran"},
            {"LP", "Penyerahan"},
    };

    private static final String DEFAULT_HEADER = "KANTOR PERTANAHAN\nKABUPATEN BONE BOLANGO";
    private static final String DEFAULT_FOOTER = "Mohon menunggu panggilan";
    private static final int REQ_BT = 10;
    private static final int BIRU = Color.parseColor("#1A56DB");

    private final Locale ID = new Locale("id", "ID");
    private final ExecutorService printThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView tvPrinter;
    private GridLayout grid;
    private boolean sedangCetak = false;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("antrian", Context.MODE_PRIVATE);
        bangunLayar();
        mintaIzinBluetooth();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // cek ganti hari setiap aplikasi dibuka lagi
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        render();
    }

    @Override
    protected void onDestroy() {
        printThread.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private String hariIni() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** Reset semua nomor ke 0 jika sudah ganti hari. */
    private void cekGantiHari() {
        String hari = hariIni();
        if (!hari.equals(prefs.getString("tanggal", ""))) {
            SharedPreferences.Editor e = prefs.edit().putString("tanggal", hari);
            for (String[] l : LOKET) e.putInt("last_" + l[0], 0);
            e.apply();
        }
    }

    private int terakhir(String kode) {
        cekGantiHari();
        return prefs.getInt("last_" + kode, 0);
    }

    private void setTerakhir(String kode, int n) {
        prefs.edit().putString("tanggal", hariIni()).putInt("last_" + kode, n).apply();
    }

    private static String fmt(String kode, int n) {
        return kode + String.format(Locale.US, "%03d", n);
    }

    private String namaLoket(String kode) {
        for (String[] l : LOKET) if (l[0].equals(kode)) return l[1];
        return kode;
    }

    // ------------------------------------------------------------------ cetak

    private void cetakBerikut(String kode) {
        final int n = terakhir(kode) + 1;
        cetak(kode, n, true);
    }

    private void cetakUlang(String kode) {
        int n = terakhir(kode);
        if (n == 0) { toast("Belum ada nomor yang dicetak hari ini"); return; }
        cetak(kode, n, false);
    }

    /** Nomor baru dianggap terpakai hanya jika cetak berhasil. */
    private void cetak(final String kode, final int n, final boolean simpanJikaBerhasil) {
        if (sedangCetak) return;
        final String mac = prefs.getString("printer_mac", "");
        if (mac.isEmpty()) {
            toast("Pilih printer dulu di Pengaturan");
            bukaPengaturan();
            return;
        }
        if (!punyaIzinBluetooth()) { mintaIzinBluetooth(); return; }

        final byte[] data = BluetoothPrinter.tiket(
                prefs.getString("header", DEFAULT_HEADER),
                "Loket " + namaLoket(kode),
                fmt(kode, n),
                new SimpleDateFormat("dd-MM-yyyy HH:mm", ID).format(new Date()),
                prefs.getString("footer", DEFAULT_FOOTER));

        sedangCetak = true;
        render();
        printThread.execute(() -> {
            String error = null;
            try {
                BluetoothPrinter.kirim(mac, data);
            } catch (SecurityException e) {
                error = "Izin Bluetooth belum diberikan";
            } catch (Exception e) {
                error = e.getMessage();
            }
            final String err = error;
            ui.post(() -> {
                sedangCetak = false;
                if (err == null) {
                    if (simpanJikaBerhasil) setTerakhir(kode, n);
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle("Gagal mencetak " + fmt(kode, n))
                            .setMessage(err)
                            .setPositiveButton("OK", null)
                            .show();
                }
                render();
            });
        });
    }

    private void aturNomor(final String kode) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setHint("Contoh: 5");
        in.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        in.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(this)
                .setTitle("Samakan nomor loket " + namaLoket(kode))
                .setMessage("Isi nomor yang akan dicetak berikutnya (sesuai layar antrian).")
                .setView(bungkus(in))
                .setPositiveButton("Simpan", (d, w) -> {
                    try {
                        int n = Integer.parseInt(in.getText().toString().trim());
                        if (n < 1 || n > 999) throw new NumberFormatException();
                        setTerakhir(kode, n - 1);
                        render();
                    } catch (NumberFormatException e) {
                        toast("Isi angka 1 sampai 999");
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------ pengaturan

    private void bukaPengaturan() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, dp(8), p, 0);

        final TextView tvPilih = new TextView(this);
        tvPilih.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvPilih.setText(teksPrinter());
        box.addView(label("Printer"));
        box.addView(tvPilih);

        Button btnPilih = new Button(this);
        btnPilih.setText("Pilih Printer Bluetooth");
        btnPilih.setOnClickListener(v -> pilihPrinter(tvPilih));
        box.addView(btnPilih);

        final EditText etHeader = new EditText(this);
        etHeader.setText(prefs.getString("header", DEFAULT_HEADER));
        etHeader.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(label("Judul tiket"));
        box.addView(etHeader);

        final EditText etFooter = new EditText(this);
        etFooter.setText(prefs.getString("footer", DEFAULT_FOOTER));
        box.addView(label("Catatan bawah tiket"));
        box.addView(etFooter);

        Button btnTes = new Button(this);
        btnTes.setText("Tes Cetak");
        btnTes.setOnClickListener(v -> {
            simpanTeks(etHeader, etFooter);
            tesCetak();
        });
        box.addView(btnTes);

        Button btnReset = new Button(this);
        btnReset.setText("Reset Semua Nomor Hari Ini");
        btnReset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Semua loket kembali ke nomor 001?")
                .setPositiveButton("Ya, reset", (d, w) -> {
                    for (String[] l : LOKET) setTerakhir(l[0], 0);
                    render();
                    toast("Nomor direset");
                })
                .setNegativeButton("Batal", null)
                .show());
        box.addView(btnReset);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Pengaturan")
                .setView(sv)
                .setPositiveButton("Simpan", (d, w) -> {
                    simpanTeks(etHeader, etFooter);
                    render();
                })
                .setNegativeButton("Tutup", (d, w) -> render())
                .show();
    }

    private void simpanTeks(EditText header, EditText footer) {
        String h = header.getText().toString().trim();
        prefs.edit()
                .putString("header", h.isEmpty() ? DEFAULT_HEADER : h)
                .putString("footer", footer.getText().toString().trim())
                .apply();
    }

    /** Tampilkan daftar printer yang sudah di-pairing di menu Bluetooth tablet. */
    private void pilihPrinter(final TextView tvPilih) {
        if (!punyaIzinBluetooth()) { mintaIzinBluetooth(); return; }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) { toast("Tablet tidak punya Bluetooth"); return; }
        if (!adapter.isEnabled()) { toast("Nyalakan Bluetooth dulu"); return; }

        final List<BluetoothDevice> devices = new ArrayList<>();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) devices.addAll(bonded);
        } catch (SecurityException e) {
            toast("Izin Bluetooth belum diberikan");
            return;
        }
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Belum ada printer")
                    .setMessage("Pairing printer dulu lewat Pengaturan > Bluetooth di tablet "
                            + "(PIN umumnya 0000 atau 1234), lalu kembali ke sini.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String[] items = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String nama;
            try { nama = d.getName(); } catch (SecurityException e) { nama = null; }
            items[i] = (nama == null ? "(tanpa nama)" : nama) + "\n" + d.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Pilih printer")
                .setItems(items, (dlg, which) -> {
                    BluetoothDevice d = devices.get(which);
                    String nama;
                    try { nama = d.getName(); } catch (SecurityException e) { nama = d.getAddress(); }
                    prefs.edit()
                            .putString("printer_mac", d.getAddress())
                            .putString("printer_name", nama == null ? d.getAddress() : nama)
                            .apply();
                    tvPilih.setText(teksPrinter());
                    render();
                })
                .show();
    }

    private void tesCetak() {
        final String mac = prefs.getString("printer_mac", "");
        if (mac.isEmpty()) { toast("Pilih printer dulu"); return; }
        if (!punyaIzinBluetooth()) { mintaIzinBluetooth(); return; }
        final byte[] data = BluetoothPrinter.tiket(
                prefs.getString("header", DEFAULT_HEADER), "TES CETAK", "A000",
                new SimpleDateFormat("dd-MM-yyyy HH:mm", ID).format(new Date()),
                prefs.getString("footer", DEFAULT_FOOTER));
        toast("Mengirim ke printer...");
        printThread.execute(() -> {
            String err = null;
            try { BluetoothPrinter.kirim(mac, data); }
            catch (SecurityException e) { err = "Izin Bluetooth belum diberikan"; }
            catch (Exception e) { err = e.getMessage(); }
            final String msg = err == null ? "Tes cetak berhasil" : err;
            ui.post(() -> toast(msg));
        });
    }

    private String teksPrinter() {
        String mac = prefs.getString("printer_mac", "");
        if (mac.isEmpty()) return "Belum dipilih";
        return prefs.getString("printer_name", "") + " (" + mac + ")";
    }

    // ------------------------------------------------------------------ izin

    private boolean punyaIzinBluetooth() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void mintaIzinBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && !punyaIzinBluetooth()) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_BT && !punyaIzinBluetooth()) {
            toast("Aplikasi butuh izin Perangkat Sekitar/Bluetooth untuk mencetak");
        }
    }

    // ------------------------------------------------------------------ tampilan

    private void bangunLayar() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F2F4F7"));
        int p = dp(16);
        root.setPadding(p, p, p, p);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout judul = new LinearLayout(this);
        judul.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("Cetak Nomor Antrian");
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#111827"));
        judul.addView(t);
        tvPrinter = new TextView(this);
        tvPrinter.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvPrinter.setTextColor(Color.parseColor("#6B7280"));
        judul.addView(tvPrinter);
        bar.addView(judul, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnSet = new Button(this);
        btnSet.setText("Pengaturan");
        btnSet.setOnClickListener(v -> bukaPengaturan());
        bar.addView(btnSet);
        root.addView(bar);

        grid = new GridLayout(this);
        ScrollView sv = new ScrollView(this);
        sv.addView(grid);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(12);
        root.addView(sv, lp);

        setContentView(root);
    }

    private void render() {
        if (grid == null) return;
        cekGantiHari();
        String tgl = new SimpleDateFormat("EEEE, d MMMM yyyy", ID).format(new Date());
        tvPrinter.setText(tgl + "  •  Printer: " + teksPrinter());

        boolean landscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        grid.removeAllViews();
        grid.setColumnCount(landscape ? 3 : 2);

        for (String[] l : LOKET) {
            final String kode = l[0];
            int last = terakhir(kode);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = dp(14);
            card.setPadding(p, p, p, p);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.WHITE);
            bg.setCornerRadius(dp(12));
            card.setBackground(bg);

            TextView nama = new TextView(this);
            nama.setText("Loket " + l[1]);
            nama.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            nama.setTypeface(Typeface.DEFAULT_BOLD);
            nama.setGravity(Gravity.CENTER);
            nama.setTextColor(Color.parseColor("#111827"));
            card.addView(nama);

            TextView next = new TextView(this);
            next.setText(fmt(kode, last + 1));
            next.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
            next.setTypeface(Typeface.DEFAULT_BOLD);
            next.setTextColor(BIRU);
            next.setGravity(Gravity.CENTER);
            card.addView(next);

            Button cetak = new Button(this);
            cetak.setText(sedangCetak ? "Mencetak..." : "CETAK");
            cetak.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            cetak.setTextColor(Color.WHITE);
            GradientDrawable bb = new GradientDrawable();
            bb.setColor(sedangCetak ? Color.parseColor("#9CA3AF") : BIRU);
            bb.setCornerRadius(dp(10));
            cetak.setBackground(bb);
            cetak.setEnabled(!sedangCetak);
            cetak.setOnClickListener(v -> cetakBerikut(kode));
            card.addView(cetak, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button atur = new Button(this);
            atur.setText("Samakan No.");
            atur.setOnClickListener(v -> aturNomor(kode));
            Button ulang = new Button(this);
            ulang.setText(last > 0 ? "Ulang " + fmt(kode, last) : "Cetak Ulang");
            ulang.setEnabled(last > 0 && !sedangCetak);
            ulang.setOnClickListener(v -> cetakUlang(kode));
            row.addView(atur, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(ulang, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = dp(6);
            card.addView(row, rp);

            TextView info = new TextView(this);
            info.setText("Terakhir dicetak: " + (last > 0 ? fmt(kode, last) : "-"));
            info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            info.setTextColor(Color.parseColor("#6B7280"));
            info.setGravity(Gravity.CENTER);
            card.addView(info);

            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f));
            gp.width = 0;
            gp.setMargins(dp(6), dp(6), dp(6), dp(6));
            grid.addView(card, gp);
        }
    }

    // ------------------------------------------------------------------ helper

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(14), 0, dp(2));
        return t;
    }

    private View bungkus(View v) {
        LinearLayout box = new LinearLayout(this);
        int p = dp(20);
        box.setPadding(p, 0, p, 0);
        box.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
