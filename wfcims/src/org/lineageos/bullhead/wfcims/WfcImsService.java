/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.os.HandlerThread;
import android.telephony.ims.ImsService;
import android.telephony.ims.feature.ImsFeature;
import android.telephony.ims.feature.MmTelFeature;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.telephony.ims.stub.ImsFeatureConfiguration;
import android.telephony.ims.stub.ImsRegistrationImplBase;
import android.util.Log;
import android.util.SparseArray;

/** Wi-Fi calling ImsService: SIP over the IMS PDN that the IWLAN data service brings up. */
public class WfcImsService extends ImsService {
    private static final String TAG = "WfcIms";

    private final SparseArray<ImsRegistrationImplBase> mRegistrations = new SparseArray<>();
    private final SparseArray<WfcMmTelFeature> mFeatures = new SparseArray<>();
    private HandlerThread mThread;

    private synchronized HandlerThread thread() {
        if (mThread == null) {
            mThread = new HandlerThread("WfcIms");
            mThread.start();
        }
        return mThread;
    }

    @Override
    public ImsFeatureConfiguration querySupportedImsFeatures() {
        return new ImsFeatureConfiguration.Builder()
                .addFeature(0, ImsFeature.FEATURE_MMTEL)
                .build();
    }

    @Override
    public long getImsServiceCapabilities() {
        return 0;
    }

    @Override
    public synchronized ImsRegistrationImplBase getRegistrationForSubscription(int slotId,
            int subscriptionId) {
        ImsRegistrationImplBase r = mRegistrations.get(slotId);
        if (r == null) {
            r = new ImsRegistrationImplBase();
            mRegistrations.put(slotId, r);
        }
        return r;
    }

    @Override
    public synchronized MmTelFeature createMmTelFeatureForSubscription(int slotId,
            int subscriptionId) {
        Log.i(TAG, "MMTEL feature for slot " + slotId + " sub " + subscriptionId);
        WfcMmTelFeature f = new WfcMmTelFeature(this, thread().getLooper(), slotId,
                subscriptionId, getRegistrationForSubscription(slotId, subscriptionId));
        f.setFeatureState(ImsFeature.STATE_READY);
        mFeatures.put(slotId, f);
        return f;
    }

    @Override
    public synchronized ImsConfigImplBase getConfig(int slotId) {
        WfcMmTelFeature f = mFeatures.get(slotId);
        ImsConfigImplBase config = f != null ? f.legacyConfig() : null;
        return config != null ? config : super.getConfig(slotId);
    }

    @Override
    public void onDestroy() {
        synchronized (this) {
            for (int i = 0; i < mFeatures.size(); i++) mFeatures.valueAt(i).shutdown();
            if (mThread != null) mThread.quitSafely();
        }
        super.onDestroy();
    }
}
