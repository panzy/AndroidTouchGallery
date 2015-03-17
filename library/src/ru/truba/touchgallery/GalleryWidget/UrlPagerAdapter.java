/*
 Copyright (c) 2013 Roman Truba

 Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all copies or substantial
 portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
 THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package ru.truba.touchgallery.GalleryWidget;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import ru.truba.touchgallery.TouchView.UrlTouchImageView;

import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


/**
 Class wraps URLs to adapter, then it instantiates {@link ru.truba.touchgallery.TouchView.UrlTouchImageView} objects to paging up through them.
 */
public class UrlPagerAdapter extends BasePagerAdapter {

    /**
     * {@link Message#obj} = UrlTouchImageView;
     * {@link Message#arg1} = position
     */
    private static final int MSG_LOAD_LARGE_IMG = 1;

    // image size limit
    int maxWidth = 1280;
    int maxHeight = 720;
    int maxPreloadWidth = 320;
    int maxPreloadHeight = 240;

    UrlTouchImageView currUrlTouchImageView;

    protected final List<URL> mUrls;

    private Handler handler = new MyHandler(this);

    private static class MyHandler extends Handler {
        WeakReference<UrlPagerAdapter> adapterWeakReference;

        public MyHandler(UrlPagerAdapter urlPagerAdapter) {
            adapterWeakReference = new WeakReference<>(urlPagerAdapter);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOAD_LARGE_IMG:
                    UrlPagerAdapter adapter = adapterWeakReference.get();
                    if (adapter != null) {
                        ((UrlTouchImageView) msg.obj).setUrl(
                                adapter.mUrls.get(msg.arg1),
                                adapter.maxWidth,
                                adapter.maxHeight, true);
                    }
                    return;
            }
            super.handleMessage(msg);
        }
    };

    /**
     * @param context
     * @param urls
     * @param dummy not used
     */
	public UrlPagerAdapter(Context context, List<URL> urls, int dummy)
	{
        super(context, urls2literals(urls));
        mUrls = urls;
	}

    public UrlPagerAdapter(Context context, List<String> urls)
    {
        super(context, urls);
        mUrls = literals2urls(urls);
    }

    private static List<String> urls2literals(List<URL> urls) {
        List<String> literals = new ArrayList<>(urls.size());
        for (URL url : urls) {
            literals.add(url.toExternalForm());
        }
        return literals;
    }

    private static List<URL> literals2urls(List<String> literalUrls) {
        List<URL> urls = new ArrayList<>(literalUrls.size());
        for (String url : literalUrls) {
            try {
                urls.add(new URL(url));
            }
            catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        return urls;
    }

    /**
     * Set max size of decoded bitmap.
     * @param width
     * @param height
     * @param preloadWidth size for non-current item.
     * @param preloadHeight size for non-current item.
     */
    public void setBmpSizeLimit(int width, int height, int preloadWidth, int preloadHeight) {
        maxWidth = width;
        maxHeight = height;
        maxPreloadWidth = preloadWidth;
        maxPreloadHeight = preloadHeight;
    }

    @Override
    public void setPrimaryItem(ViewGroup container, int position, Object object) {
        if (currUrlTouchImageView != null && mCurrentPosition != position) {
            currUrlTouchImageView.setUrl(mUrls.get(mCurrentPosition), maxPreloadWidth, maxPreloadHeight, false);
        }

        if (mCurrentPosition != position) {
            Message msg = new Message();
            msg.what = MSG_LOAD_LARGE_IMG;
            msg.obj = object;
            msg.arg1 = position;
            handler.removeMessages(MSG_LOAD_LARGE_IMG);
            handler.sendMessageDelayed(msg, 600);
        }

        super.setPrimaryItem(container, position, object);
        ((GalleryViewPager)container).mCurrentView = ((UrlTouchImageView)object).getImageView();
        currUrlTouchImageView = (UrlTouchImageView)object;
    }

    @Override
    public void destroyItem(ViewGroup collection, int position, Object view) {
        collection.removeView((View) view);
        if (view instanceof UrlTouchImageView)
            ((UrlTouchImageView)view).recycleBmp();
    }

    @Override
    public Object instantiateItem(ViewGroup collection, final int position){
        final UrlTouchImageView iv = new UrlTouchImageView(mContext);
        if (position == mCurrentPosition) {
            Message msg = new Message();
            msg.what = MSG_LOAD_LARGE_IMG;
            msg.obj = iv;
            msg.arg1 = position;
            handler.removeMessages(MSG_LOAD_LARGE_IMG);
            handler.sendMessageDelayed(msg, 300);
        } else {
            iv.setUrl(mUrls.get(position), maxPreloadWidth, maxPreloadHeight, false);
        }
        iv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        collection.addView(iv, 0);
        return iv;
    }
}
