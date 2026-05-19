package com.bablsoft.accessflow.notifications.internal.codec;

public record TelegramChannelConfig(
        String botTokenPlain,
        String chatId) {
}
