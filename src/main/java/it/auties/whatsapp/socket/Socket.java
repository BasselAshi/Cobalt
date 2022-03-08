package it.auties.whatsapp.socket;

import it.auties.bytes.Bytes;
import it.auties.curve25519.Curve25519;
import it.auties.protobuf.decoder.ProtobufDecoder;
import it.auties.protobuf.encoder.ProtobufEncoder;
import it.auties.whatsapp.api.QrHandler;
import it.auties.whatsapp.api.SerializationStrategy.Event;
import it.auties.whatsapp.api.WhatsappListener;
import it.auties.whatsapp.api.WhatsappOptions;
import it.auties.whatsapp.binary.BinaryMessage;
import it.auties.whatsapp.controller.WhatsappKeys;
import it.auties.whatsapp.controller.WhatsappStore;
import it.auties.whatsapp.crypto.*;
import it.auties.whatsapp.model.action.*;
import it.auties.whatsapp.model.chat.Chat;
import it.auties.whatsapp.model.chat.ChatMute;
import it.auties.whatsapp.model.chat.GroupMetadata;
import it.auties.whatsapp.model.contact.Contact;
import it.auties.whatsapp.model.contact.ContactJid;
import it.auties.whatsapp.model.contact.ContactStatus;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.media.MediaConnection;
import it.auties.whatsapp.model.message.device.DeviceSentMessage;
import it.auties.whatsapp.model.message.model.MediaMessage;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.model.MessageKey;
import it.auties.whatsapp.model.message.model.MessageStatus;
import it.auties.whatsapp.model.message.server.ProtocolMessage;
import it.auties.whatsapp.model.message.server.SenderKeyDistributionMessage;
import it.auties.whatsapp.model.setting.EphemeralSetting;
import it.auties.whatsapp.model.signal.auth.*;
import it.auties.whatsapp.model.signal.auth.ClientPayload.ClientPayloadBuilder;
import it.auties.whatsapp.model.signal.keypair.SignalPreKeyPair;
import it.auties.whatsapp.model.signal.keypair.SignalSignedKeyPair;
import it.auties.whatsapp.model.signal.message.SignalDistributionMessage;
import it.auties.whatsapp.model.signal.message.SignalMessage;
import it.auties.whatsapp.model.signal.message.SignalPreKeyMessage;
import it.auties.whatsapp.model.signal.sender.SenderKeyName;
import it.auties.whatsapp.model.sync.*;
import it.auties.whatsapp.util.*;
import jakarta.websocket.*;
import jakarta.websocket.ClientEndpointConfig.Configurator;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;
import lombok.extern.java.Log;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static it.auties.bytes.Bytes.newBuffer;
import static it.auties.bytes.Bytes.ofBase64;
import static it.auties.protobuf.encoder.ProtobufEncoder.encode;
import static it.auties.whatsapp.api.SerializationStrategy.Event.*;
import static it.auties.whatsapp.socket.Node.*;
import static jakarta.websocket.ContainerProvider.getWebSocketContainer;
import static java.lang.Long.parseLong;
import static java.lang.Runtime.getRuntime;
import static java.lang.String.valueOf;
import static java.time.Instant.now;
import static java.util.Base64.getEncoder;
import static java.util.Map.of;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;
import static java.util.concurrent.CompletableFuture.completedFuture;

@Accessors(fluent = true)
@ClientEndpoint(configurator = Socket.OriginPatcher.class)
@Log
public class Socket {
    private static final String BUILD_HASH = "S9Kdc4pc4EJryo21snc5cg==";
    private static final int KEY_TYPE = 5;

    @Getter(onMethod = @__(@NonNull))
    @Setter(onParam = @__(@NonNull))
    private Session session;

    private boolean loggedIn;

    private ScheduledExecutorService pingService;

    @NonNull
    private final Handshake handshake;

    @NonNull
    private final WhatsappOptions options;

    @NonNull
    private final AuthHandler authHandler;

    @NonNull
    private final StreamHandler streamHandler;

    @NonNull
    private final MessageHandler messageHandler;

    @NonNull
    private final AppStateHandler appStateHandler;

    @Getter
    @NonNull
    private WhatsappKeys keys;

    @Getter
    @NonNull
    private WhatsappStore store;

    private CompletableFuture<Void> loginFuture;

    static {
        getWebSocketContainer().setDefaultMaxSessionIdleTimeout(0);
    }

    public Socket(@NonNull WhatsappOptions options, @NonNull WhatsappStore store, @NonNull WhatsappKeys keys) {
        this.pingService = Executors.newSingleThreadScheduledExecutor();
        this.handshake = new Handshake();
        this.options = options;
        this.store = store;
        this.keys = keys;
        this.authHandler = new AuthHandler();
        this.streamHandler = new StreamHandler();
        this.messageHandler = new MessageHandler();
        this.appStateHandler = new AppStateHandler();
        getRuntime().addShutdownHook(new Thread(() -> serialize(ON_CLOSE)));
        serialize(OTHER);
    }

    private void serialize(Event event) {
        options.serializationStrategies()
                .stream()
                .filter(strategy -> strategy.trigger() == event)
                .forEach(strategy -> strategy.serialize(store, keys));
    }

    @OnOpen
    @SneakyThrows
    public void onOpen(@NonNull Session session) {
        if(options.debug()){
            System.out.println("Opened");
        }

        session(session);
        if(loggedIn){
            return;
        }

        handshake.start(keys);
        handshake.updateHash(keys.ephemeralKeyPair().publicKey());
        var clientHello = new ClientHello(keys.ephemeralKeyPair().publicKey());
        var handshakeMessage = new HandshakeMessage(clientHello);
        Request.with(handshakeMessage)
                .sendWithPrologue(session(), keys, store);
    }

    @OnMessage
    @SneakyThrows
    public void onBinary(byte @NonNull [] raw) {
        var message = new BinaryMessage(raw);
        if(message.decoded().isEmpty()){
            return;
        }

        var header = message.decoded().getFirst();
        if(!loggedIn){
            authHandler.sendUserPayload(header.toByteArray());
            return;
        }

        message.toNodes(keys)
                .forEach(this::handleNode);
    }

    private void handleNode(Node deciphered) {
        if(options.debug()) {
            System.out.println("Received: " + deciphered);
        }

        store.resolvePendingRequest(deciphered, false);
        streamHandler.digest(deciphered);
    }

    @SneakyThrows
    public CompletableFuture<Void> connect() {
        if(loginFuture == null || loginFuture.isDone()){
            this.loginFuture = new CompletableFuture<>();
        }

        getWebSocketContainer().connectToServer(this, URI.create(options.whatsappUrl()));
        return loginFuture;
    }

    @SneakyThrows
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void await(){
        pingService.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
    }

    public CompletableFuture<Void> reconnect(){
        return disconnect()
                .thenComposeAsync(ignored -> connect());
    }

    @SneakyThrows
    public CompletableFuture<Void> disconnect(){
        changeState(false);
        session.close();
        return completedFuture(null); // session#close is a synchronous operation
    }

    public CompletableFuture<Void> logout(){
        if (keys.hasCompanion()) {
            var metadata = of("jid", keys.companion(), "reason", "user_initiated");
            var device = withAttributes("remove-companion-device", metadata);
            sendQuery("set", "md", device);
        }

        return disconnect()
                .thenRunAsync(this::changeKeys);
    }

    private void changeState(boolean loggedIn){
        this.loggedIn = loggedIn;
        keys.clear();
    }

    @OnClose
    public void onClose(){
        if(options.debug()){
            System.out.println("Closed");
        }

        if(loginFuture != null && !loginFuture.isDone()){
            loginFuture.complete(null);
        }

        if(loggedIn) {
            store.callListeners(listener -> listener.onDisconnected(true));
            reconnect();
            return;
        }

        store.callListeners(listener -> listener.onDisconnected(false));
        store.dispose();
        dispose();
    }

    @OnError
    public void onError(Throwable throwable){
        serialize(ON_ERROR);
        handleError(throwable);
    }

    private <T> T handleError(Throwable throwable){
        throwable.printStackTrace();
        return null;
    }

    public CompletableFuture<Node> send(Node node){
        if(options.debug()){
            System.out.println("Sending: " + node);
        }

        return node.toRequest(store.nextTag())
                .send(session(), keys, store);
    }

    public CompletableFuture<Void> sendWithNoResponse(Node node){
        return node.toRequest(store.nextTag())
                .sendWithNoResponse(session(), keys, store);
    }

    @SafeVarargs
    public final CompletableFuture<Node> sendMessage(MessageInfo info, Entry<String, Object>... metadata){
        return messageHandler.encode(info, metadata);
    }

    public CompletableFuture<Node> sendQuery(String method, String category, Node... body){
        return sendQuery(null, ContactJid.SOCKET,
                method, category, null, body);
    }

    public CompletableFuture<Node> sendQuery(String method, String category, Map<String, Object> metadata, Node... body){
        return sendQuery(null, ContactJid.SOCKET,
                method, category, metadata, body);
    }

    public CompletableFuture<Node> sendQuery(ContactJid to, String method, String category, Node... body){
        return sendQuery(null, to,
                method, category, null, body);
    }

    public CompletableFuture<Node> sendQuery(String id, ContactJid to, String method, String category, Map<String, Object> metadata, Node... body){
        var attributes = Attributes.of(metadata)
                .put("id", id, Objects::nonNull)
                .put("type", method)
                .put("to", to.toString())
                .put("xmlns", category, Objects::nonNull)
                .map();
        return send(withChildren("iq", attributes, body));
    }

    public CompletableFuture<List<Node>> sendQuery(Node queryNode, Node... queryBody) {
        var query = withChildren("query", queryNode);
        var list = withChildren("list", queryBody);
        var sync = withChildren("usync",
                of("sid", store.nextTag(), "mode", "query", "last", "true", "index", "0", "context", "interactive"),
                query, list);
        return sendQuery("get", "usync", sync)
                .thenApplyAsync(this::parseQueryResult);
    }

    private List<Node> parseQueryResult(Node result) {
        return result.findNodes("usync")
                .stream()
                .map(node -> node.findNode("list"))
                .map(node -> node.findNodes("user"))
                .flatMap(Collection::stream)
                .toList();
    }

    private CompletableFuture<GroupMetadata> queryGroupMetadata(ContactJid group){
        var body = withAttributes("query", of("request", "interactive"));
        return sendQuery(group, "get", "w:g2", body)
                .thenApplyAsync(node -> GroupMetadata.of(node.findNode("group")));
    }

    private void sendReceipt(ContactJid jid, ContactJid participant, List<String> messages, String type) {
        if(messages.isEmpty()){
            return;
        }

        var attributes = Attributes.empty()
                .put("id", messages.get(0))
                .put("t", valueOf(now().toEpochMilli()))
                .put("to", jid.toString())
                .put("type", type, Objects::nonNull)
                .put("participant", participant, Objects::nonNull, value -> !Objects.equals(jid, value));
        var receipt = withChildren("receipt",
                attributes.map(), toMessagesNode(messages));
        send(receipt);
    }

    private List<Node> toMessagesNode(List<String> messages) {
        if (messages.size() <= 1) {
            return null;
        }

        return messages.subList(1, messages.size())
                .stream()
                .map(id -> withAttributes("item", of("id", id)))
                .toList();
    }

    private void sendMessageAck(Node node, Map<String, Object> metadata){
        var attributes = Attributes.of(metadata)
                .put("id", node.id())
                .put("to", node.attributes().getJid("from").orElseThrow(() -> new NoSuchElementException("Missing from in message ack")))
                .put("participant", node.attributes().getNullableString("participant"), Objects::nonNull)
                .map();
        var receipt = withAttributes("ack", attributes);
        send(receipt);
    }

    private void changeKeys() {
        keys.delete();
        var newId = SignalHelper.randomRegistrationId();
        this.keys = WhatsappKeys.newKeys(newId);
        var newStore = WhatsappStore.newStore(newId);
        newStore.listeners().addAll(store.listeners());
        this.store = newStore;
    }

    private void dispose(){
        pingService.shutdownNow();
        store.save(false);
        keys.save(false);
    }

    public static class OriginPatcher extends Configurator{
        @Override
        public void beforeRequest(@NonNull Map<String, List<String>> headers) {
            headers.put("Origin", List.of("https://web.whatsapp.com"));
            headers.put("Host", List.of("web.whatsapp.com"));
        }
    }

    private class AuthHandler {
        @SneakyThrows
        private void sendUserPayload(byte[] message) {
            var serverHello = ProtobufDecoder.forType(HandshakeMessage.class)
                    .decode(message)
                    .serverHello();
            handshake.updateHash(serverHello.ephemeral());
            var sharedEphemeral = Curve25519.calculateAgreement(serverHello.ephemeral(), keys.ephemeralKeyPair().privateKey());
            handshake.mixIntoKey(sharedEphemeral);

            var decodedStaticText = handshake.cipher(serverHello.staticText(), false);
            var sharedStatic = Curve25519.calculateAgreement(decodedStaticText, keys.ephemeralKeyPair().privateKey());
            handshake.mixIntoKey(sharedStatic);
            handshake.cipher(serverHello.payload(), false);

            var encodedKey = handshake.cipher(keys.companionKeyPair().publicKey(), true);
            var sharedPrivate = Curve25519.calculateAgreement(serverHello.ephemeral(), keys.companionKeyPair().privateKey());
            handshake.mixIntoKey(sharedPrivate);

            var encodedPayload = handshake.cipher(createUserPayload(), true);
            var clientFinish = new ClientFinish(encodedKey, encodedPayload);
            var handshakeMessage = new HandshakeMessage(clientFinish);
            Request.with(handshakeMessage)
                    .sendWithNoResponse(session(), keys, store)
                    .thenRunAsync(() -> changeState(true))
                    .thenRunAsync(handshake::finish);
        }

        private byte[] createUserPayload() {
            var builder = ClientPayload.builder()
                    .connectReason(ClientPayload.ClientPayloadConnectReason.USER_ACTIVATED)
                    .connectType(ClientPayload.ClientPayloadConnectType.WIFI_UNKNOWN)
                    .userAgent(createUserAgent())
                    .passive(keys.hasCompanion())
                    .webInfo(new WebInfo(WebInfo.WebInfoWebSubPlatform.WEB_BROWSER));
            return ProtobufEncoder.encode(finishUserPayload(builder));
        }

        private ClientPayload finishUserPayload(ClientPayloadBuilder builder) {
            if(keys.hasCompanion()){
                return builder.username(parseLong(keys.companion().user()))
                        .device(keys.companion().device())
                        .build();
            }

            return builder.regData(createRegisterData())
                    .build();
        }

        private UserAgent createUserAgent() {
            return UserAgent.builder()
                    .appVersion(new Version(options.whatsappVersion()))
                    .platform(UserAgent.UserAgentPlatform.WEB)
                    .releaseChannel(UserAgent.UserAgentReleaseChannel.RELEASE)
                    .build();
        }

        private CompanionData createRegisterData() {
            return CompanionData.builder()
                    .buildHash(ofBase64(BUILD_HASH).toByteArray())
                    .companion(encode(createCompanionProps()))
                    .id(SignalHelper.toBytes(keys.id(), 4))
                    .keyType(SignalHelper.toBytes(KEY_TYPE, 1))
                    .identifier(keys.identityKeyPair().publicKey())
                    .signatureId(keys.signedKeyPair().encodedId())
                    .signaturePublicKey(keys.signedKeyPair().keyPair().publicKey())
                    .signature(keys.signedKeyPair().signature())
                    .build();
        }

        private Companion createCompanionProps() {
            return Companion.builder()
                    .os(options.description())
                    .platformType(Companion.CompanionPropsPlatformType.DESKTOP)
                    .build();
        }
    }

    private class StreamHandler {
        private static final byte[] MESSAGE_HEADER = {6, 0};
        private static final byte[] SIGNATURE_HEADER = {6, 1};

        private void digest(@NonNull Node node) {
            switch (node.description()) {
                case "ack" -> digestAck(node);
                case "call" -> digestCall(node);
                case "failure" -> digestFailure(node);
                case "ib" -> digestIb(node);
                case "iq" -> digestIq(node);
                case "receipt" -> digestReceipt(node);
                case "stream:error" -> digestError(node);
                case "success" -> digestSuccess();
                case "message" -> digestMessage(node);
                case "notification" -> digestNotification(node);
                case "presence", "chatstate" -> digestChatState(node);
            }
        }

        private void digestMessage(Node node) {
            messageHandler.decode(node);
            serialize(ON_MESSAGE);
        }

        private void digestChatState(Node node) {
            var chatJid = node.attributes()
                    .getJid("from")
                    .orElseThrow(() -> new NoSuchElementException("Missing from in chat state update"));
            var participantJid = node.attributes()
                    .getJid("participant")
                    .orElse(chatJid);
            var updateType = node.attributes()
                    .getOptionalString("type")
                    .orElseGet(() -> node.children().getFirst().description());
            var status = ContactStatus.forValue(updateType);
            store.findContactByJid(participantJid)
                    .ifPresent(contact -> updateContactPresence(chatJid, status, contact));
        }

        private void updateContactPresence(ContactJid chatJid, ContactStatus status, Contact contact) {
            contact.lastKnownPresence(status);
            contact.lastSeen(ZonedDateTime.now());
            store.findChatByJid(chatJid)
                    .ifPresent(chat -> updateChatPresence(status, contact, chat));
        }

        private void updateChatPresence(ContactStatus status, Contact contact, Chat chat) {
            chat.presences().put(contact, status);
            store.callListeners(listener -> {
                listener.onContactPresence(chat, contact, status);
                if (status != ContactStatus.PAUSED) {
                    return;
                }

                listener.onContactPresence(chat, contact, ContactStatus.AVAILABLE);
            });
        }

        private void digestReceipt(Node node) {
            var type = node.attributes().getNullableString("type");
            var status = MessageStatus.forValue(type);
            if(status != null) {
                updateMessageStatus(node, status);
            }

            var attributes = Attributes.empty()
                    .put("class", "receipt")
                    .put("type", type, Objects::nonNull);
            sendMessageAck(node, attributes.map());
        }

        private void updateMessageStatus(Node node, MessageStatus status) {
            node.attributes().getJid("from")
                    .flatMap(store::findChatByJid)
                    .ifPresent(chat -> updateMessageStatus(node, status, chat));
        }

        private void updateMessageStatus(Node node, MessageStatus status, Chat chat) {
            var participant = node.attributes().getJid("participant")
                    .flatMap(store::findContactByJid)
                    .orElse(null);
            var messageIds = Stream.ofNullable(node.findNode("list"))
                    .map(list -> list.findNodes("item"))
                    .flatMap(Collection::stream)
                    .map(item -> item.attributes().getOptionalString("id"))
                    .flatMap(Optional::stream)
                    .collect(Collectors.toList());
            messageIds.add(node.attributes().getRequiredString("id"));
            messageIds.stream()
                    .map(messageId -> store.findMessageById(chat, messageId))
                    .flatMap(Optional::stream)
                    .forEach(message -> updateMessageStatus(status, participant, message));
        }

        private void updateMessageStatus(MessageStatus status, Contact participant, MessageInfo message) {
            var chat = message.chat()
                    .orElseThrow(() -> new NoSuchElementException("Missing chat in status update"));
            message.status(status);
            if(participant != null){
                message.individualStatus().put(participant, status);
            }

            store.callListeners(listener -> {
                if(participant == null) {
                    listener.onMessageStatus(message, status);
                }

                listener.onMessageStatus(chat, participant, message, status);
            });
        }

        private void digestCall(Node node) {
            var call = node.children().peekFirst();
            if(call == null){
                return;
            }

            sendMessageAck(node, of("class", "call", "type", call.description()));
        }

        private void digestAck(Node node) {
            System.out.println("Received ack: " + node);
            var clazz = node.attributes().getString("class");
            if (!Objects.equals(clazz, "message")) {
                return;
            }

            var from = node.attributes().getJid("from")
                    .orElseThrow(() -> new NoSuchElementException("Cannot digest ack: missing from"));
            var receipt = withAttributes("ack",
                    of("class", "receipt", "id", node.id(), "from", from));
            send(receipt);
        }

        private void digestNotification(Node node) {
            var type = node.attributes().getString("type", null);
            sendMessageAck(node, of("class", "notification", "type", type));
            if (!Objects.equals(type, "server_sync")) {
                return;
            }

            var update = node.findNode("internal");
            if (update == null) {
                return;
            }

            var patchName = update.attributes().getRequiredString("name");
            appStateHandler.pull(patchName);
        }

        private void digestIb(Node node) {
            var dirty = node.findNode("dirty");
            if(dirty == null){
                Validate.isTrue(!node.hasNode("downgrade_webclient"),
                        "Multi device beta is not enabled. Please enable it from Whatsapp");
                return;
            }

            var type = dirty.attributes().getString("type");
            if(!Objects.equals(type, "account_sync")){
                return;
            }

            var timestamp = dirty.attributes().getString("timestamp");
            sendQuery("set", "urn:xmpp:whatsapp:dirty",
                    withAttributes("clean", of("type", type, "timestamp", timestamp)));
        }

        private void digestFailure(Node node) {
            var statusCode = node.attributes().getLong("reason");
            var reason = node.attributes().getString("location");
            handleFailure(statusCode, reason, reason);
        }

        private void handleFailure(long statusCode, String reason, String location) {
            Validate.isTrue(shouldHandleFailure(statusCode, reason),
                    "Invalid or expired credentials: socket failed with status code %s at %s", statusCode, location);
            log.warning("Handling failure caused by %s at %s with status code %s: restoring session".formatted(reason, location, statusCode));
            changeKeys();
            reconnect();
        }

        private boolean shouldHandleFailure(long statusCode, String reason) {
            return store.listeners()
                    .stream()
                    .allMatch(listener -> listener.onFailure(statusCode, reason));
        }

        private void digestError(Node node) {
            var statusCode = node.attributes().getInt("code");
            switch (statusCode) {
                case 515 -> reconnect();
                case 401 -> handleStreamError(node, statusCode);
                default -> handleStreamError(node);
            }
        }

        private void handleStreamError(Node node) {
            Validate.isTrue(node.findNode("xml-not-well-formed") == null,
                    "An invalid node was sent to Whatsapp");
            node.children().forEach(error -> store.resolvePendingRequest(error, true));
        }

        private void handleStreamError(Node node, int statusCode) {
            var child = node.children().getFirst();
            var type = child.attributes().getString("type");
            var reason = child.attributes().getString("reason", null);
            handleFailure(statusCode, requireNonNullElse(reason, type), requireNonNullElse(reason, type));
        }

        private void digestSuccess() {
            confirmConnection();
            sendPreKeys();
            createPingService();
            sendStatusUpdate();
            loginFuture.complete(null);
            store.callListeners(WhatsappListener::onLoggedIn);
            if (!store.hasSnapshot()) {
                return;
            }

            store.callListeners(WhatsappListener::onChats);
            store.callListeners(WhatsappListener::onContacts);
        }

        private void sendStatusUpdate() {
            var presence = withAttributes("presence", of("type", "available"));
            sendWithNoResponse(presence);
            sendQuery("get", "blocklist");
            sendQuery("get", "privacy", with("privacy"));
            sendQuery("get", "abt", withAttributes("props", of("protocol", "1")));
            sendQuery("get", "w", with("props"))
                    .thenAcceptAsync(this::parseProps);
        }

        private void parseProps(Node result) {
            var properties = result.findNode("props")
                    .findNodes("prop")
                    .stream()
                    .map(node -> Map.entry(node.attributes().getString("name"), node.attributes().getString("value")))
                    .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
            store.callListeners(listener -> listener.onProps(properties));
        }

        private void createPingService() {
            if(pingService.isShutdown()){
                pingService = Executors.newSingleThreadScheduledExecutor();
            }

            pingService.scheduleAtFixedRate(this::sendPing,
                    20L, 20L, TimeUnit.SECONDS);
        }

        private void sendPing() {
            if(!loggedIn){
                pingService.shutdownNow();
                return;
            }

            sendQuery("get", "w:p", with("ping"));
        }

        private void createMediaConnection(){
            if(!loggedIn){
                return;
            }

            sendQuery("set", "w:m", with("media_conn"))
                    .thenApplyAsync(MediaConnection::ofNode)
                    .thenApplyAsync(this::scheduleMediaConnection)
                    .thenApplyAsync(store::mediaConnection);
        }

        private MediaConnection scheduleMediaConnection(MediaConnection connection) {
            CompletableFuture.delayedExecutor(connection.ttl(), TimeUnit.SECONDS)
                    .execute(this::createMediaConnection);
            return connection;
        }

        private void digestIq(Node node) {
            var container = node.children().peekFirst();
            if(container == null){
                return;
            }

            if (container.description().equals("pair-device")) {
                generateQrCode(node, container);
                return;
            }

            if (!container.description().equals("pair-success")) {
                return;
            }

            confirmQrCode(node, container);
        }

        private void confirmConnection() {
            sendQuery("set", "passive", with("active"))
                    .thenRunAsync(this::createMediaConnection);
        }

        private void sendPreKeys() {
            if(keys.hasPreKeys()){
                return;
            }

            sendQuery("set", "encrypt", createPreKeysContent());
        }

        private Node[] createPreKeysContent() {
            return new Node[]{createPreKeysRegistration(), createPreKeysType(),
                    createPreKeysIdentity(), createPreKeys(), keys.signedKeyPair().toNode()};
        }

        private Node createPreKeysIdentity() {
            return with("identity", keys.identityKeyPair().publicKey());
        }

        private Node createPreKeysType() {
            return with("type", "");
        }

        private Node createPreKeysRegistration() {
            return with("registration", SignalHelper.toBytes(keys.id(), 4));
        }

        private Node createPreKeys() {
            var nodes = IntStream.range(0, 30)
                    .mapToObj(SignalPreKeyPair::ofIndex)
                    .peek(keys.preKeys()::add)
                    .map(SignalPreKeyPair::toNode)
                    .toList();

            return with("list", nodes);
        }

        private void generateQrCode(Node node, Node container) {
            printQrCode(container);
            sendConfirmNode(node, null);
        }

        private void printQrCode(Node container) {
            var ref = container.findNode("ref");
            var qr = new String(ref.bytes(), StandardCharsets.UTF_8);
            var matrix = QrGenerator.generate(keys, qr);
            if (!store.listeners().isEmpty()) {
                store.callListeners(listener -> listener.onQRCode(matrix).accept(matrix));
                return;
            }

            QrHandler.toTerminal().accept(matrix);
        }

        @SneakyThrows
        private void confirmQrCode(Node node, Node container) {
            saveCompanion(container);

            var deviceIdentity = requireNonNull(container.findNode("device-identity"), "Missing device identity");
            var advIdentity = ProtobufDecoder.forType(SignedDeviceIdentityHMAC.class)
                    .decode(deviceIdentity.bytes());
            var advSign = Hmac.calculateSha256(advIdentity.details(), keys.companionKey());
            if(!Arrays.equals(advIdentity.hmac(), advSign)) {
                handleFailure(503, "hmac_validation", "login_adv_sign");
                return;
            }

            var account = ProtobufDecoder.forType(SignedDeviceIdentity.class)
                    .decode(advIdentity.details());
            var message = Bytes.of(MESSAGE_HEADER)
                    .append(account.details())
                    .append(keys.identityKeyPair().publicKey())
                    .toByteArray();
            if(!Curve25519.verifySignature(account.accountSignatureKey(), message, account.accountSignature())) {
                handleFailure(503, "hmac_validation", "login_verify_signature");
                return;
            }

            var deviceSignatureMessage = Bytes.of(SIGNATURE_HEADER)
                    .append(account.details())
                    .append(keys.identityKeyPair().publicKey())
                    .append(account.accountSignatureKey())
                    .toByteArray();
            var deviceSignature = Curve25519.calculateSignature(keys.identityKeyPair().privateKey(), deviceSignatureMessage);
            account.deviceSignature(deviceSignature);
            var oldSignature = Arrays.copyOf(account.accountSignatureKey(), account.accountSignature().length);

            var keyIndex = ProtobufDecoder.forType(DeviceIdentity.class)
                    .decode(account.details())
                    .keyIndex();
            var identityNode = with("device-identity", of("key-index", valueOf(keyIndex)), ProtobufEncoder.encode(account.accountSignatureKey(null)));
            var content = withChildren("pair-device-sign", identityNode);

            keys.companionIdentity(account.accountSignature(oldSignature));
            sendConfirmNode(node, content);
        }

        private void sendConfirmNode(Node node, Node content) {
            sendQuery(node.id(), ContactJid.SOCKET,
                    "result", null, of(), content);
        }

        private void saveCompanion(Node container) {
            var node = requireNonNull(container.findNode("device"), "Missing device");
            var companion = node.attributes().getJid("jid")
                    .orElseThrow(() -> new NoSuchElementException("Missing companion"));
            keys.companion(companion);
        }
    }

    private class MessageHandler {
        private final CacheMap<ContactJid, GroupMetadata> groupsCache;
        private final CacheMap<String, List<ContactJid>> devicesCache;
        public MessageHandler() {
            this.groupsCache = new CacheMap<>();
            this.devicesCache = new CacheMap<>();
        }

        @SafeVarargs
        public final CompletableFuture<Node> encode(MessageInfo info, Entry<String, Object>... attributes) {
            var encodedMessage = SignalHelper.pad(ProtobufEncoder.encode(info));
            if (isConversation(info)) {
                var whatsappMessage = DeviceSentMessage.newDeviceSentMessage(info.chatJid().toString(), info.message());
                var paddedMessage = SignalHelper.pad(ProtobufEncoder.encode(whatsappMessage));
                return querySyncDevices(info.chatJid(), true)
                        .thenCombineAsync(querySyncDevices(keys.companion().toUserJid(), true), this::joinContacts)
                        .thenComposeAsync(this::createSessions)
                        .thenApplyAsync(result -> createParticipantsSessions(result, paddedMessage))
                        .thenComposeAsync(participants -> encode(info, encodedMessage, participants, attributes))
                        .exceptionallyAsync(Socket.this::handleError);
            }

            var senderName = new SenderKeyName(info.chatJid().toString(), keys.companion().toSignalAddress());
            var sessionBuilder = new GroupSessionBuilder(keys);
            var signalMessage = sessionBuilder.createMessage(senderName);
            return groupsCache.getOptional(info.chatJid())
                    .map(CompletableFuture::completedFuture)
                    .orElseGet(() -> cacheGroupMetadata(info))
                    .thenComposeAsync(metadata -> getGroupParticipants(info, metadata, signalMessage))
                    .thenComposeAsync(participants -> encode(info, encodedMessage, participants, attributes))
                    .exceptionallyAsync(Socket.this::handleError);
        }

        private CompletableFuture<List<Node>> getGroupParticipants(MessageInfo info, GroupMetadata metadata, SignalDistributionMessage message) {
            return metadata.participants()
                    .stream()
                    .map(participant -> querySyncDevices(participant.jid(), false))
                    .reduce(completedFuture(List.of()), (left, right) -> left.thenCombineAsync(right, this::joinContacts))
                    .thenComposeAsync(contacts -> createDistributionMessage(info, message, contacts));
        }

        private List<ContactJid> joinContacts(List<ContactJid> first, List<ContactJid> second) {
            return Stream.of(first, second)
                    .flatMap(Collection::stream)
                    .toList();
        }

        @SafeVarargs
        private CompletableFuture<Node> encode(MessageInfo info, byte[] encodedMessage, List<Node> participants, Entry<String, Object>... metadata) {
            var body = new ArrayList<Node>();
            if (!isConversation(info)) {
                var senderName = new SenderKeyName(info.chatJid().toString(), keys.companion().toSignalAddress());
                var groupCipher = new GroupCipher(senderName, keys);
                var cipheredMessage = groupCipher.encrypt(encodedMessage);
                body.add(with("enc", of("v", "2", "type", "skmsg"), cipheredMessage));
            }

            if(!participants.isEmpty()){
                body.add(with("participants", participants));
            }

            if(hasPreKeyMessage(participants)) {
                body.add(with("device-identity", ProtobufEncoder.encode(keys.companionIdentity())));
            }

            var attributes = Attributes.of(metadata)
                    .put("id", info.id())
                    .put("type", "text")
                    .put("to", info.chatJid())
                    .map();
            var request = withChildren("message", attributes, body);
            Validate.isTrue(request.content() != null,
                    "Missing message content",
                    IllegalArgumentException.class);
            return send(request);
        }

        private boolean hasPreKeyMessage(List<Node> participants) {
            return participants.stream()
                    .map(Node::children)
                    .flatMap(Collection::stream)
                    .map(node -> node.attributes().getOptionalString("type"))
                    .flatMap(Optional::stream)
                    .anyMatch("pkmsg"::equals);
        }

        private boolean isConversation(MessageInfo info) {
            return info.chatJid().type() == ContactJid.Type.USER
                    || info.chatJid().type() == ContactJid.Type.STATUS;
        }

        @SneakyThrows
        private CompletableFuture<GroupMetadata> cacheGroupMetadata(MessageInfo info) {
            return queryGroupMetadata(info.chatJid())
                    .thenApplyAsync(this::cacheMetadata);
        }

        private GroupMetadata cacheMetadata(GroupMetadata metadata) {
            groupsCache.put(metadata.jid(), metadata);
            return metadata;
        }

        private CompletableFuture<List<Node>> createDistributionMessage(MessageInfo info, SignalDistributionMessage signalMessage, List<ContactJid> participants) {
            var missingParticipants= participants.stream()
                    .filter(participant -> !keys.hasSession(participant.toSignalAddress()))
                    .toList();
            if(missingParticipants.isEmpty()){
                return completedFuture(List.of());
            }

            var whatsappMessage = new SenderKeyDistributionMessage(info.chatJid().toString(), signalMessage.serialized());
            var paddedMessage = SignalHelper.pad(ProtobufEncoder.encode(whatsappMessage));
            return createSessions(missingParticipants)
                    .thenApplyAsync(result -> createParticipantsSessions(result, paddedMessage));
        }

        private List<Node> createParticipantsSessions(List<ContactJid> contacts, byte[] senderKeyMessage) {
            return contacts.stream()
                    .map(contact -> createParticipantSession(senderKeyMessage, contact))
                    .toList();
        }

        private Node createParticipantSession(byte[] senderKeyMessage, ContactJid contact) {
            var cipher = new SessionCipher(contact.toSignalAddress(), keys);
            var encrypted = cipher.encrypt(senderKeyMessage);
            return withChildren("to", of("jid", contact), encrypted);
        }

        private CompletableFuture<List<ContactJid>> createSessions(List<ContactJid> contacts) {
            var nodes = contacts.stream()
                    .filter(contact -> !keys.hasSession(contact.toSignalAddress()))
                    .map(contact -> withAttributes("user", of("jid", contact, "reason", "identity")))
                    .toList();
            return nodes.isEmpty() ? completedFuture(contacts) : sendQuery("get", "encrypt", withChildren("key", nodes))
                    .thenAcceptAsync(this::parseSessions)
                    .thenCompose(result -> completedFuture(contacts));
        }

        private void parseSessions(Node result) {
            result.findNode("list")
                    .findNodes("user")
                    .forEach(this::parseSession);
        }

        private void parseSession(Node node) {
            Validate.isTrue(!node.hasNode("error"),
                    "Erroneous session node",
                    SecurityException.class);
            var jid = node.attributes().getJid("jid")
                    .orElseThrow(() -> new NoSuchElementException("Missing jid for session"));
            var signedKey = node.findNode("skey");
            var key = node.findNode("key");
            var identity = node.findNode("identity");
            var registrationId = node.findNode("registration");

            var builder = new SessionBuilder(jid.toSignalAddress(), keys);
            builder.createOutgoing(
                    SignalHelper.fromBytes(registrationId.bytes(), 4),
                    SignalHelper.appendKeyHeader(identity.bytes()),
                    SignalSignedKeyPair.of(signedKey).orElseThrow(),
                    SignalSignedKeyPair.of(key).orElse(null)
            );
        }

        private CompletableFuture<List<ContactJid>> querySyncDevices(ContactJid contact, boolean ignoreZeroDevices) {
            var cachedDevices = devicesCache.getOptional(contact.user());
            if(cachedDevices.isPresent()){
                return completedFuture(cachedDevices.get());
            }

            var body = withChildren("usync",
                    of("sid", store.nextTag(), "mode", "query", "last", "true", "index", "0", "context", "message"),
                    withChildren("query", withAttributes("devices", of("version", "2"))),
                    withChildren("list", withAttributes("user", of("jid", contact))));
            return sendQuery("get", "usync", body)
                    .thenApplyAsync(response -> cacheSyncDevices(response, contact, ignoreZeroDevices));
        }

        private List<ContactJid> cacheSyncDevices(Node node, ContactJid contact, boolean excludeZeroDevices) {
            var contacts = node.children()
                    .stream()
                    .map(child -> child.findNode("list"))
                    .filter(Objects::nonNull)
                    .map(Node::children)
                    .flatMap(Collection::stream)
                    .flatMap(wrapper -> parseDevices(wrapper, excludeZeroDevices))
                    .toList();
            devicesCache.put(contact.user(), contacts);
            return contacts;
        }

        private Stream<ContactJid> parseDevices(Node wrapper, boolean excludeZeroDevices) {
            var jid = ContactJid.ofUser(wrapper.attributes().getString("jid"), ContactJid.Server.USER);
            return wrapper.findNode("devices")
                    .findNode("device-list")
                    .children()
                    .stream()
                    .map(child -> parseDeviceId(child, jid, excludeZeroDevices))
                    .flatMap(Optional::stream)
                    .map(id -> ContactJid.ofDevice(jid.user(), id));
        }

        private Optional<Integer> parseDeviceId(Node child, ContactJid jid, boolean excludeZeroDevices) {
            var deviceId = child.attributes().getInt("id");
            return child.description().equals("device")
                    && (!excludeZeroDevices || deviceId != 0)
                    && (!jid.user().equals(keys.companion().user()) || keys.companion().device() != deviceId)
                    && (deviceId == 0 || child.attributes().hasKey("key-index")) ? Optional.of(deviceId) : Optional.empty();
        }

        public void decode(Node node) {
            var timestamp = node.attributes().getLong("t");
            var id = node.attributes().getRequiredString("id");
            var from = node.attributes().getJid("from")
                    .orElseThrow(() -> new NoSuchElementException("Missing from"));
            var recipient = node.attributes().getJid("recipient")
                    .orElse(from);
            var participant = node.attributes().getJid("participant")
                    .orElse(null);
            var messageBuilder = MessageInfo.newMessageInfo();
            var keyBuilder = MessageKey.newMessageKey();
            switch (from.type()){
                case USER, OFFICIAL_BUSINESS_ACCOUNT, STATUS, ANNOUNCEMENT, COMPANION -> {
                    keyBuilder.chatJid(recipient);
                    messageBuilder.senderJid(from);
                }

                case GROUP, GROUP_CALL, BROADCAST -> {
                    var sender = requireNonNull(participant, "Missing participant in group message");
                    keyBuilder.chatJid(from);
                    messageBuilder.senderJid(sender);
                }

                default -> throw new IllegalArgumentException("Cannot decode message, unsupported type: %s".formatted(from.type().name()));
            }

            var key = keyBuilder.id(id).create();
            var info = messageBuilder.storeId(store.id())
                    .key(key)
                    .timestamp(timestamp)
                    .create();

            node.findNodes("enc")
                    .forEach(messageNode -> decodeMessage(info, node, messageNode, from));
        }

        private void decodeMessage(MessageInfo info, Node container, Node messageNode, ContactJid from) {
            try {
                var encodedMessage = messageNode.bytes();
                var messageType = messageNode.attributes().getString("type");
                var buffer = decodeCipheredMessage(info, encodedMessage, messageType);
                if(buffer.isEmpty()){
                    return;
                }

                info.message(decodeMessageContainer(buffer.get()));
                sendMessageAck(container, of("class", "receipt"));
                sendReceipt(info.chatJid(), info.senderJid(),
                        List.of(info.key().id()), null);
                handleMessage(info, from);
            }catch (Throwable throwable){
                log.warning("An exception occurred while processing a message: " + throwable.getMessage());
                log.warning("The application will continue running normally, but submit an issue on GitHub");
                throwable.printStackTrace();
            }
        }

        private void handleMessage(MessageInfo info, ContactJid from) {
            handleStubMessage(info);
            switch (info.message().content()){
                case SenderKeyDistributionMessage distributionMessage -> handleDistributionMessage(distributionMessage, from);
                case ProtocolMessage protocolMessage -> handleProtocolMessage(info, protocolMessage);
                case DeviceSentMessage deviceSentMessage -> saveMessage(info.message(deviceSentMessage.message()));
                default -> saveMessage(info);
            }
        }

        private void handleStubMessage(MessageInfo info) {
            if(!info.hasStub()) {
                return;
            }

            log.warning("Received stub %s with %s: unsupported!".formatted(info.stubType(), info.stubParameters()));
        }

        private Optional<byte[]> decodeCipheredMessage(MessageInfo info, byte[] message, String type) {
            try {
                return Optional.of(switch (type) {
                    case "skmsg" -> {
                        var senderName = new SenderKeyName(info.chatJid().toString(), info.senderJid().toSignalAddress());
                        var signalGroup = new GroupCipher(senderName, keys);
                        yield signalGroup.decrypt(message);
                    }

                    case "pkmsg" -> {
                        var session = new SessionCipher(info.chatJid().toSignalAddress(), keys);
                        var preKey = SignalPreKeyMessage.ofSerialized(message);
                        yield session.decrypt(preKey);
                    }

                    case "msg" -> {
                        var session = new SessionCipher(info.chatJid().toSignalAddress(), keys);
                        var signalMessage = SignalMessage.ofSerialized(message);
                        yield session.decrypt(signalMessage);
                    }

                    default -> throw new IllegalArgumentException("Unsupported encoded message type: %s".formatted(type));
                });
            }catch (SecurityException exception){
                streamHandler.handleFailure(400, "hmac_validation", "message_decoding");
                return Optional.empty();
            }
        }

        @SneakyThrows
        private MessageContainer decodeMessageContainer(byte[] buffer) {
            var bufferWithNoPadding = Bytes.of(buffer)
                    .cut(-buffer[buffer.length - 1])
                    .toByteArray();
            return ProtobufDecoder.forType(MessageContainer.class)
                    .decode(bufferWithNoPadding);
        }

        private void saveMessage(MessageInfo info) {
            if(info.message().content() instanceof MediaMessage mediaMessage){
                mediaMessage.storeId(info.storeId());
            }

            var chat = info.chat()
                    .orElseThrow(() -> new NoSuchElementException("Missing chat: %s".formatted(info.chatJid())));
            chat.messages().add(info);
            if(info.timestamp() <= store.initializationTimeStamp()){
                return;
            }

            store.callListeners(listener -> listener.onNewMessage(info));
        }

        private void handleDistributionMessage(SenderKeyDistributionMessage distributionMessage, ContactJid from) {
            var groupName = new SenderKeyName(distributionMessage.groupId(), from.toSignalAddress());
            var builder = new GroupSessionBuilder(keys);
            var message = SignalDistributionMessage.ofSerialized(distributionMessage.data());
            builder.process(groupName, message);
        }

        @SneakyThrows
        private void handleProtocolMessage(MessageInfo info, ProtocolMessage protocolMessage){
            switch(protocolMessage.type()) {
                case HISTORY_SYNC_NOTIFICATION -> {
                    var compressed = Medias.download(protocolMessage.historySyncNotification(), store);
                    var decompressed = SignalHelper.deflate(compressed);
                    var history = ProtobufDecoder.forType(HistorySync.class)
                            .decode(decompressed);
                    switch(history.syncType()) {
                        case INITIAL_BOOTSTRAP -> {
                            history.conversations().forEach(store::addChat);
                            store.callListeners(WhatsappListener::onChats);
                            store.hasSnapshot(true);
                        }

                        case FULL -> history.conversations().forEach(store::addChat);

                        case INITIAL_STATUS_V3 -> {
                            history.statusV3Messages()
                                    .stream()
                                    .peek(message -> message.storeId(store.id()))
                                    .forEach(store.status()::add);
                            store.callListeners(WhatsappListener::onStatus);
                        }

                        case RECENT -> history.conversations()
                                .forEach(this::handleRecentMessage);

                        case PUSH_NAME -> {
                            history.pushNames()
                                    .forEach(this::handNewPushName);
                            store.callListeners(WhatsappListener::onContacts);
                        }
                    }

                    var receipt = withAttributes("receipt",
                            of("to",  keys.companion().toUserJid().toString(), "type", "hist_sync", "id", info.key().id()));
                    send(receipt);
                }

                case APP_STATE_SYNC_KEY_SHARE -> keys.addAppKeys(protocolMessage.appStateSyncKeyShare().keys());

                case REVOKE -> {
                    var chat = info.chat()
                            .orElseThrow(() -> new NoSuchElementException("Missing chat"));
                    var message = store.findMessageById(chat, protocolMessage.key().id())
                            .orElseThrow(() -> new NoSuchElementException("Missing message"));
                    chat.messages().add(message);
                    store.callListeners(listener -> listener.onMessageDeleted(message, true));
                }

                case EPHEMERAL_SETTING -> {
                    var chat = info.chat()
                            .orElseThrow(() -> new NoSuchElementException("Missing chat"));
                    chat.ephemeralMessagesToggleTime(info.timestamp())
                            .ephemeralMessageDuration(protocolMessage.ephemeralExpiration());
                    var setting = new EphemeralSetting(info.ephemeralDuration(), info.timestamp());
                    store.callListeners(listener -> listener.onSetting(setting));
                }
            }
        }

        private void handNewPushName(PushName pushName) {
            var jid = ContactJid.ofUser(pushName.id());
            var oldContact = store.findContactByJid(jid)
                    .orElseGet(() -> createContact(jid));
            oldContact.chosenName(pushName.pushname());
            var name = oldContact.name();
            store.findChatByJid(oldContact.jid())
                    .ifPresentOrElse(chat -> chat.name(name), () -> createChat(oldContact));
            var firstName = pushName.pushname().contains(" ")  ? pushName.pushname().split(" ")[0]
                    : null;
            var action = new ContactAction(pushName.pushname(), firstName);
            store.callListeners(listener -> listener.onAction(action));
        }

        private void createChat(Contact oldContact){
            var newChat = Chat.builder()
                    .jid(oldContact.jid())
                    .name(oldContact.name())
                    .build();
            store.addChat(newChat);
        }

        private Contact createContact(ContactJid jid) {
            var newContact = Contact.ofJid(jid);
            store.addContact(newContact);
            return newContact;
        }

        private void handleRecentMessage(Chat recent) {
            var oldChat = store.findChatByJid(recent.jid());
            if (oldChat.isEmpty()) {
                store.addChat(recent);
                return;
            }

            recent.messages()
                    .stream()
                    .peek(message -> message.storeId(store.id()))
                    .forEach(oldChat.get().messages()::add);
            store.callListeners(listener -> listener.onChatRecentMessages(oldChat.get()));
        }
    }

    private class AppStateHandler {
        private static final int MAX_SYNC_ATTEMPTS = 5;

        public void push(PatchRequest patch) {
            var index = patch.index().getBytes(StandardCharsets.UTF_8);
            var key = keys.appStateKeys().getLast();
            var hashState = keys.findHashStateByName(patch.type()).copy();
            var actionData = ActionDataSync.builder()
                    .index(index)
                    .value(patch.sync())
                    .padding(new byte[0])
                    .version(patch.version())
                    .build();
            var encodedActionData = ProtobufEncoder.encode(actionData);
            var mutationKeys = MutationKeys.of(key.keyData().keyData());
            var encrypted = AesCbc.encrypt(encodedActionData, mutationKeys.macKey());
            var valueMac = generateMac(patch.operation(), encrypted, key.keyId().keyId(), mutationKeys.macKey());
            var indexMac = Hmac.calculateSha256(index, mutationKeys.indexKey());

            var generator = new LTHash(hashState);
            generator.mix(indexMac, valueMac, patch.operation());

            var result = generator.finish();
            hashState.hash(result.hash());
            hashState.indexValueMap(result.indexValueMap());
            hashState.version(hashState.version() + 1);

            var snapshotMac = generateSnapshotMac(hashState.hash(), hashState.version(), patch.type(), mutationKeys.snapshotMacKey());
            var syncId = new KeyId(key.keyId().keyId());
            var patchMac = generatePatchMac(snapshotMac, Bytes.of(valueMac), hashState.version(), patch.type(), mutationKeys.patchMacKey());
            var record = RecordSync.builder()
                    .index(new IndexSync(indexMac))
                    .value(new ValueSync(Bytes.of(encrypted, valueMac).toByteArray()))
                    .keyId(syncId)
                    .build();
            var mutation = MutationSync.builder()
                    .operation(patch.operation())
                    .record(record)
                    .build();
            var sync = PatchSync.builder()
                    .patchMac(patchMac)
                    .keyId(syncId)
                    .mutations(List.of(mutation))
                    .build();
            hashState.indexValueMap().put(getEncoder().encodeToString(indexMac), valueMac);

            var collectionNode = withAttributes("internal",
                    of("name", patch.type(), "version", valueOf(hashState.version() - 1)));
            var patchNode = with("patch",
                    ProtobufEncoder.encode(sync));
            sendQuery("set", "w:sync:app:state",
                    withChildren("sync", collectionNode, patchNode));
            keys.hashStates().put(patch.type(), hashState);

            decodePatch(patch.type(), hashState.version(), hashState, sync)
                    .stream()
                    .map(MutationsRecord::records)
                    .flatMap(Collection::stream)
                    .forEach(this::processSyncActions);
        }

        @SneakyThrows
        public void pull(String... requests) {
            var states = Arrays.stream(requests)
                    .map(LTHashState::new)
                    .peek(state -> keys.hashStates().put(state.name(), state))
                    .toList();

            var nodes = states.stream()
                    .map(LTHashState::toNode)
                    .toList();

            sendQuery("set", "w:sync:app:state", withChildren("sync", nodes))
                    .thenApplyAsync(this::parseSyncRequest)
                    .thenApplyAsync(this::parsePatches)
                    .thenAcceptAsync(actions -> actions.forEach(this::processSyncActions))
                    .exceptionallyAsync(Socket.this::handleError);
        }

        private List<ActionDataSync> parsePatches(List<SnapshotSyncRecord> patches) {
            return patches.stream()
                    .map(patch -> parsePatch(patch, 0))
                    .flatMap(Collection::stream)
                    .toList();
        }

        private List<ActionDataSync> parsePatch(SnapshotSyncRecord patch, int tries) {
            var results = new ArrayList<ActionDataSync>();
            var name = patch.name();
            try {
                if (patch.hasSnapshot()) {
                    var decodedSnapshot = decodeSnapshot(name, patch.snapshot());
                    results.addAll(decodedSnapshot.records());
                }

                if (patch.hasPatches()) {
                    decodePatches(name, patch.patches())
                            .stream()
                            .map(MutationsRecord::records)
                            .forEach(results::addAll);
                }

                return results;
            } catch (Throwable throwable) {
                keys.hashStates().remove(name);
                if (tries > MAX_SYNC_ATTEMPTS) {
                    throw new RuntimeException("Cannot parse patch", throwable);
                }

                return parsePatch(patch, tries + 1);
            }
        }

        private List<SnapshotSyncRecord> parseSyncRequest(Node node) {
            var syncNode = node.findNode("dataSync");
            return syncNode.findNodes("internal")
                    .stream()
                    .map(this::parseSync)
                    .toList();
        }

        private SnapshotSyncRecord parseSync(Node sync) {
            var snapshot = sync.findNode("snapshot");
            var name = sync.attributes().getString("name");
            var more = sync.attributes().getBool("has_more_patches");
            var snapshotSync = decodeSnapshot(snapshot);
            var patches = decodePatches(sync);
            return new SnapshotSyncRecord(name, snapshotSync, patches, more);
        }

        @SneakyThrows
        private SnapshotSync decodeSnapshot(Node snapshot)  {
            if(snapshot == null){
                return null;
            }

            var blob = ProtobufDecoder.forType(ExternalBlobReference.class)
                    .decode(snapshot.bytes());
            var syncedData = Medias.download(blob, store);
            return ProtobufDecoder.forType(SnapshotSync.class)
                    .decode(syncedData);
        }

        private List<PatchSync> decodePatches(Node sync) {
            var versionCode = sync.attributes().getInt("version");
            return requireNonNullElse(sync.findNode("patches"), sync)
                    .findNodes("patch")
                    .stream()
                    .map(patch -> decodePatch(patch, versionCode))
                    .flatMap(Optional::stream)
                    .toList();
        }

        @SneakyThrows
        private Optional<PatchSync> decodePatch(Node patch, int versionCode) {
            if (!patch.hasContent()) {
                return Optional.empty();
            }

            var patchSync = ProtobufDecoder.forType(PatchSync.class)
                    .decode(patch.bytes());
            if (!patchSync.hasVersion()) {
                var version = new VersionSync(versionCode + 1);
                patchSync.version(version);
            }

            return Optional.of(patchSync);
        }

        private void processSyncActions(ActionDataSync mutation) {
            var value = mutation.value();
            if(value == null){
                return;
            }

            var action = value.action();
            if (action != null){
                var jid = ContactJid.ofUser(mutation.messageIndex().chatJid());
                var targetContact = store.findContactByJid(jid);
                var targetChat = store.findChatByJid(jid);
                var targetMessage = targetChat.flatMap(chat -> store.findMessageById(chat, mutation.messageIndex().messageId()));
                switch (action) {
                    case AndroidUnsupportedActions ignored -> {}
                    case ClearChatAction ignored -> targetChat.map(Chat::messages).ifPresent(SortedMessageList::clear);
                    case ContactAction contactAction -> targetContact.ifPresent(contact -> updateContactName(contact, contactAction));
                    case DeleteChatAction ignored -> targetChat.ifPresent(store.chats()::remove);
                    case DeleteMessageForMeAction ignored -> targetMessage.ifPresent(message -> targetChat.ifPresent(chat -> deleteMessage(message, chat)));
                    case MarkChatAsReadAction markAction -> targetChat.ifPresent(chat -> chat.unreadMessages(markAction.read() ? 0 : -1));
                    case MuteAction muteAction -> targetChat.ifPresent(chat -> chat.mute(ChatMute.muted(muteAction.muteEndTimestamp())));
                    case PinAction pinAction -> targetChat.ifPresent(chat -> chat.pinned(pinAction.pinned() ? mutation.value().timestamp() : 0));
                    case StarAction starAction -> targetMessage.ifPresent(message -> message.starred(starAction.starred()));
                    case ArchiveChatAction archiveChatAction -> targetChat.ifPresent(chat -> chat.archived(archiveChatAction.archived()));
                    default -> log.info("Unsupported sync: " + mutation.value().action());
                }

                store.callListeners(listener -> listener.onAction(action));
            }

            var setting = value.setting();
            if(setting != null){
                store.callListeners(listener -> listener.onSetting(setting));
            }

            var features = mutation.value().primaryFeature();
            if(features != null && !features.flags().isEmpty()){
                store.callListeners(listener -> listener.onFeatures(features.flags()));
            }
        }

        private void deleteMessage(MessageInfo message, Chat chat) {
            chat.messages().remove(message);
            store.callListeners(listener -> listener.onMessageDeleted(message, false));
        }

        private void updateContactName(Contact contact, ContactAction action) {
            contact.update(action);
            store.findChatByJid(contact.jid())
                    .ifPresent(chat -> chat.name(contact.name()));
        }

        private List<MutationsRecord> decodePatches(String name, List<PatchSync> patches) {
            var oldState = keys.findHashStateByName(name);
            var newState = oldState.copy();
            var result = patches.stream()
                    .map(patch -> decodePatch(name, oldState.version(), newState, patch))
                    .flatMap(Optional::stream)
                    .toList();
            keys.hashStates().put(name, newState);
            return result;
        }

        @SneakyThrows
        private Optional<MutationsRecord> decodePatch(String name, long minimumVersionNumber, LTHashState newState, PatchSync patch) {
            if(patch.hasExternalMutations()) {
                var blob = Medias.download(patch.externalMutations(), store);
                var mutationsSync = ProtobufDecoder.forType(MutationsSync.class)
                        .decode(blob);
                patch.mutations().addAll(mutationsSync.mutations());
            }

            newState.version(patch.version().version());
            if(!Arrays.equals(calculateSyncMac(patch, name), patch.patchMac())){
                streamHandler.handleFailure(400, "hmac_validation", "patch");
                return Optional.empty();
            }

            var mutations = decodeMutations(patch.mutations(), newState);
            newState.hash(mutations.hash());
            newState.indexValueMap(mutations.indexValueMap());
            // FIXME: 06/02/2022 Invalid hmac
            // if(!Arrays.equals(generatePatchMac(name, newState, patch), patch.snapshotMac())){
            //    streamHandler.handleFailure(400, "hmac_validation", "snapshot");
            //    return Optional.empty();
            //  }

            return Optional.of(mutations)
                    .filter(ignored -> patch.version().version() == 0 || patch.version().version() > minimumVersionNumber);
        }

        private byte[] generatePatchMac(String name, LTHashState newState, PatchSync patch) {
            var appStateSyncKey = keys.findAppKeyById(patch.keyId().id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
            var mutationKeys = MutationKeys.of(appStateSyncKey.keyData().keyData());
            return generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.snapshotMacKey());
        }

        private byte[] calculateSyncMac(PatchSync sync, String name) {
            var appStateSyncKey = keys.findAppKeyById(sync.keyId().id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
            var mutationKeys = MutationKeys.of(appStateSyncKey.keyData().keyData());
            var mutationMacs = sync.mutations()
                    .stream()
                    .map(mutation -> mutation.record().value().blob())
                    .map(Bytes::of)
                    .map(binary -> binary.slice(-32))
                    .reduce(newBuffer(), Bytes::append);
            return generatePatchMac(sync.snapshotMac(), mutationMacs, sync.version().version(), name, mutationKeys.patchMacKey());
        }

        private MutationsRecord decodeSnapshot(String name, SnapshotSync snapshot) {
            var newState = new LTHashState(snapshot.version().version());
            var mutations = decodeMutations(snapshot.records(), newState);
            newState.hash(mutations.hash());
            newState.indexValueMap(mutations.indexValueMap());
            if(!Arrays.equals(snapshot.mac(), computeSnapshotMac(name, snapshot, newState))){
                streamHandler.handleFailure(400, "hmac_validation", "snapshot");
                return mutations;
            }

            var oldState = keys.findHashStateByName(name);
            var required = oldState.version() == 0 || newState.version() > oldState.version();
            if(!required){
                mutations.records().clear();
            }

            keys.hashStates().put(name, newState);
            return mutations;
        }

        private byte[] computeSnapshotMac(String name, SnapshotSync snapshot, LTHashState newState) {
            var encryptedKey = keys.findAppKeyById(snapshot.keyId().id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));
            var mutationKeys = MutationKeys.of(encryptedKey.keyData().keyData());
            return generateSnapshotMac(newState.hash(), newState.version(), name, mutationKeys.snapshotMacKey());
        }

        private MutationsRecord decodeMutations(List<? extends ParsableMutation> syncs, LTHashState initialState) {
            var generator = new LTHash(initialState);
            var mutations = syncs.stream()
                    .map(mutation -> decodeMutation(generator, mutation))
                    .toList();
            var result = generator.finish();
            return new MutationsRecord(result.hash(), result.indexValueMap(), mutations);
        }

        @SneakyThrows
        private ActionDataSync decodeMutation(LTHash generator, ParsableMutation mutation) {
            var appStateSyncKey = keys.findAppKeyById(mutation.id())
                    .orElseThrow(() -> new NoSuchElementException("No keys available for mutation"));

            var mutationKeys = MutationKeys.of(appStateSyncKey.keyData().keyData());
            var encryptedBlob = mutation.valueBlob()
                    .cut(-32)
                    .toByteArray();
            var encryptedMac = mutation.valueBlob()
                    .slice(-32)
                    .toByteArray();
            if(!Arrays.equals(generateMac(MutationSync.Operation.SET, encryptedBlob, mutation.id(), mutationKeys.macKey()), encryptedMac)){
                streamHandler.handleFailure(400, "hmac_validation", "mutation");
                throw new RuntimeException();
            }

            var result = AesCbc.decrypt(encryptedBlob, mutationKeys.encKey());
            var actionSync = ProtobufDecoder.forType(ActionDataSync.class).decode(result);
            if(!mutation.indexBlob().contentEquals(Hmac.calculateSha256(actionSync.index(), mutationKeys.indexKey()))){
                streamHandler.handleFailure(400, "hmac_validation", "mutation");
                throw new RuntimeException();
            }

            generator.mix(mutation.indexBlob().toByteArray(), encryptedMac, MutationSync.Operation.SET);
            return actionSync;
        }

        private byte[] generateMac(MutationSync.Operation operation, byte[] data, byte[] keyId, byte[] key) {
            var keyData = (byte) switch (operation){
                case SET -> 0x01;
                case REMOVE -> 0x02;
            };

            var encodedKey = Bytes.of(keyData)
                    .append(keyId)
                    .toByteArray();

            var last = Bytes.newBuffer(7)
                    .append(encodedKey.length)
                    .toByteArray();

            var total = Bytes.of(encodedKey)
                    .append(data)
                    .append(last)
                    .toByteArray();

            return Bytes.of(Hmac.calculateSha512(total, key))
                    .cut(32)
                    .toByteArray();
        }

        private byte[] generateSnapshotMac(byte[] ltHash, long version, String patchName, byte[] key) {
            var total = Bytes.of(ltHash)
                    .append(SignalHelper.toBytes(version))
                    .append(patchName.getBytes(StandardCharsets.UTF_8))
                    .toByteArray();
            return Hmac.calculateSha256(total, key);
        }

        private byte[] generatePatchMac(byte[] snapshotMac, Bytes valueMacs, long version, String type, byte[] key) {
            var total = Bytes.of(snapshotMac)
                    .append(valueMacs)
                    .append(SignalHelper.toBytes(version))
                    .append(type.getBytes(StandardCharsets.UTF_8))
                    .toByteArray();
            return Hmac.calculateSha256(total, key);
        }
    }
}
