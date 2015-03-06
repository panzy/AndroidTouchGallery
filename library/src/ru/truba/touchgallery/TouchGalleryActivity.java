package ru.truba.touchgallery;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.ActionBarActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Window;
import ru.truba.touchgallery.GalleryWidget.BasePagerAdapter;
import ru.truba.touchgallery.GalleryWidget.GalleryViewPager;
import ru.truba.touchgallery.TouchView.UrlTouchImageView;

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

    public static final int IMG_LOAD_WIDTH = 1280;
    public static final int IMG_LOAD_HEIGHT = 720;
    public static final int IMG_PRELOAD_WIDTH = 320;
    public static final int IMG_PRELOAD_HEIGHT = 180;

    private GalleryViewPager mViewPager;

    protected ArrayList<String> items;
    protected int position = 0;
    
    private int imgLoadWidth, imgLoadHeight, imgPreloadWidth, imgPreloadHeight;

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
    }
}