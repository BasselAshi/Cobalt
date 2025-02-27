package it.auties.whatsapp.util;

import it.auties.curve25519.Curve25519;
import it.auties.whatsapp.api.AsyncCaptchaCodeSupplier;
import it.auties.whatsapp.api.AsyncVerificationCodeSupplier;
import it.auties.whatsapp.controller.Keys;
import it.auties.whatsapp.controller.Store;
import it.auties.whatsapp.crypto.AesGcm;
import it.auties.whatsapp.exception.RegistrationException;
import it.auties.whatsapp.model.mobile.VerificationCodeError;
import it.auties.whatsapp.model.mobile.VerificationCodeMethod;
import it.auties.whatsapp.model.mobile.VerificationCodeResponse;
import it.auties.whatsapp.model.mobile.VerificationCodeStatus;
import it.auties.whatsapp.model.node.Attributes;
import it.auties.whatsapp.model.signal.keypair.SignalKeyPair;
import it.auties.whatsapp.util.Specification.Whatsapp;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class RegistrationHelper {
    public static CompletableFuture<Void> registerPhoneNumber(Store store, Keys keys, AsyncVerificationCodeSupplier codeHandler, AsyncCaptchaCodeSupplier captchaHandler, VerificationCodeMethod method) {
        if (method == VerificationCodeMethod.NONE) {
            return sendVerificationCode(store, keys, codeHandler, captchaHandler);
        }

        return requestVerificationCode(store, keys, method)
                .thenComposeAsync(ignored -> sendVerificationCode(store, keys, codeHandler, captchaHandler));
    }

    public static CompletableFuture<Void> requestVerificationCode(Store store, Keys keys, VerificationCodeMethod method) {
        return requestVerificationCode(store, keys, method, null);
    }

    private static CompletableFuture<Void> requestVerificationCode(Store store, Keys keys, VerificationCodeMethod method, VerificationCodeError lastError) {
        if (method == VerificationCodeMethod.NONE) {
            return CompletableFuture.completedFuture(null);
        }

        return requestVerificationCodeOptions(store, keys, method, lastError)
                .thenComposeAsync(attrs -> sendRegistrationRequest(store, "/code", attrs))
                .thenComposeAsync(result -> checkRequestResponse(store, keys, result.statusCode(), result.body(), lastError, method))
                .thenRunAsync(() -> saveRegistrationStatus(store, keys, false));
    }

    private static CompletableFuture<Void> checkRequestResponse(Store store, Keys keys, int statusCode, String body, VerificationCodeError lastError, VerificationCodeMethod method) {
        if (statusCode != HttpURLConnection.HTTP_OK) {
            throw new RegistrationException(null, body);
        }

        var response = Json.readValue(body, VerificationCodeResponse.class);
        if (response.status() == VerificationCodeStatus.SUCCESS) {
            return CompletableFuture.completedFuture(null);
        }

        System.out.println(body);
        System.out.println(response);
        switch (response.errorReason()) {
            case NO_ROUTES -> throw new RegistrationException(response, "VOIPs are not supported by Whatsapp");
            case TOO_RECENT ->  throw new RegistrationException(response, "Please wait before trying to register this phone number again");
            default -> {
                var newErrorReason = response.errorReason();
                if (newErrorReason != lastError) {
                    return requestVerificationCode(store, keys, method, newErrorReason);
                }

                throw new RegistrationException(response, body);
            }
        }
    }

    private static CompletableFuture<Map<String, Object>> requestVerificationCodeOptions(Store store, Keys keys, VerificationCodeMethod method, VerificationCodeError lastError) {
        var countryCode = store.phoneNumber()
                .orElseThrow()
                .countryCode();
        return getRegistrationOptions(store,
                keys,
                lastError == VerificationCodeError.OLD_VERSION || lastError == VerificationCodeError.BAD_TOKEN,
                Map.entry("mcc", padCountryCodeValue(String.valueOf(countryCode.mcc()))),
                Map.entry("mnc", padCountryCodeValue(countryCode.mnc())),
                Map.entry("sim_mcc", "000"),
                Map.entry("sim_mnc", "000"),
                Map.entry("method", method.type()),
                Map.entry("reason", ""),
                Map.entry("hasav", 2),
                Map.entry("prefer_sms_over_flash", true)

        );
    }

    private static String padCountryCodeValue(String inputString) {
        if (inputString.length() >= 3) {
            return inputString;
        }

        var stringBuilder = new StringBuilder();
        while (stringBuilder.length() < 3 - inputString.length()) {
            stringBuilder.append('0');
        }

        stringBuilder.append(inputString);
        return stringBuilder.toString();
    }

    public static CompletableFuture<Void> sendVerificationCode(Store store, Keys keys, AsyncVerificationCodeSupplier handler, AsyncCaptchaCodeSupplier captchaHandler) {
        return handler.get()
                .thenComposeAsync(result -> sendVerificationCode(store, keys, result, captchaHandler, false))
                .thenRunAsync(() -> saveRegistrationStatus(store, keys, true));
    }

    private static void saveRegistrationStatus(Store store, Keys keys, boolean registered) {
        keys.setRegistered(registered);
        if (registered) {
            var jid = store.phoneNumber().orElseThrow().toJid();
            store.setJid(jid);
            store.addLinkedDevice(jid, 0);
        }
        keys.serialize(true);
        store.serialize(true);
    }

    private static CompletableFuture<Void> sendVerificationCode(Store store, Keys keys, String code, AsyncCaptchaCodeSupplier captchaHandler, boolean badToken) {
        return getRegistrationOptions(store, keys, badToken, Map.entry("code", normalizeCodeResult(code)))
                .thenComposeAsync(attrs -> sendRegistrationRequest(store, "/register", attrs))
                .thenComposeAsync(result -> checkVerificationResponse(store, keys, code, result, captchaHandler));
    }

    private static CompletableFuture<Void> sendVerificationCode(Store store, Keys keys, String code, String captcha) {
        return getRegistrationOptions(store, keys, false, Map.entry("code", normalizeCodeResult(code)), Map.entry("fraud_checkpoint_code", normalizeCodeResult(captcha)))
                .thenComposeAsync(attrs -> sendRegistrationRequest(store, "/register", attrs))
                .thenComposeAsync(result -> checkVerificationResponse(store, keys, code, result, null));
    }

    private static CompletableFuture<Void> checkVerificationResponse(Store store, Keys keys, String code, HttpResponse<String> result, AsyncCaptchaCodeSupplier captchaHandler) {
        if (result.statusCode() != HttpURLConnection.HTTP_OK) {
            throw new RegistrationException(null, result.body());
        }

        var response = Json.readValue(result.body(), VerificationCodeResponse.class);
        if (response.errorReason() == VerificationCodeError.BAD_TOKEN || response.errorReason() == VerificationCodeError.OLD_VERSION) {
            return sendVerificationCode(store, keys, code, captchaHandler, true);
        }

        if (response.errorReason() == VerificationCodeError.CAPTCHA) {
            Objects.requireNonNull(captchaHandler, "Received captcha, but no handler was specified in the options");
            return captchaHandler.apply(response)
                    .thenComposeAsync(captcha -> sendVerificationCode(store, keys, code, captcha));
        }

        if (response.status() == VerificationCodeStatus.SUCCESS) {
            return CompletableFuture.completedFuture(null);
        }

        throw new RegistrationException(response, result.body());
    }

    private static String normalizeCodeResult(String captcha) {
        return captcha.replaceAll("-", "").trim();
    }

    private static CompletableFuture<HttpResponse<String>> sendRegistrationRequest(Store store, String path, Map<String, Object> params) {
        try (var client = createClient(store)) {
            var encodedParams = toFormParams(params);
            var keypair = SignalKeyPair.random();
            var key = Curve25519.sharedKey(Whatsapp.REGISTRATION_PUBLIC_KEY, keypair.privateKey());
            var buffer = AesGcm.encrypt(new byte[12], encodedParams.getBytes(StandardCharsets.UTF_8), key);
            var enc = Base64.getUrlEncoder().encodeToString(BytesHelper.concat(keypair.publicKey(), buffer));
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("%s%s?ENC=%s".formatted(Whatsapp.MOBILE_REGISTRATION_ENDPOINT, path, enc)))
                    .GET()
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", getUserAgent(store))
                    .build();
            return client.sendAsync(request, BodyHandlers.ofString());
        }
    }

    private static String getUserAgent(Store store) {
        var osName = getMobileOsName(store);
        var device = store.device().orElseThrow();
        var osVersion = device.osVersion();
        var manufacturer = device.manufacturer();
        var model = device.model().replaceAll(" ", "_");
        return "WhatsApp/%s %s/%s Device/%s-%s".formatted(store.version(), osName, osVersion, manufacturer, model);
    }

    private static String getMobileOsName(Store store) {
        var device = store.device().orElseThrow();
        return switch (device.platform()) {
            case ANDROID -> "Android";
            case SMB_ANDROID -> "SMBA";
            case IOS -> "iOS";
            case SMB_IOS -> "SMBI";
            default -> throw new IllegalStateException("Unsupported mobile os: " + device.platform());
        };
    }

    private static HttpClient createClient(Store store) {
        var clientBuilder = HttpClient.newBuilder();
        store.proxy().ifPresent(proxy -> {
            clientBuilder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
            clientBuilder.authenticator(new ProxyAuthenticator());
        });
        return clientBuilder.build();
    }

    @SafeVarargs
    private static CompletableFuture<Map<String, Object>> getRegistrationOptions(Store store, Keys keys, boolean isRetry, Entry<String, Object>... attributes) {
        var device = store.device().orElseThrow();
        return MetadataHelper.getToken(store.phoneNumber().orElseThrow().numberWithoutPrefix(), store.business() ? device.businessPlatform() : device.platform(), !isRetry)
                .thenApplyAsync(token -> getRegistrationOptions(store, keys, token, attributes));
    }

    private static Map<String, Object> getRegistrationOptions(Store store, Keys keys, String token, Entry<String, Object>[] attributes) {
        var phoneNumber = store.phoneNumber()
                .orElseThrow(() -> new NoSuchElementException("Missing phone number"));
        var gpiaToken = "CtMBARCnMGtXcq1smbOgv-7yGVv1XR" +
                "_3r038Qkd0PDP1tDYicEbS-wt9W4yiIRZ1Nhe4yuzdU9kc3B4J-2emJmTsJNA8hJ32OoM1qBGTUzgTf6gNb" +
                "_q4VjuQmSIY0XJ7DHSeB4GX" +
                "_k3PegBGME8AwGMOTOM0YDl2mKeGRnD7ZcRqpuz1mjr" +
                "_2mPxljEozMXiV1aP0uPPKLpuh1z2xZvguZ" +
                "_y6mhxlg2mwTDHHPfSt1JkfgSkDEeRyFvxz6V1yuZN6zxgYkP0rvG0ezg8744ou8ol0ONTsRpqAUjrfvTcHPo7nz70oc" +
                "_0gXmuZem" +
                "_vcwQP-mATomTpoazuh1nlqQm72m-5q" +
                "_d9iJv5pFDHr" +
                "_CXxnIpiApPmbjWczHCfkCyWmPDCEgFALemTCA8gkklUWav24eF7nqSV0ShIJjHbenoiG1aA";
        return Attributes.of(attributes)
                .put("lc", "US")
                .put("authkey", Base64.getEncoder().encodeToString(keys.noiseKeyPair().publicKey()))
                .put("e_skey_val", Base64.getEncoder().encodeToString(keys.signedKeyPair().publicKey()))
                .put("in", phoneNumber.numberWithoutPrefix())
                .put("gpia", "{\"token\":\"%s\",\"error_code\":0}".formatted(gpiaToken))
                .put("lg", "us")
                .put("push_code", "UwLlJ0G2vqE%3D")
                .put("feo2_query_status", "error_security_exception")
                .put("sim_type", "1")
                .put("network_radio_type", "1")
                .put("token", token)
                .put("expid", keys.deviceId())
                .put("prefer_sms_over_flash", "true")
                .put("id", keys.recoveryToken())
                .put("e_keytype", "BQ")
                .put("gpia_token", gpiaToken)
                .put("simnum", "0")
                .put("clicked_education_link", "false")
                .put("rc", "0")
                .put("airplane_mode_type", "0")
                .put("mistyped", "7")
                .put("advertising_id", UUID.randomUUID().toString())
                .put("cc", phoneNumber.countryCode().prefix())
                .put("e_regid", Base64.getEncoder().encodeToString(keys.encodedRegistrationId()))
                .put("e_skey_sig", Base64.getEncoder().encodeToString(keys.signedKeyPair().signature()))
                .put("hasinrc", "1")
                .put("roaming_type", "0")
                .put("device_ram", "3,4")
                .put("client_metrics", "{\"attempts\":1}")
                .put("education_screen_displayed", "true")
                .put("e_ident", Base64.getEncoder().encodeToString(keys.identityKeyPair().publicKey()))
                .put("cellular_strength", ThreadLocalRandom.current().nextInt(3, 6))
                .put("e_skey_id", Base64.getEncoder().encodeToString(keys.signedKeyPair().encodedId()))
                .put("fdid", keys.phoneId())
                .toMap();
    }

    private static String toFormParams(Map<String, Object> values) {
        return values.entrySet()
                .stream()
                .map(entry -> "%s=%s".formatted(entry.getKey(), URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8)))
                .collect(Collectors.joining("&"));
    }
}
