package dev.codex.matepadaudio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.os.Process;

final class AudioLoopback {
    interface Listener {
        void onLoopbackStatus(String message, boolean running);
    }

    private static final int SAMPLE_RATE = 48000;

    private final Context context;
    private final MediaProjection projection;
    private volatile Listener listener;
    private volatile boolean running;
    private volatile AudioRecord record;
    private volatile AudioTrack track;
    private volatile Thread worker;

    AudioLoopback(Context context, MediaProjection projection, Listener listener) {
        this.context = context;
        this.projection = projection;
        this.listener = listener;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runLoopback();
            }
        }, "matepad-audio-loopback");
        worker.start();
    }

    void stop() {
        running = false;
        Thread localWorker = worker;
        if (localWorker != null) {
            localWorker.interrupt();
        }
        closeAudio();
        try {
            projection.stop();
        } catch (RuntimeException ignored) {
        }
    }

    private void runLoopback() {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService("audio");
            AudioDeviceInfo speaker = findSpeaker(audioManager);
            if (speaker == null) {
                throw new IllegalStateException("没有找到平板内置扬声器");
            }

            AudioPlaybackCaptureConfiguration capture =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .excludeUid(Process.myUid())
                            .build();

            AudioFormat inputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            AudioFormat outputFormat = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build();

            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minimum * 4, 32768);

            record = new AudioRecord.Builder()
                    .setAudioFormat(inputFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(capture)
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("内部音频读取器初始化失败");
            }

            AudioAttributes replayAttributes = new AudioAttributes.Builder()
                    // Huawei routes every active media track to the speaker once one media
                    // track explicitly prefers it. Accessibility usage avoids moving the
                    // original HDMI track, so only this delayed copy is audible there.
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(replayAttributes)
                    .setAudioFormat(outputFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("扬声器输出初始化失败");
            }
            if (!track.setPreferredDevice(speaker)) {
                throw new IllegalStateException("系统拒绝将回送音轨指定到内置扬声器");
            }

            record.startRecording();
            track.play();
            AudioDeviceInfo routed = track.getRoutedDevice();
            String routeName = routed == null ? "内置扬声器"
                    : String.valueOf(routed.getProductName());
            notifyStatus("正在回送到 " + routeName + "。实体音量键可调节回送音量。", true);

            byte[] buffer = new byte[bufferSize];
            while (running) {
                int count = record.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    int mediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                    int mediaMaximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    float gain = mediaVolume <= 0 || mediaMaximum <= 0
                            ? 0.0f
                            : Math.min(2.5f, mediaVolume / (mediaMaximum * 0.4f));
                    applyPcm16Gain(buffer, count, gain);
                    int offset = 0;
                    while (running && offset < count) {
                        int written = track.write(buffer, offset, count - offset,
                                AudioTrack.WRITE_BLOCKING);
                        if (written <= 0) {
                            throw new IllegalStateException("扬声器输出中断：" + written);
                        }
                        offset += written;
                    }
                } else if (count < 0) {
                    throw new IllegalStateException("内部音频读取中断：" + count);
                }
            }
        } catch (Throwable error) {
            if (running) {
                String detail = error.getMessage();
                notifyStatus("启动失败：" + (detail == null
                        ? error.getClass().getSimpleName() : detail), false);
            }
        } finally {
            running = false;
            closeAudio();
        }
    }

    private static void applyPcm16Gain(byte[] data, int count, float gain) {
        for (int index = 0; index + 1 < count; index += 2) {
            short sample = (short) ((data[index] & 0xff) | (data[index + 1] << 8));
            int scaled = Math.round(sample * gain);
            if (scaled > Short.MAX_VALUE) {
                scaled = Short.MAX_VALUE;
            } else if (scaled < Short.MIN_VALUE) {
                scaled = Short.MIN_VALUE;
            }
            data[index] = (byte) scaled;
            data[index + 1] = (byte) (scaled >> 8);
        }
    }

    private static AudioDeviceInfo findSpeaker(AudioManager audioManager) {
        if (audioManager == null) {
            return null;
        }
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                return device;
            }
        }
        return null;
    }

    private void closeAudio() {
        AudioRecord localRecord = record;
        record = null;
        if (localRecord != null) {
            try {
                localRecord.stop();
            } catch (RuntimeException ignored) {
            }
            localRecord.release();
        }
        AudioTrack localTrack = track;
        track = null;
        if (localTrack != null) {
            try {
                localTrack.stop();
            } catch (RuntimeException ignored) {
            }
            localTrack.release();
        }
    }

    private void notifyStatus(String message, boolean isRunning) {
        Listener localListener = listener;
        if (localListener != null) {
            localListener.onLoopbackStatus(message, isRunning);
        }
    }
}
