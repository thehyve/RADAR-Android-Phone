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

import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceManager;
import org.radarcns.android.device.DeviceService;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.util.PersistentStorage;

import static org.radarcns.android.RadarConfiguration.SOURCE_ID_KEY;

public class PhoneLocationService extends DeviceService {
    private String sourceId;

    @Override
    protected DeviceManager createDeviceManager() {
        return new PhoneLocationManager(this, getDataHandler(), getUserId(), getSourceId());
    }

    @Override
    protected BaseDeviceState getDefaultState() {
        BaseDeviceState state = new BaseDeviceState();
        state.setStatus(DeviceStatusListener.Status.DISCONNECTED);
        return state;
    }

    @Override
    protected PhoneLocationTopics getTopics() {
        return PhoneLocationTopics.getInstance();
    }

    public String getSourceId() {
        if (sourceId == null) {
            sourceId = new PersistentStorage(getClass()).loadOrStoreUUID(SOURCE_ID_KEY);
        }
        return sourceId;
    }
}
