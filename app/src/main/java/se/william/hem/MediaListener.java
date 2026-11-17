package se.william.hem;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing by itself. Android only lets an app read "what is playing" (media sessions)
 * if it has an enabled notification listener, so this empty listener is what gives the
 * home screen permission to show real titles under "Keep watching".
 * Enable it once with:  adb shell cmd notification allow_listener se.william.hem/se.william.hem.MediaListener
 */
public class MediaListener extends NotificationListenerService {
}
