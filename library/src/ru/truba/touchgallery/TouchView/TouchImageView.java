/*
 Copyright (c) 2012 Robert Foss, Roman Truba

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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.util.AttributeSet;
import android.util.FloatMath;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.Timer;
import java.util.TimerTask;

@SuppressLint("NewApi")
public class TouchImageView extends ImageView {

    private static final String TAG = "TouchImageView";

    public boolean touchEnabled = true;

    // private static final String TAG = "Touch";
    // These matrices will be used to move and zoom image
    Matrix matrix = new Matrix();
    Matrix savedMatrix = new Matrix();

    static final long DOUBLE_PRESS_INTERVAL = 300;
    static final float FRICTION = 0.9f;

    // We can be in one of these 4 states
    static final int NONE = 0;
    static final int DRAG = 1;
    static final int ZOOM = 2;
    static final int CLICK = 10;
    int mode = NONE;

    float redundantXSpace, redundantYSpace;
    /**
     * the X position of image should be between [-right, 0].
     */
    float right, bottom;
    /**
     * <ul>
     * <li> origWidth = width - 2 * redundantXSpace;</li>
     * <li> origHeight = height - 2 * redundantYSpace;</li>
     * </ul>
     */
    float origWidth, origHeight;
    /** bitmap size */
    float bmWidth, bmHeight;
    /** View size */
    float width, height;
    /** original bmp size, retrieved from BitmapRegionDecoder. */
    int origBmWidth, origBmHeight;

    Bitmap overlapBmp;
    Rect overlapBmpDstRect = new Rect();
    BitmapRegionDecodingDelegate regionDecoder;
    Rect currVisibleRegion = new Rect();
    Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);


    PointF last = new PointF();
    PointF mid = new PointF();
    PointF start = new PointF();
    float[] m;
    float matrixX, matrixY;

    /*
    scale:

    MIN <= save <= MAX
    MIN <= normalized == MAX/2
    MIN = 1
    MAX is initialized depending on image size and view size.
     */
    /** minimum of saveScale */
    final static float MIN_SCALE = 1f;
    /** define as 1 when img fits screen */
    float saveScale = 1f;
    /**
     * When saveScale reach normalizedScale, the image will be displayed as
     * 1:1, or fit the screen if its instinct size is smaller than screen.
     *
     * Will be calculated later.
     * */
    float normalizedScale = 1.0f;

    float oldDist = 1f;

    PointF lastDelta = new PointF(0, 0);
    float velocity = 0;

    long lastPressTime = 0, lastDragTime = 0;
    boolean allowInert = false;

    private Context mContext;
    private Timer mClickTimer;
    private OnClickListener mOnClickListener;
    private Object mScaleDetector;
    private Handler mTimerHandler = null;
    private WorkThread workThread = null;
    private UIHandler uiHandler = null;

    // Scale mode on DoubleTap
    private boolean zoomToOriginalSize = false;

    public boolean isZoomToOriginalSize() {
        return  this.zoomToOriginalSize;
    }

    public void setZoomToOriginalSize(boolean zoomToOriginalSize) {
        this.zoomToOriginalSize = zoomToOriginalSize;
    }

    public boolean onLeftSide = false, onTopSide = false, onRightSide = false, onBottomSide = false;

    private float maxScale() {
        return normalizedScale * 2;
    }

    private static class UIHandler extends Handler {
        public static final int MSG_INVALIDATE = 1;

        private WeakReference<TouchImageView> ref;

        public UIHandler(TouchImageView touchImageView) {
            ref = new WeakReference<>(touchImageView);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_INVALIDATE) {
                TouchImageView touchImageView = ref.get();
                if (ref != null)
                    touchImageView.invalidate();
            }
        }
    }

    private static class WorkThread extends Thread {
        public static final int MSG_DECODE_REGION = 1;

        private WeakReference<TouchImageView> ref;
        private Handler handler;
        private int lastArg1 = 0;

        public WorkThread(TouchImageView touchImageView) {
            ref = new WeakReference<>(touchImageView);
        }

        @Override
        public void run() {

            Looper.prepare();

            handler = new Handler() {
                public void handleMessage(Message msg) {
                    if (msg.what == MSG_DECODE_REGION) {
                        TouchImageView touchImageView = ref.get();
                        if (touchImageView != null) {
                            // if lastArg1 != msg.arg1, then this task is stale
                            ((Runnable) msg.obj).run();

                            if (lastArg1 == msg.arg1)
                                touchImageView.uiHandler.sendEmptyMessage(UIHandler.MSG_INVALIDATE);
                            else {
                                Log.d(TAG, "discard stale overlapBmp, random code = " + msg.arg1);
                                touchImageView.overlapBmp = null;
                            }
                        }
                    }
                }
            };

            Looper.loop();
        }

        public void removeMessage(int what) {
            handler.removeMessages(what);
        }

        public void sendMessage(Message msg) {
            handler.sendMessage(msg);
            lastArg1 = msg.arg1;
        }
    }

    public TouchImageView(Context context) {
        super(context);
        super.setClickable(true);
        this.mContext = context;

        init();
    }
    public TouchImageView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        super.setClickable(true);
        this.mContext = context;

        init();
    }

	protected void init()
    {
//        bmpPaint.setAlpha(100); // half transparent for testing
		mTimerHandler = new TimeHandler(this);
        uiHandler = new UIHandler(this);
        workThread = new WorkThread(this);
        workThread.start();
        matrix.setTranslate(1f, 1f);
        m = new float[9];
        setImageMatrix(matrix);
        setScaleType(ScaleType.MATRIX);
        if (Build.VERSION.SDK_INT >= 8)
        {
            mScaleDetector = new ScaleGestureDetector(mContext, new ScaleListener());
        }
        setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent rawEvent) {
                if (!touchEnabled)
                    return false;

                WrapMotionEvent event = WrapMotionEvent.wrap(rawEvent);
                if (mScaleDetector != null) {
                    ((ScaleGestureDetector) mScaleDetector).onTouchEvent(rawEvent);
                }
                fillMatrixXY();
                PointF curr = new PointF(event.getX(), event.getY());

                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        allowInert = false;
                        savedMatrix.set(matrix);
                        last.set(event.getX(), event.getY());
                        start.set(last);
                        mode = DRAG;

                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        oldDist = spacing(event);
                        //Log.d(TAG, "oldDist=" + oldDist);
                        if (oldDist > 10f) {
                            savedMatrix.set(matrix);
                            midPoint(mid, event);
                            mode = ZOOM;
                            //Log.d(TAG, "mode=ZOOM");
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        allowInert = true;
                        mode = NONE;
                        int xDiff = (int) Math.abs(event.getX() - start.x);
                        int yDiff = (int) Math.abs(event.getY() - start.y);

                        if (xDiff < CLICK && yDiff < CLICK) {

                            //Perform scale on double click
                            long pressTime = System.currentTimeMillis();
                            if (pressTime - lastPressTime <= DOUBLE_PRESS_INTERVAL) {
                                if (mClickTimer != null) mClickTimer.cancel();
                                if (saveScale == 1) {
                                    final float targetScale = normalizedScale / saveScale;
                                    saveScale = normalizedScale;

                                    // if drag is not needed on max scale, center the img,
                                    // otherwise, center the touch point.
                                    float scaleWidth = Math.round(origWidth * saveScale);
                                    float scaleHeight = Math.round(origHeight * saveScale);
                                    float centerX = (scaleWidth < width) ? width / 2 : start.x;
                                    float centerY = (scaleHeight < height) ? height / 2 : start.y;

                                    matrix.postScale(targetScale, targetScale, centerX, centerY);
                                } else {
                                    resetScale();
                                }
                                calcPadding();
                                checkAndSetTranslate(0, 0);
                                lastPressTime = 0;
                            } else {
                                lastPressTime = pressTime;
                                mClickTimer = new Timer();
                                mClickTimer.schedule(new Task(), 300);
                            }
                            if (saveScale == MIN_SCALE) {
                                scaleMatrixToBounds();
                            }
                        }

                        break;

                    case MotionEvent.ACTION_POINTER_UP:
                        mode = NONE;
                        velocity = 0;
                        savedMatrix.set(matrix);
                        oldDist = spacing(event);
                        //Log.d(TAG, "mode=NONE");
                        break;

                    case MotionEvent.ACTION_MOVE:
                        allowInert = false;
                        if (mode == DRAG) {
                            float deltaX = curr.x - last.x;
                            float deltaY = curr.y - last.y;

                            long dragTime = System.currentTimeMillis();

                            velocity = (float) distanceBetween(curr, last) / (dragTime - lastDragTime) * FRICTION;
                            lastDragTime = dragTime;

                            checkAndSetTranslate(deltaX, deltaY);
                            lastDelta.set(deltaX, deltaY);
                            last.set(curr.x, curr.y);
                        } else if (mScaleDetector == null && mode == ZOOM) {
                            float newDist = spacing(event);
                            if (rawEvent.getPointerCount() < 2) break;
                            //There is one serious trouble: when you scaling with two fingers, then pick up first finger of gesture, ACTION_MOVE being called.
                            //Magic number 50 for this case
                            if (10 > Math.abs(oldDist - newDist) || Math.abs(oldDist - newDist) > 50) break;
                            float mScaleFactor = newDist / oldDist;
                            oldDist = newDist;

                            float origScale = saveScale;
                            saveScale *= mScaleFactor;
                            if (saveScale > maxScale()) {
                                saveScale = maxScale();
                                mScaleFactor = maxScale() / origScale;
                            } else if (saveScale < MIN_SCALE) {
                                saveScale = MIN_SCALE;
                                mScaleFactor = MIN_SCALE / origScale;
                            }

                            calcPadding();
                            if (origWidth * saveScale <= width || origHeight * saveScale <= height) {
                                matrix.postScale(mScaleFactor, mScaleFactor, width / 2, height / 2);
                                if (mScaleFactor < 1) {
                                    fillMatrixXY();
                                    if (mScaleFactor < 1) {
                                        scaleMatrixToBounds();
                                    }
                                }
                            } else {
                                PointF mid = midPointF(event);
                                matrix.postScale(mScaleFactor, mScaleFactor, mid.x, mid.y);
                                fillMatrixXY();
                                if (mScaleFactor < 1) {
                                    if (matrixX < -right)
                                        matrix.postTranslate(-(matrixX + right), 0);
                                    else if (matrixX > 0)
                                        matrix.postTranslate(-matrixX, 0);
                                    if (matrixY < -bottom)
                                        matrix.postTranslate(0, -(matrixY + bottom));
                                    else if (matrixY > 0)
                                        matrix.postTranslate(0, -matrixY);
                                }
                            }
                            checkSiding();
                        }
                        break;
                }

                setImageMatrix(matrix);
                // suspend region decoding for performance
                //Log.d(TAG, "mode = " + mode + ", v = " + velocity);
                if (mode == ZOOM || (mode == DRAG && !isInertiaStopped())) {
                    overlapBmp = null;
                } else if (mode == NONE && isInertiaStopped()) {
                    clipBmpRegion();
                }
                invalidate();
                return false;
            }
        });
    }

    public void resetScale()
    {
        fillMatrixXY();
        matrix.postScale(MIN_SCALE / saveScale, MIN_SCALE / saveScale, width / 2, height / 2);
        saveScale = MIN_SCALE;

        calcPadding();
        checkAndSetTranslate(0, 0);

        scaleMatrixToBounds();

        setImageMatrix(matrix);
        overlapBmp = null;
        invalidate();
    }

    public boolean pagerCanScroll()
    {
        if (mode != NONE) return false;
        return saveScale == MIN_SCALE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        drawOverlapImg(canvas);

        if (!allowInert) return;

        // translate with inertia
        final float deltaX = lastDelta.x * velocity, deltaY = lastDelta.y * velocity;
        //Log.d(TAG, "delta = " + deltaX + ", " + deltaY);
        if (deltaX > width || deltaY > height)
        {
            if (mode == NONE) {
                clipBmpRegion();
                drawOverlapImg(canvas);
                velocity = 0;
            }
            return;
        }

        // decrease velocity
        velocity *= FRICTION;

        if (Math.abs(deltaX) < 1 && Math.abs(deltaY) < 1) return;

        // perform translation
        //Log.d(TAG, "perform translation, delta = " + deltaX + ", " + deltaY);
        checkAndSetTranslate(deltaX, deltaY);
        setImageMatrix(matrix);

        //Log.d(TAG, "velocity = " + velocity + ", is inertia stopped = " + isInertiaStopped());

        // re-clip after inertia stopped
        if (isInertiaStopped()) {
            clipBmpRegion();
            drawOverlapImg(canvas);
            velocity = 0;
        } else {
            overlapBmp = null; // not match

            // continue decrease velocity
            if (mode == NONE)
                invalidate();
        }
    }

    private void drawOverlapImg(Canvas canvas) {
        if (overlapBmp != null) {
            canvas.drawBitmap(overlapBmp, null, overlapBmpDstRect, bmpPaint);
        }
    }

    private boolean isInertiaStopped() {
        final float nextDeltaX = lastDelta.x * velocity, nextDeltaY = lastDelta.y * velocity;
        return Math.abs(nextDeltaX) < 1 && Math.abs(nextDeltaY) < 1;
    }

    private void checkAndSetTranslate(float deltaX, float deltaY)
    {
        float scaleWidth = Math.round(origWidth * saveScale);
        float scaleHeight = Math.round(origHeight * saveScale);
        fillMatrixXY();
        if (scaleWidth < width) {
            deltaX = 0;
            if (matrixY + deltaY > 0)
                deltaY = -matrixY;
            else if (matrixY + deltaY < -bottom)
                deltaY = -(matrixY + bottom);
        } else if (scaleHeight < height) {
            deltaY = 0;
            if (matrixX + deltaX > 0)
                deltaX = -matrixX;
            else if (matrixX + deltaX < -right)
                deltaX = -(matrixX + right);
        }
        else {
            if (matrixX + deltaX > 0)
                deltaX = -matrixX;
            else if (matrixX + deltaX < -right)
                deltaX = -(matrixX + right);

            if (matrixY + deltaY > 0)
                deltaY = -matrixY;
            else if (matrixY + deltaY < -bottom)
                deltaY = -(matrixY + bottom);
        }
        matrix.postTranslate(deltaX, deltaY);
        checkSiding();
    }
    private void checkSiding()
    {
        fillMatrixXY();
        //Log.d(TAG, "x: " + matrixX + " y: " + matrixY + " left: " + right / 2 + " top:" + bottom / 2);
        float scaleWidth = Math.round(origWidth * saveScale);
        float scaleHeight = Math.round(origHeight * saveScale);
        onLeftSide = onRightSide = onTopSide = onBottomSide = false;
        if (-matrixX < 10.0f ) onLeftSide = true;
        //Log.d("GalleryViewPager", String.format("ScaleW: %f; W: %f, MatrixX: %f", scaleWidth, width, matrixX));
        if ((scaleWidth >= width && (matrixX + scaleWidth - width) < 10) ||
            (scaleWidth <= width && -matrixX + scaleWidth <= width)) onRightSide = true;
        if (-matrixY < 10.0f) onTopSide = true;
        if (Math.abs(-matrixY + height - scaleHeight) < 10.0f) onBottomSide = true;
    }
    private void calcPadding()
    {
        right = width * saveScale - width - (2 * redundantXSpace * saveScale);
        bottom = height * saveScale - height - (2 * redundantYSpace * saveScale);
    }
    private void fillMatrixXY()
    {
        matrix.getValues(m);
        matrixX = m[Matrix.MTRANS_X];
        matrixY = m[Matrix.MTRANS_Y];
    }
    private void scaleMatrixToBounds()
    {
        if (Math.abs(matrixX + right / 2) > 0.5f)
            matrix.postTranslate(-(matrixX + right / 2), 0);
        if (Math.abs(matrixY + bottom / 2) > 0.5f)
            matrix.postTranslate(0, -(matrixY + bottom / 2));
    }
    @Override
    public void setImageBitmap(Bitmap bm) {
        setImageBitmap(bm, null);
    }

    public void setImageBitmap(Bitmap bm, BitmapRegionDecodingDelegate decoder) {
        super.setImageBitmap(bm);
        bmWidth = bm.getWidth();
        bmHeight = bm.getHeight();
        regionDecoder = decoder;

        if (regionDecoder != null) {
            origBmWidth = regionDecoder.getWidth();
            origBmHeight = regionDecoder.getHeight();
        } else {
            origBmWidth = origBmHeight = 0;
        }

        resetMatrix();

        // clear overlap bmp
        overlapBmp = null;
        invalidate();
    }

    // calc normalized and max scale, if view size hasn't been initialized yet,
    // set normal scale to min scale.
    private void resetMaxScale() {
        if (width > 1) {
            if (regionDecoder != null) {
                normalizedScale = Math.max(origBmWidth / bmWidth,
                        Math.max(bmWidth / width, bmHeight / height));
            } else {
                normalizedScale = Math.max(bmWidth / width, bmHeight / height);
            }
        } else {
            normalizedScale = MIN_SCALE;
        }

        matrix.getValues(m);
        normalizedScale /= m[Matrix.MSCALE_X];

        // little img, large screen...
        if (normalizedScale < MIN_SCALE)
            normalizedScale = MIN_SCALE;

        Log.d(TAG, String.format("scale: init matrix = %f, saved = %f, max = %f, min = %f",
                m[Matrix.MSCALE_X], saveScale, maxScale(), MIN_SCALE));
    }

    @Override
    protected void onMeasure (int widthMeasureSpec, int heightMeasureSpec)
    {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        width = MeasureSpec.getSize(widthMeasureSpec);
        height = MeasureSpec.getSize(heightMeasureSpec);

        resetMatrix();
    }

    /** Reset matrix to fit to screen. */
    private void resetMatrix() {
        //Fit to screen.
        float scale;
        float scaleX =  width / bmWidth;
        float scaleY = height / bmHeight;
        scale = Math.min(scaleX, scaleY);
        matrix.setScale(scale, scale);
        setImageMatrix(matrix);
        saveScale = 1f;

        // Center the image
        redundantYSpace = height - (scale * bmHeight) ;
        redundantXSpace = width - (scale * bmWidth);
        redundantYSpace /= (float)2;
        redundantXSpace /= (float)2;

        matrix.postTranslate(redundantXSpace, redundantYSpace);

        origWidth = width - 2 * redundantXSpace;
        origHeight = height - 2 * redundantYSpace;
        calcPadding();
        setImageMatrix(matrix);

        resetMaxScale();
    }

    private double distanceBetween(PointF left, PointF right)
    {
        return Math.sqrt(Math.pow(left.x - right.x, 2) + Math.pow(left.y - right.y, 2));
    }
    /** Determine the space between the first two fingers */
    private float spacing(WrapMotionEvent event) {
        // ...
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return FloatMath.sqrt(x * x + y * y);
    }

    /** Calculate the mid point of the first two fingers */
    private void midPoint(PointF point, WrapMotionEvent event) {
        // ...
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        point.set(x / 2, y / 2);
    }
    private PointF midPointF(WrapMotionEvent event) {
        // ...
        float x = event.getX(0) + event.getX(1);
        float y = event.getY(0) + event.getY(1);
        return new PointF(x / 2, y / 2);
    }

    private static boolean rectEquals(Rect a, Rect b) {
        return a.top == b.top && a.right == b.right
                && a.bottom == b.bottom && a.left == b.left;
    }

    private void clipBmpRegion() {
        if (regionDecoder == null)
            return;

        final float subsampleRate = origBmWidth / bmWidth ;

        if (subsampleRate <= 1)
            return;

        matrix.getValues(m);

        //Log.d(TAG, "---------------");
        //Log.d(TAG, "savedScale = " + saveScale
        //        + ", matrix scale = " + m[Matrix.MSCALE_X]
        //        + ", subsample rate = " + subsampleRate);

        RectF rect;
        // rect1
        rect = new RectF();
        rect.left = -m[Matrix.MTRANS_X];
        rect.top = -m[Matrix.MTRANS_Y];
        rect.right = rect.left + width;
        rect.bottom = rect.top + height;
        //Log.d(TAG, String.format("rect 1 = %d,%d %d*%d of canvas",
        //        (int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height()));

        // rect2
        rect.left /= m[Matrix.MSCALE_X];
        rect.top /= m[Matrix.MSCALE_Y];
        rect.right /= m[Matrix.MSCALE_X];
        rect.bottom /= m[Matrix.MSCALE_Y];
        //Log.d(TAG, String.format("rect 2 = %d,%d %d*%d of background image (%d*%d) ",
        //        (int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(),
        //        (int)bmWidth, (int)bmHeight));

        // rect3
        rect.left *= subsampleRate;
        rect.right *= subsampleRate;
        rect.top *= subsampleRate;
        rect.bottom *= subsampleRate;
        //Log.d(TAG, String.format("rect 3 = %d,%d %d*%d of original image (%d*%d)",
        //        (int)rect.left, (int)rect.top, (int)rect.width(), (int)rect.height(),
        //        origBmWidth, origBmHeight));


        final Rect visibleRect = new Rect((int)rect.left, (int)rect.top, (int)rect.right, (int)rect.bottom);

        if (!rectEquals(currVisibleRegion, visibleRect) || overlapBmp == null) {
            if (m[Matrix.MSCALE_X] > 1.1f) {

                currVisibleRegion = visibleRect;

                final BitmapFactory.Options opt = new BitmapFactory.Options();

                opt.inSampleSize = 1;
                int halfWidth = visibleRect.width() / 2;
                int halfHeight = visibleRect.height() / 2;
                while (opt.inSampleSize * 2 < subsampleRate
                        && halfWidth > width * opt.inSampleSize
                        && halfHeight > height * opt.inSampleSize) {
                    opt.inSampleSize *= 2;
                }

                //Log.d(TAG, String.format("clip region %d,%d %d*%d with inSampleSize=%d",
                //        visibleRect.left,
                //        visibleRect.top,
                //        visibleRect.width(),
                //        visibleRect.height(),
                //        opt.inSampleSize));


                if (visibleRect.top < 0) {
                    float heightScale = ((float)visibleRect.height() + 2 * visibleRect.top) / visibleRect.height();
                    overlapBmpDstRect.top = (int) (-visibleRect.top / subsampleRate * m[Matrix.MSCALE_Y]);
                    overlapBmpDstRect.bottom = overlapBmpDstRect.top + (int) (height * heightScale);

                    visibleRect.bottom += visibleRect.top;
                    visibleRect.top = 0;
                } else {
                    overlapBmpDstRect.top = 0;
                    overlapBmpDstRect.bottom = (int) height;
                }

                if (visibleRect.left < 0) {
                    float widthScale = ((float)visibleRect.width() + 2 * visibleRect.left) / visibleRect.width();
                    overlapBmpDstRect.left = (int) (- visibleRect.left / subsampleRate * m[Matrix.MSCALE_X]);
                    overlapBmpDstRect.right = overlapBmpDstRect.left + (int) (width * widthScale);

                    visibleRect.right += visibleRect.left;
                    visibleRect.left = 0;
                } else {
                    overlapBmpDstRect.left = 0;
                    overlapBmpDstRect.right = (int) width;
                }

                overlapBmp = null;

                // check IllegalArgumentException("rectangle is outside the image");
                if (!(visibleRect.right <= 0 || visibleRect.bottom <= 0 || visibleRect.left >= origBmWidth || visibleRect.top >= origBmHeight)) {
                    // decode region async
                    Message msg = new Message();
                    msg.what = WorkThread.MSG_DECODE_REGION;
                    msg.arg1 = (int) (Math.random() * 100000); // random code
                    msg.obj = new Runnable() {
                        @Override
                        public void run() {
                            overlapBmp = regionDecoder.decodeRegion(visibleRect, opt);
                            Log.d(TAG, String.format("overlapBmp, %dx%d, src rect %s, dst rect %s",
                                    overlapBmp.getWidth(), overlapBmp.getHeight(), visibleRect, overlapBmpDstRect));

                            // dump for testing
                            if (false) dumpOverlapBmp();
                        }
                    };
                    workThread.removeMessage(msg.what);
                    workThread.sendMessage(msg);
                }
            } else {
                overlapBmp = null;
            }
        }
    }

    private void dumpOverlapBmp() {
        try {
            OutputStream os = new FileOutputStream(new File(
                    Environment.getExternalStorageDirectory() +
                            "/" + Environment.DIRECTORY_PICTURES +
                            "/overlap.jpg"));
            overlapBmp.compress(Bitmap.CompressFormat.JPEG, 100, os);
            os.close();
        }
        catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void setOnClickListener(OnClickListener l) {
        mOnClickListener = l;
    }

    
    private class Task extends TimerTask {
        public void run() {
            mTimerHandler.sendEmptyMessage(0);
        }
    }

	private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            mode = ZOOM;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float mScaleFactor = (float)Math.min(Math.max(.95f, detector.getScaleFactor()), 1.05);
            float origScale = saveScale;
            saveScale *= mScaleFactor;
            if (saveScale > maxScale()) {
                saveScale = maxScale();
                mScaleFactor = maxScale() / origScale;
            } else if (saveScale < MIN_SCALE) {
                saveScale = MIN_SCALE;
                mScaleFactor = MIN_SCALE / origScale;
            }
            right = width * saveScale - width - (2 * redundantXSpace * saveScale);
            bottom = height * saveScale - height - (2 * redundantYSpace * saveScale);
            if (origWidth * saveScale <= width || origHeight * saveScale <= height) {
                matrix.postScale(mScaleFactor, mScaleFactor, width / 2, height / 2);
                if (mScaleFactor < 1) {
                    matrix.getValues(m);
                    float x = m[Matrix.MTRANS_X];
                    float y = m[Matrix.MTRANS_Y];
                    if (mScaleFactor < 1) {
                        if (Math.round(origWidth * saveScale) < width) {
                            if (y < -bottom)
                                matrix.postTranslate(0, -(y + bottom));
                            else if (y > 0)
                                matrix.postTranslate(0, -y);
                        } else {
                            if (x < -right)
                                matrix.postTranslate(-(x + right), 0);
                            else if (x > 0)
                                matrix.postTranslate(-x, 0);
                        }
                    }
                }
            } else {
                matrix.postScale(mScaleFactor, mScaleFactor, detector.getFocusX(), detector.getFocusY());
                matrix.getValues(m);
                float x = m[Matrix.MTRANS_X];
                float y = m[Matrix.MTRANS_Y];
                if (mScaleFactor < 1) {
                    if (x < -right)
                        matrix.postTranslate(-(x + right), 0);
                    else if (x > 0)
                        matrix.postTranslate(-x, 0);
                    if (y < -bottom)
                        matrix.postTranslate(0, -(y + bottom));
                    else if (y > 0)
                        matrix.postTranslate(0, -y);
                }
            }

            return true;
        }
    }

    static class TimeHandler extends Handler {
	    private final WeakReference<TouchImageView> mService; 

	    TimeHandler(TouchImageView view) {
	        mService = new WeakReference<TouchImageView>(view);
	        
	    }
	    @Override
	    public void handleMessage(Message msg)
	    {
	    	mService.get().performClick();
            if (mService.get().mOnClickListener != null) mService.get().mOnClickListener.onClick(mService.get());
	    }
	}

    /**
     * Like {@link BitmapRegionDecoder}.
     */
    public interface BitmapRegionDecodingDelegate {
        Bitmap decodeRegion(Rect rect, BitmapFactory.Options options);
        int getHeight();
        int getWidth();
    }
}