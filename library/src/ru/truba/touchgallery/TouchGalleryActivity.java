package ru.truba.touchgallery;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.util.Pair;
import android.view.View;
import android.view.Window;
import android.widget.Toast;
import ru.truba.touchgallery.GalleryWidget.BasePagerAdapter;
import ru.truba.touchgallery.GalleryWidget.GalleryViewPager;
import ru.truba.touchgallery.TouchView.UrlTouchImageView;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;

public class TouchGalleryActivity extends ActionBarActivity
    implements BasePagerAdapter.OnItemChangeListener
{

    /**
     * Input URL list as String ArrayList. To support custom URL, override {@link #parseUrl}.
     */
    public static final String EXTRA_URLS = "extra_urls";
    public static final String EXTRA_POSITION = "extra_position";
    /**
     * Width limit on image being displayed on current page.
     */
    public static final String EXTRA_IMG_LOAD_WIDTH = "extra_img_load_width";
    /**
     * Height limit on image being displayed on current page.
     */
    public static final String EXTRA_IMG_LOAD_HEIGHT = "extra_img_load_height";
    /**
     * Width limit on image being displayed on nearby pages.
     */
    public static final String EXTRA_IMG_PRELOAD_WIDTH = "extra_img_preload_width";
    /**
     * Height limit on image being displayed on nearby pages.
     */
    public static final String EXTRA_IMG_PRELOAD_HEIGHT = "extra_img_preload_height";

    public static final int IMG_LOAD_WIDTH = 320;
    public static final int IMG_LOAD_HEIGHT = 180;
    public static final int IMG_PRELOAD_WIDTH = 320;
    public static final int IMG_PRELOAD_HEIGHT = 180;

    private GalleryViewPager mViewPager;

    protected ArrayList<String> items;
    protected int position = 0;
    
    private int imgLoadWidth, imgLoadHeight, imgPreloadWidth, imgPreloadHeight;

    private boolean isShowingPropertiesDlg = false;
    private AlertDialog propertiesDlg;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().clearFlags(Window.FEATURE_ACTION_BAR); // for Toolbar
        setContentView(R.layout.activity_image_viewer);

        if (savedInstanceState != null) {
            items = savedInstanceState.getStringArrayList(EXTRA_URLS);
            position = savedInstanceState.getInt(EXTRA_POSITION);
            imgLoadWidth = savedInstanceState.getInt(EXTRA_IMG_LOAD_WIDTH, IMG_LOAD_WIDTH);
            imgLoadHeight = savedInstanceState.getInt(EXTRA_IMG_LOAD_HEIGHT, IMG_LOAD_HEIGHT);
            imgPreloadWidth = savedInstanceState.getInt(EXTRA_IMG_PRELOAD_WIDTH, IMG_PRELOAD_WIDTH);
            imgPreloadHeight = savedInstanceState.getInt(EXTRA_IMG_PRELOAD_HEIGHT, IMG_PRELOAD_HEIGHT);
        } else {
            Intent intent = getIntent();
            items = intent.getStringArrayListExtra(EXTRA_URLS);
            position = intent.getIntExtra(EXTRA_POSITION, position);
            imgLoadWidth = intent.getIntExtra(EXTRA_IMG_LOAD_WIDTH, IMG_LOAD_WIDTH);
            imgLoadHeight = intent.getIntExtra(EXTRA_IMG_LOAD_HEIGHT, IMG_LOAD_HEIGHT);
            imgPreloadWidth = intent.getIntExtra(EXTRA_IMG_PRELOAD_WIDTH, IMG_PRELOAD_WIDTH);
            imgPreloadHeight = intent.getIntExtra(EXTRA_IMG_PRELOAD_HEIGHT, IMG_PRELOAD_HEIGHT);
        }

        if (items == null)
            items = new ArrayList<>();

        ArrayList<URL> urls = new ArrayList<>(items.size());
        for (String item : items) {
            URL url = parseUrl(item);
            if (url != null) {
                urls.add(url);
            }
        }

        UrlTouchImageView.bmpCnt = 0;

        ru.truba.touchgallery.GalleryWidget.UrlPagerAdapter pagerAdapter =
                new ru.truba.touchgallery.GalleryWidget.UrlPagerAdapter(this, urls, 0);
        pagerAdapter.setBmpSizeLimit(imgLoadWidth, imgLoadHeight, imgPreloadWidth, imgPreloadHeight);
        pagerAdapter.setOnItemChangeListener(this);

        Toolbar toolbar = (Toolbar)findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_menu_back);
        toolbar.setNavigationContentDescription("back");
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onBackPressed();
            }
        });

        mViewPager = (GalleryViewPager)findViewById(R.id.viewer);
        mViewPager.setOffscreenPageLimit(3);
        mViewPager.setAdapter(pagerAdapter);
        mViewPager.setCurrentItem(position);
        mViewPager.setOnItemClickListener(new GalleryViewPager.OnItemClickListener() {
            @Override
            public void onItemClicked(View view, int position) {
                toggleActionBar();
            }
        });

        if (savedInstanceState != null) {
            isShowingPropertiesDlg = savedInstanceState.getBoolean("isShowingPropertiesDlg");
        }

        if (isShowingPropertiesDlg) {
            viewImageProperties();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (propertiesDlg != null) {
            propertiesDlg.dismiss();
        }
    }

    @Override
    public void onItemChange(int i) {
        position = i;
        updateTitle();
    }

    protected URL parseUrl(String s) {
        try {
            return new URL(s);
        }
        catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void toggleActionBar() {
        if (getSupportActionBar().isShowing())
            getSupportActionBar().hide();
        else
            getSupportActionBar().show();
    }

    private void updateTitle() {
        setTitle(String.format("%d/%d", position + 1, items.size()));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putStringArrayList(EXTRA_URLS, items);
        outState.putInt(EXTRA_POSITION, position);
        outState.putBoolean("isShowingPropertiesDlg", isShowingPropertiesDlg);
    }

    /** Save image. */
    public void save() {
        File srcFile = getCurrentImageFile();

        if (srcFile != null && srcFile.exists()) {
            String outDir = Environment.getExternalStorageDirectory().getPath() + "/"
                    + Environment.DIRECTORY_PICTURES;
            String shortFileName = getShortFileName(srcFile.getPath());
            File dst = new File(getOutputPath(outDir, shortFileName));

            try {
                if (!new File(outDir).exists())
                    new File(outDir).mkdirs();

                copy(new FileInputStream(srcFile), dst);

                // add to media provider
                sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)));

                Toast.makeText(this, getString(R.string.image_saved_to, dst), Toast.LENGTH_LONG).show();
            }
            catch (IOException e) {
                e.printStackTrace();
                Toast.makeText(this, getString(R.string.image_failed_to_save, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, getString(R.string.image_operation_failed_due_to_invalid_src), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Get local file of current image - not valid if downloading is in progress.
     * @return maybe null or not exist.
     */
    protected File getCurrentImageFile() {
        File srcFile;
        URL srcUrl = parseUrl(items.get(position));

        if (srcUrl.getProtocol().equals("file")) {
            srcFile = new File(srcUrl.getFile());
        } else {
            srcFile = new File(UrlTouchImageView.getCachePath(this, srcUrl));
        }
        return srcFile;
    }

    private static String getShortFileName(String path) {
        int slashPos = path.lastIndexOf('/');
        if (slashPos > 0)
            return path.substring(slashPos + 1);
        return path;
    }

    /**
     * @param outDir not necessarily ends with '/'
     * @param shortFileName
     * @return
     */
    private static String getOutputPath(String outDir, String shortFileName) {
        String name, extWithDot;
        int dotPos = shortFileName.lastIndexOf('.');
        if (dotPos > 0) {
            name = shortFileName.substring(0, dotPos);
            extWithDot = shortFileName.substring(dotPos);
        } else {
            name = shortFileName;
            extWithDot = "";
        }

        if (!new File(outDir + "/" + name + extWithDot).exists()) {
            return outDir + "/" + name + extWithDot;
        }

        int idx = 2;
        while (new File(outDir + "/" + name + "(" + idx + ")" + extWithDot).exists()) {
            ++idx;
        }
        return outDir + "/" + name + "(" + idx + ")" + extWithDot;
    }

    private static void copy(InputStream in, File dst) throws IOException {

        OutputStream out = new FileOutputStream(dst);

        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    protected void viewWithOtherApp() {
        File file = getCurrentImageFile();
        if (file != null && file.exists()) {
            Uri uri = Uri.fromFile(file);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/*");
            startActivity(Intent.createChooser(intent, null));
        } else {
            Toast.makeText(this, getString(R.string.image_operation_failed_due_to_invalid_src), Toast.LENGTH_LONG).show();
        }
    }

    protected void viewImageProperties() {
        File file = getCurrentImageFile();
        if (file != null && file.exists()) {
            String path = file.getAbsolutePath();
            String size = formatFileSize((int) new File(path).length());
            Pair<Integer, Integer> resolution = getImageResolution(path);

            StringBuilder sb = new StringBuilder()
                    .append(path).append("\n\n")
                    .append("Size:").append(size).append("\n")
                    .append("Resolution:")
                    .append(resolution.first).append("x").append(resolution.second).append("\n");

            propertiesDlg = new AlertDialog.Builder(this)
                    .setTitle(R.string.image_properties)
                    .setMessage(sb.toString())
                    .create();
            propertiesDlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    isShowingPropertiesDlg = false;
                }
            });
            propertiesDlg.setCanceledOnTouchOutside(true);
            propertiesDlg.show();
            isShowingPropertiesDlg = true;
        } else {
            Toast.makeText(this, getString(R.string.image_operation_failed_due_to_invalid_src,
                    file == null ? "null" : file.getAbsoluteFile()), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * @param size in bytes.
     * @return
     */
    private String formatFileSize(int size) {
        if (size >= 1024 * 1024) {
            return String.format("%.1fMB", (float) size / 1024 / 1024);
        } else if (size >= 1024) {
            return String.format("%dKB", size / 1024);
        } else {
            return String.format("%dB", size);
        }
    }

    private static Pair<Integer, Integer> getImageResolution(String path) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        return new Pair<>(options.outWidth, options.outHeight);
    }
}