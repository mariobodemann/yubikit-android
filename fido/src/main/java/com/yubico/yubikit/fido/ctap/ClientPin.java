/*
 * Copyright (C) 2020-2023 Yubico.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yubico.yubikit.fido.ctap;

import com.yubico.yubikit.core.application.CommandException;
import com.yubico.yubikit.core.internal.Logger;
import com.yubico.yubikit.core.util.Pair;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Implements Client PIN commands.
 */
public class ClientPin {
    private static final byte CMD_GET_RETRIES = 0x01;
    private static final byte CMD_GET_KEY_AGREEMENT = 0x02;
    private static final byte CMD_SET_PIN = 0x03;
    private static final byte CMD_CHANGE_PIN = 0x04;
    private static final byte CMD_GET_PIN_TOKEN = 0x05;
    private static final byte CMD_GET_PIN_TOKEN_USING_UV_WITH_PERMISSIONS = 0x06;
    private static final byte CMD_GET_UV_RETRIES = 0x07;
    private static final byte CMD_GET_PIN_TOKEN_USING_PIN_WITH_PERMISSIONS = 0x09;

    private static final int RESULT_KEY_AGREEMENT = 0x01;
    private static final int RESULT_PIN_TOKEN = 0x02;
    private static final int RESULT_RETRIES = 0x03;

    private static final int MIN_PIN_LEN = 4;
    private static final int PIN_BUFFER_LEN = 64;
    private static final int MAX_PIN_LEN = PIN_BUFFER_LEN - 1;
    private static final int PIN_HASH_LEN = 16;

    public static final int PIN_PERMISSION_NONE = 0x00;
    public static final int PIN_PERMISSION_MC = 0x01;
    public static final int PIN_PERMISSION_GA = 0x02;
    public static final int PIN_PERMISSION_CM = 0x04;
    public static final int PIN_PERMISSION_BE = 0x08;
    public static final int PIN_PERMISSION_LBW = 0x10;
    public static final int PIN_PERMISSION_ACFG = 0x20;
    public static final int PIN_PERMISSION_DEFAULT = PIN_PERMISSION_MC | PIN_PERMISSION_GA;
    public static final String PIN_PERMISSION_DEFAULT_RPID = "localhost";

    private final Ctap2Session ctap;
    private final PinUvAuthProtocol pinUvAuth;
    private final FidoVersion fidoVersion;

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(ClientPin.class);

    /**
     * Construct a new ClientPin object using a specified PIN/UV Auth protocol.
     *
     * @param ctap       an active CTAP2 connection
     * @param pinUvAuth the PIN/UV Auth protocol to use
     */
    public ClientPin(Ctap2Session ctap, FidoVersion fidoVersion, PinUvAuthProtocol pinUvAuth) {

        if (fidoVersion == FidoVersion.U2F_V2 || fidoVersion == FidoVersion.INVALID) {
            throw new IllegalArgumentException("Unsupported version");
        }

        this.ctap = ctap;
        this.pinUvAuth = pinUvAuth;
        this.fidoVersion = fidoVersion;
    }

    private Pair<Map<Integer, ?>, byte[]> getSharedSecret() throws IOException, CommandException {
        Logger.debug(logger, "Getting shared secret");
        Map<Integer, ?> result = ctap.clientPin(
                pinUvAuth.getVersion(),
                CMD_GET_KEY_AGREEMENT,
                null,
                null,
                null,
                null,
                null,
                null
        );

        @SuppressWarnings("unchecked")
        Map<Integer, ?> peerCoseKey = Objects.requireNonNull((Map<Integer, ?>) result.get(RESULT_KEY_AGREEMENT));
        return pinUvAuth.encapsulate(peerCoseKey);
    }

    /**
     * Get the underlying Pin/UV Auth protocol in use.
     *
     * @return the PinUvAuthProtocol in use
     */
    public PinUvAuthProtocol getPinUvAuth() {
        return pinUvAuth;
    }

    /**
     * Get the number of invalid PIN attempts available before the PIN becomes blocked.
     *
     * @return The number of PIN attempts remaining
     * @throws IOException                   A communication error in the transport layer.
     * @throws CommandException A communication in the protocol layer.
     */
    public int getPinRetries() throws IOException, CommandException {
        Logger.debug(logger, "Getting PIN retries");
        Map<Integer, ?> result = ctap.clientPin(
                pinUvAuth.getVersion(),
                CMD_GET_RETRIES,
                null,
                null,
                null,
                null,
                null,
                null
        );

        return Objects.requireNonNull((Integer) result.get(RESULT_RETRIES));
    }

    private Integer getPermissions(byte command) {
        switch(command) {
            case Ctap2Session.CMD_MAKE_CREDENTIAL:
                return PIN_PERMISSION_MC;
            case Ctap2Session.CMD_GET_ASSERTION:
                return PIN_PERMISSION_GA;
            case Ctap2Session.CMD_BIO_ENROLLMENT:
            case Ctap2Session.CMD_BIO_ENROLLMENT_PRE:
                return PIN_PERMISSION_BE;

            case Ctap2Session.CMD_CREDENTIAL_MANAGEMENT:
            case Ctap2Session.CMD_CREDENTIAL_MANAGEMENT_PRE:
                return PIN_PERMISSION_CM;

            case Ctap2Session.CMD_LARGE_BLOBS:
                return PIN_PERMISSION_LBW;

            case Ctap2Session.CMD_CONFIG:
                return PIN_PERMISSION_ACFG;

            case Ctap2Session.CMD_GET_INFO:
            case Ctap2Session.CMD_CLIENT_PIN:
            case Ctap2Session.CMD_RESET:
            case Ctap2Session.CMD_GET_NEXT_ASSERTION:
            case Ctap2Session.CMD_SELECTION:
            default:
                return PIN_PERMISSION_NONE;
        }
    }


    /**
//     * Get a pinToken from the YubiKey which can be use to authenticate commands for the given
//     * session.
//     *
//     * @param pin The FIDO PIN set for the YubiKey.
//     * @return A pinToken valid for the current CTAP2 session.
//     * @throws IOException                   A communication error in the transport layer.
//     * @throws CommandException A communication in the protocol layer.
//     */
//    public byte[] getPinToken(@Nullable char[] pin)
//            throws IOException, CommandException {
//        return getPinToken(pin, null, null);
//    }
//
//    /**
//     * Get a pinToken from the YubiKey which can be use to authenticate commands for the given
//     * session.
//     *
//     * @param pin The FIDO PIN set for the YubiKey.
//     * @param permissions requested permissions
//     * @return A pinToken valid for the current CTAP2 session.
//     * @throws IOException                   A communication error in the transport layer.
//     * @throws CommandException A communication in the protocol layer.
//     */
//    public byte[] getPinToken(@Nullable char[] pin, @Nullable Integer permissions)
//            throws IOException, CommandException {
//        return getPinToken(pin, permissions, null);
//    }

    /**
     * Get a pinToken from the YubiKey which can be use to authenticate commands for the given
     * session.
     *
     * @param pin The FIDO PIN set for the YubiKey.
     * @param permissions requested permissions
     * @param rpId rpId for token
     * @return A pinToken valid for the current CTAP2 session.
     * @throws IOException                   A communication error in the transport layer.
     * @throws CommandException A communication in the protocol layer.
     */
    public byte[] getPinToken(@Nullable char[] pin,
                              @Nullable Integer permissions,
                              @Nullable String rpId)
            throws IOException, CommandException {
        Pair<Map<Integer, ?>, byte[]> pair = getSharedSecret();
        byte[] pinHash = null;
        try {
            pinHash = Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(preparePin(pin, false)),
                    PIN_HASH_LEN);
            byte[] pinHashEnc = pinUvAuth.encrypt(pair.second, pinHash);

            Logger.debug(logger, "Getting PIN token");

            byte subCommand;

            switch (fidoVersion) {
                case FIDO_2_1:
                    if (pin == null) {
                        subCommand = CMD_GET_PIN_TOKEN_USING_UV_WITH_PERMISSIONS;
                    } else {
                        subCommand = CMD_GET_PIN_TOKEN_USING_PIN_WITH_PERMISSIONS;
                    }
                    break;

                case FIDO_2_1_PRE:
                case FIDO_2_0:
                    subCommand = CMD_GET_PIN_TOKEN;
                    break;
                default:
                    throw new IllegalArgumentException("PIN not supported");
            }

            Map<Integer, ?> result = ctap.clientPin(
                    pinUvAuth.getVersion(),
                    subCommand,
                    pair.first,
                    null,
                    null,
                    pinHashEnc,
                    subCommand != CMD_GET_PIN_TOKEN ? permissions : null,
                    subCommand != CMD_GET_PIN_TOKEN ? rpId : null
            );

            byte[] pinTokenEnc = (byte[]) result.get(RESULT_PIN_TOKEN);
            return pinUvAuth.decrypt(pair.second, pinTokenEnc);
        } catch (NoSuchAlgorithmException e) {
            Logger.error(logger, "Failure getting PIN token: ", e);
            throw new IllegalStateException(e);
        } finally {
            if (pinHash != null) {
                Arrays.fill(pinHash, (byte) 0);
            }
        }
    }

    /**
     * Set the FIDO PIN on a YubiKey with no PIN currently set.
     *
     * @param pin The PIN to set
     * @throws IOException                   A communication error in the transport layer.
     * @throws CommandException A communication in the protocol layer.
     */
    public void setPin(char[] pin) throws IOException, CommandException {
        Pair<Map<Integer, ?>, byte[]> pair = getSharedSecret();

        byte[] pinEnc = pinUvAuth.encrypt(pair.second, preparePin(pin, true));
        Logger.debug(logger, "Setting PIN");
        ctap.clientPin(
                pinUvAuth.getVersion(),
                CMD_SET_PIN,
                pair.first,
                pinUvAuth.authenticate(pair.second, pinEnc),
                pinEnc,
                null,
                PIN_PERMISSION_CM,
                null
        );
        Logger.info(logger, "PIN set");
    }

    /**
     * Change the FIDO PIN on a YubiKey.
     *
     * @param currentPin The currently set PIN
     * @param newPin     The new PIN to set
     * @throws IOException                   A communication error in the transport layer.
     * @throws CommandException A communication in the protocol layer.
     */
    public void changePin(char[] currentPin, char[] newPin)
            throws IOException, CommandException
    {
        byte[] newPinBytes = preparePin(newPin, true);
        Pair<Map<Integer, ?>, byte[]> pair = getSharedSecret();

        byte[] pinHash = null;
        try {
            pinHash = Arrays.copyOf(
                    MessageDigest.getInstance("SHA-256").digest(preparePin(currentPin, false)),
                    PIN_HASH_LEN
            );
            byte[] pinHashEnc = pinUvAuth.encrypt(pair.second, pinHash);
            byte[] newPinEnc = pinUvAuth.encrypt(pair.second, newPinBytes);

            Logger.debug(logger, "Changing PIN");

            byte[] pinUvAuthParam = pinUvAuth.authenticate(
                    pair.second,
                    ByteBuffer.allocate(newPinEnc.length + pinHashEnc.length)
                            .put(newPinEnc)
                            .put(pinHashEnc).array()
            );
            ctap.clientPin(
                    pinUvAuth.getVersion(),
                    CMD_CHANGE_PIN,
                    pair.first,
                    pinUvAuthParam,
                    newPinEnc,
                    pinHashEnc,
                    fidoVersion != FidoVersion.FIDO_2_0 ? PIN_PERMISSION_CM : null,
                    null
            );
            Logger.info(logger, "PIN changed");
        } catch (NoSuchAlgorithmException e) {
            Logger.error(logger, "Failure changing PIN: ", e);
            throw new IllegalStateException(e);
        } finally {
            if (pinHash != null) {
                Arrays.fill(pinHash, (byte) 0);
            }
        }
    }

    /**
     * Check PIN length, encode to bytes, and optionally pad.
     */
    static @Nullable byte[] preparePin(@Nullable char[] pin, boolean pad) {
        if (pin == null) {
            return null;
        }

        if (pin.length < MIN_PIN_LEN) {
            throw new IllegalArgumentException(
                    "PIN must be at least " + MIN_PIN_LEN + " characters");
        }
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pin));
        try {
            int byteLen = byteBuffer.limit() - byteBuffer.position();
            if (byteLen > MAX_PIN_LEN) {
                throw new IllegalArgumentException(
                        "PIN must be no more than " + MAX_PIN_LEN + " bytes");
            }
            byte[] pinBytes = new byte[pad ? PIN_BUFFER_LEN : byteLen];
            System.arraycopy(byteBuffer.array(), byteBuffer.position(), pinBytes, 0, byteLen);

            return pinBytes;
        } finally {
            Arrays.fill(byteBuffer.array(), (byte) 0); // clear sensitive data
        }
    }

}
