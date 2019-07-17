package local.webrtc.androidsdk.signal;

import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.Event;

import local.webrtc.androidsdk.call.ILocalCall;

public interface ILocalRoom {
    String getRoomId();

    void sendText(String text);

    void sendEvent(Event event, ApiCallback<Void> callback);
    void onEventReceived(String from, Event event);

    void leave();

    boolean isReady();
    boolean hasLeft();

    void bindCall(ILocalCall call);

    int getNumberOfJoinedMembers();
    boolean isEncrypted();
}
