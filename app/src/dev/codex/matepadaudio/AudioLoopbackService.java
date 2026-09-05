package dev.codex.matepadaudio;

import android.R;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.os.PowerManager;

public final class AudioLoopbackService extends Service implements AudioLoopback.Listener {
    static final String ACTION_START = "dev.codex.matepadaudio.START";
    static final String ACTION_STOP = "dev.codex.matepadaudio.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "audio_loopback";
    private static final int NOTIFICATION_ID = 4201;

    private static volatile AudioLoopback.Listener listener;
    private static volatile boolean active;
    private static volatile String lastStatus;

    private AudioLoopback loopback;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    static void setListener(AudioLoopback.Listener newListener) {
        listener = newListener;
    }

    static void clearListener(AudioLoopback.Listener oldListener) {
        if (listener == oldListener) {
            listener = null;
        }
    }

    static boolean isActive() {
        return active;
    }

    static String getLastStatus() {
        return lastStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService("notification");
        if (notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "扬声器回送", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持显示器连接期间的平板扬声器回送持续运行");
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopEverything("回送服务被系统重启，请重新点击开始。", false);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEverything("已停止。显示器连接期间，系统音频会恢复到原来的 HDMI 路由。",
                    true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        active = true;
        publishStatus("正在启动后台回送服务……", true);
        startForeground(NOTIFICATION_ID, createNotification("正在启动……"));
        acquireWakeLock();

        try {
            if (loopback != null) {
                loopback.stop();
                loopback = null;
            }
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) {
                throw new IllegalStateException("系统没有返回有效的捕获授权");
            }
            MediaProjectionManager manager = (MediaProjectionManager)
                    getSystemService("media_projection");
            MediaProjection projection = manager == null ? null
                    : manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                throw new IllegalStateException("无法建立系统音频捕获会话");
            }
            loopback = new AudioLoopback(getApplicationContext(), projection, this);
            loopback.start();
        } catch (Throwable error) {
            String detail = error.getMessage();
            stopEverything("启动失败：" + (detail == null
                    ? error.getClass().getSimpleName() : detail), true);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onLoopbackStatus(String message, boolean running) {
        publishStatus(message, running);
        if (!running) {
            stopEverything(message, true);
        }
    }

    @Override
    public void onDestroy() {
        stopLoopbackAndReleaseWakeLock();
        active = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager manager = (PowerManager) getSystemService("power");
        if (manager != null) {
            wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "MatePadAudio:Loopback");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void stopEverything(String message, boolean removeNotification) {
        stopLoopbackAndReleaseWakeLock();
        active = false;
        publishStatus(message, false);
        if (removeNotification) {
            stopForeground(true);
        }
        stopSelf();
    }

    private void stopLoopbackAndReleaseWakeLock() {
        AudioLoopback current = loopback;
        loopback = null;
        if (current != null) {
            current.stop();
        }
        PowerManager.WakeLock currentWakeLock = wakeLock;
        wakeLock = null;
        if (currentWakeLock != null && currentWakeLock.isHeld()) {
            currentWakeLock.release();
        }
    }

    private void publishStatus(String message, boolean running) {
        lastStatus = message;
        active = running;
        AudioLoopback.Listener currentListener = listener;
        if (currentListener != null) {
            currentListener.onLoopbackStatus(message, running);
        }
        if (running && notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, createNotification(message));
        }
    }

    private Notification createNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_media_play)
                .setContentTitle("MatePad 扬声器回送运行中")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }
}
