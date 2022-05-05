package com.tby.sample.libpag.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import com.tby.sample.libpag.openGL.PngGLRender
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class PagCameraRenderer(private val glSurfaceView: GLSurfaceView) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private val pngGlRender = PngGLRender(glSurfaceView.context)
    private val mContext: Context = glSurfaceView.context
    private var mCamera: Camera? = null
    private val mCameraIndex = Camera.CameraInfo.CAMERA_FACING_FRONT
    private var mCameraRotation = 0
    @Volatile
    private var mSurfaceTexture: SurfaceTexture? = null
    @Volatile
    private var mDstSurfaceTexture: SurfaceTexture? = null
    private val mSrcTexture = OESTexture()
    private val mOffscreenShader = Shader()
    private var mViewWidth = 0
    private var mViewHeight = 0
    @Volatile
    private var mUpdateTexture = false
    private val mFullQuadVertices: ByteBuffer = ByteBuffer.allocateDirect(4 * 2)
    private val mOrientationM = FloatArray(16)
    private val mRatio = FloatArray(2)
    private var mPreviewing = false
    private var mFboToView: TextureRenderer? = null
    private var mCameraToFbo: TextureRenderer? = null
    private val mGLCubeBuffer: FloatBuffer
    private val mResizeBuffer: ByteBuffer
    private var mFbo = 0
    private var mDstTexture = 0
    private val FULL_QUAD_COORDS = byteArrayOf(-1, 1, -1, -1, 1, 1, 1, -1)

    init {
        // Create full scene quad buffer
        mFullQuadVertices.put(FULL_QUAD_COORDS).position(0)
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(this)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glSurfaceView.debugFlags = GLSurfaceView.DEBUG_LOG_GL_CALLS
        mGLCubeBuffer = ByteBuffer.allocateDirect(CUBE.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        mGLCubeBuffer.put(CUBE).position(0)
        mResizeBuffer = ByteBuffer.allocate(360 * 640 * 4)
    }

    fun onPause() {
        glSurfaceView.onPause()
    }

    fun onResume() {
        glSurfaceView.onResume()
    }

    @Synchronized
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        mUpdateTexture = true
        Log.d(LOG_TAG, "onFrameAvailable")
        glSurfaceView.requestRender()
    }

    @Synchronized
    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        initCameraTexture()
        if (mFbo == 0) {
            createFbo(CAMERA_WIDTH, CAMERA_HEIGHT)
            if (mDstSurfaceTexture != null) {
                mDstSurfaceTexture!!.release()
            }
            mDstSurfaceTexture = SurfaceTexture(mDstTexture)
        }
        pngGlRender.onSurfaceCreated(mDstTexture, CAMERA_WIDTH, CAMERA_HEIGHT)
    }

    private fun initCameraTexture() {
        try {
            mOffscreenShader.setProgram(vShader, fShader, mContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (mCamera == null) {
            mCamera = Camera.open(mCameraIndex)
        }
        // Generate camera texture
        mSrcTexture.init()
        // Set up SurfaceTexture
        val oldSurfaceTexture = mSurfaceTexture
        mSurfaceTexture = SurfaceTexture(mSrcTexture.textureId)
        mSurfaceTexture!!.setOnFrameAvailableListener(this)
        oldSurfaceTexture?.release()
    }

    private var mCameraPreviewWidth = 1280
    private var mCameraPreviewHeight = 720

    @SuppressLint("NewApi")
    @Synchronized
    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        Log.d(LOG_TAG, "onSurfaceChanged $gl $width $height")
        mViewWidth = width
        mViewHeight = height
        if (mPreviewing) {
            mCamera!!.stopPreview()
        }
        pngGlRender.onSurfaceChanged(gl, width, height)
        // set camera paras
        val camera_width = 0
        val camera_height = 0
        try {
            mCamera!!.setPreviewTexture(mSurfaceTexture)
        } catch (ioe: IOException) {
            Log.w(LOG_TAG, "setPreviewTexture " + Log.getStackTraceString(ioe))
        }
        var param = mCamera!!.parameters
        val psize = param.supportedPreviewSizes
        if (psize.size > 0) {
            var supports_1280_720 = false
            for (i in psize.indices) {
                if (psize[i].width == 1280 && psize[i].height == 720) {
                    supports_1280_720 = true
                }
            }
            if (supports_1280_720) {
                mCameraPreviewWidth = 1280
                mCameraPreviewHeight = 720
            } else {
                mCameraPreviewWidth = param.supportedPreviewSizes[0].width
                mCameraPreviewHeight = param.supportedPreviewSizes[0].height
            }
            val supportedFPSRange = param.supportedPreviewFpsRange
            // 15 FPS default
            val fps = adaptPreviewFps(15.0f, supportedFPSRange)
            param.setPreviewFpsRange(fps[0], fps[1])
            Log.d(LOG_TAG, "setPreviewSize $mCameraPreviewWidth $mCameraPreviewHeight")
            param.setPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight)
        }

        // get the camera orientation and display dimension
        if (mContext.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            Matrix.setRotateM(mOrientationM, 0, 90.0f, 0f, 0f, 1f)
            mRatio[1] = camera_width * 1.0f / height
            mRatio[0] = camera_height * 1.0f / width
        } else {
            Matrix.setRotateM(mOrientationM, 0, 0.0f, 0f, 0f, 1f)
            mRatio[1] = camera_height * 1.0f / height
            mRatio[0] = camera_width * 1.0f / width
        }
        mCamera!!.setErrorCallback { error, _ ->
            if (error == Camera.CAMERA_ERROR_EVICTED) {
                Log.e("Error", "Error")
            }
        }

        // start camera
        mCamera!!.parameters = param
        val info = Camera.CameraInfo()
        Camera.getCameraInfo(mCameraIndex, info)
        mCameraRotation = info.orientation
        mCamera!!.startPreview()
        param = mCamera!!.parameters
        val dumpedParam = param.flatten()
        mPreviewing = true
        Log.d(LOG_TAG, "onSurfaceChanged end $gl $dumpedParam $mCameraRotation $mPreviewing")
    }
    private fun adaptPreviewFps(aExpectedFps: Float, fpsRanges: List<IntArray>): IntArray {
        var expectedFps = aExpectedFps
        expectedFps *= 1000f
        var closestRange = fpsRanges[0]
        var measure = (Math.pow((closestRange[0] - expectedFps).toDouble(), 2.0)
                + Math.pow((closestRange[1] - expectedFps).toDouble(), 2.0)).toInt()
        for (range in fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                closestRange[0] = expectedFps.toInt()
                closestRange[1] = expectedFps.toInt()
                break
            } else {
                val curMeasure = (Math.pow((range[0] - expectedFps).toDouble(), 2.0)
                        + Math.pow((range[1] - expectedFps).toDouble(), 2.0)).toInt()
                if (curMeasure < measure) {
                    closestRange = range
                    measure = curMeasure
                }
            }
        }
        return closestRange
    }

    private val displayRotation: Int
        get() {
            when ((mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation) {
                Surface.ROTATION_0 -> return 0
                Surface.ROTATION_90 -> return 90
                Surface.ROTATION_180 -> return 180
                Surface.ROTATION_270 -> return 270
            }
            return 0
        }

    @Synchronized
    override fun onDrawFrame(gl: GL10) {
        if (mCameraToFbo == null) {
            mCameraToFbo = TextureRenderer(true)
        }
        if (mFboToView == null) {
            mFboToView = TextureRenderer(false)
        }
        // Calculate rotated degrees (camera to view)
        val degrees = displayRotation

        // render the texture to FBO if new frame is available
        if (!mUpdateTexture) {
            return
        }
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        // Latch surface texture
        mSurfaceTexture!!.updateTexImage()

        GLES20.glFinish()
        GLES20.glViewport(0, 0, CAMERA_WIDTH, CAMERA_HEIGHT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFbo)
        mCameraToFbo!!.rotate(mCameraRotation)
        mCameraToFbo!!.flip(true, true)
        mCameraToFbo!!.draw(mSrcTexture.textureId)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, mViewWidth, mViewHeight)
        pngGlRender.onDrawFrame(gl)
        mUpdateTexture = false
    }

    private fun createFbo(width: Int, height: Int): Int {
        val texture = IntArray(1)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        GLES20.glGenTextures(1, texture, 0)
        mFbo = fbo[0]
        mDstTexture = texture[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mDstTexture)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)

        // Bind the framebuffer
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFbo)
        // Specify texture as color attachment
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mDstTexture, 0)

        // Check for framebuffer complete
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(LOG_TAG, "Failed to create framebuffer!!!")
        }
        return 0
    }

    private fun onGlDestroy() {
        destroyCameraTexture()
    }

    private fun destroyCameraTexture() {
        mUpdateTexture = false
        if (mDstSurfaceTexture != null) {
            mDstSurfaceTexture!!.release()
        }
        if (mSurfaceTexture != null) {
            mSurfaceTexture!!.release()
        }
        mPreviewing = false
        if (mCamera != null) {
            mCamera!!.stopPreview()
            mCamera!!.setPreviewCallback(null)
            mCamera!!.release()
        }
        mCamera = null
    }

    companion object {
        private const val LOG_TAG = "CustomizedRenderer"
        private const val vShader = "uniform mat4 uTransformM;\n" +
                "uniform mat4 uOrientationM;\n" +
                "uniform vec2 ratios;\n" +
                "attribute vec2 aPosition;\n" +
                "\n" +
                "varying vec2 vTextureCoord;\n" +
                "\n" +
                "void main() {\n" +
                "\tgl_Position = vec4(aPosition, 0.0, 1.0);\n" +
                "\tvTextureCoord = (uTransformM * ((uOrientationM * gl_Position + 1.0)*0.5)).xy;\n" +
                "\tgl_Position.xy *= ratios;\n" +
                "}\n"
        private const val fShader = "#extension GL_OES_EGL_image_external : require\n" +
                "\n" +
                "precision mediump float;\n" +
                "\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "varying vec2 vTextureCoord;\n" +
                "\n" +
                "void main() {\n" +
                "\tgl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n"

        private const val CAMERA_WIDTH = 720
        private const val CAMERA_HEIGHT = 1280
        val CUBE = floatArrayOf(
                -1.0f, -1.0f,
                1.0f, -1.0f,
                -1.0f, 1.0f,
                1.0f, 1.0f)
    }
}