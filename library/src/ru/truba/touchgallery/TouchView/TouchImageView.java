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
import junit.framework.Assert;

import java.io.*;
import java.lang.ref.WeakReference;
import java.util.Timer;
import java.util.TimerTask;

@SuppressLint("NewApi")
public class TouchImageView extends ImageView {

    private static final String TAG = "TouchImageView";

    public boolean touchEnabled = true;

    // This matrix will be used to move and zoom image
    Matrix matrix = new Matrix();

    static final long DOUBLE_PRESS_INTERVAL = 300;
    static final float FRICTION = 0.9f;

    // We can be in one of these 4 states
    static final int NONE = 0;
    static final int DRAG = 1;
    static final int ZOOM = 2;
    static final int CLICK = 10;
    int mode = NONE;

    // ------- sizes --------------

    /** View size */
    float viewWidth, viewHeight;
    /** decoded image size */
    float imgWidth, imgHeight;
    /** original image size, retrieved from
     * {@link ru.truba.touchgallery.TouchView.TouchImageView.BitmapRegionDecodingDelegate}. */
    int origImgWidth, origImgHeight;
    /** Size of image when it fits the view.
     * <ul>
     * <li> fitBmpWidth = viewWidth - 2 * redundantXSpace;</li>
     * <li> fitBmpHeight = viewHeight - 2 * redundantYSpace;</li>
     * </ul>
     */
    float fitBmpWidth, fitBmpHeight;
    /** Redundant space of view when image fits view.
     *
     * At least one of these two space should be zero.
     *
     * These values are determined by the aspect ratio of the image
     * and the size of the view, while the zooming and translating of
     * image don't matter.*/
    float redundantXSpace, redundantYSpace;
    /** Diff of scaled image size and view size,
     * a.k.a the maximum translating range.
     *
     * These values change when zooming.
     *
     * The X position of image should be between [-outsideXSpace, 0].
     */
    float outsideXSpace, outsideYSpace;

    // ------- scales --------------

    /*
    scale:

    MIN <= save <= MAX
    MIN <= normalized == MAX/2
    MIN = 1
    MAX is initialized depending on image size and view size.
     */
    /** define as 1 when img fits screen */
    float saveScale = 1f;
    /** minimum of saveScale */
    final static float MIN_SCALE = 1f;
    /**
     * When saveScale reach normalizedScale, the image will be displayed as
     * 1:1, or fit the screen if its instinct size is smaller than screen.
     *
     * Will be calculated later.
     * */
    float normalizedScale = 1.0f;

    // ------- end --------------

    Bitmap overlapBmp;
    Rect overlapBmpDstRect = new Rect();
    BitmapRegionDecodingDelegate regionDecoder;
    Rect currVisibleRegion = new Rect();
    Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    PointF last = new PointF();
    PointF mid = new PointF();
    PointF start = new PointF();
    float[] m;

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
                PointF curr = new PointF(event.getX(), event.getY());

                switch (event.getAction() & MotionEvent.ACTION_MASK) {
                    case MotionEvent.ACTION_DOWN:
                        allowInert = false;
                        last.set(event.getX(), event.getY());
                        start.set(last);
                        mode = DRAG;

                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        oldDist = spacing(event);
                        //Log.d(TAG, "oldDist=" + oldDist);
                        if (oldDist > 10f) {
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

                                // double tapping changes scale: min -> normalized -> max -> min
                                float scaleFactor;
                                if (saveScale < normalizedScale) {
                                    scaleFactor = normalizedScale / saveScale;
                                    saveScale = normalizedScale;
                                } else if (saveScale < maxScale()) {
                                    scaleFactor = maxScale() / saveScale;
                                    saveScale = maxScale();
                                } else {
                                    scaleFactor = MIN_SCALE / saveScale;
                                    saveScale = MIN_SCALE;
                                }

                                if (scaleFactor < 0.99 || scaleFactor > 1.01) {
                                    // if drag is not needed on max scale, center the img,
                                    // otherwise, center the touch point.
                                    float scaleWidth = Math.round(fitBmpWidth * saveScale);
                                    float scaleHeight = Math.round(fitBmpHeight * saveScale);
                                    float centerX = (scaleWidth < viewWidth) ? viewWidth / 2 : start.x;
                                    float centerY = (scaleHeight < viewHeight) ? viewHeight / 2 : start.y;

                                    matrix.postScale(scaleFactor, scaleFactor, centerX, centerY);
                                }

                                calcPadding();
                                checkAndSetTranslate(0, 0);
                                lastPressTime = 0;
                            } else {
                                lastPressTime = pressTime;
                                mClickTimer = new Timer();
                                mClickTimer.schedule(new Task(), 300);
                            }
                            if (scaleEqual(saveScale, MIN_SCALE)) {
                                scaleMatrixToBounds();
                            }
                        }

                        break;

                    case MotionEvent.ACTION_POINTER_UP:
                        if (mode == ZOOM) {
                            if (saveScale > maxScale()) {
                                float mScaleFactor = maxScale() / saveScale;
                                saveScale = maxScale();
                                zoomBy(mScaleFactor, midPointF(event));
                            }
                        }

                        mode = NONE;
                        velocity = 0;
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

                            mScaleFactor = limitScale(mScaleFactor);
                            saveScale *= mScaleFactor;

                            if (mScaleFactor <= 0.99 || mScaleFactor >= 1.01)
                                zoomBy(mScaleFactor, midPointF(event));
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

            private void zoomBy(float mScaleFactor, PointF center) {
                calcPadding();
                if (fitBmpWidth * saveScale <= viewWidth || fitBmpHeight * saveScale <= viewHeight) {
                    matrix.postScale(mScaleFactor, mScaleFactor, viewWidth / 2, viewHeight / 2);
                    if (mScaleFactor < 1) {
                        if (mScaleFactor < 1) {
                            scaleMatrixToBounds();
                        }
                    }
                } else {
                    matrix.postScale(mScaleFactor, mScaleFactor, center.x, center.y);

                    matrix.getValues(m);
                    float matrixX = m[Matrix.MTRANS_X];
                    float matrixY = m[Matrix.MTRANS_Y];

                    if (mScaleFactor < 1) {
                        if (matrixX < -outsideXSpace)
                            matrix.postTranslate(-(matrixX + outsideXSpace), 0);
                        else if (matrixX > 0)
                            matrix.postTranslate(-matrixX, 0);
                        if (matrixY < -outsideYSpace)
                            matrix.postTranslate(0, -(matrixY + outsideYSpace));
                        else if (matrixY > 0)
                            matrix.postTranslate(0, -matrixY);
                    }
                }
                checkSiding();
            }

        });
    }

    protected float limitScale(float scaleFactor) {
        if (saveScale * scaleFactor > maxScale()) {
            scaleFactor *= 0.98; // it become harder to zoom in further
            if (scaleFactor < 1.0) {
                scaleFactor = 1;
            }
        } else if (saveScale * scaleFactor < MIN_SCALE) {
            scaleFactor = 1; // don't zoom
        }
        return scaleFactor;
    }

    public void resetScale()
    {
        matrix.postScale(MIN_SCALE / saveScale, MIN_SCALE / saveScale, viewWidth / 2, viewHeight / 2);
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
        return scaleEqual(saveScale, MIN_SCALE);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        drawOverlapImg(canvas);

        if (!allowInert) return;

        // translate with inertia
        final float deltaX = lastDelta.x * velocity, deltaY = lastDelta.y * velocity;
        //Log.d(TAG, "delta = " + deltaX + ", " + deltaY);
        if (deltaX > viewWidth || deltaY > viewHeight)
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
        matrix.getValues(m);
        float matrixX = m[Matrix.MTRANS_X];
        float matrixY = m[Matrix.MTRANS_Y];

        float scaleWidth = Math.round(fitBmpWidth * saveScale);
        float scaleHeight = Math.round(fitBmpHeight * saveScale);

        if (scaleWidth < viewWidth) {
            deltaX = 0;
            if (matrixY + deltaY > 0)
                deltaY = -matrixY;
            else if (matrixY + deltaY < -outsideYSpace)
                deltaY = -(matrixY + outsideYSpace);
        } else if (scaleHeight < viewHeight) {
            deltaY = 0;
            if (matrixX + deltaX > 0)
                deltaX = -matrixX;
            else if (matrixX + deltaX < -outsideXSpace)
                deltaX = -(matrixX + outsideXSpace);
        }
        else {
            if (matrixX + deltaX > 0)
                deltaX = -matrixX;
            else if (matrixX + deltaX < -outsideXSpace)
                deltaX = -(matrixX + outsideXSpace);

            if (matrixY + deltaY > 0)
                deltaY = -matrixY;
            else if (matrixY + deltaY < -outsideYSpace)
                deltaY = -(matrixY + outsideYSpace);
        }
        matrix.postTranslate(deltaX, deltaY);
        checkSiding();
    }
    private void checkSiding()
    {
        matrix.getValues(m);
        float matrixX = m[Matrix.MTRANS_X];
        float matrixY = m[Matrix.MTRANS_Y];

        //Log.d(TAG, "x: " + matrixX + " y: " + matrixY + " left: " + outsideXSpace / 2 + " top:" + outsideYSpace / 2);
        float scaleWidth = Math.round(fitBmpWidth * saveScale);
        float scaleHeight = Math.round(fitBmpHeight * saveScale);
        onLeftSide = onRightSide = onTopSide = onBottomSide = false;
        if (-matrixX < 10.0f ) onLeftSide = true;
        //Log.d("GalleryViewPager", String.format("ScaleW: %f; W: %f, MatrixX: %f", scaleWidth, viewWidth, matrixX));
        if ((scaleWidth >= viewWidth && (matrixX + scaleWidth - viewWidth) < 10) ||
            (scaleWidth <= viewWidth && -matrixX + scaleWidth <= viewWidth)) onRightSide = true;
        if (-matrixY < 10.0f) onTopSide = true;
        if (Math.abs(-matrixY + viewHeight - scaleHeight) < 10.0f) onBottomSide = true;
    }

    private void calcPadding()
    {
        outsideXSpace = viewWidth * saveScale - viewWidth - (2 * redundantXSpace * saveScale);
        outsideYSpace = viewHeight * saveScale - viewHeight - (2 * redundantYSpace * saveScale);
    }

    private void scaleMatrixToBounds()
    {
        matrix.getValues(m);
        float matrixX = m[Matrix.MTRANS_X];
        float matrixY = m[Matrix.MTRANS_Y];

        if (Math.abs(matrixX + outsideXSpace / 2) > 0.5f)
            matrix.postTranslate(-(matrixX + outsideXSpace / 2), 0);
        if (Math.abs(matrixY + outsideYSpace / 2) > 0.5f)
            matrix.postTranslate(0, -(matrixY + outsideYSpace / 2));
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        setImageBitmap(bm, null);
    }

    public void setImageBitmap(Bitmap bm, BitmapRegionDecodingDelegate decoder) {
        super.setImageBitmap(bm);
        imgWidth = bm.getWidth();
        imgHeight = bm.getHeight();
        regionDecoder = decoder;

        if (regionDecoder != null) {
            origImgWidth = regionDecoder.getWidth();
            origImgHeight = regionDecoder.getHeight();
        } else {
            origImgWidth = origImgHeight = 0;
        }

        resetMatrix();

        // clear overlap bmp
        overlapBmp = null;
        invalidate();
    }

    // calc normalized and max scale, if view size hasn't been initialized yet,
    // set normal scale to min scale.
    private void resetMaxScale() {
        if (viewWidth > 1) {
            if (regionDecoder != null) {
                normalizedScale = Math.max(origImgWidth / imgWidth,
                        Math.max(imgWidth / viewWidth, imgHeight / viewHeight));
            } else {
                normalizedScale = Math.max(imgWidth / viewWidth, imgHeight / viewHeight);
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
        viewWidth = MeasureSpec.getSize(widthMeasureSpec);
        viewHeight = MeasureSpec.getSize(heightMeasureSpec);

        resetMatrix();
    }

    /** Reset matrix to fit to screen. */
    private void resetMatrix() {
        //Fit to screen.
        float scale;
        float scaleX =  viewWidth / imgWidth;
        float scaleY = viewHeight / imgHeight;
        scale = Math.min(scaleX, scaleY);
        matrix.setScale(scale, scale);
        setImageMatrix(matrix);
        saveScale = 1f;

        // Center the image
        redundantYSpace = viewHeight - (scale * imgHeight) ;
        redundantXSpace = viewWidth - (scale * imgWidth);
        Assert.assertTrue(scaleEqual(redundantXSpace, 0)
                || scaleEqual(redundantYSpace, 0));
        redundantYSpace /= (float)2;
        redundantXSpace /= (float)2;

        matrix.postTranslate(redundantXSpace, redundantYSpace);

        fitBmpWidth = viewWidth - 2 * redundantXSpace;
        fitBmpHeight = viewHeight - 2 * redundantYSpace;
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

        final float subsampleRate = origImgWidth / imgWidth;

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
        rect.right = rect.left + viewWidth;
        rect.bottom = rect.top + viewHeight;
        //Log.d(TAG, String.format("rect 1 = %d,%d %d*%d of canvas",
        //        (int)rect.left, (int)rect.top, (int)rect.viewWidth(), (int)rect.height()));

        // rect2
        rect.left /= m[Matrix.MSCALE_X];
        rect.top /= m[Matrix.MSCALE_Y];
        rect.right /= m[Matrix.MSCALE_X];
        rect.bottom /= m[Matrix.MSCALE_Y];
        //Log.d(TAG, String.format("rect 2 = %d,%d %d*%d of background image (%d*%d) ",
        //        (int)rect.left, (int)rect.top, (int)rect.viewWidth(), (int)rect.height(),
        //        (int)imgWidth, (int)imgHeight));

        // rect3
        rect.left *= subsampleRate;
        rect.right *= subsampleRate;
        rect.top *= subsampleRate;
        rect.bottom *= subsampleRate;
        //Log.d(TAG, String.format("rect 3 = %d,%d %d*%d of original image (%d*%d)",
        //        (int)rect.left, (int)rect.top, (int)rect.viewWidth(), (int)rect.height(),
        //        origImgWidth, origImgHeight));


        final Rect visibleRect = new Rect((int)rect.left, (int)rect.top, (int)rect.right, (int)rect.bottom);

        if (!rectEquals(currVisibleRegion, visibleRect) || overlapBmp == null) {
            if (m[Matrix.MSCALE_X] > 1.1f) {

                currVisibleRegion = visibleRect;

                final BitmapFactory.Options opt = new BitmapFactory.Options();

                opt.inSampleSize = 1;
                int halfWidth = visibleRect.width() / 2;
                int halfHeight = visibleRect.height() / 2;
                while (opt.inSampleSize * 2 < subsampleRate
                        && halfWidth > viewWidth * opt.inSampleSize
                        && halfHeight > viewHeight * opt.inSampleSize) {
                    opt.inSampleSize *= 2;
                }

                //Log.d(TAG, String.format("clip region %d,%d %d*%d with inSampleSize=%d",
                //        visibleRect.left,
                //        visibleRect.top,
                //        visibleRect.viewWidth(),
                //        visibleRect.height(),
                //        opt.inSampleSize));


                if (visibleRect.top < 0) {
                    float heightScale = ((float)visibleRect.height() + 2 * visibleRect.top) / visibleRect.height();
                    overlapBmpDstRect.top = (int) (-visibleRect.top / subsampleRate * m[Matrix.MSCALE_Y]);
                    overlapBmpDstRect.bottom = overlapBmpDstRect.top + (int) (viewHeight * heightScale);

                    visibleRect.bottom += visibleRect.top;
                    visibleRect.top = 0;
                } else {
                    overlapBmpDstRect.top = 0;
                    overlapBmpDstRect.bottom = (int) viewHeight;
                }

                if (visibleRect.left < 0) {
                    float widthScale = ((float)visibleRect.width() + 2 * visibleRect.left) / visibleRect.width();
                    overlapBmpDstRect.left = (int) (- visibleRect.left / subsampleRate * m[Matrix.MSCALE_X]);
                    overlapBmpDstRect.right = overlapBmpDstRect.left + (int) (viewWidth * widthScale);

                    visibleRect.right += visibleRect.left;
                    visibleRect.left = 0;
                } else {
                    overlapBmpDstRect.left = 0;
                    overlapBmpDstRect.right = (int) viewWidth;
                }

                overlapBmp = null;

                // check IllegalArgumentException("rectangle is outside the image");
                if (!(visibleRect.right <= 0 || visibleRect.bottom <= 0 || visibleRect.left >= origImgWidth || visibleRect.top >= origImgHeight)) {
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

    private static boolean scaleEqual(float s1, float s2) {
        return Math.abs(s1 - s2) < 0.01;
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
            mScaleFactor = limitScale(mScaleFactor);

            if (mScaleFactor > 0.99 && mScaleFactor < 1.01)
                return true;

            saveScale *= mScaleFactor;
            calcPadding();

            if (fitBmpWidth * saveScale <= viewWidth || fitBmpHeight * saveScale <= viewHeight) {
                matrix.postScale(mScaleFactor, mScaleFactor, viewWidth / 2, viewHeight / 2);
                if (mScaleFactor < 1) {
                    matrix.getValues(m);
                    float x = m[Matrix.MTRANS_X];
                    float y = m[Matrix.MTRANS_Y];
                    if (mScaleFactor < 1) {
                        if (Math.round(fitBmpWidth * saveScale) < viewWidth) {
                            if (y < -outsideYSpace)
                                matrix.postTranslate(0, -(y + outsideYSpace));
                            else if (y > 0)
                                matrix.postTranslate(0, -y);
                        } else {
                            if (x < -outsideXSpace)
                                matrix.postTranslate(-(x + outsideXSpace), 0);
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
                    if (x < -outsideXSpace)
                        matrix.postTranslate(-(x + outsideXSpace), 0);
                    else if (x > 0)
                        matrix.postTranslate(-x, 0);
                    if (y < -outsideYSpace)
                        matrix.postTranslate(0, -(y + outsideYSpace));
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
