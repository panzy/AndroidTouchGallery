package ru.truba.touchgallery.TouchView;

import android.annotation.TargetApi;
import android.graphics.*;
import android.os.Build;

import java.io.IOException;

/**
 * Created by panzy on 3/31/15.
 */
public class RotationBitmapRegionDecoder implements TouchImageView.BitmapRegionDecodingDelegate {
    private BitmapRegionDecoder regionDecoder;
    private Matrix matrix = new Matrix();
    private int rotation = 0;

    public static RotationBitmapRegionDecoder newInstance(String path) throws IOException {
        RotationBitmapRegionDecoder r = new RotationBitmapRegionDecoder();
        if (Build.VERSION.SDK_INT >= 10)
            r.regionDecoder = BitmapRegionDecoder.newInstance(path, true);
        return r;
    }

    public void setRotation(int degree) {
        rotation = degree;
        matrix.setRotate(rotation);
    }

    @TargetApi(Build.VERSION_CODES.GINGERBREAD_MR1)
    @Override
    public Bitmap decodeRegion(Rect rect, BitmapFactory.Options options) {
        if (Build.VERSION.SDK_INT >= 10) {
            Rect rect2;
            switch (rotation) {
                case 90:
                    rect2 = new Rect(rect.top, getWidth() - rect.right, 0, 0);
                    rect2.right = rect2.left + rect.height();
                    rect2.bottom = rect2.top + rect.width();
                    break;
                case 180:
                    rect2 = new Rect(getWidth() - rect.right, getHeight() - rect.bottom, 0, 0);
                    rect2.right = rect2.left + rect.width();
                    rect2.bottom = rect2.top + rect.height();
                    break;
                case 270:
                    rect2 = new Rect(getHeight() - rect.bottom, rect.left, 0, 0);
                    rect2.right = rect2.left + rect.height();
                    rect2.bottom = rect2.top + rect.width();
                    break;
                default:
                    rect2 = rect;
            }

            Bitmap bmp = regionDecoder.decodeRegion(rect2, options);
            if (rotation != 0) {
                return Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
            } else {
                return bmp;
            }
        }
        return null;
    }

    @Override
    public int getHeight() {
        if (Build.VERSION.SDK_INT >= 10) {
            if (rotation == 0 || rotation == 180)
                return regionDecoder.getHeight();
            else
                return regionDecoder.getWidth();
        }
        return 0;
    }

    @Override
    public int getWidth() {
        if (Build.VERSION.SDK_INT >= 10) {
            if (rotation == 0 || rotation == 180)
                return regionDecoder.getWidth();
            else
                return regionDecoder.getHeight();
        }
        return 0;
    }
}
