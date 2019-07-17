package local.webrtc.androidsdk.call;

import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.Event;

public interface ILocalCallsManager {
    void createCallInRoom(final String roomId, final boolean isVideo, final ApiCallback<ILocalCall> callback);
    void handleCallEvent(final Event event);

    ILocalCall getCallWithCallId(String callId);
    ILocalCall getCallWithRoomId(String roomId);

    void addListener(ILocalCallsManagerListener listener);
    void removeListener(ILocalCallsManagerListener listener);
}
