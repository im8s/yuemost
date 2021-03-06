package com.sk.weichat.call;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.sk.weichat.util.FileUtil;
import com.sk.weichat.util.PreferenceUtils;

import java.io.IOException;


public class RecordService extends Service {
    private MediaProjection mediaProjection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;

    private boolean running;
    private int width = 720;
    private int height = 1080;
    private int dpi;

    @Override
    public IBinder onBind(Intent intent) {
        return new RecordBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = false;
        mediaRecorder = new MediaRecorder();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    public void setMediaProject(MediaProjection project) {
        mediaProjection = project;
    }

    public boolean isRunning() {
        return running;
    }

    public void setConfig(int width, int height, int dpi) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
    }

    public boolean startRecord() {
        if (mediaProjection == null || running) {
            return false;
        }

        initRecorder();
        createVirtualDisplay();
        mediaRecorder.start();
        running = true;
        return true;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public boolean stopRecord() {
        if (!running) {
            return false;
        }
        running = false;
        mediaRecorder.stop();
        mediaRecorder.reset();
        virtualDisplay.release();
        mediaProjection.stop();
        return true;
    }

    // ??????????????????
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void createVirtualDisplay() {
        virtualDisplay = mediaProjection.createVirtualDisplay("MainScreen", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mediaRecorder.getSurface(), null, null);
    }

    // ??????????????????

    /**
     * 1.??????????????????
     * 2.?????????????????????
     */
    private void initRecorder() {
        /*
        ??????????????????????????????????????????????????????(?????????????????????????????????????????????????????????)?????????GG
         */
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        // mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        // mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);

        mediaRecorder.setVideoEncodingBitRate(1024 * 1024);// ??????????????????????????????5 * 1024 *1024???????????????????????????????????????????????????
        mediaRecorder.setVideoSize(width, height);// ????????????????????????
        mediaRecorder.setVideoFrameRate(20);      // ?????????????????????

        String outputFilePath = FileUtil.getSaveDirectory("IMScreenRecord") + System.currentTimeMillis() + ".mp4";
        PreferenceUtils.putString(getApplicationContext(), "IMScreenRecord", outputFilePath);
        mediaRecorder.setOutputFile(outputFilePath);
        try {
            mediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public class RecordBinder extends Binder {
        public RecordService getRecordService() {
            return RecordService.this;
        }
    }
}