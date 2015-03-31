/*
 Copyright (c) 2012 Roman Truba

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
package ru.truba.touchgallery.TouchView;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.AsyncTask;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.ImageView.ScaleType;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import ru.truba.touchgallery.R;
import ru.truba.touchgallery.TouchView.InputStreamWrapper.InputStreamProgressListener;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.LinkedList;
import java.util.concurrent.Executor;

public class UrlTouchImageView extends RelativeLayout {
    private static final String TAG = "UrlTouchImageView";
    protected ProgressBar mProgressBar;
    protected TouchImageView mImageView;

    protected Context mContext;
    protected Bitmap mBmp;
    public static int bmpCnt = 0;

    LinkedList<String> cachedFiles = new LinkedList<>();
    private ImageLoadTask loadTask;

    private Executor networkExecutor = new Executor()
    {
        @Override
        public void execute(Runnable runnable)
        {
            new Thread(runnable).start();
        }
    };

    public UrlTouchImageView(Context ctx)
    {
        super(ctx);
        mContext = ctx;
        init();

    }
    public UrlTouchImageView(Context ctx, AttributeSet attrs)
    {
        super(ctx, attrs);
        mContext = ctx;
        init();
    }
    public TouchImageView getImageView() { return mImageView; }

    @SuppressWarnings("deprecation")
    protected void init() {
        mImageView = new TouchImageView(mContext);
        LayoutParams params = new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT);
        mImageView.setLayoutParams(params);
        this.addView(mImageView);
        mImageView.setVisibility(GONE);

        mProgressBar = new ProgressBar(mContext, null, android.R.attr.progressBarStyleHorizontal);
        params = new LayoutParams(LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.CENTER_VERTICAL);
        params.setMargins(30, 0, 30, 0);
        mProgressBar.setLayoutParams(params);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setMax(100);
        this.addView(mProgressBar);
    }

    public void setUrl(String imageUrl, int maxWidth, int maxHeight, boolean enableTouchAfterDone)
    {
        try {
            setUrl(new URL(imageUrl), maxWidth, maxHeight, enableTouchAfterDone);
        }
        catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    public void setUrl(URL imageUrl, int maxWidth, int maxHeight, boolean enableTouchAfterDone)
    {
//        Log.d(TAG, String.format("setUrl(%s, %d, %d), touchEnabled=%s", imageUrl, maxWidth, maxHeight, enableTouchAfterDone));
        if (loadTask != null && !loadTask.finished && TextUtils.equals(
                loadTask.url.toExternalForm(),
                imageUrl.toExternalForm())) {
            loadTask.setSizeLimit(maxWidth, maxHeight)
                    .setEnableTouchAfterDone(enableTouchAfterDone);
        } else {
            loadTask = new ImageLoadTask().setSizeLimit(maxWidth, maxHeight)
                    .setEnableTouchAfterDone(enableTouchAfterDone);
            if (Build.VERSION.SDK_INT > 11) {
                loadTask.executeOnExecutor(networkExecutor, imageUrl);
            } else {
                loadTask.execute(imageUrl);
            }
        }
    }

    public void setScaleType(ScaleType scaleType) {
        mImageView.setScaleType(scaleType);
    }
    
    //No caching load
    public class ImageLoadTask extends AsyncTask<URL, Integer, Bitmap>
    {
        public URL url;
        public boolean finished;
        int maxWidth;
        int maxHeight;
        RotationBitmapRegionDecoder regionDecoder;
        boolean touchEnabledAfterDone;

        public ImageLoadTask setSizeLimit(int w, int h) {
            maxWidth = w;
            maxHeight = h;
            return this;
        }

        public ImageLoadTask setEnableTouchAfterDone(boolean value) {
            touchEnabledAfterDone = value;
            return this;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            mImageView.touchEnabled = false; // suspend touch
        }

        @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
        @Override
        protected Bitmap doInBackground(URL... urls) {
            url = urls[0];
            finished = false;
            Bitmap bm = null;

            if (url == null)
                return bm;

            try {
                // although a URL of file protocol can also be handled properly by
                // stream, to avoid temp file, we decode local file without using
                // stream.
                if (url.getProtocol().equals("file")) {
                    int rotationDegress = getRotationDegress(url.getFile());
                    bm = decodeBmp(url.getFile(), rotationDegress);
                    if (Build.VERSION.SDK_INT >= 10) {
                        regionDecoder = RotationBitmapRegionDecoder.newInstance(url.getFile());
                        if (rotationDegress != 0)
                            regionDecoder.setRotation(rotationDegress);
                    }
                } else {
                    String cachePath = getCachePath(url);
                    int rotationDegress = 0;
                    if (new File(cachePath).exists()) {
                        rotationDegress = getRotationDegress(cachePath);
                        bm = decodeBmp(cachePath, rotationDegress);
                    }

                    if (bm == null) {
                        URLConnection conn = url.openConnection();
                        conn.connect();
                        InputStream is = conn.getInputStream();
                        int totalLen = conn.getContentLength();
                        InputStreamWrapper bis = new InputStreamWrapper(is, 8192, totalLen);
                        bis.setProgressListener(new InputStreamProgressListener() {
                            @Override
                            public void onProgress(float progressValue, long bytesLoaded,
                                                   long bytesTotal) {
                                publishProgress((int) (progressValue * 100));
                            }
                        });

                        String downloadPath = getDownloaPath(url);
                        // download to downloadPath
                        copy(bis, new File(downloadPath));
                        // copy to cachePath
                        new File(downloadPath).renameTo(new File(cachePath));
                        rotationDegress = getRotationDegress(cachePath);
                        bm = decodeBmp(cachePath, rotationDegress);
                        cachedFiles.add(cachePath);

                        bis.close();
                        is.close();
                    }

                    if (Build.VERSION.SDK_INT >= 10 && bm != null) {
                        regionDecoder = RotationBitmapRegionDecoder.newInstance(cachePath);
                        if (rotationDegress != 0)
                            regionDecoder.setRotation(rotationDegress);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return bm;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                ++bmpCnt;
                Log.i(TAG, "decode bmp, " + bitmap.getWidth() + "x" + bitmap.getHeight() + ", bmp cnt = " + bmpCnt);
            }

            // recycle old
            recycleBmp();

        	if (bitmap == null)
        	{
        		mImageView.setScaleType(ScaleType.CENTER);
        		bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.no_photo);
        		mImageView.setImageBitmap(bitmap, null);
        	}
        	else 
        	{
        		mImageView.setScaleType(ScaleType.MATRIX);
	            mImageView.setImageBitmap(bitmap, regionDecoder);
        	}
            mImageView.setVisibility(VISIBLE);
            mProgressBar.setVisibility(GONE);
            if (touchEnabledAfterDone)
                mImageView.touchEnabled = touchEnabledAfterDone;

            mBmp = bitmap;
            finished = true;
        }

		@Override
		protected void onProgressUpdate(Integer... values)
		{
			mProgressBar.setProgress(values[0]);
		}

        private void copy(InputStream in, File dst) throws IOException {
            //Log.d(TAG, "begin download -> " + dst.getAbsolutePath());
            OutputStream out = new FileOutputStream(dst);

            // Transfer bytes from in to out
            byte[] buf = new byte[1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
                //Log.d(TAG, "download in progress");
            }
            in.close();
            out.close();
            //Log.d(TAG, "end download");
        }

        private Bitmap decodeBmp(String filename, int rotationDegress) throws IOException {
            BitmapFactory.Options options = null;
            if (maxWidth > 0 && maxHeight > 0) {
                // First decode with inJustDecodeBounds=true to check dimensions
                options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(filename, options);

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight);

                // Decode bitmap with inSampleSize set
                options.inJustDecodeBounds = false;
            }

            Bitmap bmp = BitmapFactory.decodeFile(filename, options);

            // rotate according to Exif
            //int rotationDegress = getRotationDegress(filename);
            if (rotationDegress != 0) {
                return rotateBmp(bmp, rotationDegress);
            } else {
                return bmp;
            }
        }

        /**
         * Calculate the largest inSampleSize value that is a power of 2 and keeps both
         * height and width smaller than the requested height and width.
         *
         * <p>The order of reqWidth and reqHeight doesn't matter.</p>
         *
         * @param options
         * @param reqWidth
         * @param reqHeight
         * @return
         */
        private int calculateInSampleSize(
                BitmapFactory.Options options, int reqWidth, int reqHeight) {
            // Raw height and width of image
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;

            // swap request width and height
            if (reqWidth > reqHeight && width < height) {
                int t = reqWidth;
                reqWidth = reqHeight;
                reqHeight = t;
            }

            if (height > reqHeight || width > reqWidth) {

                // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                // height and width smaller than the requested height and width.
                do {
                    inSampleSize *= 2;
                } while ((height / inSampleSize) > reqHeight
                        || (width / inSampleSize) > reqWidth);
            }

            return inSampleSize;
        }
    }

    private Bitmap rotateBmp(Bitmap bmp, int rotationDegress) {
        Matrix matrix = new Matrix();
        matrix.setRotate(rotationDegress);
        return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
    }

    private int getRotationDegress(String filename) throws IOException {
        ExifInterface exif = new ExifInterface(filename);
        int rotation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        return exifToDegrees(rotation);
    }

    private static int exifToDegrees(int exifOrientation) {
        if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_90) { return 90; }
        else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_180) {  return 180; }
        else if (exifOrientation == ExifInterface.ORIENTATION_ROTATE_270) {  return 270; }
        return 0;
    }

    private String getDownloaPath(URL url) {
        return getCachePath(getContext(), url) + ".download";
    }

    private String getCachePath(URL url) {
        return getCachePath(getContext(), url);
    }

    public static String getCachePath(Context context, URL url) {
        // prefer public dir, so can share with other apps
        File dir = context.getExternalCacheDir();
        if (dir == null)
            dir = context.getFilesDir().getAbsoluteFile();
        return dir + "/" + url.getFile().replace('/', '-');
    }

    private void deleteCacheFiles() {
        Log.d(TAG, "deleteCacheFiles");
        for (String path : cachedFiles) {
            new File(path).delete();
        }
        cachedFiles.clear();
    }

    public void recycleBmp() {
        if (mBmp != null) {
            --bmpCnt;
            Log.i(TAG, "recycle bmp, " + mBmp.getWidth() + "x" + mBmp.getHeight() + ", bmp cnt = " + bmpCnt);
            mBmp.recycle();
            mBmp = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        deleteCacheFiles();
        super.finalize();
    }
}
