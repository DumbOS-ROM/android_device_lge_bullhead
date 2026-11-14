/*
 * SPDX-FileCopyrightText: 2026 Romain Théry
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.bullhead.wfcims;

import android.os.RemoteException;
import android.telephony.ims.stub.ImsConfigImplBase;
import android.util.Log;

import com.android.ims.internal.IImsConfig;

/** Provisioning kept where it has always been, in the modem, reached through its legacy config. */
final class WfcLegacyConfig extends ImsConfigImplBase {
    private static final String TAG = "WfcIms";
    private static final int LEGACY_UNKNOWN = -1;
    private static final int LEGACY_SUCCESS = 0;

    private final IImsConfig mLegacy;

    WfcLegacyConfig(IImsConfig legacy) {
        super(Runnable::run);
        mLegacy = legacy;
    }

    @Override
    public int setConfig(int item, int value) {
        try {
            if (mLegacy.setProvisionedValue(item, value) == LEGACY_SUCCESS) {
                return CONFIG_RESULT_SUCCESS;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "setConfig " + item + ": " + e);
        }
        return CONFIG_RESULT_FAILED;
    }

    @Override
    public int setConfig(int item, String value) {
        try {
            if (mLegacy.setProvisionedStringValue(item, value) == LEGACY_SUCCESS) {
                return CONFIG_RESULT_SUCCESS;
            }
        } catch (RemoteException e) {
            Log.w(TAG, "setConfig " + item + ": " + e);
        }
        return CONFIG_RESULT_FAILED;
    }

    @Override
    public int getConfigInt(int item) {
        try {
            int value = mLegacy.getProvisionedValue(item);
            if (value != LEGACY_UNKNOWN) return value;
        } catch (RemoteException e) {
            Log.w(TAG, "getConfigInt " + item + ": " + e);
        }
        return CONFIG_RESULT_UNKNOWN;
    }

    @Override
    public String getConfigString(int item) {
        try {
            return mLegacy.getProvisionedStringValue(item);
        } catch (RemoteException e) {
            Log.w(TAG, "getConfigString " + item + ": " + e);
            return null;
        }
    }
}
