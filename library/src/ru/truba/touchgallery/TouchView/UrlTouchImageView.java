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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.util.AttributeSet;
import android.widget.ImageView.ScaleType;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import ru.truba.touchgallery.R;
import ru.truba.touchgallery.TouchView.InputStreamWrapper.InputStreamProgressListener;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;

public class UrlTouchImageView extends RelativeLayout {
    private static final String TAG = "UrlTouchImageView";
    protected ProgressBar mProgressBar;
    protected TouchImageView mImageView;

    protected Context mContext;
    protected Bitmap mBmp;

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

    public void setUrl(String imageUrl, int maxWidth, int maxHeight)
    {
        try {
            setUrl(new URL(imageUrl), maxWidth, maxHeight);
        }
        catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    public void setUrl(URL imageUrl, int maxWidth, int maxHeight)
    {
        new ImageLoadTask().setSizeLimit(maxWidth, maxHeight).execute(imageUrl);
    }

    public void setScaleType(ScaleType scaleType) {
        mImageView.setScaleType(scaleType);
    }
    
    //No caching load
    public class ImageLoadTask extends AsyncTask<URL, Integer, Bitmap>
    {
        int maxWidth;
        int maxHeight;

        public ImageLoadTask setSizeLimit(int w, int h) {
            maxWidth = w;
            maxHeight = h;
            return this;
        }

        @Override
        protected Bitmap doInBackground(URL... urls) {
            URL aURL = urls[0];
            Bitmap bm = null;

            if (aURL == null)
                return bm;

            try {
                // although a URL of file protocol can also be handled properly by
                // stream, to avoid temp file, we decode local file without using
                // stream.
                if (aURL.getProtocol().equals("file")) {
                    bm = decodeBmp(aURL.getFile());
                } else {
                    URLConnection conn = aURL.openConnection();
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
                    bm = decodeBmp(bis);
                    bis.close();
                    is.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return bm;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (mBmp != null) {
//                Log.i(TAG, "recycle bmp, " + mBmp.getByteCount() + " bytes");
                mBmp.recycle();
            }

        	if (bitmap == null)
        	{
        		mImageView.setScaleType(ScaleType.CENTER);
        		bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.no_photo);
        		mImageView.setImageBitmap(bitmap);
        	}
        	else 
        	{
        		mImageView.setScaleType(ScaleType.MATRIX);
	            mImageView.setImageBitmap(bitmap);
        	}
            mImageView.setVisibility(VISIBLE);
            mProgressBar.setVisibility(GONE);

            mBmp = bitmap;
        }

		@Override
		protected void onProgressUpdate(Integer... values)
		{
			mProgressBar.setProgress(values[0]);
		}

        private void copy(InputStream in, File dst) throws IOException {

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

        private Bitmap decodeBmp(InputStreamWrapper bis) throws IOException {
            if (maxWidth > 0 && maxHeight > 0) {
                // since we need to decode twice, and BufferedInputStream.reset() may
                // raise "java.io.IOException: Mark has been invalidated" however the
                // size parameter of mark() is, we dump the input stream to temp file.
                File tmpFile = new File(getContext().getFilesDir().getAbsoluteFile() + "/" + Math.random());
                copy(bis, tmpFile);
                bis.close();
                Bitmap bmp = decodeBmp(tmpFile.getAbsolutePath());
                tmpFile.delete();
                return bmp;
            } else {
                Bitmap bmp = BitmapFactory.decodeStream(bis);
                bis.close();
                return bmp;
            }
        }

        private Bitmap decodeBmp(String filename) throws IOException {
            if (maxWidth > 0 && maxHeight > 0) {
                // First decode with inJustDecodeBounds=true to check dimensions
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(filename, options);

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, maxWidth, maxHeight);

                // Decode bitmap with inSampleSize set
                options.inJustDecodeBounds = false;
                return BitmapFactory.decodeFile(filename, options);
            } else {
                return BitmapFactory.decodeFile(filename);
            }
        }

        private int calculateInSampleSize(
                BitmapFactory.Options options, int reqWidth, int reqHeight) {
            // Raw height and width of image
            final int height = options.outHeight;
            final int width = options.outWidth;
            int inSampleSize = 1;

            if (height > reqHeight || width > reqWidth) {

                final int halfHeight = height / 2;
                final int halfWidth = width / 2;

                // Calculate the largest inSampleSize value that is a power of 2 and keeps both
                // height and width larger than the requested height and width.
                while ((halfHeight / inSampleSize) > reqHeight
                        && (halfWidth / inSampleSize) > reqWidth) {
                    inSampleSize *= 2;
                }
            }

            return inSampleSize;
        }
    }
}
