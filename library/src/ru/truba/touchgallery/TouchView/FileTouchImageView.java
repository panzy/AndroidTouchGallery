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
import android.util.AttributeSet;
import ru.truba.touchgallery.TouchView.InputStreamWrapper.InputStreamProgressListener;

import java.io.File;
import java.io.FileInputStream;

public class FileTouchImageView extends UrlTouchImageView 
{
    // limit max bmp size to avoid out-of-memory.
    private final static int MAX_WIDTH = 1280;
    private final static int MAX_HEIGHT = 720;

    public FileTouchImageView(Context ctx)
    {
        super(ctx);

    }
    public FileTouchImageView(Context ctx, AttributeSet attrs)
    {
        super(ctx, attrs);
    }

    public void setUrl(String imagePath)
    {
        new ImageLoadTask().execute(imagePath);
    }
    //No caching load
    public class ImageLoadTask extends UrlTouchImageView.ImageLoadTask
    {
        @Override
        protected Bitmap doInBackground(String... strings) {
            String path = strings[0];
            Bitmap bm = null;
            try {
            	File file = new File(path);
            	FileInputStream fis = new FileInputStream(file);
                InputStreamWrapper bis = new InputStreamWrapper(fis, 8192, file.length());
                bis.setProgressListener(new InputStreamProgressListener()
				{					
					@Override
					public void onProgress(float progressValue, long bytesLoaded,
							long bytesTotal)
					{
						publishProgress((int)(progressValue * 100));
					}
				});

                // First decode with inJustDecodeBounds=true to check dimensions
                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, options);

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, MAX_WIDTH, MAX_HEIGHT);

                // Decode bitmap with inSampleSize set
                options.inJustDecodeBounds = false;
                bm = BitmapFactory.decodeStream(bis, null, options);

                bis.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            return bm;
        }
    }

    private static int calculateInSampleSize(
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
