package com.kevin.flutterfloatwindow.flutter_float_window

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DataSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

enum class FloatWindowGravity {
    LEFT, TOP, RIGHT, BOTTOM, CENTER,
}

class FloatWindowService : Service() {
    private lateinit var wmParams: WindowManager.LayoutParams
    private lateinit var mWindowManager: WindowManager
    private lateinit var mWindowView: View
    private lateinit var mContainer: FrameLayout
    private lateinit var mCloseImage: ImageView
    private var hasAdded = false
    private var hasRelease = false
    private var currentUrl = ""
    private var isBig = false//默认是小屏
    private var isButtonShown = true
    private var mWidth = 500
    private var mHeight = 280
    private var mAspectRatio: Float = (9 / 16).toFloat()
    private var useAspectRatio = false
    private var mFloatGravity: FloatWindowGravity = FloatWindowGravity.BOTTOM
    private lateinit var mContext: Context
    private var mScreenWidth: Int = 0
    private var mScreenHeight: Int = 0

    //使用exoPlayer自带的播放器样式
    private var useController = false

    val touchResponseDistance = 10

    //声明IBinder接口的一个接口变量mBinder
    val mBinder: IBinder = LocalBinder()
    private var mNM: NotificationManager? = null
    private val handler = Handler()
    val runnable = Runnable {
        ivFullScreen.visibility = View.GONE
        ivPlay.visibility = View.GONE
        isButtonShown = false
    }


    //LocalBinder是继承Binder的一个内部类
    inner class LocalBinder : Binder() {
        val service: FloatWindowService
            get() = this@FloatWindowService

        fun initFloatWindow(context: Context, isUserController: Boolean = false) {
            mContext = context
            useController = isUserController
            initWindowParams()
            initView(context)
            initGestureListener(context)
        }

        fun initMediaSource(url: String, context: Context) {
            currentUrl = url
            val uri = Uri.parse(url)
            val mediaSource = buildMediaSource(uri, context)
            player?.setMediaSource(mediaSource!!)
            player?.prepare()
//            player?.play()
            mContainer.requestLayout()
//            Log.d(
//                javaClass.name,
//                "player width height======${spvPlayerView.width},,${spvPlayerView.height}"
//            )
        }

        fun startPlay() {//开始播放的时候展示出画面
            showFloatView()
            hasClickClose = false
//            Log.d(javaClass.name, "player is playing======${player!!.isPlaying},,${hasRelease}")
            if (!player!!.isPlaying) {
                if (hasRelease) {
                    player?.prepare()
                    player?.playWhenReady = true
                    player?.play()
//                    Log.d(javaClass.name, "player is playing======走了吗")
                } else {
                    player?.play()
                }
            }
            ivPlay.setImageResource(R.drawable.ic_pause)
        }

        fun stopPlay() {
            if (player!!.isPlaying) {
                player?.stop()
                hasRelease = true
//                player?.clearMediaItems()
            }
        }

        fun pausePlay() {
            player?.let {
                if (it.isPlaying) {
                    it.pause()
                    ivPlay.setImageResource(R.drawable.ic_play)
                }
            }
        }

        fun seekTo(position: Long) {
            player?.seekTo(position)
        }

        fun removeFloatWindow(): Long {
            removeWindowView()
            return if (player != null) {
                player.contentPosition
            } else {
                0
            }
        }

        fun hasClickClose(): Boolean = hasClickClose

        fun getFloatService(): FloatWindowService = FloatWindowService()

    }

    override fun onCreate() {
        mNM = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        Log.e(javaClass.name, "onCreate")
        showNotification()
    }

    lateinit var player: ExoPlayer
    lateinit var ivClose: ImageView
    lateinit var ivPlay: ImageView
    lateinit var ivFullScreen: ImageView
    lateinit var spvPlayerView: StyledPlayerView
    lateinit var clContainer: ConstraintLayout
    var hasClickClose = false
    private fun initView(context: Context) {
        mContainer = FrameLayout(context)
        mContainer.setBackgroundColor(Color.parseColor("#000000"))
        var flp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        mContainer.layoutParams = flp

        player = ExoPlayer.Builder(context).build()

        val view = LayoutInflater.from(context).inflate(R.layout.layout_float_window, null)
        clContainer = view.findViewById(R.id.cl_parent)
        ivClose = view.findViewById(R.id.iv_close)
        ivPlay = view.findViewById(R.id.iv_play)
        ivFullScreen = view.findViewById(R.id.iv_full_screen)
        spvPlayerView = view.findViewById(R.id.player_view)
        val layoutParams = spvPlayerView.layoutParams
        mWidth = layoutParams.width
        mHeight = layoutParams.height
        spvPlayerView.useController = useController
        spvPlayerView.player = player
        if (useController) {
            ivPlay.visibility = View.GONE
            ivFullScreen.visibility = View.GONE
            isButtonShown = false
        }

        ivPlay.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                ivPlay.setImageResource(R.drawable.ic_play)
                listener?.onPlayClick(false)
            } else {
                player.play()
                ivPlay.setImageResource(R.drawable.ic_pause)
                listener?.onPlayClick(true)
            }
        }
        ivClose.setOnClickListener {
            hasClickClose = true
            listener?.onCloseClick()
            removeWindowView()
        }
        ivFullScreen.setOnClickListener {
            listener?.onFullScreenClick()
//            openApp(context)
        }
    }

    lateinit var dataSourceFactory: DataSource.Factory
    private fun buildMediaSource(uri: Uri, context: Context): MediaSource? {

        dataSourceFactory = if (isHTTP(uri)) {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("ExoPlayer")
                .setAllowCrossProtocolRedirects(true)
            httpDataSourceFactory
        } else {
            DefaultDataSource.Factory(context)
        }
        return ProgressiveMediaSource.Factory(
            dataSourceFactory
        ).createMediaSource(MediaItem.fromUri(uri))
    }

    private fun isHTTP(uri: Uri?): Boolean {
        if (uri == null || uri.scheme == null) {
            return false
        }
        val scheme = uri.scheme
        return scheme == "http" || scheme == "https"
    }

    override fun onDestroy() {
        Log.e(javaClass.name, "onDestroy")
        mNM!!.cancel(101)
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.e(javaClass.name, "onBind")
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Log.e(javaClass.name, "onUnbind")
        player?.stop()
        removeWindowView()
        return super.onUnbind(intent)
    }

    private fun showNotification() {
//        CharSequence text = ;
//        val contentIntent = PendingIntent.getActivity(
//            this, 0,
//            Intent(this, ServiceActivity::class.java), 0
//        )
//        val notification = Notification.Builder(this)
//            .setSmallIcon(R.mipmap.ic_launcher)
//            .setTicker("Service Start")
//            .setWhen(System.currentTimeMillis())
//            .setContentTitle("current service")
//            .setContentText("Service Start")
//            .setContentIntent(contentIntent)
//            .build()
//        mNM!!.notify(101, notification)
        Log.e(javaClass.name, "通知栏已出")
    }

    private fun initWindowParams() {
        mWindowManager = application.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mScreenWidth = mWindowManager.defaultDisplay.width
        mScreenHeight = mWindowManager.defaultDisplay.height
        wmParams = WindowManager.LayoutParams()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            setWMTypeCompat()
        } else if (RomUtil.isMiui) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setWMTypeCompat()
            } else {
                wmParams.type = WindowManager.LayoutParams.TYPE_PHONE
            }
        } else {
            wmParams.type = WindowManager.LayoutParams.TYPE_TOAST
        }
        wmParams.format = PixelFormat.RGBA_8888
        wmParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        wmParams.gravity = Gravity.START or Gravity.TOP
        wmParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        wmParams.height = WindowManager.LayoutParams.WRAP_CONTENT
    }


    private fun setWMTypeCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            wmParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            wmParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            wmParams.type = WindowManager.LayoutParams.TYPE_PHONE
        }
//        wmParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    /**
     * 高宽比
     * 0.0~1.0
     */
    fun setVideoAspectRatio(float: Float) {
        val layoutParams = spvPlayerView.layoutParams
        var vWidth = layoutParams.width
        var vHeight = layoutParams.height
        vHeight = (vWidth * float).toInt()
        layoutParams.height = vHeight
        spvPlayerView.layoutParams = layoutParams
        mAspectRatio = if (mAspectRatio > 1.0) {
            1.0f
        } else {
            float
        }
    }

    /**
     * 设置视频悬浮窗的宽高
     */
    fun setVideoWidthAndHeight(width: Int, height: Int) {
        var sWidth = mWindowManager.defaultDisplay.width
        var sHeight = mWindowManager.defaultDisplay.height
        val layoutParams = spvPlayerView.layoutParams
        if (width <= sWidth) {
            sWidth = if (width < 500) {
                500
            } else {

                width
            }
            sHeight = if (height < 280) {
                280
            } else {
                height
            }
        }
        if (useAspectRatio) {//用高宽比
            layoutParams.height = (sWidth * mAspectRatio).toInt()
            spvPlayerView.layoutParams = layoutParams
            mWidth = sWidth
            mHeight = (sWidth * mAspectRatio).toInt()
        } else {
            layoutParams.width = sWidth
            layoutParams.height = sHeight
            spvPlayerView.layoutParams = layoutParams
            mWidth = sWidth
            mHeight = sHeight
        }
    }

    fun setFloatWindowGravity(gravity: FloatWindowGravity) {
        mFloatGravity = gravity
    }

    fun setGravity(gravity: FloatWindowGravity) {
        val layoutParams = spvPlayerView.layoutParams
        val lWidth = layoutParams.width
        val lHeight = layoutParams.height
        val sWidth = mWindowManager.defaultDisplay.width
        val sHeight = mWindowManager.defaultDisplay.height
        when (gravity) {
            FloatWindowGravity.LEFT -> {
                if (lWidth < sWidth - dip2px(mContext, 32f)) {
                    wmParams.x = dip2px(mContext, 16f)
                    wmParams.y = (sHeight - lHeight) / 2
                } else {//居中
                    wmParams.x = (sWidth - lWidth) / 2
                    wmParams.y = (sHeight - lHeight) / 2
                }
                mWindowManager.updateViewLayout(mContainer, wmParams)
            }
            FloatWindowGravity.TOP -> {
                wmParams.x = (sWidth - lWidth) / 2
                wmParams.y = dip2px(mContext, 60f)
                mWindowManager.updateViewLayout(mContainer, wmParams)
            }
            FloatWindowGravity.RIGHT -> {
                if (lWidth < sWidth - dip2px(mContext, 32f)) {
                    wmParams.x = sWidth - lWidth - dip2px(mContext, 16f)
                    wmParams.y = (sHeight - lHeight) / 2
                } else {//居中
                    wmParams.x = (sWidth - lWidth) / 2
                    wmParams.y = (sHeight - lHeight) / 2
                }
                mWindowManager.updateViewLayout(mContainer, wmParams)
            }
            FloatWindowGravity.BOTTOM -> {
                wmParams.x = (sWidth - lWidth) / 2
                wmParams.y = sHeight - lHeight - dip2px(mContext, 16f)
                mWindowManager.updateViewLayout(mContainer, wmParams)
            }
            FloatWindowGravity.CENTER -> {
                wmParams.x = (sWidth - lWidth) / 2
                wmParams.y = (sHeight - lHeight) / 2
                mWindowManager.updateViewLayout(mContainer, wmParams)
            }
        }
    }

    fun showFloatView() {
        if (!hasAdded) {
            try {
                if (mContainer.childCount > 0) {
                    mContainer.removeAllViews()
                }
                Log.d(
                    javaClass.name,
                    "player width height=23=====${spvPlayerView.width},,${spvPlayerView.height}"
                )
                mContainer.addView(clContainer)
                mWindowManager.addView(mContainer, wmParams)
                val width = mWindowManager.defaultDisplay.width
                val height = mWindowManager.defaultDisplay.height
                setGravity(mFloatGravity)//设置窗口位置
//                wmParams.x = width - 600
//                wmParams.y = 200
//                mWindowManager.updateViewLayout(mContainer, wmParams)
                hasAdded = true
                if (!useController) {
                    handler.postDelayed(runnable, 3000)
                }
            } catch (e: Exception) {
                hasAdded = false
            }
            Log.e(
                javaClass.name,
                "initFloatWindow12-------${spvPlayerView.width},,${spvPlayerView.height}"
            )
        }
    }

    private fun addViewToWindow(view: View) {
        if (!hasAdded) {
            try {
                if (mContainer.childCount > 0) {
                    mContainer.removeAllViews()
                }
                mContainer.addView(view)
                mContainer.addView(mCloseImage)
                mWindowManager.addView(mContainer, wmParams)
                val width = mWindowManager.defaultDisplay.width
                val height = mWindowManager.defaultDisplay.height
                wmParams.x = width
                wmParams.y = height / 2
                hasAdded = true
                mWindowManager.updateViewLayout(mContainer, wmParams)
            } catch (e: Exception) {
                hasAdded = false
            }
        }

    }

    /**
     * 移除控件
     */
    private fun removeWindowView() {
        if (hasAdded) {
            if (player!!.isPlaying) {
                player?.stop()
                hasRelease = true
            }
//            player?.stop()
            //移除悬浮窗口
            mWindowManager.removeView(mContainer)
            hasAdded = false
        }
    }

    var lastX: Int = 0
    var lastY: Int = 0

    @SuppressLint("ClickableViewAccessibility")
    private fun initGestureListener(context: Context) {
        var gestureDetector =
            GestureDetector(applicationContext, object : GestureDetector.OnGestureListener {
                override fun onDown(e: MotionEvent): Boolean {
                    lastX = e.rawX.toInt()
                    lastY = e.rawY.toInt()
                    return false
                }

                override fun onShowPress(e: MotionEvent?) {
                }

                override fun onSingleTapUp(e: MotionEvent?): Boolean = false

                override fun onScroll(
                    e1: MotionEvent,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float
                ): Boolean {
                    var distanceX = e2.rawX - lastX
                    var distanceY = e2.rawY - lastY
                    lastX = e2.rawX.toInt()
                    lastY = e2.rawY.toInt()
                    wmParams.x = wmParams.x + distanceX.toInt()
                    wmParams.y = wmParams.y + distanceY.toInt()
                    mWindowManager.updateViewLayout(mContainer, wmParams)
                    return true
                }

                override fun onLongPress(e: MotionEvent?) {
                }

                override fun onFling(
                    e1: MotionEvent,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float
                ): Boolean = false

            })
        gestureDetector.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                if (!isButtonShown) {
                    handler.removeCallbacks(runnable)
                    ivPlay.visibility = View.VISIBLE
                    ivFullScreen.visibility = View.VISIBLE
                    isButtonShown = true
                    handler.postDelayed(runnable, 2000)
                }
//                openApp(context)
//                else {
//                    player?.play()
//                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent?): Boolean {
                val layoutParams = spvPlayerView.layoutParams
                var width = layoutParams.width
                var height = layoutParams.height
                val i = mScreenWidth - dip2px(mContext, 16f)
                var tempWidth = i * 2 / 3
                    var tempX=wmParams.x
                var tempY=wmParams.y
                var tempHeight=0
                if (width < tempWidth) {//放大
                    layoutParams.width = layoutParams.width*(i/mWidth)
                    layoutParams.height = layoutParams.height*(i/mWidth)
                    spvPlayerView.layoutParams = layoutParams
                    setGravity(mFloatGravity)
                } else {//缩小
                    tempHeight=layoutParams.height*(i/mWidth)
                    layoutParams.width = mWidth
                    layoutParams.height = mHeight
                    spvPlayerView.layoutParams = layoutParams
                    wmParams.x=tempX
                    wmParams.y=tempY
                Log.i(javaClass.name, "!!!!!!!!===width=$width,i=$i,tempWidth=$tempWidth,,tempY=${tempY},tempHeight=$tempHeight")
                    mWindowManager.updateViewLayout(mContainer, wmParams)
                }
//                if (isBig) {
//                    layoutParams.width = layoutParams.width * 2 / 3
//                    layoutParams.height = layoutParams.height * 2 / 3
//                    spvPlayerView.layoutParams = layoutParams
//                    isBig = false
//                } else {
//                    val layoutParams = spvPlayerView.layoutParams
//                    layoutParams.width = layoutParams.width * 3 / 2
//                    layoutParams.height = layoutParams.height * 3 / 2
//                    spvPlayerView.layoutParams = layoutParams
//
//                    isBig = true
//                }
                return true
            }

            override fun onDoubleTapEvent(e: MotionEvent?): Boolean = false

        })
        clContainer?.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun openApp(context: Context) {
        var packageName = context.packageName
        val packageManager = context.packageManager
        val launchIntentForPackage = packageManager.getLaunchIntentForPackage(packageName)
        startActivity(launchIntentForPackage)
        if (player!!.isPlaying) {
            player?.pause()
        }
        removeWindowView()
    }
    //getTouchSlop


    // 根据手机的分辨率从 dp 的单位 转成为 px(像素)
    fun dip2px(context: Context, dpValue: Float): Int {
        // 获取当前手机的像素密度（1个dp对应几个px）
        val scale = context.resources.displayMetrics.density
        return (dpValue * scale + 0.5f).toInt() // 四舍五入取整
    }

    // 根据手机的分辨率从 px(像素) 的单位 转成为 dp
    fun px2dip(context: Context, pxValue: Float): Int {
        // 获取当前手机的像素密度（1个dp对应几个px）
        val scale = context.resources.displayMetrics.density
        return (pxValue / scale + 0.5f).toInt() // 四舍五入取整
    }

    private var listener: OnClickListener? = null
    fun setOnClickListener(l: OnClickListener) {
        listener = l
    }

    interface OnClickListener {
        fun onFullScreenClick()
        fun onCloseClick()
        fun onPlayClick(b: Boolean)
    }

}