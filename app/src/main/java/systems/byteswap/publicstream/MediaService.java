package systems.byteswap.publicstream;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;
import org.videolan.libvlc.IVLCVout;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

//TODO: locks passen noch nicht ganz... (underlocking)

public class MediaService extends Service implements IVLCVout.Callback, LibVLC.HardwareAccelerationError {
    public final static String ACTION_PLAY_PAUSE = "systems.byteswap.action.PLAY";
    public final static String ACTION_STOP = "systems.byteswap.action.STOP";
    public final static String ACTION_SETTIME = "systems.byteswap.action.SETTIME";
    public final static String ACTION_LOAD = "systems.byteswap.action.LOAD";

    public final static String MEDIA_STATE_IDLE = "systems.byteswap.mediastate.IDLE";
    public final static String MEDIA_STATE_PLAYING = "systems.byteswap.mediastate.PLAYING";
    public final static String MEDIA_STATE_PAUSED = "systems.byteswap.mediastate.PAUSED";
    public final static String MEDIA_STATE_PREPARING = "systems.byteswap.mediastate.PREPARING";

    public final static int MEDIA_BUFFER_MS = 6000;

    private MediaPlayer mMediaPlayer = null;
    private LibVLC libvlc;
    private String mState = MEDIA_STATE_IDLE;
    private MediaPlayer.EventListener mPlayerListener = new MyPlayerListener(this);
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private boolean isLive = false;


    @Override
    public IBinder onBind(Intent intent) {
        wifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, "publicStreamWifiLock");
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "publicStreamWakeLock");
        return new LocalBinder<MediaService>(this);
    }

    public MediaPlayer getMediaPlayer() {
        return mMediaPlayer;
    }

    public boolean onCommand(String command, String parameter, int parameter2) {
        switch(command) {
            case ACTION_PLAY_PAUSE:
                switch(mState) {
                    case MEDIA_STATE_PAUSED:
                        wifiLock.acquire();
                        wakeLock.acquire();
                        mMediaPlayer.play();
                        mState = MEDIA_STATE_PLAYING;
                        break;
                    case MEDIA_STATE_PLAYING:
                        mMediaPlayer.pause();
                        mState = MEDIA_STATE_PAUSED;
                        wifiLock.release();
                        wakeLock.release();
                        break;
                }
                break;
            case ACTION_LOAD:
                    if(parameter.equals(ORFParser.ORF_LIVE_URL)) {
                        isLive=true;
                    } else {
                        isLive=false;
                    }
                    wifiLock.acquire();
                    wakeLock.acquire();
                    createPlayer(parameter);
                    mState = MEDIA_STATE_PLAYING;
                break;
            case ACTION_SETTIME:
                float position = Float.valueOf(parameter);
                mMediaPlayer.setPosition(Float.valueOf(parameter));
                break;
            default:
                return false;

        }
        return true;
    }

    @Override
    public void onDestroy() {
        if(mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }

    public int getDuration() {
        switch(mState) {
            case MEDIA_STATE_PLAYING:
                return safeLongToInt(mMediaPlayer.getLength());
            case MEDIA_STATE_PAUSED:
                return -2;
            default:
                return -1;
        }
    }

    public int getCurrentPosition() {
        switch(mState) {
            case MEDIA_STATE_PLAYING:
                return safeLongToInt(mMediaPlayer.getTime());
            case MEDIA_STATE_PAUSED:
                return -2;
            default:
                return -1;
        }
    }


    @Override
    public void onNewLayout(IVLCVout ivlcVout, int i, int i1, int i2, int i3, int i4, int i5) {

    }

    @Override
    public void onSurfacesCreated(IVLCVout ivlcVout) {

    }

    @Override
    public void onSurfacesDestroyed(IVLCVout ivlcVout) {

    }

    @Override
    public void eventHardwareAccelerationError() {
        Toast.makeText(MediaService.this, "HardwareAccelerationError...", Toast.LENGTH_SHORT).show();
    }

    private void createPlayer(String media) {
        releasePlayer();
        try {
            // Create LibVLC
            ArrayList<String> options = new ArrayList<String>();
            options.add("--aout=opensles");
            options.add("--audio-time-stretch"); // time stretching
            options.add("-vvv"); // verbosity
            //options.add("--network-caching=" + MEDIA_BUFFER_MS);

            libvlc = new LibVLC(options);
            libvlc.setOnHardwareAccelerationError(this);

            // Create media player
            mMediaPlayer = new MediaPlayer(libvlc);
            mMediaPlayer.setEventListener(mPlayerListener);

            Media m = new Media(libvlc, Uri.parse(media));
            //m.setHWDecoderEnabled(false,false);
            m.addOption(":network-caching=6000");
            mMediaPlayer.setMedia(m);
            mMediaPlayer.play();
        } catch (Exception e) {
            Toast.makeText(MediaService.this, "Fehler beim Erstellen des Players...", Toast.LENGTH_SHORT).show();
            Log.e("PUBLICSTREAM", "Error creating player: " + e.getMessage());
        }
    }

    private void releasePlayer() {
        if (libvlc == null)
            return;
        mMediaPlayer.stop();
        libvlc.release();
        wifiLock.release();
        wakeLock.release();
        libvlc = null;
    }

    public static int safeLongToInt(long l) {
        int i = (int)l;
        if ((long)i != l) {
            throw new IllegalArgumentException(l + " cannot be cast to int without changing its value.");
        }
        return i;
    }

    public void setState(String state) {
        this.mState = state;
    }

    public boolean isLive() {
        return isLive;
    }

    private static class MyPlayerListener implements MediaPlayer.EventListener {
        private WeakReference<MediaService> mOwner;

        public MyPlayerListener(MediaService owner) {
            mOwner = new WeakReference<MediaService>(owner);
        }

        @Override
        public void onEvent(MediaPlayer.Event event) {
            MediaService player = mOwner.get();

            switch(event.type) {
                case MediaPlayer.Event.EndReached:
                    player.setState(MediaService.MEDIA_STATE_IDLE);
                    player.releasePlayer();
                    break;
                case MediaPlayer.Event.Playing:
                case MediaPlayer.Event.Paused:
                case MediaPlayer.Event.Stopped:
                default:
                    break;
            }
        }
    }
}