/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.phone;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.util.SparseArray;
import org.radarcns.android.data.DataCache;
import org.radarcns.android.data.TableDataHandler;
import org.radarcns.android.device.AbstractDeviceManager;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.key.MeasurementKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;
import java.util.Set;

class PhoneUsageManager extends AbstractDeviceManager<PhoneUsageService, BaseDeviceState> implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(PhoneUsageManager.class);

    private static final SparseArray<UsageEventType> EVENT_TYPES = new SparseArray<>(6);

    static {
        EVENT_TYPES.append(UsageEvents.Event.MOVE_TO_FOREGROUND, UsageEventType.FOREGROUND);
        EVENT_TYPES.append(UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEventType.BACKGROUND);
        EVENT_TYPES.append(UsageEvents.Event.CONFIGURATION_CHANGE, UsageEventType.CONFIG);
        EVENT_TYPES.append(UsageEvents.Event.NONE, UsageEventType.UNKNOWN);
        if (android.os.Build.VERSION.SDK_INT >= 25) {
            EVENT_TYPES.append(UsageEvents.Event.SHORTCUT_INVOCATION, UsageEventType.SHORTCUT);
            EVENT_TYPES.append(UsageEvents.Event.USER_INTERACTION, UsageEventType.INTERACTION);
        }
    }
    private static final String LAST_PACKAGE_NAME = "org.radarcns.phone.packageName";
    private static final String LAST_EVENT_TIMESTAMP = "org.radarcns.phone.timestamp";
    private static final String LAST_EVENT_TYPE = "org.radarcns.phone.PhoneUsageManager.lastEventType";
    private static final String LAST_EVENT_IS_SENT = "org.radarcns.phone.PhoneUsageManager.lastEventIsSent";
    private static final String LAST_USER_INTERACTION = "org.radarcns.phone.lastAction";
    private static final String ACTION_BOOT = "org.radarcns.phone.ACTION_BOOT";
    private static final String ACTION_UPDATE_EVENTS = "org.radarcns.phone.PhoneUsageManager.ACTION_UPDATE_EVENTS";
    private static final int USAGE_EVENT_REQUEST_CODE = 586106;

    private final DataCache<MeasurementKey, PhoneUsageEvent> usageEventTable;
    private final DataCache<MeasurementKey, PhoneUserInteraction> userInteractionTable;

    private final BroadcastReceiver phoneStateReceiver;

    private final UsageStatsManager usageStatsManager;
    private final SharedPreferences preferences;
    private final OfflineProcessor phoneUsageProcessor;

    private String lastPackageName;
    private long lastTimestamp;
    private int lastEventType;
    private boolean lastEventIsSent;

    public PhoneUsageManager(PhoneUsageService context, TableDataHandler dataHandler, String groupId, String sourceId,
                             long usageEventInterval) {
        super(context, new BaseDeviceState(), dataHandler, groupId, sourceId);

        PhoneUsageTopics topics = PhoneUsageTopics.getInstance();
        this.usageEventTable = dataHandler.getCache(topics.getUsageEventTopic());
        this.userInteractionTable = dataHandler.getCache(topics.getUserInteractionTopic());

        this.usageStatsManager = (UsageStatsManager) context.getSystemService("usagestats");
        this.preferences = context.getSharedPreferences(PhoneUsageService.class.getName(), Context.MODE_PRIVATE);
        this.loadLastEvent();

        // Listen for screen lock/unlock events
        phoneStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // If previous event was a shutdown, then this action indicates that the phone has booted
                if (preferences.getString(LAST_USER_INTERACTION,"").equals(Intent.ACTION_SHUTDOWN)) {
                    sendInteractionState(ACTION_BOOT);
                }

                sendInteractionState(intent.getAction());
            }
        };

        phoneUsageProcessor = new OfflineProcessor(context, this, USAGE_EVENT_REQUEST_CODE,
                ACTION_UPDATE_EVENTS, usageEventInterval, false);

        setName(String.format(context.getString(R.string.app_usage_service_name), android.os.Build.MODEL));
        updateStatus(DeviceStatusListener.Status.READY);
    }

    @Override
    public void start(final Set<String> acceptableIds) {
        // Start query of usage events
        phoneUsageProcessor.start();

        IntentFilter phoneStateFilter = new IntentFilter();
        phoneStateFilter.addAction(Intent.ACTION_USER_PRESENT); // unlock
        phoneStateFilter.addAction(Intent.ACTION_SCREEN_OFF); // lock
        phoneStateFilter.addAction(Intent.ACTION_SHUTDOWN); // shutdown

        Context context = getService();
        // Activity to perform when alarm is triggered
        context.registerReceiver(phoneStateReceiver, phoneStateFilter);

        updateStatus(DeviceStatusListener.Status.CONNECTED);
    }

    @Override
    public void run() {
        processUsageEvents();
        storeLastEvent();
    }

    private void sendInteractionState(String action) {
        PhoneInteractionState state;

        switch (action) {
            case Intent.ACTION_SCREEN_OFF:
                state = PhoneInteractionState.STANDBY;
                break;
            case Intent.ACTION_USER_PRESENT:
                state = PhoneInteractionState.UNLOCKED;
                break;
            case Intent.ACTION_SHUTDOWN:
                state = PhoneInteractionState.SHUTDOWN;
                break;
            case ACTION_BOOT:
                state = PhoneInteractionState.BOOTED;
                break;
            default:
                return;
        }

        double time = System.currentTimeMillis() / 1000d;
        send(userInteractionTable, new PhoneUserInteraction(time, time, state));

        // Save the last user interaction state. Value shutdown is used to register boot.
        preferences.edit()
                .putString(LAST_USER_INTERACTION, action)
                .apply();
        logger.info("Interaction State: {} {}", time, state);
    }

    /**
     * Set the interval in which to collect logs about app usage.
     * @param interval collection interval in seconds
     */
    public void setUsageEventUpdateRate(long interval) {
        phoneUsageProcessor.setInterval(interval);
        logger.info("Usage event alarm activated and set to a period of {} seconds", interval);
    }

    private void processUsageEvents() {
        if (phoneUsageProcessor.isDone()) {
            return;
        }

        // Get events from previous event to now or from a fixed history
        UsageEvents usageEvents = usageStatsManager.queryEvents(lastTimestamp, System.currentTimeMillis());

        // Loop through all events, send opening and closing of app
        // Assume events are ordered on timestamp in ascending order (old to new)
        UsageEvents.Event event = new UsageEvents.Event();
        while (usageEvents.getNextEvent(event) && !phoneUsageProcessor.isDone()) {
            // Ignore config changes, old events, and events from the same activity
            if (event.getEventType() == UsageEvents.Event.CONFIGURATION_CHANGE
                    || event.getTimeStamp() < lastTimestamp) {
                continue;
            }

            if (event.getPackageName().equals(lastPackageName)) {
               updateLastEvent(event, false);
           } else {
                // send this closing event
                if (lastPackageName != null && !lastEventIsSent) {
                    sendLastEvent();
                }

                updateLastEvent(event, true);

                // Send the opening of new event
                sendLastEvent();
            }
        }

        // Store the last previous event on internal memory for the next run
        this.storeLastEvent();
    }

    private void sendLastEvent() {
        // Event type conversion to Schema defined
        UsageEventType usageEventType = EVENT_TYPES.get(lastEventType, UsageEventType.UNKNOWN);

        double time = lastTimestamp / 1000d;
        double timeReceived = System.currentTimeMillis() / 1000d;
        PhoneUsageEvent value = new PhoneUsageEvent(
                time, timeReceived, lastPackageName, null, null, usageEventType);
        send(usageEventTable, value);

        if (logger.isDebugEnabled()) {
            logger.debug("Event: [{}] {}\n\t{}", lastEventType, lastPackageName, new Date(lastTimestamp));
        }
    }

    private void updateLastEvent(UsageEvents.Event event, boolean isSent) {
        lastPackageName = event.getPackageName();
        lastTimestamp = event.getTimeStamp();
        lastEventType = event.getEventType();
        lastEventIsSent = isSent;
    }

    private void storeLastEvent() {
        preferences.edit()
                .putString(LAST_PACKAGE_NAME, lastPackageName)
                .putLong(LAST_EVENT_TIMESTAMP, lastTimestamp)
                .putInt(LAST_EVENT_TYPE, lastEventType)
                .putBoolean(LAST_EVENT_IS_SENT, lastEventIsSent)
                .apply();
    }

    private void loadLastEvent() {
        lastPackageName = preferences.getString(LAST_PACKAGE_NAME, null);
        lastTimestamp = preferences.getLong(LAST_EVENT_TIMESTAMP, System.currentTimeMillis());
        lastEventType = preferences.getInt(LAST_EVENT_TYPE, 0);
        lastEventIsSent = preferences.getBoolean(LAST_EVENT_IS_SENT, true);

        if (lastPackageName == null) {
            logger.info("No previous event details stored");
        }
    }

    @Override
    public void close() throws IOException {
        phoneUsageProcessor.close();
        PhoneUsageService context = getService();
        context.unregisterReceiver(phoneStateReceiver);
        super.close();
    }
}
