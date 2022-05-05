package com.tby.sample.libpag.camera

import android.opengl.GLES11Ext
import android.opengl.GLES20

/**
 * This class defines the OES Texture that can be attached to SurfaceTexture
 * which is updated to the most recent camera frame image when requested.
 */
class OESTexture {
    var textureId = 0
        private set
    var rotation = 0
    var mirror = false

    fun init() {
        val mTextureHandles = IntArray(1)
        GLES20.glGenTextures(1, mTextureHandles, 0)
        textureId = mTextureHandles[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureHandles[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    }
}