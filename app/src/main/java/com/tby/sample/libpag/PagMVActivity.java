package com.tby.sample.libpag;

import android.app.ProgressDialog;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.libpag.PAGFile;
import org.libpag.PAGImage;
import org.libpag.PAGPlayer;
import org.libpag.PAGSurface;
import org.libpag.PAGView;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

public class PagMVActivity extends AppCompatActivity {

    private static final String TAG = "APIsDetailActivity";
    private PAGFile exportPagFile;
    private PAGFile displayPagFile;
    private Button exportButton;

    // video export
    private static final String MIME_TYPE = "video/avc";    // H.264 Advanced Video Coding
    private static final int FRAME_RATE = 30;
    private static final int IFRAME_INTERVAL = 10;          // 10 seconds between I-frames
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    private MediaCodec.BufferInfo mBufferInfo;
    private static final boolean VERBOSE = true;
    private int mBitRate = 8000000;
    private PAGPlayer pagPlayer;
    private ProgressDialog progressDialog;

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.layout_detail);
        exportButton = (Button)findViewById(R.id.export);
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("正在导出...");
        initPAGView();
    }

    private void initPAGView() {
        RelativeLayout backgroundView = findViewById(R.id.background_view);
        final PAGView pagView = new PAGView(this);
        pagView.setLayoutParams(new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        backgroundView.addView(pagView);
        Intent intent = getIntent();
        WindowManager manager = this.getWindowManager();
        DisplayMetrics outMetrics = new DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(outMetrics);
        displayPagFile = PAGFile.Load(getAssets(), "nodegroup_clip1.pag");
        replaceImages(displayPagFile);
        pagView.setComposition(displayPagFile);
        pagView.setRepeatCount(0);
        pagView.play();
    }

    private void replaceImages(PAGFile pagFile) {
        pagFile.replaceImage(0, PAGImage.FromAssets(getAssets(), "1.jpg"));
        pagFile.replaceImage(1, PAGImage.FromAssets(getAssets(), "2.jpg"));
        pagFile.replaceImage(2, PAGImage.FromAssets(getAssets(), "1.jpg"));
        pagFile.replaceImage(3, PAGImage.FromAssets(getAssets(), "2.jpg"));
        pagFile.replaceImage(4, PAGImage.FromAssets(getAssets(), "1.jpg"));
    }

    public void export(View view) {
        progressDialog.show();
        exportPagFile = displayPagFile.copyOriginal();
        replaceImages(exportPagFile);
        new Thread(this::pagExportToMP4).start();
    }

    // video export
    private void pagExportToMP4() {
        try {
            prepareEncoder();
            int totalFrames = (int)(exportPagFile.duration() * exportPagFile.frameRate() / 1000000);
            for (int i = 0; i < totalFrames; i++) {
                // Feed any pending encoder output into the muxer.
                drainEncoder(false);
                generateSurfaceFrame(i);
                if (VERBOSE) Log.d(TAG, "sending frame " + i + " to encoder");
            }
            drainEncoder(true);
        } finally {
            releaseEncoder();
        }
        runOnUiThread(() -> {
            Toast.makeText(PagMVActivity.this, "导出成功！", Toast.LENGTH_LONG).show();
            progressDialog.dismiss();
        });
        Log.d(TAG, "encode finished!!! \n");
    }

    private void prepareEncoder() {
        mBufferInfo = new MediaCodec.BufferInfo();
        int width = exportPagFile.width();
        int height = exportPagFile.height();
        if (width % 2 == 1) {
            width --;
        }
        if (height % 2 == 1) {
            height --;
        }

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        try {
            mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        if (pagPlayer == null) {
            PAGSurface pagSurface = PAGSurface.FromSurface(mEncoder.createInputSurface());
            pagPlayer = new PAGPlayer();
            pagPlayer.setSurface(pagSurface);
            pagPlayer.setComposition(exportPagFile);
            pagPlayer.setProgress(0);
        }

        mEncoder.start();
        String outputPath = new File(getApplicationContext().getExternalFilesDir(null),
                "test." + width + "x" + height + ".mp4").toString();
        Log.d(TAG, "video output file is " + outputPath);
        try {
            mMuxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (IOException ioe) {
            throw new RuntimeException("MediaMuxer creation failed", ioe);
        }

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     */
    private void releaseEncoder() {
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer = null;
        }
    }

    private void drainEncoder(boolean endOfStream) {
        final int TIMEOUT_USEC = (int)(10000 * 60 / FRAME_RATE);
        if (VERBOSE) Log.d(TAG, "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d(TAG, "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }
                    // adjust the ByteBuffer values to match BufferInfo (not needed?)
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    private void generateSurfaceFrame(int frameIndex) {
        int totalFrames = (int)(exportPagFile.duration() * exportPagFile.frameRate() / 1000000);
        float progress = frameIndex % totalFrames * 1.0f / totalFrames;
        pagPlayer.setProgress(progress);
        pagPlayer.flush();
    }
}