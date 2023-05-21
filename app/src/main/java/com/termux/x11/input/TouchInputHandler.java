// Copyright 2013 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.termux.x11.input;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.ViewConfiguration;

import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class is responsible for handling Touch input from the user.  Touch events which manipulate
 * the local canvas are handled in this class and any input which should be sent to the remote host
 * are passed to the InputStrategyInterface implementation set by the DesktopView.
 */
public class TouchInputHandler {
    private static final float EPSILON = 0.001f;

    /** Used to set/store the selected input mode. */
    @SuppressWarnings("unused")
    @IntDef({InputMode.UNKNOWN, InputMode.TRACKPAD, InputMode.SIMULATED_TOUCH, InputMode.TOUCH})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InputMode {
        // Values are starting from 0 and don't have gaps.
        int UNKNOWN = 0;
        int TRACKPAD = 1;
        int SIMULATED_TOUCH = 2;
        int TOUCH = 3;
        int NUM_ENTRIES = 4;
    }

    private final PointF cursorPos = new PointF();

    private final RenderData mRenderData;
    private final RenderStub mRenderStub;
    private final GestureDetector mScroller;
    private final ScaleGestureDetector mZoomer;
    private final TapGestureDetector mTapDetector;
    private final HardwareMouseListener mHMListener = new HardwareMouseListener();

    /** Used to disambiguate a 2-finger gesture as a swipe or a pinch. */
    private final SwipePinchDetector mSwipePinchDetector;

    // Used for processing cursor & scroller fling animations.
    // May consider using a List of AnimationJob if we have more than two animation jobs in
    // the future.
    private final FlingAnimationJob mCursorAnimationJob;
    private final FlingAnimationJob mScrollAnimationJob;

    private InputStrategyInterface mInputStrategy;
    private final InputEventSender mInjector;
    private final Context mContext;

    /**
     * Used for tracking swipe gestures. Only the Y-direction is needed for responding to swipe-up
     * or swipe-down.
     */
    private float mTotalMotionY;

    /**
     * Distance in pixels beyond which a motion gesture is considered to be a swipe. This is
     * initialized using the Context passed into the ctor.
     */
    private final float mSwipeThreshold;

    /**
     * Distance, in pixels, from the edge of the screen in which a touch event should be considered
     * as having originated from that edge.
     */
    private final int mEdgeSlopInPx;

    /**
     * Defines an inset boundary within which pan gestures are allowed.  Pan gestures which
     * originate outside of this boundary will be ignored.
     */
    private Rect mPanGestureBounds = new Rect();

    /**
     * Set to true to prevent any further movement of the cursor, for example, when showing the
     * keyboard to prevent the cursor wandering from the area where keystrokes should be sent.
     */
    private boolean mSuppressCursorMovement;

    /**
     * Set to true to suppress the fling animation at the end of a gesture, for example, when
     * dragging whilst a button is held down.
     */
    private boolean mSuppressFling;

    /**
     * Set to true when 2-finger fling (scroll gesture with final velocity) is detected to trigger
     * a scrolling animation.
     */
    private boolean mScrollFling;

    /**
     * Set to true when 3-finger swipe gesture is complete, so that further movement doesn't
     * trigger more swipe actions.
     */
    private boolean mSwipeCompleted;

    /**
     * Set to true when a 1 finger pan gesture originates with a longpress.  This means the user
     * is performing a drag operation.
     */
    private boolean mIsDragging;

//    private final Event.ParameterCallback<Boolean, Void> mProcessAnimationCallback;

    /**
     * This class implements fling animation for cursor
     */
    private class CursorAnimationJob extends FlingAnimationJob {
        public CursorAnimationJob(Context context) {
            super(context);
        }

        @Override
        protected void processAction(float deltaX, float deltaY) {
            float[] delta = {deltaX, deltaY};
            Matrix canvasToImage = new Matrix();
            mRenderData.transform.invert(canvasToImage);
            canvasToImage.mapVectors(delta);

            moveCursorByOffset(-delta[0], -delta[1]);
        }
    }

    /**
     * This class implements fling animation for scrolling
     */
    private class ScrollAnimationJob extends FlingAnimationJob {
        public ScrollAnimationJob(Context context) {
            super(context);
        }

        @Override
        protected void processAction(float deltaX, float deltaY) {
            mInputStrategy.onScroll(-deltaX, -deltaY);
        }
    }

    /**
     * This class provides a NULL implementation which will be used until a real input
     * strategy has been created and set.  Using this as the default implementation will prevent
     * crashes if the owning class does not create/set a real InputStrategy before the host size
     * information is received (or if the user interacts with the screen in that case).  This class
     * has default values which will also allow the user to pan/zoom the desktop image until an
     * InputStrategy implementation has been set.
     */
    private static class NullInputStrategy implements InputStrategyInterface {
        NullInputStrategy() {}

        @Override
        public boolean onTap(int button) {
            return false;
        }

        @Override
        public boolean onPressAndHold(int button) {
            return false;
        }

        @Override
        public void onMotionEvent(MotionEvent event) {}

        @Override
        public void onScroll(float distanceX, float distanceY) {}

        @Override
        public void injectCursorMoveEvent(int x, int y) {}

        @Override
        public @RenderStub.InputFeedbackType int getShortPressFeedbackType() {
            return RenderStub.InputFeedbackType.NONE;
        }

        @Override
        public @RenderStub.InputFeedbackType int getLongPressFeedbackType() {
            return RenderStub.InputFeedbackType.NONE;
        }

        @Override
        public boolean isIndirectInputMode() {
            return false;
        }
    }

    public TouchInputHandler(Context ctx, /*DesktopView viewer, Desktop desktop, */ RenderStub renderStub,
                             final InputEventSender injector) {
        Preconditions.notNull(renderStub);
        Preconditions.notNull(injector);

//        mDesktop = desktop;
        mRenderStub = renderStub;
        mRenderData = new RenderData();
        mInjector = injector;
        mContext = ctx;
//        mDesktopCanvas = new DesktopCanvas(renderStub, mRenderData);

        GestureListener listener = new GestureListener();
        mScroller = new GestureDetector(/*desktop*/ ctx, listener, null, false);

        // If long-press is enabled, the gesture-detector will not emit any further onScroll
        // notifications after the onLongPress notification. Since onScroll is being used for
        // moving the cursor, it means that the cursor would become stuck if the finger were held
        // down too long.
        mScroller.setIsLongpressEnabled(false);

        mZoomer = new ScaleGestureDetector(/*desktop*/ ctx, listener);
        mTapDetector = new TapGestureDetector(/*desktop*/ ctx, listener);
        mSwipePinchDetector = new SwipePinchDetector(/*desktop*/ ctx);

        // The threshold needs to be bigger than the ScaledTouchSlop used by the gesture-detectors,
        // so that a gesture cannot be both a tap and a swipe. It also needs to be small enough so
        // that intentional swipes are usually detected.
        float density = /*desktop*/ ctx.getResources().getDisplayMetrics().density;
        mSwipeThreshold = 40 * density;

        mEdgeSlopInPx = ViewConfiguration.get(/*desktop*/ ctx).getScaledEdgeSlop();

        mInputStrategy = new NullInputStrategy();

        mCursorAnimationJob = new CursorAnimationJob(/*desktop*/ ctx);
        mScrollAnimationJob = new ScrollAnimationJob(/*desktop*/ ctx);

//        mProcessAnimationCallback = p -> processAnimation();
    }

//    /**
//     * Steps forward the animation.
//     * @return true if the animation is not finished yet.
//     */
//    private boolean processAnimation() {
//        return mCursorAnimationJob.processAnimation() || mScrollAnimationJob.processAnimation();
//    }

    /**
     * Start stepping animation when onCanvasRendered is triggered.
     */
    private void startAnimation() {
//        mRenderStub.onCanvasRendered().addSelfRemovable(mProcessAnimationCallback);
    }

    /**
     * Abort all animations.
     */
    private void abortAnimation() {
        mCursorAnimationJob.abortAnimation();
        mScrollAnimationJob.abortAnimation();
    }

    public boolean handleTouchEvent(MotionEvent event) {
        Log.d("TouchListener", "Motion tool" + event.getToolType(event.getActionIndex()));
        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE)
            return mHMListener.onTouch(event);

        if (event.getToolType(event.getActionIndex()) == MotionEvent.TOOL_TYPE_FINGER) {
            // Give the underlying input strategy a chance to observe the current motion event before
            // passing it to the gesture detectors.  This allows the input strategy to react to the
            // event or save the payload for use in recreating the gesture remotely.
            mInputStrategy.onMotionEvent(event);

            // Avoid short-circuit logic evaluation - ensure all gesture detectors see all events so
            // that they generate correct notifications.
            boolean handled = mScroller.onTouchEvent(event);
            handled |= mZoomer.onTouchEvent(event);
            handled |= mTapDetector.onTouchEvent(event);
            mSwipePinchDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    abortAnimation();
                    mSuppressCursorMovement = false;
                    mSuppressFling = false;
                    mSwipeCompleted = false;
                    mIsDragging = false;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    mTotalMotionY = 0;
                    break;

                default:
                    break;
            }
            return handled;
        }

        return false;
    }

    private void resetTransformation() {
        float sx = (float) mRenderData.imageWidth / mRenderData.screenWidth;
        float sy = (float) mRenderData.imageHeight / mRenderData.screenHeight;
        mRenderData.transform.setScale(sx, sy);
    }

    public void handleClientSizeChanged(int w, int h) {
        mRenderData.screenWidth = w;
        mRenderData.screenHeight = h;
        mPanGestureBounds = new Rect(mEdgeSlopInPx, mEdgeSlopInPx, w - mEdgeSlopInPx, h - mEdgeSlopInPx);

        resetTransformation();
    }

    public void handleHostSizeChanged(int width, int height) {
        mRenderData.imageWidth = width;
        mRenderData.imageHeight = height;

        resetTransformation();
    }

    public void setInputMode(@InputMode int inputMode) {
        if (inputMode == InputMode.TOUCH) {
            mInputStrategy = new TouchInputStrategy(mRenderData, mInjector);
        } else if (inputMode == InputMode.SIMULATED_TOUCH) {
            mInputStrategy = new SimulatedTouchInputStrategy(mRenderData, mInjector, mContext);
        } else if (inputMode == InputMode.TRACKPAD) {
            mInputStrategy = new TrackpadInputStrategy(mRenderData, mInjector);
        } else {
           assert false : "Unreached";
        }
    }

    private void moveCursorByOffset(float deltaX, float deltaY) {
        cursorPos.offset(-deltaX, -deltaY);
        if (cursorPos.x < 0)
            cursorPos.x = 0;
        if (cursorPos.y < 0)
            cursorPos.y = 0;
        if (cursorPos.x > mRenderData.screenWidth)
            cursorPos.x = mRenderData.screenWidth;
        if (cursorPos.y > mRenderData.screenHeight)
            cursorPos.y = mRenderData.screenHeight;
        moveCursor(cursorPos.x, cursorPos.y);
    }

    /** Moves the cursor to the specified position on the screen. */
    private void moveCursorToScreenPoint(float screenX, float screenY) {
        float[] imagePoint = mapScreenPointToImagePoint(screenX, screenY);
        moveCursor(imagePoint[0], imagePoint[1]);
    }

    /** Moves the cursor to the specified position on the remote host. */
    private void moveCursor(float newX, float newY) {
        boolean cursorMoved = mRenderData.setCursorPosition(newX, newY);
        if (cursorMoved)
            mInputStrategy.injectCursorMoveEvent((int) newX, (int) newY);

        mRenderStub.moveCursor(mRenderData.getCursorPosition());
    }

    /** Processes a (multi-finger) swipe gesture. */
    private boolean onSwipe() {
        if (mTotalMotionY > mSwipeThreshold)
            mRenderStub.swipeDown();
        else if (mTotalMotionY < -mSwipeThreshold)
            mRenderStub.swipeUp();
        else
            return false;

        mSuppressCursorMovement = true;
        mSuppressFling = true;
        mSwipeCompleted = true;
        return true;
    }

    /** Translates a point in screen coordinates to a location on the desktop image. */
    private float[] mapScreenPointToImagePoint(float screenX, float screenY) {
        float[] mappedPoints = {screenX, screenY};
        Matrix screenToImage = new Matrix();

        mRenderData.transform.invert(screenToImage);
        screenToImage.mapPoints(mappedPoints);

        return mappedPoints;
    }

    /** Responds to touch events filtered by the gesture detectors. */
    private class GestureListener extends GestureDetector.SimpleOnGestureListener
            implements ScaleGestureDetector.OnScaleGestureListener,
                       TapGestureDetector.OnTapListener {
        /**
         * Called when the user drags one or more fingers across the touchscreen.
         */
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            int pointerCount = e2.getPointerCount();

            // Check to see if the motion originated at the edge of the screen.
            // If so, then the user is likely swiping in to display system UI.
            if (!mPanGestureBounds.contains((int) e1.getX(), (int) e1.getY())) {
                // Prevent the cursor being moved or flung by the gesture.
                mSuppressCursorMovement = true;
                return false;
            }

            if (pointerCount >= 3 && !mSwipeCompleted) {
                // Note that distance values are reversed. For example, dragging a finger in the
                // direction of increasing Y coordinate (downwards) results in distanceY being
                // negative.
                mTotalMotionY -= distanceY;
                return onSwipe();
            }

            if (pointerCount == 2 && mSwipePinchDetector.isSwiping()) {
                if (!mInputStrategy.isIndirectInputMode()) {
                    // Ensure the cursor is located at the coordinates of the original event,
                    // otherwise the target window may not receive the scroll event correctly.
                    moveCursorToScreenPoint(e1.getX(), e1.getY());
                }
                mInputStrategy.onScroll(distanceX, distanceY);

                // Prevent the cursor being moved or flung by the gesture.
                mSuppressCursorMovement = true;
                mScrollFling = true;
                return true;
            }

            if (pointerCount != 1 || mSuppressCursorMovement) {
                return false;
            }

            float[] delta = {distanceX, distanceY};

            Matrix canvasToImage = new Matrix();
            mRenderData.transform.invert(canvasToImage);
            canvasToImage.mapVectors(delta);

            if (mInputStrategy.isIndirectInputMode())
                moveCursorByOffset(delta[0], delta[1]);
            if (!mInputStrategy.isIndirectInputMode() && mIsDragging) {
                // Ensure the cursor follows the user's finger when the user is dragging under
                // direct input mode.
                moveCursorToScreenPoint(e2.getX(), e2.getY());
            }
            return true;
        }

        /**
         * Called when a fling gesture is recognized.
         */
        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (mSuppressFling) {
                return false;
            }

            if (mScrollFling) {
                mScrollAnimationJob.startAnimation(velocityX, velocityY);
                startAnimation();
                mScrollFling = false;
                return true;
            }

            if (mSuppressCursorMovement) {
                return false;
            }

            // If cursor movement is suppressed, fling also needs to be suppressed, as the
            // gesture-detector will still generate onFling() notifications based on movement of
            // the fingers, which would result in unwanted cursor movement.
            mCursorAnimationJob.startAnimation(velocityX, velocityY);
            startAnimation();
            return true;
        }

        /** Called when the user is in the process of pinch-zooming. */
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return true;
        }

        /** Called whenever a gesture starts. Always accepts the gesture so it isn't ignored. */
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        /**
         * Called when the user starts to zoom. Always accepts the zoom so that
         * onScale() can decide whether to respond to it.
         */
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        /** Called when the user is done zooming. Defers to onScale()'s judgement. */
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
        }

        /** Called when the user taps the screen with one or more fingers. */
        @Override
        public boolean onTap(int pointerCount, float x, float y) {
            int button = mouseButtonFromPointerCount(pointerCount);
            if (button == InputStub.BUTTON_UNDEFINED) {
                return false;
            }

            if (!mInputStrategy.isIndirectInputMode()) {
                if (screenPointLiesOutsideImageBoundary(x, y)) {
                    return false;
                }
                moveCursorToScreenPoint(x, y);
            }

            if (mInputStrategy.onTap(button)) {
                PointF pos = mRenderData.getCursorPosition();

                mRenderStub.showInputFeedback(mInputStrategy.getShortPressFeedbackType(), pos);
            }
            return true;
        }

        /** Called when a long-press is triggered for one or more fingers. */
        @Override
        public void onLongPress(int pointerCount, float x, float y) {
            int button = mouseButtonFromPointerCount(pointerCount);
            if (button == InputStub.BUTTON_UNDEFINED) {
                return;
            }

            if (!mInputStrategy.isIndirectInputMode()) {
                if (screenPointLiesOutsideImageBoundary(x, y)) {
                    return;
                }
                moveCursorToScreenPoint(x, y);
            }

            if (mInputStrategy.onPressAndHold(button)) {
                PointF pos = mRenderData.getCursorPosition();

                mRenderStub.showInputFeedback(mInputStrategy.getLongPressFeedbackType(), pos);
                mSuppressFling = true;
                mIsDragging = true;
            }
        }

        /** Maps the number of fingers in a tap or long-press gesture to a mouse-button. */
        private int mouseButtonFromPointerCount(int pointerCount) {
            switch (pointerCount) {
                case 1:
                    return InputStub.BUTTON_LEFT;
                case 2:
                    return InputStub.BUTTON_RIGHT;
                case 3:
                    return InputStub.BUTTON_MIDDLE;
                default:
                    return InputStub.BUTTON_UNDEFINED;
            }
        }

        /** Determines whether the given screen point lies outside the desktop image. */
        private boolean screenPointLiesOutsideImageBoundary(float screenX, float screenY) {
            float[] mappedPoints = mapScreenPointToImagePoint(screenX, screenY);

            float imageWidth = (float) mRenderData.imageWidth + EPSILON;
            float imageHeight = (float) mRenderData.imageHeight + EPSILON;

            return mappedPoints[0] < -EPSILON || mappedPoints[0] > imageWidth
                    || mappedPoints[1] < -EPSILON || mappedPoints[1] > imageHeight;
        }
    }


    public boolean sendKeyEvent(KeyEvent event) {
        return mInjector.sendKeyEvent(event);
    }

    private class HardwareMouseListener {
        private int savedBS = 0;
        private int currentBS = 0;

        boolean isMouseButtonChanged(int mask) {
            return (savedBS & mask) != (currentBS & mask);
        }

        boolean mouseButtonDown(int mask) {
            return ((currentBS & mask) != 0);
        }

        @SuppressLint("ClickableViewAccessibility")
        boolean onTouch(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_SCROLL) {
                float scrollY = -1 * e.getAxisValue(MotionEvent.AXIS_VSCROLL);
                float scrollX = -1 * e.getAxisValue(MotionEvent.AXIS_HSCROLL);

                mInjector.sendMouseWheelEvent(scrollX, scrollY);
                return true;
            }

            float[] imagePoint = mapScreenPointToImagePoint((int) e.getX(), (int) e.getY());
            if (mRenderData.setCursorPosition(imagePoint[0], imagePoint[1]))
                mInjector.sendCursorMove(imagePoint[0], imagePoint[1]);

            currentBS = e.getButtonState();
            if (isMouseButtonChanged(MotionEvent.BUTTON_PRIMARY))
                mInjector.sendMouseEvent(mRenderData.getCursorPosition(), 1, mouseButtonDown(MotionEvent.BUTTON_PRIMARY));
            if (isMouseButtonChanged(MotionEvent.BUTTON_TERTIARY))
                mInjector.sendMouseEvent(mRenderData.getCursorPosition(), 2, mouseButtonDown(MotionEvent.BUTTON_TERTIARY));
            if (isMouseButtonChanged(MotionEvent.BUTTON_SECONDARY))
                mInjector.sendMouseEvent(mRenderData.getCursorPosition(), 3, mouseButtonDown(MotionEvent.BUTTON_SECONDARY));
            savedBS = currentBS;
            return true;
        }
    }
}
