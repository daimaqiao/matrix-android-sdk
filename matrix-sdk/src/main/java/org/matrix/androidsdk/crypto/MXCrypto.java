/*
 * Copyright 2016 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.androidsdk.crypto;

import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonElement;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.crypto.algorithms.IMXDecrypting;
import org.matrix.androidsdk.crypto.algorithms.IMXEncrypting;
import org.matrix.androidsdk.crypto.algorithms.MXDecryptionResult;
import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXEncryptEventContentResult;
import org.matrix.androidsdk.crypto.data.MXKey;
import org.matrix.androidsdk.crypto.data.MXOlmSessionResult;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.data.cryptostore.IMXCryptoStore;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.EventContent;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.NewDeviceContent;
import org.matrix.androidsdk.rest.model.RoomKeyContent;
import org.matrix.androidsdk.rest.model.RoomMember;
import org.matrix.androidsdk.rest.model.crypto.KeysQueryResponse;
import org.matrix.androidsdk.rest.model.crypto.KeysUploadResponse;
import org.matrix.androidsdk.util.JsonUtils;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * A `MXCrypto` class instance manages the end-to-end crypto for a MXSession instance.
 *
 * Messages posted by the user are automatically redirected to MXCrypto in order to be encrypted
 * before sending.
 * In the other hand, received events goes through MXCrypto for decrypting.
 * MXCrypto maintains all necessary keys and their sharing with other devices required for the crypto.
 * Specially, it tracks all room membership changes events in order to do keys updates.
 */
public class MXCrypto {
    private static final String LOG_TAG = "MXCrypto";

    private static final long UPLOAD_KEYS_DELAY_MS = 10 * 60 * 1000;

    private static final int ONE_TIME_KEY_GENERATION_MAX_NUMBER = 5;

    // The Matrix session.
    private final MXSession mSession;

    // the crypto store
    public IMXCryptoStore mCryptoStore;

    // MXEncrypting instance for each room.
    private HashMap<String, IMXEncrypting> mRoomEncryptors;

    // A map from algorithm to MXDecrypting instance, for each room
    private HashMap<String, /* room id */
                HashMap<String /* algorithm */, IMXDecrypting>> mRoomDecryptors;

    // Our device keys
    private MXDeviceInfo mMyDevice;

    // The libolm wrapper.
    private MXOlmDevice mOlmDevice;

    private Map<String, Map<String, String>> mLastPublishedOneTimeKeys;

    // tell if the crypto is started
    private boolean mIsStarted;

    // Timer to periodically upload keys
    private Timer mUploadKeysTimer;

    // Map from userId -> deviceId -> roomId -> timestamp
    // to manage rate limiting for pinging devices
    private final MXUsersDevicesMap<HashMap<String, Long>> mLastNewDeviceMessageTsByUserDeviceRoom;

    private final MXEventListener mEventListener = new MXEventListener() {
        @Override
        public void onToDeviceEvent(Event event) {
            MXCrypto.this.onToDeviceEvent(event);
        }

        @Override
        public void onLiveEvent(Event event, RoomState roomState) {
            if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_MESSAGE_ENCRYPTION)) {
                onCryptoEvent(event);
            } else if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_STATE_ROOM_MEMBER)) {
                onRoomMembership(event);
            }
        }
    };

    /**
     * Constructor
     * @param matrixSession the session
     * @param cryptoStore the crypto store
     */
    public MXCrypto(MXSession matrixSession, IMXCryptoStore cryptoStore) {
        mSession = matrixSession;
        mCryptoStore = cryptoStore;

        mOlmDevice = new MXOlmDevice(mCryptoStore);
        mRoomEncryptors = new HashMap<>();
        mRoomDecryptors = new HashMap<>();

        String deviceId = mSession.getCredentials().deviceId;

        if (TextUtils.isEmpty(deviceId)) {
            // use the stored one
            mSession.getCredentials().deviceId = deviceId = mCryptoStore.getDeviceId();
        }

        if (TextUtils.isEmpty(deviceId)) {
            mSession.getCredentials().deviceId = deviceId = UUID.randomUUID().toString();
            Log.d(LOG_TAG, "Warning: No device id in MXCredentials. An id was created. Think of storing it");
            mCryptoStore.storeDeviceId(deviceId);
        }

        mMyDevice = new MXDeviceInfo(deviceId);
        mMyDevice.userId = mSession.getMyUserId();
        HashMap<String, String> keys = new HashMap<>();

        if (!TextUtils.isEmpty(mOlmDevice.getDeviceEd25519Key())) {
            keys.put("ed25519:" + mSession.getCredentials().deviceId, mOlmDevice.getDeviceEd25519Key());
        }

        if (!TextUtils.isEmpty(mOlmDevice.getDeviceCurve25519Key())) {
            keys.put("curve25519:" + mSession.getCredentials().deviceId, mOlmDevice.getDeviceCurve25519Key());
        }

        mMyDevice.keys = keys;

        mMyDevice.algorithms = MXCryptoAlgorithms.sharedAlgorithms().supportedAlgorithms();
        mMyDevice.mVerified = MXDeviceInfo.DEVICE_VERIFICATION_VERIFIED;

        // Add our own deviceinfo to the store
        Map<String, MXDeviceInfo> endToEndDevicesForUser = mCryptoStore.devicesForUser(mSession.getMyUserId());

        HashMap<String, MXDeviceInfo> myDevices;

        if (null != endToEndDevicesForUser) {
            myDevices = new HashMap<>(endToEndDevicesForUser);
        } else {
            myDevices = new HashMap<>();
        }

        myDevices.put(mMyDevice.deviceId, mMyDevice);

        mCryptoStore.storeDevicesForUser(mSession.getMyUserId(), myDevices);
        mSession.getDataHandler().setCryptoEventsListener(mEventListener);
        mLastNewDeviceMessageTsByUserDeviceRoom = new MXUsersDevicesMap<>();
    }

    /**
     * The MXSession is paused.
     */
    public void pause() {
        stopUploadKeysTimer();
    }

    /**
     * The MXSession is resumed.
     */
    public void resume() {
        if (mIsStarted) {
            startUploadKeysTimer(false);
        }
    }

    /**
     * Stop the upload keys timer
     */
    private void stopUploadKeysTimer() {
        if (null != mUploadKeysTimer) {
            mUploadKeysTimer.cancel();
            mUploadKeysTimer = null;
        }
    }

    /**
     * @return true if some saved data is corrupted
     */
    public boolean isCorrupted() {
        return mCryptoStore.isCorrupted();
    }

    /**
     * Start the timer to periodically upload the keys
     * @param delayed true when the keys upload must be delayed
     */
    private void startUploadKeysTimer(boolean delayed) {
        mUploadKeysTimer = new Timer();
        mUploadKeysTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // check race conditions while logging out
                if (null != mMyDevice) {
                    uploadKeys(ONE_TIME_KEY_GENERATION_MAX_NUMBER, new ApiCallback<Void>() {
                        @Override
                        public void onSuccess(Void info) {
                            Log.d(LOG_TAG, "## startUploadKeysTimer() : uploaded");
                        }

                        @Override
                        public void onNetworkError(Exception e) {
                            Log.e(LOG_TAG, "## startUploadKeysTimer() : failed " + e.getMessage());
                        }

                        @Override
                        public void onMatrixError(MatrixError e) {
                            Log.e(LOG_TAG, "## startUploadKeysTimer() : failed " + e.getMessage());
                        }

                        @Override
                        public void onUnexpectedError(Exception e) {
                            Log.e(LOG_TAG, "## startUploadKeysTimer() : failed " + e.getMessage());
                        }
                    });
                }
            }
        }, delayed ? UPLOAD_KEYS_DELAY_MS : 0, UPLOAD_KEYS_DELAY_MS);
    }

    /**
     * Tell if the MXCrypto is started
     * @return true if the crypto is started
     */
    public boolean isIsStarted() {
        return mIsStarted;
    }

    /**
     * Start the crypto module.
     * Device keys will be uploaded, then one time keys if there are not enough on the homeserver
     * and, then, if this is the first time, this new device will be announced to all other users
     * devices.
     * @param callback the asynchrous callback
     */
    public void start(final ApiCallback<Void> callback) {
        uploadKeys(5, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
                Log.d(LOG_TAG, "###########################################################");
                Log.d(LOG_TAG, "uploadKeys done for " + mSession.getMyUserId());
                Log.d(LOG_TAG, "   - device id  : " +  mSession.getCredentials().deviceId);
                Log.d(LOG_TAG, "  - ed25519    : " + mOlmDevice.getDeviceEd25519Key());
                Log.d(LOG_TAG, "   - curve25519 : " + mOlmDevice.getDeviceCurve25519Key());
                Log.d(LOG_TAG, "  - oneTimeKeys: "  + mLastPublishedOneTimeKeys);     // They are
                Log.d(LOG_TAG, "");

                checkDeviceAnnounced(new ApiCallback<Void>() {
                    @Override
                    public void onSuccess(Void info) {
                        mIsStarted = true;
                        startUploadKeysTimer(true);

                        if (null != callback) {
                            callback.onSuccess(null);
                        }
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        if (null != callback) {
                            callback.onNetworkError(e);
                        }
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        if (null != callback) {
                            callback.onMatrixError(e);
                        }
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        if (null != callback) {
                            callback.onUnexpectedError(e);
                        }
                    }
                });
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG, "## uploadKeys : failed " + e.getMessage());
                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG, "## uploadKeys : failed " + e.getMessage());
                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG, "## uploadKeys : failed " + e.getMessage());
                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    /**
     * Close the crypto
     */
    public void close() {
        mSession.getDataHandler().removeListener(mEventListener);

        if (null != mOlmDevice) {
            mOlmDevice.release();
            mOlmDevice = null;
        }

        mRoomDecryptors = null;
        mRoomEncryptors = null;
        mMyDevice = null;

        stopUploadKeysTimer();

        mCryptoStore.close();
        mCryptoStore = null;
    }

    /**
     * @return teh olmdevice instance
     */
    public MXOlmDevice getOlmDevice() {
        return mOlmDevice;
    }

    /**
     * Upload the device keys to the homeserver and ensure
     * that the homeserver has enough one-time keys.
     * @param maxKeys The maximum number of keys to generate.
     * @param callback the asynchronous callback
     */
    public void uploadKeys(final int maxKeys, final ApiCallback<Void> callback) {
        uploadDeviceKeys(new ApiCallback<KeysUploadResponse>() {

            @Override
            public void onSuccess(KeysUploadResponse keysUploadResponse) {
                // We need to keep a pool of one time public keys on the server so that
                // other devices can start conversations with us. But we can only store
                // a finite number of private keys in the olm Account object.
                // To complicate things further then can be a delay between a device
                // claiming a public one time key from the server and it sending us a
                // message. We need to keep the corresponding private key locally until
                // we receive the message.
                // But that message might never arrive leaving us stuck with duff
                // private keys clogging up our local storage.
                // So we need some kind of enginering compromise to balance all of
                // these factors.

                // We first find how many keys the server has for us.
                int keyCount  = keysUploadResponse.oneTimeKeyCountsForAlgorithm("signed_curve25519");

                // We then check how many keys we can store in the Account object.
                float maxOneTimeKeys = mOlmDevice.maxNumberOfOneTimeKeys();

                // Try to keep at most half that number on the server. This leaves the
                // rest of the slots free to hold keys that have been claimed from the
                // server but we haven't recevied a message for.
                // If we run out of slots when generating new keys then olm will
                // discard the oldest private keys first. This will eventually clean
                // out stale private keys that won't receive a message.
                int keyLimit = (int)Math.floor(maxOneTimeKeys / 2.0);

                // We work out how many new keys we need to create to top up the server
                // If there are too many keys on the server then we don't need to
                // create any more keys.
                int numberToGenerate = Math.max(keyLimit - keyCount, 0);

                if (maxKeys > 0) {
                    // Creating keys can be an expensive operation so we limit the
                    // number we generate in one go to avoid blocking the application
                    // for too long.
                    numberToGenerate = Math.min(numberToGenerate, maxKeys);

                    // Ask olm to generate new one time keys, then upload them to synapse.
                    mOlmDevice.generateOneTimeKeys(numberToGenerate);

                    uploadOneTimeKeys(new ApiCallback<KeysUploadResponse>() {
                        @Override
                        public void onSuccess(KeysUploadResponse info) {
                            if (null != callback) {
                                callback.onSuccess(null);
                            }
                        }

                        @Override
                        public void onNetworkError(Exception e) {
                            if (null != callback) {
                                callback.onNetworkError(e);
                            }
                        }

                        @Override
                        public void onMatrixError(MatrixError e) {
                            if (null != callback) {
                                callback.onMatrixError(e);
                            }
                        }

                        @Override
                        public void onUnexpectedError(Exception e) {
                            if (null != callback) {
                                callback.onUnexpectedError(e);
                            }
                        }
                    });
                } else {
                    // If we don't need to generate any keys then we are done.
                    if (null != callback) {
                        callback.onSuccess(null);
                    }
                }
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG, "## uploadKeys() : onNetworkError " + e.getMessage());

                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG, "## uploadKeys() : onMatrixError " + e.getLocalizedMessage());

                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG, "## uploadKeys() : onUnexpectedError " + e.getMessage());

                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    /**
     * Download the device keys for a list of users and stores the keys in the MXStore.
     * @param userIds The users to fetch.
     * @param forceDownload Always download the keys even if cached.
     * @param callback the asynchronous callback
     */
    public void downloadKeys(List<String> userIds, boolean forceDownload, final ApiCallback<MXUsersDevicesMap<MXDeviceInfo>> callback) {
        // Map from userid -> deviceid -> DeviceInfo
        final MXUsersDevicesMap<MXDeviceInfo> stored = new MXUsersDevicesMap<>();

        // List of user ids we need to download keys for
        final ArrayList<String> downloadUsers = new ArrayList<>();

        if (null != userIds) {
            for (String userId : userIds) {

                Map<String, MXDeviceInfo> devices = mCryptoStore.devicesForUser(userId);
                boolean isEmpty = (null == devices) || (devices.size() == 0);

                if (!isEmpty) {
                    stored.setObjects(devices, userId);
                }

                if (isEmpty || forceDownload) {
                    downloadUsers.add(userId);
                }
            }
        }

        if (0 == downloadUsers.size()) {
            if (null != callback) {
                callback.onSuccess(stored);
            }
        } else {
            // Download
            mSession.getCryptoRestClient().downloadKeysForUsers(downloadUsers, new ApiCallback<KeysQueryResponse>() {
                @Override
                public void onSuccess(KeysQueryResponse keysQueryResponse) {
                    MXUsersDevicesMap<MXDeviceInfo> deviceKeys = new MXUsersDevicesMap<>(keysQueryResponse.deviceKeys);

                    for (String userId : deviceKeys.userIds()) {
                        HashMap<String, MXDeviceInfo> devices;

                        if (deviceKeys.getMap().containsKey(userId)) {
                            devices = new HashMap<>(deviceKeys.getMap().get(userId));
                        } else {
                            devices = new HashMap<>();
                        }

                        ArrayList<String> deviceIds = new ArrayList<>(devices.keySet());

                        for (String deviceId : deviceIds) {
                            // Get the potential previously store device keys for this device
                            MXDeviceInfo previouslyStoredDeviceKeys = stored.objectForDevice(deviceId, userId);

                            // Validate received keys
                            if (!validateDeviceKeys(devices.get(deviceId), userId, deviceId, previouslyStoredDeviceKeys)) {
                                // New device keys are not valid. Do not store them
                                devices.remove(deviceId);

                                if (null != previouslyStoredDeviceKeys) {
                                    // But keep old validated ones if any
                                    devices.put(deviceId, previouslyStoredDeviceKeys);
                                }
                            } else if (null != previouslyStoredDeviceKeys) {
                                // The verified status is not sync'ed with hs.
                                // This is a client side information, valid only for this client.
                                // So, transfer its previous value
                                if (devices.containsKey(deviceId)) {
                                    devices.get(deviceId).mVerified = previouslyStoredDeviceKeys.mVerified;
                                }
                            }
                        }

                        // Update the store. Note
                        mCryptoStore.storeDevicesForUser(userId, devices);

                        // And the response result
                        stored.setObjects(devices, userId);
                    }

                    if (null != callback) {
                        callback.onSuccess(stored);
                    }
                }

                @Override
                public void onNetworkError(Exception e) {
                    Log.e(LOG_TAG, "## downloadKeys() : onNetworkError " + e.getMessage());
                    if (null != callback) {
                        callback.onNetworkError(e);
                    }
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    Log.e(LOG_TAG, "## downloadKeys() : onMatrixError " + e.getLocalizedMessage());
                    if (null != callback) {
                        callback.onMatrixError(e);
                    }
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    Log.e(LOG_TAG, "## downloadKeys() : onUnexpectedError " + e.getMessage());
                    if (null != callback) {
                        callback.onUnexpectedError(e);
                    }
                }
            });
        }
    }

    /**
     * Get the stored device keys for a user.
     * @param userId the user to list keys for.
     * @return the list of devices.
     */
    public List<MXDeviceInfo> storedDevicesForUser(String userId) {
        Map<String, MXDeviceInfo> map = mCryptoStore.devicesForUser(userId);

        if (null == map) {
            return null;
        } else {
            return new ArrayList<>(map.values());
        }
    }

    /**
     * Find a device by curve25519 identity key
     * @param userId the owner of the device.
     * @param algorithm the encryption algorithm.
     * @param senderKey the curve25519 key to match.
     * @return the device info.
     */
    public MXDeviceInfo deviceWithIdentityKey(String senderKey, String userId, String algorithm) {
        if (!TextUtils.equals(algorithm, MXCryptoAlgorithms.MXCRYPTO_ALGORITHM_MEGOLM) && !TextUtils.equals(algorithm, MXCryptoAlgorithms.MXCRYPTO_ALGORITHM_OLM)) {
            // We only deal in olm keys
            return null;
        }

        if (!TextUtils.isEmpty(userId)) {
            List<MXDeviceInfo> devices = storedDevicesForUser(userId);

            if (null != devices) {
                for (MXDeviceInfo device : devices) {
                    Set<String> keys = device.keys.keySet();

                    for (String keyId : keys) {
                        if (keyId.startsWith("curve25519:")) {
                            if (TextUtils.equals(senderKey, device.keys.get(keyId))) {
                                return device;
                            }
                        }
                    }
                }
            }
        }

        // Doesn't match a known device
        return null;
    }

    /**
     * Provides the device information for a device id and an user Id
     * @param userId the user id
     * @param deviceId the device id
     * @return the device info if it exists
     */
    public MXDeviceInfo getDeviceInfo(String userId, String deviceId) {
        MXDeviceInfo di = null;

        if (!TextUtils.isEmpty(userId) &&  !TextUtils.isEmpty(deviceId)) {
            di = mCryptoStore.deviceWithDeviceId(deviceId, userId);
        }

        return di;
    }

    /**
     * Update the blocked/verified state of the given device
     * @param verificationStatus the new verification status.
     * @param deviceId the unique identifier for the device.
     * @param userId the owner of the device.
     */
    public void setDeviceVerification(int verificationStatus, String deviceId, String userId) {
        MXDeviceInfo device = mCryptoStore.deviceWithDeviceId(deviceId, userId);

        // Sanity check
        if (null == device) {
            Log.e(LOG_TAG, "## setDeviceVerification() : Unknown device " +  userId + ":" + deviceId);
            return;
        }

        if (device.mVerified != verificationStatus) {
            device.mVerified = verificationStatus;
            mCryptoStore.storeDeviceForUser(userId, device);

            Collection<Room> rooms = mSession.getDataHandler().getStore().getRooms();

            for(Room room : rooms) {
                IMXEncrypting alg = mRoomEncryptors.get(room.getRoomId());

                if (null != alg) {
                    alg.onDeviceVerificationStatusUpdate(userId, deviceId);
                }
            }
        }
    }

    /**
     * Configure a room to use encryption.
     * @param roomId the room id to enable encryption in.
     * @param algorithm the encryption config for the room.
     * @return true if the operation succeeds.
     */
    private boolean setEncryptionInRoom(String roomId, String algorithm) {
        // If we already have encryption in this room, we should ignore this event
        // (for now at least. Maybe we should alert the user somehow?)
        String existingAlgorithm = mCryptoStore.algorithmForRoom(roomId);

        if (!TextUtils.isEmpty(existingAlgorithm) && !TextUtils.equals(existingAlgorithm, algorithm)) {
            Log.e(LOG_TAG, "## setEncryptionInRoom() : Ignoring m.room.encryption event which requests a change of config in " + roomId);
            return false;
        }

        Class<IMXEncrypting> encryptingClass = MXCryptoAlgorithms.sharedAlgorithms().encryptorClassForAlgorithm(algorithm);

        if (null == encryptingClass) {
            Log.e(LOG_TAG, "## setEncryptionInRoom() : Unable to encrypt with " + algorithm);
            return false;
        }

        mCryptoStore.storeAlgorithmForRoom(roomId, algorithm);

        IMXEncrypting alg;

        try {
            Constructor<?> ctor = encryptingClass.getConstructors()[0];
            alg = (IMXEncrypting)ctor.newInstance(new Object[]{});
        } catch (Exception e) {
            Log.e(LOG_TAG, "## setEncryptionInRoom() : fail to load the class");
            return false;
        }

        alg.initWithMatrixSession(mSession, roomId);
        mRoomEncryptors.put(roomId, alg);

        return true;
    }

    /**
     * Tells if a room is encrypted
     * @param roomId the room id
     * @return true if the room is encrypted
     */
    public boolean isRoomEncrypted(String roomId) {
        boolean res = false;

        if (null != roomId) {
            res = mRoomEncryptors.containsKey(roomId);

            if (!res) {
                Room room = mSession.getDataHandler().getRoom(roomId);

                if (null != room) {
                    res = room.getLiveState().isEncrypted();
                }
            }
        }

        return res;
    }

    /**
     * Try to make sure we have established olm sessions for the given users.
     * @param users a list of user ids.
     * @param callback the asynchronous callback
     */
    public void ensureOlmSessionsForUsers(List<String> users, final ApiCallback<MXUsersDevicesMap<MXOlmSessionResult>> callback) {
        ArrayList<MXDeviceInfo> devicesWithoutSession = new ArrayList<>();

        final MXUsersDevicesMap<MXOlmSessionResult> results = new MXUsersDevicesMap<>();

        if (null != users) {
            for (String userId : users) {
                List<MXDeviceInfo> devices = storedDevicesForUser(userId);

                if (null != devices) {
                    for (MXDeviceInfo device : devices) {
                        String key = device.identityKey();

                        if (TextUtils.equals(key, mOlmDevice.getDeviceCurve25519Key())) {
                            // Don't bother setting up session to ourself
                            continue;
                        }

                        if (device.mVerified == MXDeviceInfo.DEVICE_VERIFICATION_BLOCKED) {
                            // Don't bother setting up sessions with blocked users
                            continue;
                        }

                        String sessionId = mOlmDevice.sessionIdForDevice(key);

                        if (null == sessionId) {
                            devicesWithoutSession.add(device);
                        }

                        MXOlmSessionResult olmSessionResult = new MXOlmSessionResult(device, sessionId);
                        results.setObject(olmSessionResult, device.userId, device.deviceId);
                    }
                }
            }
        }

        if (0 == devicesWithoutSession.size()) {
            // No need to get session from the homeserver
            if (null != callback) {
                callback.onSuccess(results);
            }
            return;
        }

        // Prepare the request for claiming one-time keys
        MXUsersDevicesMap<String> usersDevicesToClaim = new MXUsersDevicesMap<>();

        final String oneTimeKeyAlgorithm = MXKey.KEY_SIGNED_CURVE_25519_TYPE;

        for (MXDeviceInfo device : devicesWithoutSession){
            usersDevicesToClaim.setObject(oneTimeKeyAlgorithm, device.userId, device.deviceId);
        }

        // TODO: this has a race condition - if we try to send another message
        // while we are claiming a key, we will end up claiming two and setting up
        // two sessions.
        //
        // That should eventually resolve itself, but it's poor form.

        Log.d(LOG_TAG, "## claimOneTimeKeysForUsersDevices() : " + usersDevicesToClaim);

        mSession.getCryptoRestClient().claimOneTimeKeysForUsersDevices(usersDevicesToClaim, new ApiCallback<MXUsersDevicesMap<MXKey>>() {
            @Override
            public void onSuccess(MXUsersDevicesMap<MXKey> oneTimeKeys) {
                Log.d(LOG_TAG, "## claimOneTimeKeysForUsersDevices() : keysClaimResponse.oneTimeKeys: " + oneTimeKeys);

                if ((null != oneTimeKeys) && (null != oneTimeKeys.userIds())) {
                    boolean hasNewOutboundSession = false;

                    ArrayList<String> userIds = new ArrayList<>(oneTimeKeys.userIds());

                    for (String userId : userIds) {
                        Set<String> deviceIds = oneTimeKeys.deviceIdsForUser(userId);

                        if (null != deviceIds) {
                            for (String deviceId : deviceIds) {
                                MXKey key = oneTimeKeys.objectForDevice(deviceId, userId);

                                if ((null != key) &&
                                        (null != key.signatures) &&
                                        key.signatures.containsKey(userId) &&
                                        TextUtils.equals(key.type, oneTimeKeyAlgorithm)) {

                                    String signKeyId = "ed25519:" + deviceId;
                                    String signature = key.signatureForUserId(userId, signKeyId);

                                    if (TextUtils.isEmpty(signature)) {
                                        Log.e(LOG_TAG, "## claimOneTimeKeysForUsersDevices() : no signature for userid " + userId + " deviceId " + deviceId);
                                    } else {
                                        // Update the result for this device in results
                                        MXOlmSessionResult olmSessionResult = results.objectForDevice(deviceId, userId);
                                        MXDeviceInfo device = olmSessionResult.mDevice;

                                        String signKey = device.keys.get(signKeyId);

                                        if (mOlmDevice.verifySignature(signKey, key.signalableJSONDictionary(), signature)) {
                                            hasNewOutboundSession = true;
                                            olmSessionResult.mSessionId = mOlmDevice.createOutboundSession(device.identityKey(), key.value);
                                            Log.d(LOG_TAG, "Started new sessionid " + olmSessionResult.mSessionId + " for device " + device);
                                        } else {
                                            Log.e(LOG_TAG, "## claimOneTimeKeysForUsersDevices() : Unable to verify signature on device " + userId + ":" + deviceId);
                                        }
                                    }
                                } else {
                                    Log.d(LOG_TAG, "No valid one-time keys for device " + userId + " : " + deviceId);
                                }
                            }
                        }
                    }

                    if (hasNewOutboundSession) {
                        mCryptoStore.flushSessions();
                    }
                }

                if (null != callback) {
                    callback.onSuccess(results);
                }
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG,"## ensureOlmSessionsForUsers(): claimOneTimeKeysForUsersDevices request failed" + e.getMessage());

                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG,"## ensureOlmSessionsForUsers(): claimOneTimeKeysForUsersDevices request failed" + e.getLocalizedMessage());

                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG,"## ensureOlmSessionsForUsers(): claimOneTimeKeysForUsersDevices request failed" + e.getMessage());

                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    /**
     * Encrypt an event content according to the configuration of the room.
     * @param eventContent the content of the event.
     * @param eventType the type of the event.
     * @param room the room the event will be sent.
     * @param callback the asynchronous callback
     */
    public void encryptEventContent(JsonElement eventContent, String eventType, Room room, final ApiCallback<MXEncryptEventContentResult> callback) {
        IMXEncrypting alg = mRoomEncryptors.get(room.getRoomId());

        if (null == alg) {
            String algorithm = room.getLiveState().encryptionAlgorithm();

            if (null != algorithm) {
                if (setEncryptionInRoom(room.getRoomId(), algorithm)) {
                    alg = mRoomEncryptors.get(room.getRoomId());
                }
            }
        }

        if (null != alg) {
            alg.encryptEventContent(eventContent, eventType, room, new ApiCallback<JsonElement>() {
                @Override
                public void onSuccess(JsonElement encryptedContent) {
                    if (null != callback) {
                        callback.onSuccess(new MXEncryptEventContentResult(encryptedContent, Event.EVENT_TYPE_MESSAGE_ENCRYPTED));
                    }
                }

                @Override
                public void onNetworkError(Exception e) {
                    if (null != callback) {
                        callback.onNetworkError(e);
                    }
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    if (null != callback) {
                        callback.onMatrixError(e);
                    }
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    if (null != callback) {
                        callback.onUnexpectedError(e);
                    }
                }
            });
        } else {
            String reason = String.format(MXCryptoError.UNABLE_TO_ENCRYPT_REASON, room.getLiveState().encryptionAlgorithm());

            Log.e(LOG_TAG, "## encryptEventContent() : " + reason);

            if (null != callback) {
                callback.onMatrixError(new MXCryptoError(MXCryptoError.UNABLE_TO_ENCRYPT_ERROR_CODE, reason));
                callback.onSuccess(new MXEncryptEventContentResult(eventContent, eventType));
            }
        }
    }

    /**
     * Decrypt a received event
     * @param event the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return true if the decryption was successful.
     */
    public boolean decryptEvent(Event event, String timeline) {
        if (null == event) {
            Log.e(LOG_TAG, "## decryptEvent : null event");
            return false;
        }

        EventContent eventContent = event.getWireEventContent();

        if (null == eventContent) {
            Log.e(LOG_TAG, "## decryptEvent : empty event content");
            return false;
        }

        IMXDecrypting alg = getRoomDecryptor(event.roomId, eventContent.algorithm);

        if (null == alg) {
            String reason = String.format(MXCryptoError.UNABLE_TO_DECRYPT_REASON, event.eventId, eventContent.algorithm);

            Log.e(LOG_TAG, "## decryptEvent() : " + reason);

            event.setCryptoError(new MXCryptoError(MXCryptoError.UNABLE_TO_DECRYPT_ERROR_CODE, reason));
            return false;
        }

        boolean result = alg.decryptEvent(event, timeline);

        if (!result) {
            Log.e(LOG_TAG, "## decryptEvent() : failed " + event.getCryptoError().toString());
        }

        return result;
    }

    /**
     * Encrypt an event payload for a list of devices.
     * @param payloadFields fields to include in the encrypted payload.
     * @param deviceInfos list of device infos to encrypt for.
     * @return the content for an m.room.encrypted event.
     */
    public Map<String, Object> encryptMessage(Map<String, Object> payloadFields, List<MXDeviceInfo> deviceInfos) {
        ArrayList<String> participantKeys = new ArrayList<>();
        HashMap<String, MXDeviceInfo> deviceInfoParticipantKey = new HashMap<>();

        for(MXDeviceInfo di : deviceInfos) {
            participantKeys.add(di.identityKey());
            deviceInfoParticipantKey.put(di.identityKey(), di);
        }

        HashMap<String, Object> payloadJson = new HashMap<>(payloadFields);

        payloadJson.put("sender", mSession.getMyUserId());
        payloadJson.put("sender_device", mSession.getCredentials().deviceId);

        // Include the Ed25519 key so that the recipient knows what
        // device this message came from.
        // We don't need to include the curve25519 key since the
        // recipient will already know this from the olm headers.
        // When combined with the device keys retrieved from the
        // homeserver signed by the ed25519 key this proves that
        // the curve25519 key and the ed25519 key are owned by
        // the same device.
        HashMap<String, String> keysMap = new HashMap<>();
        keysMap.put("ed25519", mOlmDevice.getDeviceEd25519Key());
        payloadJson.put("keys", keysMap);

        HashMap<String, Object> ciphertext = new HashMap<>();

        for (String deviceKey : participantKeys) {
            String sessionId = mOlmDevice.sessionIdForDevice(deviceKey);

            if (!TextUtils.isEmpty(sessionId)) {
                Log.d(LOG_TAG, "Using sessionid " + sessionId + " for device " + deviceKey);
                MXDeviceInfo deviceInfo = deviceInfoParticipantKey.get(deviceKey);

                payloadJson.put("recipient", deviceInfo.userId);

                HashMap<String, String> recipientsKeysMap = new HashMap<>();
                recipientsKeysMap.put("ed25519", deviceInfo.fingerprint());
                payloadJson.put("recipient_keys", recipientsKeysMap);


                String payloadString = JsonUtils.convertToUTF8(JsonUtils.canonicalize(JsonUtils.getGson(false).toJsonTree(payloadJson)).toString());
                ciphertext.put(deviceKey, mOlmDevice.encryptMessage(deviceKey, sessionId, payloadString));
            }
        }

        HashMap<String, Object> res = new HashMap<>();

        res.put("algorithm", MXCryptoAlgorithms.MXCRYPTO_ALGORITHM_OLM);
        res.put("sender_key", mOlmDevice.getDeviceCurve25519Key());
        res.put("ciphertext", ciphertext);

        return res;
    }

    /**
     * Announce the device to the server.
     * @param callback the asynchronous callback.
     */
    private void checkDeviceAnnounced(final ApiCallback<Void> callback) {
        if (mCryptoStore.deviceAnnounced()) {
            if (null != callback) {
                callback.onSuccess(null);
            }
            return;
        }

        // We need to tell all the devices in all the rooms we are members of that
        // we have arrived.
        // Build a list of rooms for each user.
        HashMap<String, ArrayList<String>> roomsByUser = new HashMap<>();

        ArrayList<Room> rooms = new ArrayList<>(mSession.getDataHandler().getStore().getRooms());

        for (Room room : rooms) {
            // Check for rooms with encryption enabled
            if (!room.getLiveState().isEncrypted()) {
                continue;
            }

            // Ignore any rooms which we have left
            RoomMember me = room.getMember(mSession.getMyUserId());

            if ((null == me) || (!TextUtils.equals(me.membership, RoomMember.MEMBERSHIP_JOIN) && !TextUtils.equals(me.membership, RoomMember.MEMBERSHIP_INVITE))) {
                continue;
            }

            Collection<RoomMember> members = room.getLiveState().getMembers();

            for (RoomMember r : members) {
                ArrayList<String> roomIds = roomsByUser.get(r.getUserId());

                if (null == roomIds) {
                    roomIds = new ArrayList<>();
                    roomsByUser.put(r.getUserId(), roomIds);
                }

                roomIds.add(room.getRoomId());
            }
        }

        // Build a per-device message for each user
        MXUsersDevicesMap<Map<String, Object>> contentMap = new MXUsersDevicesMap<>();

        for (String userId : roomsByUser.keySet()) {
            HashMap<String, Map<String, Object>> map = new HashMap<>();

            HashMap<String, Object> submap = new HashMap<>();
            submap.put("device_id", mMyDevice.deviceId);
            submap.put("rooms", roomsByUser.get(userId));

            map.put("*", submap);

            contentMap.setObjects(map, userId);
        }

        if (contentMap.userIds().size() > 0) {
            mSession.getCryptoRestClient().sendToDevice(Event.EVENT_TYPE_NEW_DEVICE, contentMap, new ApiCallback<Void>() {
                @Override
                public void onSuccess(Void info) {
                    Log.d(LOG_TAG, "## checkDeviceAnnounced Annoucements done");
                    mCryptoStore.storeDeviceAnnounced();

                    if (null != callback) {
                        callback.onSuccess(null);
                    }
                }

                @Override
                public void onNetworkError(Exception e) {
                    Log.e(LOG_TAG, "## checkDeviceAnnounced() : failed " + e.getMessage());
                    if (null != callback) {
                        callback.onNetworkError(e);
                    }
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    Log.e(LOG_TAG, "## checkDeviceAnnounced() : failed " + e.getMessage());
                    if (null != callback) {
                        callback.onMatrixError(e);
                    }
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    Log.e(LOG_TAG, "## checkDeviceAnnounced() : failed " + e.getMessage());
                    if (null != callback) {
                        callback.onUnexpectedError(e);
                    }
                }
            });
        }

        mCryptoStore.storeDeviceAnnounced();
        if (null != callback) {
            callback.onSuccess(null);
        }
    }

    /**
     * Handle the 'toDevice' event
     * @param event the event
     */
    private void onToDeviceEvent(Event event) {
        if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_ROOM_KEY)) {
            onRoomKeyEvent(event);
        } else if (TextUtils.equals(event.getType(), Event.EVENT_TYPE_NEW_DEVICE)) {
            onNewDeviceEvent(event);
        }
    }

    /**
     * Handle a key event.
     * @param event the key event.
     */
    private void onRoomKeyEvent(Event event) {
        // sanity check
        if (null == event) {
            Log.e(LOG_TAG, "## onRoomKeyEvent() : null event");
            return;
        }

        RoomKeyContent roomKeyContent = JsonUtils.toRoomKeyContent(event.getContentAsJsonObject());

        String roomId = roomKeyContent.room_id;
        String algorithm = roomKeyContent.algorithm;

        if (TextUtils.isEmpty(roomId) || TextUtils.isEmpty(algorithm)) {
            Log.e(LOG_TAG, "## onRoomKeyEvent() : missing fields");
            return;
        }

        IMXDecrypting alg = getRoomDecryptor(roomId, algorithm);

        if (null == alg) {
            Log.e(LOG_TAG, "## onRoomKeyEvent() : Unable to handle keys for " + algorithm);
            return;
        }

        alg.onRoomKeyEvent(event);
    }

    /**
     * Called when a new device announces itself.
     * @param event the announcement event.
     */
    private void onNewDeviceEvent(final Event event) {
        final String userId = event.sender;
        final NewDeviceContent newDeviceContent = JsonUtils.toNewDeviceContent(event.getContent());

        if ((null == newDeviceContent.rooms) || (null == newDeviceContent.deviceId)) {
            Log.e(LOG_TAG, "## onNewDeviceEvent() : new_device event missing keys");
            return;
        }

        Log.d(LOG_TAG, "## onNewDeviceEvent() : m.new_device event from " + userId + ":" + newDeviceContent.deviceId + "for rooms " + newDeviceContent.rooms);

        ArrayList<String> userIds = new ArrayList<>();
        userIds.add(userId);

        downloadKeys(userIds, true, new ApiCallback<MXUsersDevicesMap<MXDeviceInfo>>() {
            @Override
            public void onSuccess(MXUsersDevicesMap<MXDeviceInfo> usersDevicesInfoMap) {
                for(String roomId : newDeviceContent.rooms) {
                    IMXEncrypting encrypting = mRoomEncryptors.get(roomId);

                    if (null != encrypting) {
                        // The room is encrypted, report the new device to it
                        encrypting.onNewDevice(newDeviceContent.deviceId, userId);
                    }
                }
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG, "## onNewDeviceEvent() : onNetworkError " + e.getMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG, "## onNewDeviceEvent() : onMatrixError " + e.getLocalizedMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG, "## onNewDeviceEvent() : onUnexpectedError " + e.getMessage());
            }
        });
    }

    /**
     * Handle an m.room.encryption event.
     * @param event the encryption event.
     */
    private void onCryptoEvent(Event event){
        EventContent eventContent = event.getWireEventContent();
        setEncryptionInRoom(event.roomId, eventContent.algorithm);
    }

    /**
     * Handle a change in the membership state of a member of a room.
     * @param event the membership event causing the change
     */
    private void onRoomMembership(Event event) {
        IMXEncrypting alg = mRoomEncryptors.get(event.roomId);

        if (null == alg) {
            // No encrypting in this room
            return;
        }

        String userId = event.stateKey;

        RoomMember roomMember = mSession.getDataHandler().getRoom(event.roomId).getLiveState().getMember(userId);

        if (null != roomMember) {
            RoomMember prevRoomMember = JsonUtils.toRoomMember(event.prev_content);
            alg.onRoomMembership(event, roomMember, (null != prevRoomMember) ? prevRoomMember.membership : null);
        } /*else {
            Log.e(LOG_TAG, "## onRoomMembership() : Error cannot find the room member in event: " + event);
        }*/
    }

    /**
     * Upload my user's device keys.
     * @param callback the asynchronous callback
     */
    private void uploadDeviceKeys(ApiCallback<KeysUploadResponse> callback) {
        // Prepare the device keys data to send
        // Sign it
        String signature = mOlmDevice.signJSON(mMyDevice.signalableJSONDictionary());

        HashMap<String, String> submap = new HashMap<>();
        submap.put("ed25519:" + mMyDevice.deviceId,  signature);

        HashMap<String, Map<String, String> > map = new HashMap<>();
        map.put(mSession.getMyUserId(), submap);

        mMyDevice.signatures = map;

        // For now, we set the device id explicitly, as we may not be using the
        // same one as used in login.
        mSession.getCryptoRestClient().uploadKeys(mMyDevice.JSONDictionary(), null, mMyDevice.deviceId, callback);
      }

    /**
     * Upload my user's one time keys.
     * @param callback the asynchronous callback
     */
    private void uploadOneTimeKeys(final ApiCallback<KeysUploadResponse> callback) {
        final Map<String, Map<String, String>>  oneTimeKeys = mOlmDevice.oneTimeKeys();
        HashMap<String, Object> oneTimeJson = new HashMap<>();

        Map<String, String> curve25519Map = oneTimeKeys.get("curve25519");

        if (null != curve25519Map) {
            for(String key_id : curve25519Map.keySet()) {
                HashMap<String, Object> k = new HashMap<>();
                k.put("key", curve25519Map.get(key_id));

                // the key is also signed
                String signature = mOlmDevice.signJSON(k);
                HashMap<String, String> submap = new HashMap<>();
                submap.put("ed25519:" + mMyDevice.deviceId,  signature);

                HashMap<String, Map<String, String> > map = new HashMap<>();
                map.put(mSession.getMyUserId(), submap);
                k.put("signatures", map);
                
                oneTimeJson.put("signed_curve25519:" + key_id, k);
            }
        }

        // For now, we set the device id explicitly, as we may not be using the
        // same one as used in login.
        mSession.getCryptoRestClient().uploadKeys(null, oneTimeJson, mMyDevice.deviceId, new ApiCallback<KeysUploadResponse>() {
            @Override
            public void onSuccess(KeysUploadResponse info) {
                mLastPublishedOneTimeKeys = oneTimeKeys;
                mOlmDevice.markKeysAsPublished();

                if (null != callback) {
                    callback.onSuccess(info);
                }
            }

            @Override
            public void onNetworkError(Exception e) {
                if (null != callback) {
                    callback.onNetworkError(e);
                }
            }

            @Override
            public void onMatrixError(MatrixError e) {
                if (null != callback) {
                    callback.onMatrixError(e);
                }
            }

            @Override
            public void onUnexpectedError(Exception e) {
                if (null != callback) {
                    callback.onUnexpectedError(e);
                }
            }
        });
    }

    /**
     * Validate device keys.
     * @param deviceKeys the device keys to validate.
     * @param userId the id of the user of the device.
     * @param deviceId the id of the device.
     * @param previouslyStoredDeviceKeys the device keys we received before for this device
     * @return true if succeeds
     */
    private boolean validateDeviceKeys(MXDeviceInfo deviceKeys, String userId, String deviceId, MXDeviceInfo previouslyStoredDeviceKeys) {
        if ((null == deviceKeys) || (null == deviceKeys.keys)) {
            // no keys?
            return false;
        }

        // Check that the user_id and device_id in the received deviceKeys are correct
        if (!TextUtils.equals(deviceKeys.userId, userId)) {
            Log.e(LOG_TAG, "## validateDeviceKeys() : Mismatched user_id " + deviceKeys.userId + " from " + userId + ":" + deviceId);
            return false;
        }

        if (!TextUtils.equals(deviceKeys.deviceId, deviceId)) {
            Log.e(LOG_TAG, "## validateDeviceKeys() : Mismatched device_id " + deviceKeys.deviceId + " from " + userId + ":" + deviceId);
            return false;
        }

        String signKeyId = "ed25519:" + deviceKeys.deviceId;
        String signKey = deviceKeys.keys.get(signKeyId);

        if (null == signKey) {
            Log.e(LOG_TAG, "## validateDeviceKeys() : Device " + userId + ":" + deviceKeys.deviceId + " has no ed25519 key");
            return false;
        }

        Map<String, String> signatureMap = deviceKeys.signatures.get(userId);

        if (null == signatureMap) {
            Log.e(LOG_TAG, "## validateDeviceKeys() : Device " + userId + ":" + deviceKeys.deviceId + " has no map for " + userId);
            return false;
        }

        String signature = signatureMap.get(signKeyId);

        if (null == signature) {
            Log.e(LOG_TAG, "## validateDeviceKeys() : Device " + userId + ":" + deviceKeys.deviceId + " is not signed");
            return false;
        }

        if (!mOlmDevice.verifySignature(signKey, deviceKeys.signalableJSONDictionary(), signature)) {
            Log.e(LOG_TAG, "## validateDeviceKeys() : Unable to verify signature on device " +  userId + ":" + deviceKeys.deviceId);
            return false;
        }

        if (null != previouslyStoredDeviceKeys) {
            if (! TextUtils.equals(previouslyStoredDeviceKeys.fingerprint(), signKey)) {
                // This should only happen if the list has been MITMed; we are
                // best off sticking with the original keys.
                //
                // Should we warn the user about it somehow?
                Log.e(LOG_TAG, "## validateDeviceKeys() : WARNING:Ed25519 key for device " + userId + ":" + deviceKeys.deviceId + " has changed");
                return false;
            }
        }

        return true;
    }

    /**
     * Send a "m.new_device" message to remind it that we exist and are a member
     * of a room.
     *  This is rate limited to send a message at most once an hour per destination.
     * @param deviceId the id of the device to ping. If nil, all devices.
     * @param userId the id of the user to ping.
     * @param roomId the room id
     */
    private void sendPingToDevice(String deviceId, String userId, String roomId) {
        // sanity checks
        if ((null == userId) || (null == roomId)) {
            return;
        }

        if (TextUtils.isEmpty(deviceId)) {
            deviceId = "*";
        }

        // Check rate limiting
        HashMap<String, Long> lastTsByRoom = mLastNewDeviceMessageTsByUserDeviceRoom.objectForDevice(deviceId, userId);

        if (null == lastTsByRoom) {
            lastTsByRoom = new HashMap<>();
        }

        Long lastTs = lastTsByRoom.get(roomId);
        if (null == lastTs) {
            lastTs = 0L;
        }

        long now = System.currentTimeMillis();

        // 1 hour
        if ((now - lastTs) < 3600000) {
            // rate-limiting
            return;
        }

        // Update rate limiting data
        lastTsByRoom.put(roomId, now);
        mLastNewDeviceMessageTsByUserDeviceRoom.setObject(lastTsByRoom, userId, deviceId);

        // Build a per-device message for each user
        MXUsersDevicesMap<Map<String, Object>> contentMap = new MXUsersDevicesMap<>();

        HashMap<String, Object> submap = new HashMap<>();
        submap.put("device_id", mSession.getCredentials().deviceId);
        submap.put("rooms", Arrays.asList(roomId));

        HashMap<String, Map<String,Object>> map = new HashMap<>();
        map.put(deviceId, submap);

        contentMap.setObjects(map, userId);

        mSession.getCryptoRestClient().sendToDevice(Event.EVENT_TYPE_NEW_DEVICE, contentMap, new ApiCallback<Void>() {
            @Override
            public void onSuccess(Void info) {
            }

            @Override
            public void onNetworkError(Exception e) {
                Log.e(LOG_TAG, "## sendPingToDevice failed " + e.getMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                Log.e(LOG_TAG, "## sendPingToDevice failed " + e.getLocalizedMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                Log.e(LOG_TAG, "## sendPingToDevice failed " + e.getMessage());
            }
        });
    }

    /**
     * Get a decryptor for a given room and algorithm.
     * If we already have a decryptor for the given room and algorithm, return
     * it. Otherwise try to instantiate it.
     * @param roomId the room id
     * @param algorithm the crypto algorithm
     * @return the decryptor
     */
    private IMXDecrypting getRoomDecryptor(String roomId, String algorithm) {
        // sanity check
        if (TextUtils.isEmpty(algorithm)) {
            Log.e(LOG_TAG, "## getRoomDecryptor() : null algorithm");
            return null;
        }

        IMXDecrypting alg = null;

        if (!TextUtils.isEmpty(roomId)) {
            if (!mRoomDecryptors.containsKey(roomId)) {
                mRoomDecryptors.put(roomId, new HashMap<String, IMXDecrypting>());
            }

            alg = mRoomDecryptors.get(roomId).get(algorithm);

            if (null != alg) {
                return alg;
            }
        }

        Class<IMXDecrypting> decryptingClass = MXCryptoAlgorithms.sharedAlgorithms().decryptorClassForAlgorithm(algorithm);

        if (null != decryptingClass) {
            try {
                Constructor<?> ctor = decryptingClass.getConstructors()[0];
                alg = (IMXDecrypting) ctor.newInstance(new Object[]{});

                if (null != alg) {
                    alg.initWithMatrixSession(mSession);

                    if (!TextUtils.isEmpty(roomId)) {
                        mRoomDecryptors.get(roomId).put(algorithm, alg);
                    }
                }
            } catch (Exception e) {
                Log.e(LOG_TAG, "## decryptEvent() : fail to load the class");
                return null;
            }
        }

        return alg;
    }
}