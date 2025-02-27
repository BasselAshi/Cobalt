package it.auties.whatsapp.local;

import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.WebHistoryLength;
import it.auties.whatsapp.api.Whatsapp;
import it.auties.whatsapp.model.info.ChatMessageInfo;

// Just used for testing locally
public class WebRunner {
    public static void main(String[] args) {
        var whatsapp = Whatsapp.webBuilder()
                .lastConnection()
                .historyLength(WebHistoryLength.zero())
                .unregistered(QrHandler.toTerminal())
                .addLoggedInListener(api -> System.out.printf("Connected: %s%n", api.store().privacySettings()))
                .addFeaturesListener(features -> System.out.printf("Received features: %s%n", features))
                .addNewChatMessageListener((api, message) -> System.out.println(message.toJson()))
                .addContactsListener((api, contacts) -> System.out.printf("Contacts: %s%n", contacts.size()))
                .addChatsListener(chats -> System.out.printf("Chats: %s%n", chats.size()))
                .addNewslettersListener((newsletters) -> System.out.printf("Newsletters: %s%n", newsletters.size()))
                .addNodeReceivedListener(incoming -> System.out.printf("Received node %s%n", incoming))
                .addNodeSentListener(outgoing -> System.out.printf("Sent node %s%n", outgoing))
                .addActionListener ((action, info) -> System.out.printf("New action: %s, info: %s%n", action, info))
                .addSettingListener(setting -> System.out.printf("New setting: %s%n", setting))
                .addContactPresenceListener((chat, contact, status) -> System.out.printf("Status of %s changed in %s to %s%n", contact, chat.name(), status.name()))
                .addMessageStatusListener((info) -> System.out.printf("Message status update for %s%n", info.id()))
                .addChatMessagesSyncListener((api, chat, last) -> System.out.printf("%s now has %s messages: %s(oldest message: %s)%n", chat.name(), chat.messages().size(), !last ? "waiting for more" : "done", chat.oldestMessage().flatMap(ChatMessageInfo::timestamp).orElse(null)))
                .addDisconnectedListener(reason -> System.out.printf("Disconnected: %s%n", reason))
                .connect()
                .join();
    }
}
