package it.auties.whatsapp.model.message.standard;

import it.auties.protobuf.annotation.ProtobufMessageName;
import it.auties.protobuf.annotation.ProtobufProperty;
import it.auties.protobuf.model.ProtobufType;
import it.auties.whatsapp.model.info.ContextInfo;
import it.auties.whatsapp.model.message.model.ContextualMessage;
import it.auties.whatsapp.model.message.model.MessageCategory;
import it.auties.whatsapp.model.message.model.MessageType;

import java.util.Optional;

/**
 * A model class that represents a message holding a request for a phone number inside
 * Still needs to be implemented by Whatsapp
 */
@ProtobufMessageName("Message.RequestPhoneNumberMessage")
public record RequestPhoneNumberMessage(
        @ProtobufProperty(index = 1, type = ProtobufType.OBJECT)
        Optional<ContextInfo> contextInfo
) implements ContextualMessage {
    @Override
    public MessageType type() {
        return MessageType.REQUEST_PHONE_NUMBER;
    }

    @Override
    public MessageCategory category() {
        return MessageCategory.STANDARD;
    }
}
