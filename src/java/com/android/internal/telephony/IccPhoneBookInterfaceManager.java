/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.telephony;

import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.telephony.Rlog;
import android.text.TextUtils;

import com.android.internal.telephony.uicc.AdnRecord;
import com.android.internal.telephony.uicc.AdnRecordCache;
import com.android.internal.telephony.uicc.SimPhoneBookAdnRecordCache;
import com.android.internal.telephony.uicc.IccCardApplicationStatus.AppType;
import com.android.internal.telephony.uicc.IccConstants;
import com.android.internal.telephony.uicc.IccFileHandler;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccCardApplication;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SimPhoneBookInterfaceManager to provide an inter-process communication to
 * access ADN-like SIM records.
 */
public class IccPhoneBookInterfaceManager {
    static final String LOG_TAG = "IccPhoneBookIM";
    protected static final boolean DBG = true;

    protected Phone mPhone;
    private   UiccCardApplication mCurrentApp = null;
    protected AdnRecordCache mAdnCache;
    protected SimPhoneBookAdnRecordCache mSimPbAdnCache;
    protected final Object mLock = new Object();
    protected int mRecordSize[];
    protected boolean mSuccess;
    private   boolean mIs3gCard = false;  // flag to determine if card is 3G or 2G
    protected List<AdnRecord> mRecords;


    protected static final boolean ALLOW_SIM_OP_IN_UI_THREAD = false;

    protected static final int EVENT_GET_SIZE_DONE = 1;
    protected static final int EVENT_LOAD_DONE = 2;
    protected static final int EVENT_UPDATE_DONE = 3;

    protected Handler mBaseHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch (msg.what) {
                case EVENT_GET_SIZE_DONE:
                    ar = (AsyncResult) msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            mRecordSize = (int[])ar.result;
                            // recordSize[0]  is the record length
                            // recordSize[1]  is the total length of the EF file
                            // recordSize[2]  is the number of records in the EF file
                            logd("GET_RECORD_SIZE Size " + mRecordSize[0] +
                                    " total " + mRecordSize[1] +
                                    " #record " + mRecordSize[2]);
                        }
                        notifyPending(ar);
                    }
                    break;
                case EVENT_UPDATE_DONE:
                    ar = (AsyncResult) msg.obj;
                    if(ar.exception != null) {
                        if(DBG) logd("exception of EVENT_UPDATE_DONE is" + ar.exception );
                    }
                    synchronized (mLock) {
                        mSuccess = (ar.exception == null);
                        notifyPending(ar);
                    }
                    break;
                case EVENT_LOAD_DONE:
                    ar = (AsyncResult)msg.obj;
                    synchronized (mLock) {
                        if (ar.exception == null) {
                            if(DBG) logd("Load ADN records done");
                            mRecords = (List<AdnRecord>) ar.result;
                        } else {
                            if(DBG) logd("Cannot load ADN records");
                            mRecords = null;
                        }
                        notifyPending(ar);
                    }
                    break;
            }
        }

        private void notifyPending(AsyncResult ar) {
            if (ar.userObj != null) {
                AtomicBoolean status = (AtomicBoolean) ar.userObj;
                status.set(true);
            }
            mLock.notifyAll();
        }
    };

    public IccPhoneBookInterfaceManager(Phone phone) {
        this.mPhone = phone;
        IccRecords r = phone.getIccRecords();

        if (r != null) {
            mAdnCache = r.getAdnCache();
        }

        if(isSimPhoneBookEnabled()){
            if(mSimPbAdnCache == null) {
                mSimPbAdnCache = new SimPhoneBookAdnRecordCache(
                        phone.getContext(), phone.getPhoneId(), phone.mCi);
            }
        }
    }

    public void dispose() {
        if (mRecords != null) {
            mRecords.clear();
        }
    }

    public void updateIccRecords(IccRecords iccRecords) {
        if (iccRecords != null) {
            mAdnCache = iccRecords.getAdnCache();
        } else {
            mAdnCache = null;
        }
    }

    protected void logd(String msg) {
        Rlog.d(LOG_TAG, "[IccPbInterfaceManager] " + msg);
    }

    protected void loge(String msg) {
        Rlog.e(LOG_TAG, "[IccPbInterfaceManager] " + msg);
    }

    /**
     * Replace oldAdn with newAdn in ADN-like record in EF
     *
     * getAdnRecordsInEf must be called at least once before this function,
     * otherwise an error will be returned. Currently the email field
     * if set in the ADN record is ignored.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param oldTag adn tag to be replaced
     * @param oldPhoneNumber adn number to be replaced
     *        Set both oldTag and oldPhoneNubmer to "" means to replace an
     *        empty record, aka, insert new record
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number ot be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean
    updateAdnRecordsInEfBySearch (int efid,
            String oldTag, String oldPhoneNumber,
            String newTag, String newPhoneNumber, String pin2) {


        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }


        if (DBG) logd("updateAdnRecordsInEfBySearch: efid=0x" +
                Integer.toHexString(efid).toUpperCase() +
                " ("+ oldTag + "," + oldPhoneNumber + ")"+ "==>" +
                " ("+ newTag + "," + newPhoneNumber + ")"+ " pin2=" + pin2);

        efid = updateEfForIccType(efid);

        synchronized(mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord oldAdn = new AdnRecord(oldTag, oldPhoneNumber);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnBySearch(efid, oldAdn, newAdn, pin2, response);
                waitForResult(status);
            } else {
                loge("Failure while trying to update by search due to uninitialised adncache");
            }
        }
        return mSuccess;
    }

    /**
     * Replace oldAdn with newAdn in ADN-like record in EF
     *
     * getAdnRecordsInEf must be called at least once before this function,
     * otherwise an error will be returned.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param values old adn tag,  phone number, email and anr to be replaced
     *        new adn tag,  phone number, email and anr to be stored
     * @param newPhoneNumber adn number ot be stored
     * @param oldPhoneNumber adn number to be replaced
     *        Set both oldTag, oldPhoneNubmer, oldEmail and oldAnr to ""
     *        means to replace an empty record, aka, insert new record
     *        Set both newTag, newPhoneNubmer, newEmail and newAnr ""
     *        means to replace the old record with empty one, aka, delete old record
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean updateAdnRecordsWithContentValuesInEfBySearch(int efid, ContentValues values,
            String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires android.permission.WRITE_CONTACTS permission");
        }

        String oldTag = values.getAsString(IccProvider.STR_TAG);
        String newTag = values.getAsString(IccProvider.STR_NEW_TAG);
        String oldPhoneNumber = values.getAsString(IccProvider.STR_NUMBER);
        String newPhoneNumber = values.getAsString(IccProvider.STR_NEW_NUMBER);
        String oldEmail = values.getAsString(IccProvider.STR_EMAILS);
        String newEmail = values.getAsString(IccProvider.STR_NEW_EMAILS);
        String oldAnr = values.getAsString(IccProvider.STR_ANRS);
        String newAnr = values.getAsString(IccProvider.STR_NEW_ANRS);
        String[] oldEmailArray = TextUtils.isEmpty(oldEmail) ? null : getStringArray(oldEmail);
        String[] newEmailArray = TextUtils.isEmpty(newEmail) ? null : getStringArray(newEmail);
        String[] oldAnrArray = TextUtils.isEmpty(oldAnr) ? null : getAnrStringArray(oldAnr);
        String[] newAnrArray = TextUtils.isEmpty(newAnr) ? null : getAnrStringArray(newAnr);
        efid = updateEfForIccType(efid);

        if (DBG)
            logd("updateAdnRecordsWithContentValuesInEfBySearch: efid=" + efid + ", values = " +
                values + ", pin2=" + pin2);
        synchronized (mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord oldAdn = new AdnRecord(oldTag, oldPhoneNumber, oldEmailArray, oldAnrArray);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber, newEmailArray, newAnrArray);
            if (isSimPhoneBookEnabled() &&
                (efid == IccConstants.EF_PBR || efid == IccConstants.EF_ADN)) {
                if (mSimPbAdnCache != null) {
                    mSimPbAdnCache.updateSimPbAdnBySearch(oldAdn, newAdn, response);
                    waitForResult(status);
                } else {
                    loge("Failure while trying to update by search due to uninit sim pb adncache");
                }
            } else {
                if (mAdnCache != null) {
                    mAdnCache.updateAdnBySearch(efid, oldAdn, newAdn, pin2, response);
                    waitForResult(status);
                } else {
                    loge("Failure while trying to update by search due to uninitialised adncache");
                }
            }
        }
        return mSuccess;
    }

    /**
     * Update an ADN-like EF record by record index
     *
     * This is useful for iteration the whole ADN file, such as write the whole
     * phone book or erase/format the whole phonebook. Currently the email field
     * if set in the ADN record is ignored.
     * throws SecurityException if no WRITE_CONTACTS permission
     *
     * @param efid must be one among EF_ADN, EF_FDN, and EF_SDN
     * @param newTag adn tag to be stored
     * @param newPhoneNumber adn number to be stored
     *        Set both newTag and newPhoneNubmer to "" means to replace the old
     *        record with empty one, aka, delete old record
     * @param index is 1-based adn record index to be updated
     * @param pin2 required to update EF_FDN, otherwise must be null
     * @return true for success
     */
    public boolean
    updateAdnRecordsInEfByIndex(int efid, String newTag,
            String newPhoneNumber, int index, String pin2) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.WRITE_CONTACTS permission");
        }

        if (DBG) logd("updateAdnRecordsInEfByIndex: efid=0x" +
                Integer.toHexString(efid).toUpperCase() +
                " Index=" + index + " ==> " +
                "("+ newTag + "," + newPhoneNumber + ")"+ " pin2=" + pin2);
        synchronized(mLock) {
            checkThread();
            mSuccess = false;
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_UPDATE_DONE, status);
            AdnRecord newAdn = new AdnRecord(newTag, newPhoneNumber);
            if (mAdnCache != null) {
                mAdnCache.updateAdnByIndex(efid, newAdn, index, pin2, response);
                waitForResult(status);
            } else {
                loge("Failure while trying to update by index due to uninitialised adncache");
            }
        }
        return mSuccess;
    }

    /**
     * Get the capacity of records in efid
     *
     * @param efid the EF id of a ADN-like ICC
     * @return  int[3] array
     *            recordSizes[0]  is the single record length
     *            recordSizes[1]  is the total length of the EF file
     *            recordSizes[2]  is the number of records in the EF file
     */
    public int[] getAdnRecordsSize(int efid) {
        if (DBG) logd("getAdnRecordsSize: efid=" + efid);
        synchronized(mLock) {
            checkThread();
            mRecordSize = new int[3];

            //Using mBaseHandler, no difference in EVENT_GET_SIZE_DONE handling
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_GET_SIZE_DONE, status);

            IccFileHandler fh = mPhone.getIccFileHandler();
            if (fh != null) {
                fh.getEFLinearRecordSize(efid, response);
                waitForResult(status);
            }
        }

        return mRecordSize;
    }


    /**
     * Loads the AdnRecords in efid and returns them as a
     * List of AdnRecords
     *
     * throws SecurityException if no READ_CONTACTS permission
     *
     * @param efid the EF id of a ADN-like ICC
     * @return List of AdnRecord
     */
    public List<AdnRecord> getAdnRecordsInEf(int efid) {

        if (mPhone.getContext().checkCallingOrSelfPermission(
                android.Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Requires android.permission.READ_CONTACTS permission");
        }

        efid = updateEfForIccType(efid);
        if (DBG) logd("getAdnRecordsInEF: efid=0x" + Integer.toHexString(efid).toUpperCase());

        synchronized(mLock) {
            checkThread();
            AtomicBoolean status = new AtomicBoolean(false);
            Message response = mBaseHandler.obtainMessage(EVENT_LOAD_DONE, status);

            if (isSimPhoneBookEnabled() &&
                (efid == IccConstants.EF_PBR || efid == IccConstants.EF_ADN)) {
                if (mSimPbAdnCache != null) {
                    mSimPbAdnCache.requestLoadAllAdnLike(response);
                    waitForResult(status);
                } else {
                    loge("Failure while trying to load from SIM due to uninit  sim pb adncache");
                }
            } else {
                if (mAdnCache != null) {
                    mAdnCache.requestLoadAllAdnLike(
                            efid, mAdnCache.extensionEfForEf(efid), response);
                    waitForResult(status);
                } else {
                    loge("Failure while trying to load from SIM due to uninitialised adncache");
                }
            }
        }
        return mRecords;
    }

    private boolean isSimPhoneBookEnabled() {
        if (mPhone.getContext().getResources().getBoolean(
            com.android.internal.R.bool.config_sim_phonebook_batch_operation)) {
            return true;
        }
        return false;
    }

    protected void checkThread() {
        if (!ALLOW_SIM_OP_IN_UI_THREAD) {
            // Make sure this isn't the UI thread, since it will block
            if (mBaseHandler.getLooper().equals(Looper.myLooper())) {
                loge("query() called on the main UI thread!");
                throw new IllegalStateException(
                        "You cannot call query on this provder from the main UI thread.");
            }
        }
    }

    protected void waitForResult(AtomicBoolean status) {
        while (!status.get()) {
            try {
                mLock.wait();
            } catch (InterruptedException e) {
                logd("interrupted while trying to update by search");
            }
        }
    }

    private int updateEfForIccType(int efid) {
        // Check if we are trying to read ADN records
        if (efid == IccConstants.EF_ADN) {
            if (mPhone.getCurrentUiccAppType() == AppType.APPTYPE_USIM) {
                return IccConstants.EF_PBR;
            }
        }
        return efid;
    }

    private String[] getStringArray(String str) {
        if (str != null) {
            return str.split(",");
        }
        return null;
    }

    private String[] getAnrStringArray(String str) {
        if (str != null) {
            return str.split(":");
        }
        return null;
    }

    /**
     * Get the capacity of ADN records
     *
     * @return  int[6] array
     *            capacity[0]  is the max count of ADN
     *            capacity[1]  is the used count of ADN
     *            capacity[2]  is the max count of EMAIL
     *            capacity[3]  is the used count of EMAIL
     *            capacity[4]  is the max count of ANR
     *            capacity[5]  is the used count of ANR
     */
    public int[] getAdnRecordsCapacity() {
        int capacity[] = new int[6];

        if (isSimPhoneBookEnabled()) {
            if (mSimPbAdnCache != null) {
                capacity[0] = mSimPbAdnCache.getAdnCount();
                capacity[1] = mSimPbAdnCache.getUsedAdnCount();
                capacity[2] = mSimPbAdnCache.getEmailCount();
                capacity[3] = mSimPbAdnCache.getUsedEmailCount();
                capacity[4] = mSimPbAdnCache.getAnrCount();
                capacity[5] = mSimPbAdnCache.getUsedAnrCount();
            } else {
                loge("mAdnCache is NULL when getAdnRecordsCapacity.");
            }
        }
        if (DBG) logd("getAdnRecordsCapacity: max adn=" + capacity[0]
            + ", used adn=" + capacity[1] + ", max email=" + capacity[2]
            + ", used email=" + capacity[3] + ", max anr=" + capacity[4]
            + ", used anr=" + capacity[5]);

        return capacity;
    }

}

