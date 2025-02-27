package it.auties.whatsapp.model.sync;

import it.auties.protobuf.annotation.ProtobufMessageName;
import it.auties.protobuf.annotation.ProtobufProperty;
import it.auties.protobuf.model.ProtobufMessage;
import it.auties.protobuf.model.ProtobufType;

import java.util.List;

@ProtobufMessageName("SyncdMutations")
public record MutationsSync(
        @ProtobufProperty(index = 1, type = ProtobufType.OBJECT, repeated = true)
        List<MutationSync> mutations
) implements ProtobufMessage {

}