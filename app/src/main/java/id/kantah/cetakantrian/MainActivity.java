package id.kantah.cetakantrian;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
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
    // Tema merah
    private static final int MERAH = Color.parseColor("#C62828");
    private static final int MERAH_TUA = Color.parseColor("#8E1B1B");
    private static final int MERAH_MUDA = Color.parseColor("#FDECEC");
    private static final int MERAH_GARIS = Color.parseColor("#F2C4C4");
    private static final int LATAR = Color.parseColor("#F7F3F3");
    private static final int TEKS = Color.parseColor("#1F2937");
    private static final int ABU = Color.parseColor("#6B7280");
    private static final int HIJAU = Color.parseColor("#16A34A");
    private static final int KUNING = Color.parseColor("#F59E0B");

    private final Locale ID = new Locale("id", "ID");
    private final ExecutorService printThread = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private TextView tvPrinter, tvInstansi, tvJam, tvTanggal;
    private View dotPrinter;
    private GridLayout grid;
    /** Kode loket yang sedang dicetak, null jika tidak ada. */
    private String kodeCetak = null;
    private String hariTampil = "";

    // Ukuran yang menyesuaikan layar (dihitung ulang saat layar diputar)
    private float skala = 1f;
    private int kolom = 2;
    private boolean ringkas = false;
    private boolean sempit = false;

    private final Runnable detak = new Runnable() {
        @Override public void run() {
            perbaruiJam();
            if (!hariIni().equals(hariTampil)) render(); // lewat tengah malam -> reset
            ui.postDelayed(this, 15000);
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(MERAH_TUA);
        prefs = getSharedPreferences("antrian", Context.MODE_PRIVATE);
        bangunLayar();
        mintaIzinBluetooth();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render(); // cek ganti hari setiap aplikasi dibuka lagi
        ui.removeCallbacks(detak);
        ui.post(detak);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(detak);
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        bangunLayar(); // ukuran & jumlah kolom ikut layar baru
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
        if (kodeCetak != null) return;
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

        kodeCetak = kode;
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
                kodeCetak = null;
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
        box.setPadding(p, dp(8), p, dp(12));

        final TextView tvPilih = new TextView(this);
        tvPilih.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvPilih.setTextColor(TEKS);
        tvPilih.setText(teksPrinter());
        box.addView(label("Printer"));
        box.addView(tvPilih);

        Button btnPilih = tombol("Pilih Printer Bluetooth", MERAH, Color.WHITE, 0, 15);
        btnPilih.setOnClickListener(v -> pilihPrinter(tvPilih));
        box.addView(btnPilih, barisTombol(8, 48));

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

        box.addView(label("Lainnya"));
        Button btnTes = tombol("Tes Cetak", Color.WHITE, MERAH, MERAH_GARIS, 15);
        btnTes.setOnClickListener(v -> {
            simpanTeks(etHeader, etFooter);
            tesCetak();
        });
        box.addView(btnTes, barisTombol(4, 48));

        Button btnReset = tombol("Reset Semua Nomor Hari Ini", MERAH_MUDA, MERAH_TUA, 0, 15);
        btnReset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Semua loket kembali ke nomor 001?")
                .setPositiveButton("Ya, reset", (d, w) -> {
                    for (String[] l : LOKET) setTerakhir(l[0], 0);
                    render();
                    toast("Nomor direset");
                })
                .setNegativeButton("Batal", null)
                .show());
        box.addView(btnReset, barisTombol(8, 48));

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

    /** Hitung ukuran tampilan berdasarkan layar: HP, tablet 7", tablet 10", potret/landscape. */
    private void hitungUkuran() {
        Configuration c = getResources().getConfiguration();
        int lebar = c.screenWidthDp, tinggi = c.screenHeightDp, terkecil = c.smallestScreenWidthDp;

        if (terkecil >= 720) skala = 1.3f;        // tablet 10"
        else if (terkecil >= 600) skala = 1.15f;  // tablet 7-8"
        else if (terkecil < 360) skala = 0.9f;    // HP kecil
        else skala = 1f;                          // HP

        kolom = lebar >= 960 ? 3 : lebar >= 560 ? 2 : 1;
        ringkas = kolom == 1 || tinggi < 480;     // kartu mendatar agar muat tanpa banyak gulir
        sempit = lebar < 560;
    }

    private void bangunLayar() {
        hitungUkuran();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(LATAR);

        // --- header merah
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(MERAH);
        int ph = dpS(20), pv = dpS(ringkas ? 10 : 16);
        bar.setPadding(ph, pv, dpS(12), pv);
        if (Build.VERSION.SDK_INT >= 21) bar.setElevation(dp(4));

        LinearLayout judul = new LinearLayout(this);
        judul.setOrientation(LinearLayout.VERTICAL);
        TextView t = teks("Cetak Nomor Antrian", 22, Color.WHITE, true);
        t.setGravity(Gravity.START);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        judul.addView(t);
        tvInstansi = teks("", 13, Color.parseColor("#FFD9D9"), false);
        tvInstansi.setGravity(Gravity.START);
        tvInstansi.setSingleLine(true);
        tvInstansi.setEllipsize(TextUtils.TruncateAt.END);
        judul.addView(tvInstansi);
        bar.addView(judul, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // jam & tanggal (di layar sempit tanggal pindah ke baris status)
        LinearLayout jam = new LinearLayout(this);
        jam.setOrientation(LinearLayout.VERTICAL);
        jam.setGravity(Gravity.END);
        tvJam = teks("", sempit ? 18 : 26, Color.WHITE, true);
        tvJam.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        jam.addView(tvJam);
        tvTanggal = teks("", 12, sempit ? ABU : Color.parseColor("#FFD9D9"), false);
        if (!sempit) jam.addView(tvTanggal);
        LinearLayout.LayoutParams jp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        jp.leftMargin = dpS(12);
        jp.rightMargin = dpS(12);
        bar.addView(jam, jp);

        Button btnSet = tombol(sempit ? "Atur" : "Pengaturan",
                MERAH_TUA, Color.WHITE, Color.parseColor("#B23A3A"), 14);
        btnSet.setOnClickListener(v -> bukaPengaturan());
        bar.addView(btnSet, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dpS(40)));
        root.addView(bar);

        // --- baris status printer (ketuk untuk membuka pengaturan)
        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.HORIZONTAL);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dpS(14), dpS(8), dpS(14), dpS(8));
        status.setBackground(riak(kotak(Color.WHITE, 24, MERAH_GARIS, 1)));
        status.setClickable(true);
        status.setOnClickListener(v -> bukaPengaturan());
        dotPrinter = new View(this);
        status.addView(dotPrinter, new LinearLayout.LayoutParams(dp(10), dp(10)));
        tvPrinter = teks("", 13, TEKS, false);
        tvPrinter.setSingleLine(true);
        tvPrinter.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(8);
        status.addView(tvPrinter, tp);

        LinearLayout statusBaris = new LinearLayout(this);
        statusBaris.setOrientation(sempit ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        int m = dpS(ringkas ? 10 : 14);
        statusBaris.setPadding(m, dpS(10), m, 0);
        statusBaris.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (sempit) {
            tvTanggal.setGravity(Gravity.START);
            tvTanggal.setPadding(dp(4), dp(6), 0, 0);
            statusBaris.addView(tvTanggal);
        }
        root.addView(statusBaris);

        // --- kartu loket
        grid = new GridLayout(this);
        int g = dpS(ringkas ? 6 : 8);
        grid.setPadding(g, g, g, g);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true); // kartu mengisi layar jika muat
        scroll.addView(grid);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        perbaruiJam();
    }

    private void perbaruiJam() {
        if (tvJam == null) return;
        Date now = new Date();
        tvJam.setText(new SimpleDateFormat("HH:mm", ID).format(now));
        tvTanggal.setText(new SimpleDateFormat("EEEE, d MMMM yyyy", ID).format(now));
    }

    private void render() {
        if (grid == null) return;
        cekGantiHari();
        hariTampil = hariIni();
        perbaruiJam();

        tvInstansi.setText(prefs.getString("header", DEFAULT_HEADER).replace("\n", " "));
        boolean adaPrinter = !prefs.getString("printer_mac", "").isEmpty();
        tvPrinter.setText(adaPrinter
                ? "Printer: " + prefs.getString("printer_name", "")
                : "Printer belum dipilih - ketuk untuk memilih");
        dotPrinter.setBackground(kotak(adaPrinter ? HIJAU : KUNING, 5, 0, 0));

        grid.removeAllViews();
        grid.setColumnCount(kolom);

        for (int i = 0; i < LOKET.length; i++) {
            String[] l = LOKET[i];
            View card = ringkas ? kartuRingkas(l[0], l[1]) : kartuBesar(l[0], l[1]);

            // kartu terakhir melebar jika sendirian di barisnya
            boolean sendirian = i == LOKET.length - 1 && kolom > 1 && LOKET.length % kolom == 1;
            GridLayout.LayoutParams gp = new GridLayout.LayoutParams(
                    ringkas ? GridLayout.spec(GridLayout.UNDEFINED)
                            : GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, sendirian ? kolom : 1, 1f));
            gp.width = 0;
            int mg = dpS(ringkas ? 4 : 6);
            gp.setMargins(mg, mg, mg, mg);
            grid.addView(card, gp);
        }
    }

    /** Kartu tegak untuk tablet. */
    private View kartuBesar(final String kode, String nama) {
        int last = terakhir(kode);
        boolean cetakIni = kode.equals(kodeCetak);

        LinearLayout card = kartuDasar(LinearLayout.VERTICAL);
        View garis = new View(this);
        garis.setBackgroundColor(MERAH);
        card.addView(garis, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        LinearLayout isi = new LinearLayout(this);
        isi.setOrientation(LinearLayout.VERTICAL);
        isi.setGravity(Gravity.CENTER);
        int p = dpS(16);
        isi.setPadding(p, dpS(12), p, p);
        card.addView(isi, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        isi.addView(kepala(kode, nama, true));

        TextView lbl = teks("NOMOR BERIKUTNYA", 11, ABU, true);
        if (Build.VERSION.SDK_INT >= 21) lbl.setLetterSpacing(0.12f);
        lbl.setPadding(0, dpS(10), 0, 0);
        isi.addView(lbl);

        TextView next = teks(fmt(kode, last + 1), 52, MERAH, true);
        next.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        next.setIncludeFontPadding(false);
        next.setPadding(0, dpS(2), 0, dpS(10));
        isi.addView(next);

        Button cetak = tombol(cetakIni ? "Mencetak..." : "CETAK", MERAH, Color.WHITE, 0, 20);
        aktif(cetak, kodeCetak == null);
        cetak.setOnClickListener(v -> cetakBerikut(kode));
        isi.addView(cetak, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpS(60)));

        isi.addView(tombolKecil(kode, last, 42, 14, "Samakan No.",
                last > 0 ? "Ulang " + fmt(kode, last) : "Cetak Ulang"), barisTombol(8));

        TextView info = teks("Terakhir dicetak: " + (last > 0 ? fmt(kode, last) : "-"), 12, ABU, false);
        info.setPadding(0, dpS(8), 0, 0);
        isi.addView(info);
        return card;
    }

    /** Kartu mendatar untuk HP / layar pendek. */
    private View kartuRingkas(final String kode, String nama) {
        int last = terakhir(kode);
        boolean cetakIni = kode.equals(kodeCetak);

        LinearLayout card = kartuDasar(LinearLayout.HORIZONTAL);
        View garis = new View(this);
        garis.setBackgroundColor(MERAH);
        card.addView(garis, new LinearLayout.LayoutParams(dp(5), ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout isi = new LinearLayout(this);
        isi.setOrientation(LinearLayout.HORIZONTAL);
        isi.setGravity(Gravity.CENTER_VERTICAL);
        int p = dpS(12);
        isi.setPadding(p, p, p, p);
        card.addView(isi, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout kiri = new LinearLayout(this);
        kiri.setOrientation(LinearLayout.VERTICAL);
        kiri.addView(kepala(kode, nama, false));
        TextView next = teks(fmt(kode, last + 1), 38, MERAH, true);
        next.setGravity(Gravity.START);
        next.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        next.setIncludeFontPadding(false);
        next.setPadding(0, dpS(6), 0, dpS(2));
        kiri.addView(next);
        TextView info = teks("Terakhir: " + (last > 0 ? fmt(kode, last) : "-"), 12, ABU, false);
        info.setGravity(Gravity.START);
        kiri.addView(info);
        isi.addView(kiri, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout kanan = new LinearLayout(this);
        kanan.setOrientation(LinearLayout.VERTICAL);
        Button cetak = tombol(cetakIni ? "Mencetak..." : "CETAK", MERAH, Color.WHITE, 0, 18);
        aktif(cetak, kodeCetak == null);
        cetak.setOnClickListener(v -> cetakBerikut(kode));
        kanan.addView(cetak, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpS(52)));
        kanan.addView(tombolKecil(kode, last, 36, 12, "Samakan", "Ulang"), barisTombol(6));
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(
                dpS(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        kp.leftMargin = dpS(10);
        isi.addView(kanan, kp);
        return card;
    }

    private LinearLayout kartuDasar(int arah) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(arah);
        card.setBackground(kotak(Color.WHITE, 16, 0, 0));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(2));
            card.setClipToOutline(true);
        }
        return card;
    }

    /** Lencana kode loket + nama loket. */
    private View kepala(String kode, String nama, boolean tengah) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL | (tengah ? Gravity.CENTER_HORIZONTAL : Gravity.START));

        TextView badge = teks(kode, 13, MERAH_TUA, true);
        badge.setBackground(kotak(MERAH_MUDA, 8, 0, 0));
        badge.setPadding(dpS(8), dpS(3), dpS(8), dpS(3));
        row.addView(badge);

        TextView tvNama = teks(nama, ringkas ? 15 : 17, TEKS, true);
        tvNama.setMaxLines(2);
        tvNama.setEllipsize(TextUtils.TruncateAt.END);
        tvNama.setGravity(tengah ? Gravity.CENTER : Gravity.START);
        LinearLayout.LayoutParams np = tengah
                ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT)
                : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dpS(8);
        row.addView(tvNama, np);
        return row;
    }

    /** Baris tombol "Samakan" dan "Cetak Ulang". */
    private View tombolKecil(final String kode, int last, int tinggi, float ukuran,
                             String teksAtur, String teksUlang) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button atur = tombol(teksAtur, Color.WHITE, MERAH, MERAH_GARIS, ukuran);
        aktif(atur, kodeCetak == null);
        atur.setOnClickListener(v -> aturNomor(kode));
        Button ulang = tombol(teksUlang, Color.WHITE, MERAH, MERAH_GARIS, ukuran);
        aktif(ulang, last > 0 && kodeCetak == null);
        ulang.setOnClickListener(v -> cetakUlang(kode));

        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dpS(tinggi), 1f);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dpS(tinggi), 1f);
        b.leftMargin = dpS(6);
        row.addView(atur, a);
        row.addView(ulang, b);
        return row;
    }

    // ------------------------------------------------------------------ helper

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(MERAH_TUA);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private TextView teks(String s, float sp, int warna, boolean tebal) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * skala);
        t.setTextColor(warna);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        if (tebal) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Tombol datar dengan sudut membulat dan efek sentuh. */
    private Button tombol(String s, int latar, int warnaTeks, int garis, float sp) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(warnaTeks);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * skala);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackground(riak(kotak(latar, 10, garis, garis == 0 ? 0 : 1)));
        if (Build.VERSION.SDK_INT >= 21) b.setStateListAnimator(null);
        return b;
    }

    private void aktif(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.4f);
    }

    private GradientDrawable kotak(int isi, int radiusDp, int garis, int tebalGarisDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(isi);
        d.setCornerRadius(dp(radiusDp));
        if (tebalGarisDp > 0) d.setStroke(dp(tebalGarisDp), garis);
        return d;
    }

    private Drawable riak(GradientDrawable isi) {
        return new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33000000")), isi, null);
    }

    private LinearLayout.LayoutParams barisTombol(int atasDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(atasDp);
        return lp;
    }

    private LinearLayout.LayoutParams barisTombol(int atasDp, int tinggiDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(tinggiDp));
        lp.topMargin = dp(atasDp);
        return lp;
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

    /** dp yang ikut diperbesar di tablet. */
    private int dpS(int v) {
        return Math.round(v * skala * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
