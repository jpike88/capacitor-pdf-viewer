package ch.nadlo.oss.capacitor.pdf_viewer;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager2.widget.ViewPager2;

import com.github.chrisbanes.photoview.PhotoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PdfViewerActivity extends Activity {

    public static final String EXTRA_URL = "url";

    public static final String EXTRA_TOP = "top";

    private ViewPager2 viewPager;
    private ProgressBar progress;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor fileDescriptor;

    private File tempFile; // cleanup
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setFinishOnTouchOutside(false);

        int top = getIntent().getIntExtra(EXTRA_TOP, 0);

        if (top > 0) {
            WindowManager.LayoutParams params = getWindow().getAttributes();

            params.y = top;
            params.height = getResources().getDisplayMetrics().heightPixels - top;
            params.width = WindowManager.LayoutParams.MATCH_PARENT;

            getWindow().setAttributes(params);

            // Let touches outside this window (the top 100px) pass through
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            );

            // Optional: make sure background isn’t dimming
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

            // Optional: allow layout without being forced fullscreen
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            );
        }

        setContentView(R.layout.activity_pdf_viewer);

        viewPager = findViewById(R.id.viewPager);
        if (viewPager == null) {
            throw new IllegalStateException("ViewPager2 not found in activity_pdf_viewer.xml");
        }

        // keep screen on + immersive
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );

        String url = getIntent().getStringExtra(EXTRA_URL);

        io.execute(() -> {
            try {
                File pdfFile;
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    pdfFile = downloadToCache(getApplicationContext(), url);
                    tempFile = pdfFile;
                } else if (url.startsWith("content://")) {
                    pdfFile = copyContentUriToCache(getApplicationContext(), Uri.parse(url));
                    tempFile = pdfFile;
                } else {
                    pdfFile = url.startsWith("file://") ?
                            new File(Uri.parse(url).getPath()) : new File(url);
                }

                fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
                pdfRenderer = new PdfRenderer(fileDescriptor);

                runOnUiThread(() -> {
                    viewPager.setAdapter(new PdfPagerAdapter(pdfRenderer));
                    viewPager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
                });
            } catch (Exception e) {
                runOnUiThread(this::finish);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pdfRenderer != null) pdfRenderer.close();
        if (fileDescriptor != null) {
            try {
                fileDescriptor.close();
            } catch (Exception ignored) {
            }
        }
        if (tempFile != null && tempFile.exists()) tempFile.delete();
        io.shutdownNow();
    }

    // ---------- Helpers ----------
    private static File downloadToCache(Context ctx, String urlStr) throws Exception {
        File out = File.createTempFile("triapp_pdf_", ".pdf", ctx.getCacheDir());
        try (InputStream in = new URL(urlStr).openStream();
             FileOutputStream outStream = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) outStream.write(buf, 0, n);
        }
        return out;
    }

    private static File copyContentUriToCache(Context ctx, Uri uri) throws Exception {
        File out = File.createTempFile("triapp_pdf_", ".pdf", ctx.getCacheDir());
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream outStream = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) outStream.write(buf, 0, n);
        }
        return out;
    }

    // ---------- Adapter ----------
    private static class PdfPagerAdapter extends androidx.recyclerview.widget.RecyclerView.Adapter<PdfPageVH> {
        private final PdfRenderer renderer;

        PdfPagerAdapter(PdfRenderer renderer) {
            this.renderer = renderer;
        }

        @NonNull
        @Override
        public PdfPageVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            PhotoView pv = new PhotoView(parent.getContext());
            pv.setBackgroundColor(Color.WHITE);

            // 👇 Force each page to match the parent's height
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            pv.setLayoutParams(lp);

            pv.setScaleType(ImageView.ScaleType.FIT_CENTER); // keeps aspect ratio
            pv.setAdjustViewBounds(false);

            return new PdfPageVH(pv);
        }


        @Override
        public void onBindViewHolder(@NonNull PdfPageVH holder, int position) {
            PdfRenderer.Page page = renderer.openPage(position);

            int viewWidth = holder.photoView.getWidth();
            if (viewWidth == 0) {
                viewWidth = holder.itemView.getResources().getDisplayMetrics().widthPixels;
            }

            // Maintain page aspect ratio
            float pageAspect = (float) page.getWidth() / (float) page.getHeight();
            int targetWidth = viewWidth;
            int targetHeight = (int) (viewWidth / pageAspect);

            // Create bitmap at target size
            Bitmap bmp = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);

            // Build scaling matrix (PDF points → target bitmap pixels)
            float scaleX = (float) targetWidth / (float) page.getWidth();
            float scaleY = (float) targetHeight / (float) page.getHeight();
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.setScale(scaleX, scaleY);

            // Render with matrix
            page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            holder.photoView.setImageBitmap(bmp);
            page.close();
        }

        @Override
        public int getItemCount() {
            return renderer.getPageCount();
        }
    }

    private static class PdfPageVH extends androidx.recyclerview.widget.RecyclerView.ViewHolder {
        final PhotoView photoView;

        PdfPageVH(@NonNull PhotoView pv) {
            super(pv);
            this.photoView = pv;
        }
    }
}
