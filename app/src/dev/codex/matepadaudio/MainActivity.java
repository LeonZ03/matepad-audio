package dev.codex.matepadaudio;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity implements AudioLoopback.Listener {
    private static final int REQUEST_RECORD_AUDIO = 100;
    private static final int REQUEST_MEDIA_PROJECTION = 101;

    private MediaProjectionManager projectionManager;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private boolean starting;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        projectionManager = (MediaProjectionManager) getSystemService("media_projection");
        buildUi();
        AudioLoopbackService.setListener(this);
        refreshButtons();
    }

    @Override
    protected void onResume() {
        super.onResume();
        AudioLoopbackService.setListener(this);
        String lastStatus = AudioLoopbackService.getLastStatus();
        if (lastStatus != null) {
            statusView.setText(lastStatus);
        }
        refreshButtons();
    }

    @Override
    protected void onPause() {
        AudioLoopbackService.clearListener(this);
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 72, 48, 48);

        TextView title = new TextView(this);
        title.setText("MatePad 扬声器回送");
        title.setTextSize(24.0f);
        title.setTextColor(Color.BLACK);
        title.setPadding(0, 0, 0, 36);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(17.0f);
        statusView.setTextColor(Color.DKGRAY);
        statusView.setPadding(0, 0, 0, 42);
        root.addView(statusView);

        startButton = new Button(this);
        startButton.setText("开始回送到平板扬声器");
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                beginPermissionFlow();
            }
        });
        root.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("停止回送");
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopLoopback();
            }
        });
        root.addView(stopButton);

        TextView note = new TextView(this);
        note.setText("说明：只捕获允许被系统捕获的媒体/游戏声音，不联网、不保存音频。部分 DRM 视频或禁止捕获的应用可能没有声音。");
        note.setTextSize(14.0f);
        note.setTextColor(Color.GRAY);
        note.setPadding(0, 42, 0, 0);
        root.addView(note);

        setContentView(root);
    }

    private void beginPermissionFlow() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            statusView.setText("请允许录音权限；它用于读取系统允许捕获的内部播放音频。");
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO },
                    REQUEST_RECORD_AUDIO);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        if (projectionManager == null) {
            onLoopbackStatus("系统没有提供媒体捕获服务。", false);
            return;
        }
        statusView.setText("请在系统窗口中确认开始捕获。工具不会创建屏幕画面。");
        startActivityForResult(projectionManager.createScreenCaptureIntent(),
                REQUEST_MEDIA_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestProjection();
        } else {
            onLoopbackStatus("未获得录音权限，无法读取内部播放音频。", false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MEDIA_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            onLoopbackStatus("已取消系统捕获确认。", false);
            return;
        }
        Intent serviceIntent = new Intent(this, AudioLoopbackService.class)
                .setAction(AudioLoopbackService.ACTION_START)
                .putExtra(AudioLoopbackService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(AudioLoopbackService.EXTRA_RESULT_DATA, data);
        starting = true;
        statusView.setText("正在启动……");
        refreshButtons();
        startForegroundService(serviceIntent);
    }

    private void stopLoopback() {
        starting = false;
        Intent serviceIntent = new Intent(this, AudioLoopbackService.class)
                .setAction(AudioLoopbackService.ACTION_STOP);
        startService(serviceIntent);
        statusView.setText("正在停止……");
        refreshButtons();
    }

    @Override
    public void onLoopbackStatus(final String message, final boolean running) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                starting = false;
                statusView.setText(message);
                refreshButtons();
            }
        });
    }

    private void refreshButtons() {
        boolean running = starting || AudioLoopbackService.isActive();
        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
        if (statusView.getText().length() == 0) {
            statusView.setText(running
                    ? "正在通过前台服务回送到平板扬声器。"
                    : "尚未启动。请保持显示器连接，然后点击开始。");
        }
    }
}
