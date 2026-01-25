package org.ddns.net;


public interface MessageHandler {
    void onBroadcastMessage(String message);

    void onDirectMessage(String message);

    void onMulticastMessage(String message);
}
