package local.webrtc.androidsdk.signal;

import local.webrtc.androidsdk.call.ILocalCallsManager;
import local.webrtc.androidsdk.config.ILocalCallConfig;

public interface ILocalSession {
    String getUserId();

    String getMyUserId();
    String getCredentialsUserId();

    boolean open();
    void close();
    ILocalRoom joinRoom(String roomId);
    void leaveRoom(String roomId);
    ILocalRoom getRoomById(String roomId);

    boolean sendMessage(String message);

    boolean isOpen();
    boolean hasConnected();

    ILocalCallConfig getLocalCallConfig();

    boolean getCryptoWarnOnUnknownDevices();

    void bindCallManager(ILocalCallsManager callManager);
}
