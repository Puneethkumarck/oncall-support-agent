package com.stablebridge.oncall.domain.port.notification;

public interface SlackNotifier {
    void sendMessage(String channel, String message);
}
