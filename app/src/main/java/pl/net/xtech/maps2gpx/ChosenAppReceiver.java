package pl.net.xtech.maps2gpx;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Records which app the user picked in the chooser, so "open directly" can skip the
 * chooser next time.
 *
 * <p>The platform reports the pick through {@link Intent#EXTRA_CHOSEN_COMPONENT} on an
 * {@code IntentSender} handed to
 * {@link Intent#createChooser(Intent, CharSequence, android.content.IntentSender)}
 * (API 22+). Writing it straight to preferences from a receiver keeps the activity out of
 * the loop - it just re-reads the setting when it resumes.
 */
public class ChosenAppReceiver extends BroadcastReceiver {

    static final String ACTION_APP_CHOSEN = "pl.net.xtech.maps2gpx.APP_CHOSEN";

    @Override
    public void onReceive(Context context, Intent intent) {
        ComponentName chosen = intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT);
        if (chosen == null) {
            return;
        }
        Log.i("Maps2Gpx", "Remembering chosen app: " + chosen.flattenToShortString());
        new Settings(context).setDirectComponent(chosen);
    }
}
