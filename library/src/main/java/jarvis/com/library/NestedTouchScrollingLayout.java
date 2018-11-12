package jarvis.com.library;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.view.NestedScrollingParent;
import android.support.v4.view.animation.PathInterpolatorCompat;
import android.util.AttributeSet;
import android.util.Property;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * @author yyf @ JarvisGG.io
 * @since 10-16-2018
 * @function 无缝拖拽 parentView，假如 childView 可以滚动，犟 touch dispatch 给它，假如不可以，当前会自己消化 touch 事件
 */
public class NestedTouchScrollingLayout extends FrameLayout implements NestedScrollingParent {

    private static final String TAG = "NestedTouchScrolling";

    private View mChildView;

    private ObjectAnimator mTransYAnim;
    private ObjectAnimator currentAnimator;

    private VelocityTracker velocityTracker;

    private float minFlingVelocity;

    private float mTouchSlop;

    private float mDownY;

    private float mDownX;

    private float mDownSheetTranslation;

    private float mOriginTranslate = 0;

    /**
     * 针对包含的子 View 为 webview 的情况
     */
    private int mWebViewContentHeight;

    /**
     * 横向拖拽 dispatchTouch 给 childView
     */
    private boolean mParentOwnsTouch;

    /**
     * 竖向拖拽 NestedTouchScrollingLayout 是否消化 touch（根据 childView (canScrollUp or canScrollDown)）
     */
    private boolean isHoldTouch = true;

    private float mSheetTranslation;

    /**
     * 是否允许左右滑动，下发滑动事件
     */
    private boolean isLeftorRightTouchLimit = true;

    private List<INestChildScrollChange> mNestChildScrollChangeCallbacks;


    private final Property<NestedTouchScrollingLayout, Float> SHEET_TRANSLATION = new Property<NestedTouchScrollingLayout, Float>(Float.class, "sheetTranslation") {
        @Override
        public Float get(NestedTouchScrollingLayout object) {
            return getHeight() - object.mSheetTranslation;
        }

        @Override
        public void set(NestedTouchScrollingLayout object, Float value) {
            object.seAnimtTranslation(value);
        }
    };

    public void setLeftorRightTouchLimit(boolean leftorRightTouchLimit) {
        this.isLeftorRightTouchLimit = leftorRightTouchLimit;
    }

    public interface INestChildScrollChange {
        /**
         * nestChild scroll change
         * @param deltaY
         */
        void onNestChildScrollChange(float deltaY);

        void onNestChildScrollRelease(float deltaY, int velocityY);

        void onNestChildHorizationScroll(boolean show);
    }

    public NestedTouchScrollingLayout(@NonNull Context context) {
        super(context);
        init();
    }

    public NestedTouchScrollingLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();

    }

    public NestedTouchScrollingLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public NestedTouchScrollingLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        mNestChildScrollChangeCallbacks = new ArrayList<>();
        mTouchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (getChildCount() > 1) {
            throw new IllegalStateException("child must be 1!!!");
        }
        mChildView = getChildAt(0);
    }

    @Override
    public void addView(View child) {
        if (getChildCount() >= 1) {
            throw new IllegalStateException("child must be 1!!!");
        }
        mChildView = child;
        super.addView(child);
    }

    @Override
    public void addView(View child, ViewGroup.LayoutParams params) {
        if (getChildCount() >= 1) {
            throw new IllegalStateException("child must be 1!!!");
        }
        mChildView = child;
        super.addView(child, params);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        velocityTracker = VelocityTracker.obtain();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        clearNestScrollChildCallback();
        velocityTracker.clear();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getChildAt(0) == null) {
            return super.onTouchEvent(event);
        }
        if (isAnimating()) {
            return false;
        }

        if (event.getAction() == MotionEvent.ACTION_DOWN) {

            mOriginTranslate = mChildView.getTranslationY();

            mParentOwnsTouch = false;
            mDownY = event.getY();
            mDownX = event.getX();
            mSheetTranslation = getMeasuredHeight() - mOriginTranslate;
            mDownSheetTranslation = mSheetTranslation;
            velocityTracker.clear();

            if (mChildView instanceof WebView) {
                mWebViewContentHeight = (int) (((WebView)mChildView).getContentHeight() * ((WebView)mChildView).getScale());
            }
        }

        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            isLeftorRightTouchLimit = true;
        }

        getParent().requestDisallowInterceptTouchEvent(true);

        velocityTracker.addMovement(event);

        float maxSheetTranslation = getMeasuredHeight();

        float deltaY = mDownY - event.getY();
        float deltaX = mDownX - event.getX();

        if ((!canScrollLeft(getChildAt(0), event.getX(), event.getX()) && !canScrollRight(getChildAt(0), event.getX(), event.getY()) && !isLeftorRightTouchLimit)
                || (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)) {
            interceptHorizationTouch(event, deltaX, deltaY);
        }

        if (!mParentOwnsTouch) {
            mParentOwnsTouch = Math.abs(deltaY) > mTouchSlop && Math.abs(deltaY) > Math.abs(deltaX);

            if (mParentOwnsTouch) {

                mDownY = event.getY();
                mDownX = event.getX();
                deltaY = 0;
                deltaX = 0;
            }
        }

        float newSheetTranslation = mDownSheetTranslation + deltaY;

        if (mParentOwnsTouch) {


            if (isHoldTouch && (!isChildCanScroll(event, deltaY))) {
                velocityTracker.clear();
                isHoldTouch = false;
                newSheetTranslation = mSheetTranslation;

                MotionEvent cancelEvent = MotionEvent.obtain(event);
                cancelEvent.setAction(MotionEvent.ACTION_CANCEL);
                getChildAt(0).dispatchTouchEvent(cancelEvent);
                cancelEvent.recycle();
            }

            if (!isHoldTouch && isChildCanScroll(event, deltaY)) {
                setSheetTranslation(maxSheetTranslation);
                isHoldTouch = true;
                if (!(event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL)) {
                    MotionEvent downEvent = MotionEvent.obtain(event);
                    downEvent.setAction(MotionEvent.ACTION_DOWN);
                    getChildAt(0).dispatchTouchEvent(downEvent);
                    downEvent.recycle();
                }
            }

            if (isHoldTouch) {
                event.offsetLocation(0, mSheetTranslation - getMeasuredHeight());
                getChildAt(0).dispatchTouchEvent(event);
            } else {
                setSheetTranslation(newSheetTranslation);

                if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    isHoldTouch = true;
                    getParent().requestDisallowInterceptTouchEvent(false);
                    velocityTracker.computeCurrentVelocity(1000);
                    float velocityY = velocityTracker.getYVelocity();
//                    recover(0);
                    notifyNestScrollChildReleaseCallback((int) velocityY);
                }
            }
        } else {
            event.offsetLocation(0, mSheetTranslation - getMeasuredHeight());
            getChildAt(0).dispatchTouchEvent(event);
        }
        return true;
    }

    private boolean isChildCanScroll(MotionEvent event, float deltaY) {
        boolean fingerDown = deltaY - mOriginTranslate < 0;
        boolean canScrollDown = canScrollDown(getChildAt(0), event.getX(), event.getY() + (mSheetTranslation - getHeight()));
        boolean fingerUp = deltaY - mOriginTranslate > 0;
        boolean canScrollUp = canScrollUp(getChildAt(0), event.getX(), event.getY() + (mSheetTranslation - getHeight()));
        return (fingerDown && canScrollUp) || (fingerUp && canScrollDown);
    }

    private boolean canScrollUp(View view, float x, float y) {
        if (view instanceof WebView) {
            return canWebViewScrollUp();
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                int childLeft = child.getLeft() - view.getScrollX();
                int childTop = child.getTop() - view.getScrollY();
                int childRight = child.getRight() - view.getScrollX();
                int childBottom = child.getBottom() - view.getScrollY();
                boolean intersects = x > childLeft && x < childRight && y > childTop && y < childBottom;
                if (intersects && canScrollUp(child, x - childLeft, y - childTop)) {
                    return true;
                }
            }
        }
        return view.canScrollVertically(-1);
    }

    private boolean canScrollDown(View view, float x, float y) {
        if (view instanceof WebView) {
            return canWebViewScrollDown();
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                int childLeft = child.getLeft() - view.getScrollX();
                int childTop = child.getTop() - view.getScrollY();
                int childRight = child.getRight() - view.getScrollX();
                int childBottom = child.getBottom() - view.getScrollY();
                boolean intersects = x > childLeft && x < childRight && y > childTop && y < childBottom;
                if (intersects && canScrollDown(child, x - childLeft, y - childTop)) {
                    return true;
                }
            }
        }
        return view.canScrollVertically(1);
    }

    private boolean canScrollLeft(View view, float x, float y) {
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                int childLeft = child.getLeft() - view.getScrollX();
                int childTop = child.getTop() - view.getScrollY();
                int childRight = child.getRight() - view.getScrollX();
                int childBottom = child.getBottom() - view.getScrollY();
                boolean intersects = x > childLeft && x < childRight && y > childTop && y < childBottom;
                if (intersects && canScrollLeft(child, x - childLeft, y - childTop)) {
                    return true;
                }
            }
        }
        return view.canScrollHorizontally(-1);
    }

    private boolean canScrollRight(View view, float x, float y) {
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                int childLeft = child.getLeft() - view.getScrollX();
                int childTop = child.getTop() - view.getScrollY();
                int childRight = child.getRight() - view.getScrollX();
                int childBottom = child.getBottom() - view.getScrollY();
                boolean intersects = x > childLeft && x < childRight && y > childTop && y < childBottom;
                if (intersects && canScrollRight(child, x - childLeft, y - childTop)) {
                    return true;
                }
            }
        }
        return view.canScrollHorizontally(1);
    }

    /**
     * 规避 contentHeight 异步变化
     * @return
     */
    private boolean canWebViewScrollUp() {
        final int offset = mChildView.getScrollY();
        final int range = mWebViewContentHeight - mChildView.getHeight();
        if (range == 0) {
            return false;
        }
        return offset > 0;
    }

    /**
     * 规避 contentHeight 异步变化
     * @return
     */
    private boolean canWebViewScrollDown() {
        final int offset = mChildView.getScrollY();
        final int range = mWebViewContentHeight - mChildView.getHeight();
        if (range == 0) {
            return false;
        }
        return offset < range - 3;
    }


    private void setSheetTranslation(float newTranslation) {
        this.mSheetTranslation = newTranslation;
        int bottomClip = (int) (getHeight() - Math.ceil(mSheetTranslation));
        setTranslation(bottomClip);
    }

    public void seAnimtTranslation(float transY) {
        this.mSheetTranslation = getHeight() - transY;
        setTranslation(transY);
    }

    public void setTranslation(float transY) {
        notifyNestScrollChildChangeCallback(transY);
        if (mChildView != null) {
            mChildView.setTranslationY(transY);
        }
    }

    /**
     *
     * @param target
     */
    public void recover(int target) {
        recover(target, null);
    }

    public void recover(int target, final Runnable runnable) {
        currentAnimator = ObjectAnimator.ofFloat(this, SHEET_TRANSLATION, target);
        currentAnimator.setDuration(300);
        currentAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        currentAnimator.addListener(new CancelDetectionAnimationListener() {
            @Override
            public void onAnimationEnd(@NonNull Animator animation) {
                if (!canceled) {
                    currentAnimator = null;
                }
                if (runnable != null) {
                    runnable.run();
                }
            }
        });
        currentAnimator.start();
    }

    private void interceptHorizationTouch(MotionEvent event, float deltaX, float deltaY) {
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            notifyNestScrollChildHorizationCallback(false);
            return;
        }
        if (Math.abs(deltaX) > mTouchSlop * 8 && Math.abs(deltaX) > Math.abs(deltaY) && deltaX > 0) {
            notifyNestScrollChildHorizationCallback(true);
        }
    }

    private boolean isAnimating() {
        return currentAnimator != null;
    }

    private static class CancelDetectionAnimationListener extends AnimatorListenerAdapter {

        protected boolean canceled;

        @Override
        public void onAnimationCancel(Animator animation) {
            canceled = true;
        }

    }

    private void onActionMove(MotionEvent event) {
        float distance = countDragDistanceFromMotionEvent(event);
        mChildView.setTranslationY(distance);
    }

    private void onActionRelease(MotionEvent event) {
        float distance = countDragDistanceFromMotionEvent(event);
        if (mTransYAnim != null && mTransYAnim.isRunning()) {
            mTransYAnim.cancel();
        }

        mTransYAnim = ObjectAnimator.ofFloat(mChildView, View.TRANSLATION_Y,
                mChildView.getTranslationY(), 0.0F);
        mTransYAnim.setDuration(200L);
        mTransYAnim.setInterpolator(PathInterpolatorCompat.create(0.4F, 0.0F, 0.2F, 1.0F));

        mTransYAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {

            }
        });

        mTransYAnim.start();
    }

    public void registerNestScrollChildCallback(INestChildScrollChange childScrollChange) {
        if (!mNestChildScrollChangeCallbacks.contains(childScrollChange)) {
            mNestChildScrollChangeCallbacks.add(childScrollChange);
        }
    }

    public void removeNestScrollChildCallback(INestChildScrollChange childScrollChange) {
        if (mNestChildScrollChangeCallbacks.contains(childScrollChange)) {
            mNestChildScrollChangeCallbacks.remove(childScrollChange);
        }
    }

    public void clearNestScrollChildCallback() {
        mNestChildScrollChangeCallbacks.clear();
    }

    private void notifyNestScrollChildChangeCallback(float detlaY) {
        for (INestChildScrollChange change : mNestChildScrollChangeCallbacks) {
            change.onNestChildScrollChange(detlaY);
        }
    }

    private void notifyNestScrollChildReleaseCallback(int velocityY) {
        for (INestChildScrollChange change : mNestChildScrollChangeCallbacks) {
            change.onNestChildScrollRelease(getChildAt(0).getTranslationY(), velocityY);
        }
    }

    private void notifyNestScrollChildHorizationCallback(boolean show) {
        for (INestChildScrollChange change : mNestChildScrollChangeCallbacks) {
            change.onNestChildHorizationScroll(show);
        }
    }

    @Override
    protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY, int scrollRangeX, int scrollRangeY, int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
        return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, maxOverScrollX, maxOverScrollY, isTouchEvent);
    }

    private float countDragDistanceFromMotionEvent(@NonNull MotionEvent event) {
        float distance = event.getRawY();
        return distance;
    }

    @Override
    public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
        return true;
    }

    @Override
    public void onNestedScrollAccepted(View child, View target, int axes) {
        super.onNestedScrollAccepted(child, target, axes);
    }

    @Override
    public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
        super.onNestedPreScroll(target, dx, dy, consumed);
    }

    @Override
    public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
        super.onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed);
    }

    @Override
    public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
        return super.onNestedPreFling(target, velocityX, velocityY);
    }

    @Override
    public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
        return super.onNestedFling(target, velocityX, velocityY, consumed);
    }


    @Override
    public void onStopNestedScroll(View child) {
        super.onStopNestedScroll(child);
    }

    @Override
    public int getNestedScrollAxes() {
        return super.getNestedScrollAxes();
    }

}

