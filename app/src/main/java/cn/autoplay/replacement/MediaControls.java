package cn.autoplay.replacement;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.os.Build;
import android.util.Log;
import android.view.KeyEvent;

abstract class MediaControls {
    final ConnectionService service;

    MediaControls(ConnectionService service) {
        this.service = service;
    }

    static MediaControls create(ConnectionService service) {
        return Build.VERSION.SDK_INT >= 21 ? new Modern(service) : new Legacy(service);
    }

    abstract void setActive(boolean active);
    abstract void release();

    void send(int command) {
        if (!service.sendMediaCommand(command)) {
            Log.w("AutoPlayConnection", "Media command rejected: phone not connected");
        }
    }

    boolean handle(Intent intent) {
        KeyEvent key = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (key == null) return false;
        int command = service.mediaCommand(key.getKeyCode());
        SettingsScreens.logSteeringKey("media_session", key, command);
        if (command < 0 || !service.phonePlugged) return false;
        if (key.getAction() == KeyEvent.ACTION_DOWN && key.getRepeatCount() == 0) {
            service.sendSteeringCommand(command, "media_session");
        }
        return true;
    }

    @android.annotation.TargetApi(21)
    private static final class Modern extends MediaControls {
        private final android.media.session.MediaSession session;

        Modern(ConnectionService service) {
            super(service);
            session = new android.media.session.MediaSession(service, "AutoPlay media controls");
            session.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new android.media.session.MediaSession.Callback() {
                @Override public void onPlay() { send(201); }
                @Override public void onPause() { send(202); }
                @Override public void onStop() { send(202); }
                @Override public void onSkipToNext() { send(204); }
                @Override public void onSkipToPrevious() { send(205); }
                @Override public boolean onMediaButtonEvent(Intent intent) { return handle(intent); }
            });
            session.setPlaybackState(new android.media.session.PlaybackState.Builder()
                    .setActions(android.media.session.PlaybackState.ACTION_PLAY
                            | android.media.session.PlaybackState.ACTION_PAUSE
                            | android.media.session.PlaybackState.ACTION_PLAY_PAUSE
                            | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT
                            | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(android.media.session.PlaybackState.STATE_NONE, 0, 0).build());
        }

        @Override void setActive(boolean active) { session.setActive(active); }
        @Override void release() { session.release(); }
    }

    private static final class Legacy extends MediaControls {
        private static final String ACTION = "cn.autoplay.replacement.LEGACY_MEDIA_BUTTON";
        private final AudioManager manager;
        private final PendingIntent pending;
        private final RemoteControlClient remote;
        private boolean active;
        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (active && ACTION.equals(intent.getAction())) handle(intent);
            }
        };

        Legacy(ConnectionService service) {
            super(service);
            manager = (AudioManager) service.getSystemService(Context.AUDIO_SERVICE);
            pending = PendingIntent.getBroadcast(service, 0,
                    new Intent(ACTION).setPackage(service.getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT);
            remote = new RemoteControlClient(pending);
            remote.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
                    | RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
                    | RemoteControlClient.FLAG_KEY_MEDIA_PLAY_PAUSE
                    | RemoteControlClient.FLAG_KEY_MEDIA_NEXT
                    | RemoteControlClient.FLAG_KEY_MEDIA_PREVIOUS);
            AndroidCompat.registerReceiver(service, receiver, new IntentFilter(ACTION), false);
        }

        @Override void setActive(boolean enabled) {
            if (active == enabled) return;
            active = enabled;
            if (enabled) {
                manager.registerMediaButtonEventReceiver(pending);
                manager.registerRemoteControlClient(remote);
                remote.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
            } else {
                manager.unregisterRemoteControlClient(remote);
                manager.unregisterMediaButtonEventReceiver(pending);
            }
        }

        @Override void release() {
            setActive(false);
            service.unregisterReceiver(receiver);
            pending.cancel();
        }
    }
}
