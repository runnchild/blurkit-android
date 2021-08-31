package com.psnlove.common.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import io.alterac.blurkit.BlurKit
import io.alterac.blurkit.BlurKitException
import io.alterac.blurkit.R
import io.alterac.blurkit.RoundedImageView
import java.lang.ref.WeakReference

class BlurLayout2 @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    companion object {
        private const val DEFAULT_DOWNSCALE_FACTOR = 0.3f
        private const val DEFAULT_BLUR_RADIUS = 12
        private const val DEFAULT_FPS = 60
        private const val DEFAULT_CORNER_RADIUS = 0f
        private const val DEFAULT_ALPHA = Float.NaN
    }

    // Customizable attributes
    /**
     * Factor to scale the view bitmap with before blurring.
     */
    private var mDownscaleFactor = 0f

    /**
     * Blur radius passed directly to stackblur library.
     */
    private var mBlurRadius = 0

    /**
     * Number of blur invalidations to do per second.
     */
    private var mFPS = 0

    /**
     * Corner radius for the layouts blur. To make rounded rects and circles.
     */
    private var mCornerRadius = 0f

    /**
     * Alpha value to set transparency
     */
    private var mAlpha = 0f

    /**
     * Is blur running?
     */
    private var mRunning = false
    private var mFrontColor = 0
    private var mBackColor = 0
    // Calculated class dependencies
    /**
     * ImageView to show the blurred content.
     */
    private var mImageView: RoundedImageView? = null

    init {
        if (!isInEditMode) {
            BlurKit.init(context)
        }

        val a = context.theme.obtainStyledAttributes(
            attrs, R.styleable.BlurLayout, 0, 0
        )

        try {
            mDownscaleFactor = a.getFloat(
                R.styleable.BlurLayout_blk_downscaleFactor, DEFAULT_DOWNSCALE_FACTOR
            )
            mBlurRadius =
                a.getInteger(R.styleable.BlurLayout_blk_blurRadius, DEFAULT_BLUR_RADIUS)
            mFPS = a.getInteger(R.styleable.BlurLayout_blk_fps, DEFAULT_FPS)
            mCornerRadius = a.getDimension(
                R.styleable.BlurLayout_blk_cornerRadius, DEFAULT_CORNER_RADIUS
            )
            mAlpha = a.getDimension(R.styleable.BlurLayout_blk_alpha, DEFAULT_ALPHA)
            mFrontColor = a.getColor(R.styleable.BlurLayout_blk_front_ground, 0)
            mBackColor = a.getColor(R.styleable.BlurLayout_blk_back_ground, 0)
            setTargetViewId(a.getInt(R.styleable.BlurLayout_blk_targetViewId, -1))
        } finally {
            a.recycle()
        }

        mImageView = RoundedImageView(context)
        mImageView?.scaleType = ImageView.ScaleType.FIT_XY
        addView(mImageView, LayoutParams(-1, -1))

        setCornerRadius(mCornerRadius)
    }

    fun setCornerRadius(cornerRadius: Float) {
        mCornerRadius = cornerRadius
        mImageView?.cornerRadius = cornerRadius
    }

    /**
     * Choreographer callback that re-draws the blur and schedules another callback.
     */
    private val invalidationLoop = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isEnabled) {
                pauseBlur()
            }
            if (mRunning) {
                invalidate()
                Choreographer.getInstance()
                    .postFrameCallbackDelayed(this, (1000 / mFPS).toLong())
            }
        }
    }

    /**
     * Start BlurLayout continuous invalidation.
     */
    fun startBlur() {
        if (mRunning) {
            return
        }
        if (mFPS > 0) {
            mRunning = true
            Choreographer.getInstance().removeFrameCallback(invalidationLoop)
            Choreographer.getInstance().postFrameCallback(invalidationLoop)
        }
    }

    /**
     * Pause BlurLayout continuous invalidation.
     */
    fun pauseBlur() {
        if (!mRunning) {
            return
        }
        mRunning = false
        Choreographer.getInstance().removeFrameCallback(invalidationLoop)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) {
            pauseBlur()
        } else {
            startBlur()
        }
    }

    /**
     * Is window attached?
     */
    private var mAttachedToWindow = false

    override fun setBackgroundColor(color: Int) {
        mBackColor = color
    }

    fun setFrontColor(color: Int) {
        mFrontColor = color
    }

    private val offsetPoint = PointF()

    fun offset(x: Float, y: Float) {
        offsetPoint.set(x, y)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mAttachedToWindow = true
        startBlur()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mAttachedToWindow = false
        pauseBlur()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        mImageView?.updateLayoutParams {
            width = w
            height = h
        }
    }

    override fun invalidate() {
        super.invalidate()
        if (isEnabled && isVisible) {
            blur()?.let {
                post {
                    mImageView?.setImageBitmap(it)
                }
            }
        }
    }

    private val targetView: View?
        get() = weakView?.get() ?: this.rootView

    private var weakView: WeakReference<View>? = null

    fun setTargetView(view: View?) {
        weakView = WeakReference<View>(view)
    }

    fun setTargetViewId(id: Int) {
        if (id < 0) {
            return
        }
        var viewParent: View? = this
        while (viewParent is View) {
            val view = viewParent.findViewById<View?>(id)
            if (view == null) {
                viewParent = viewParent.parent as? View
            } else {
                setTargetView(view)
                break
            }
        }
    }

    private val rect = RectF()

    private fun blur(): Bitmap? {
        val view = targetView
        view ?: return null
        if (width == 0) {
            return null
        }
        val point = getPositionRelativeToTarget()
        point.offset(offsetPoint.x, offsetPoint.y + view.scrollY)
        rect.set(point.x, point.y, point.x + width, point.y + height)
        val bitmap = getDownscaledBitmapForView(view, rect, mDownscaleFactor)
        return BlurKit.getInstance().blur(bitmap, mBlurRadius)
    }

    private val cacheBitmap by lazy {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    private val canvas by lazy { Canvas() }

    private val scaleMatrix by lazy { Matrix() }

    /**
     * Users a View reference to create a bitmap, and downscales it using the passed in factor.
     * Uses a Rect to crop the view into the bitmap.
     *
     * @return Bitmap made from view, downscaled by downscaleFactor.
     * @throws NullPointerException
     */
    @Throws(BlurKitException::class, NullPointerException::class)
    private fun getDownscaledBitmapForView(
        view: View, crop: RectF, downscaleFactor: Float
    ): Bitmap? {
        val width = (crop.width() * downscaleFactor).toInt()
        val height = (crop.height() * downscaleFactor).toInt()
        if (view.width <= 0 || view.height <= 0 || width <= 0 || height <= 0) {
            throw BlurKitException("No screen available (width or height = 0)")
        }
        val dx = -crop.left * downscaleFactor
        val dy = -crop.top * downscaleFactor
        val bitmap = cacheBitmap.apply {
            setWidth(width)
            setHeight(height)
        }
        scaleMatrix.reset()
        scaleMatrix.preScale(downscaleFactor, downscaleFactor)
        scaleMatrix.postTranslate(dx, dy)
        canvas.setBitmap(bitmap)
        canvas.matrix = scaleMatrix
        canvas.drawColor(mBackColor)
        view.draw(canvas)
        canvas.drawColor(mFrontColor)
        return bitmap
    }

    private fun getPositionRelativeToTarget(): PointF {
        return if (targetView != null) {
            val targetPoint = getPositionInScreen(targetView!!)
            val pointF = getPositionInScreen(this)
            pointF.offset(-targetPoint.x, -targetPoint.y)
            pointF
        } else {
            getPositionInScreen()
        }
    }

    /**
     * Returns the position in screen. Left abstract to allow for specific implementations such as
     * caching behavior.
     */
    private fun getPositionInScreen(): PointF {
        return getPositionInScreen(this)
    }

    /**
     * Finds the Point of the parent view, and offsets result by self getX() and getY().
     *
     * @return Point determining position of the passed in view inside all of its ViewParents.
     */
    private fun getPositionInScreen(view: View): PointF {
        if (parent == null) {
            return PointF()
        }
        val parent = try {
            view.parent as ViewGroup
        } catch (e: Exception) {
            return PointF()
        }
        val point = getPositionInScreen(parent)
        point.offset(view.x, view.y)
        return point
    }
}