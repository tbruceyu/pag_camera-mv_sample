package com.tby.sample.libpag;
import android.app.Activity;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.ViewGroup;

import com.tby.sample.libpag.camera.PagCameraRenderer;

public class PagCameraActivity extends Activity {

    private GLSurfaceView glSurfaceView;
    private PagCameraRenderer cameraRenderer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_texture_demo);
        glSurfaceView = (GLSurfaceView) findViewById(R.id.surfaceView);
        glSurfaceView.setEGLContextClientVersion(2);
//        glSurfaceView.setRenderer(glRender);
        cameraRenderer = new PagCameraRenderer(glSurfaceView);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        glSurfaceView.post(() -> {
            ViewGroup.LayoutParams layoutParams = glSurfaceView.getLayoutParams();
            layoutParams.height =  glSurfaceView.getWidth() * 16 / 9;
            glSurfaceView.requestRender();
        });
    }
    @Override
    protected void onResume() {
        super.onResume();
        cameraRenderer.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraRenderer.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        glSurfaceView.onPause();
    }

}