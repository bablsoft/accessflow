package com.bablsoft.accessflow.notifications.internal.strategy;

import com.bablsoft.accessflow.notifications.api.NotificationDeliveryException;
import com.slack.api.RequestConfigurator;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.SlackApiException;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackBotMessengerTest {

    @Mock Slack slack;
    @Mock MethodsClient methods;

    private SlackBotMessenger messenger;

    @BeforeEach
    void setUp() {
        messenger = new SlackBotMessenger(slack);
        when(slack.methods("xoxb")).thenReturn(methods);
    }

    @Test
    void postsSuccessfully() throws Exception {
        var ok = mock(ChatPostMessageResponse.class);
        when(ok.isOk()).thenReturn(true);
        when(methods.chatPostMessage(any(RequestConfigurator.class))).thenReturn(ok);

        messenger.postMessage("xoxb", "C1", "hi", List.of());

        verify(methods).chatPostMessage(any(RequestConfigurator.class));
    }

    @Test
    void throwsWhenSlackReportsNotOk() throws Exception {
        var notOk = mock(ChatPostMessageResponse.class);
        when(notOk.isOk()).thenReturn(false);
        when(notOk.getError()).thenReturn("channel_not_found");
        when(methods.chatPostMessage(any(RequestConfigurator.class))).thenReturn(notOk);

        assertThatThrownBy(() -> messenger.postMessage("xoxb", "C1", "hi", List.of()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("channel_not_found");
    }

    @Test
    void wrapsSlackApiException() throws Exception {
        when(methods.chatPostMessage(any(RequestConfigurator.class)))
                .thenThrow(mock(SlackApiException.class));

        assertThatThrownBy(() -> messenger.postMessage("xoxb", "C1", "hi", List.of()))
                .isInstanceOf(NotificationDeliveryException.class);
    }

    @Test
    void wrapsIoException() throws Exception {
        when(methods.chatPostMessage(any(RequestConfigurator.class)))
                .thenThrow(new IOException("network"));

        assertThatThrownBy(() -> messenger.postMessage("xoxb", "C1", "hi", List.of()))
                .isInstanceOf(NotificationDeliveryException.class)
                .hasMessageContaining("delivery failed");
    }
}
