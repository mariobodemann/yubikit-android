/*
 * Copyright (C) 2023 Yubico.
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

package com.yubico.yubikit.openpgp;

import static com.yubico.yubikit.core.internal.PrivateKeyUtils.bytesToLength;
import static com.yubico.yubikit.openpgp.OpenPgpUtils.decodeBcd;

import com.yubico.yubikit.core.Version;
import com.yubico.yubikit.core.application.ApplicationNotAvailableException;
import com.yubico.yubikit.core.application.ApplicationSession;
import com.yubico.yubikit.core.application.BadResponseException;
import com.yubico.yubikit.core.application.Feature;
import com.yubico.yubikit.core.internal.Logger;
import com.yubico.yubikit.core.keys.PrivateKeyValues;
import com.yubico.yubikit.core.keys.PublicKeyValues;
import com.yubico.yubikit.core.smartcard.Apdu;
import com.yubico.yubikit.core.smartcard.ApduException;
import com.yubico.yubikit.core.smartcard.ApduFormat;
import com.yubico.yubikit.core.smartcard.AppId;
import com.yubico.yubikit.core.smartcard.SW;
import com.yubico.yubikit.core.smartcard.SmartCardConnection;
import com.yubico.yubikit.core.smartcard.SmartCardProtocol;
import com.yubico.yubikit.core.util.Tlv;
import com.yubico.yubikit.core.util.Tlvs;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


public class OpenPgpSession extends ApplicationSession<OpenPgpSession> {
    public static final Feature<OpenPgpSession> FEATURE_RESET = new Feature.Versioned<>("Reset", 1, 0, 6);
    public static final Feature<OpenPgpSession> FEATURE_UIF = new Feature.Versioned<>("UIF", 4, 2, 0);
    public static final Feature<OpenPgpSession> FEATURE_ATTESTATION = new Feature.Versioned<>("Attestation", 5, 2, 1);
    public static final Feature<OpenPgpSession> FEATURE_CACHED = new Feature.Versioned<>("Cached UIF", 5, 2, 1);
    public static final Feature<OpenPgpSession> FEATURE_EC_KEYS = new Feature.Versioned<>("Elliptic curve keys", 5, 2, 0);
    public static final Feature<OpenPgpSession> FEATURE_PIN_ATTEMPTS = new Feature<OpenPgpSession>("Set PIN attempts") {
        @Override
        public boolean isSupportedBy(Version version) {
            if (version.major == 1) {
                // YubiKey NEO
                return version.isAtLeast(1, 0, 7);
            }
            return version.isAtLeast(4, 3, 1);
        }
    };

    /**
     * Support for generating RSA keys.
     */
    public static final Feature<OpenPgpSession> FEATURE_RSA_GENERATION = new Feature<OpenPgpSession>("RSA key generation") {
        @Override
        public boolean isSupportedBy(Version version) {
            return version.isLessThan(4, 2, 6) || version.isAtLeast(4, 3, 5);
        }
    };

    private static final byte INS_VERIFY = 0x20;
    private static final byte INS_PSO = 0x2A;
    private static final byte INS_ACTIVATE = 0x44;
    private static final byte INS_GENERATE_ASYM = 0x47;
    private static final byte INS_GET_CHALLENGE = (byte) 0x84;
    private static final byte INS_INTERNAL_AUTHENTICATE = (byte) 0x88;
    private static final byte INS_GET_DATA = (byte) 0xca;
    private static final byte INS_PUT_DATA = (byte) 0xda;
    private static final byte INS_PUT_DATA_ODD = (byte) 0xdb;
    private static final byte INS_TERMINATE = (byte) 0xe6;
    private static final byte INS_GET_VERSION = (byte) 0xf1;
    private static final byte INS_SET_PIN_RETRIES = (byte) 0xf2;
    /*
    VERIFY = 0x20
    CHANGE_PIN = 0x24
    RESET_RETRY_COUNTER = 0x2C
    PSO = 0x2A
    ACTIVATE = 0x44
    GENERATE_ASYM = 0x47
    GET_CHALLENGE = 0x84
    INTERNAL_AUTHENTICATE = 0x88
    SELECT_DATA = 0xA5
    GET_DATA = 0xCA
    PUT_DATA = 0xDA
    PUT_DATA_ODD = 0xDB
    TERMINATE = 0xE6
    GET_VERSION = 0xF1
    SET_PIN_RETRIES = 0xF2
    GET_ATTESTATION = 0xFB
     */

    private static final int TAG_PUBLIC_KEY = 0x7F49;

    private static final byte[] INVALID_PIN = new byte[8];

    private final SmartCardProtocol protocol;
    private final Version version;
    private final ApplicationRelatedData appData;

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(OpenPgpSession.class);

    public OpenPgpSession(SmartCardConnection connection) throws IOException, ApplicationNotAvailableException, ApduException {
        protocol = new SmartCardProtocol(connection);

        try {
            protocol.select(AppId.OPENPGP);
        } catch (IOException e) {
            // The OpenPGP applet can be in an inactive state, in which case it needs activation.
            activate(e);
        }

        Logger.debug(logger, "Getting version number");
        byte[] versionBcd = protocol.sendAndReceive(new Apdu(0, INS_GET_VERSION, 0, 0, null));
        byte[] versionBytes = new byte[3];
        for (int i = 0; i < 3; i++) {
            versionBytes[i] = decodeBcd(versionBcd[i]);
        }
        version = Version.fromBytes(versionBytes);
        protocol.enableWorkarounds(version);

        // use extended length APDUs on compatible connections and devices
        if (connection.isExtendedLengthApduSupported() && version.isAtLeast(4, 0, 0)) {
            protocol.setApduFormat(ApduFormat.EXTENDED);
        }

        // Note: This value is cached!
        // Do not rely on contained information that can change!
        appData = getApplicationRelatedData();

        Logger.debug(logger, "OpenPGP session initialized (version={})", version);
    }

    private void activate(IOException e) throws IOException, ApduException, ApplicationNotAvailableException {
        Throwable cause = e.getCause();
        if (cause instanceof ApduException) {
            short sw = ((ApduException) cause).getSw();
            if (sw == SW.NO_INPUT_DATA || sw == SW.CONDITIONS_NOT_SATISFIED) {
                //Not activated, activate
                Logger.warn(logger, "Application not active, sending ACTIVATE");
                protocol.sendAndReceive(new Apdu(0, INS_ACTIVATE, 0, 0, null));
                protocol.select(AppId.OPENPGP);
                return;
            }
        }
        throw e;
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public void close() throws IOException {
        protocol.close();
    }

    public byte[] getData(int doId) throws ApduException, IOException {
        Logger.debug(logger, "Reading Data Object {}", doId);
        return protocol.sendAndReceive(new Apdu(0, INS_GET_DATA, doId >> 8, doId & 0xff, null));
    }

    public void putData(int doId, byte[] data) throws ApduException, IOException {
        protocol.sendAndReceive(new Apdu(0, INS_PUT_DATA, doId >> 8, doId & 0xff, data));
        Logger.debug(logger, "Wrote Data Object {}", doId);
    }

    public ApplicationRelatedData getApplicationRelatedData() throws ApduException, IOException {
        return ApplicationRelatedData.parse(getData(Do.APPLICATION_RELATED_DATA));
    }

    public OpenPgpAid getAid() {
        return appData.getAid();
    }

    public ExtendedCapabilities getExtendedCapabilities() {
        return appData.getDiscretionary().getExtendedCapabilities();
    }

    public PwStatus getPinStatus() throws ApduException, IOException {
        return PwStatus.parse(getData(Do.PW_STATUS_BYTES));
    }

    private void doVerify(byte pw, String pin) throws ApduException, IOException {
        byte[] pinEnc = pin.getBytes(StandardCharsets.UTF_8);
        try {
            protocol.sendAndReceive(new Apdu(0, INS_VERIFY, 0, pw, pinEnc));
        } catch (ApduException e) {
            int remaining = getPinStatus().getAttemptsUser();
            throw new IllegalStateException("Wrong PIN, " + remaining + " attempts remaining.");
        }
    }

    public void verifyUserPin(String pin, boolean extended) throws ApduException, IOException {
        doVerify(extended ? (byte) 0x82 : (byte) 0x81, pin);
    }

    public void verifyAdminPin(String pin) throws ApduException, IOException {
        doVerify((byte) 0x83, pin);
    }

    public int getSignatureCounter() throws ApduException, IOException {
        return SecuritySupportTemplate.parse(getData(Do.SECURITY_SUPPORT_TEMPLATE)).getSignatureCounter();
    }

    public byte[] getChallenge(int length) throws ApduException, IOException {
        ExtendedCapabilities capabilities = getExtendedCapabilities();
        if (!capabilities.getFlags().contains(ExtendedCapabilityFlag.GET_CHALLENGE)) {
            throw new UnsupportedOperationException("GET_CHALLENGE is not supported");
        }
        if (length < 0 || length > capabilities.getChallengeMaxLength()) {
            throw new UnsupportedOperationException("Unsupported challenge length");
        }

        Logger.debug(logger, "Getting {} random bytes", length);
        return protocol.sendAndReceive(new Apdu(0, INS_GET_CHALLENGE, 0, 0, null, length));
    }

    public void setSignaturePinPolicy(PinPolicy pinPolicy) throws ApduException, IOException {
        Logger.debug(logger, "Setting Signature PIN policy to {}", pinPolicy);
        putData(Do.PW_STATUS_BYTES, new byte[]{pinPolicy.value});
        Logger.info(logger, "Signature PIN policy set");
    }

    public void reset() throws ApduException, IOException {
        require(FEATURE_RESET);
        Logger.debug(logger, "Preparing OpenPGP reset");

        // Ensure the User and Admin PINs are blocked
        PwStatus status = getPinStatus();
        // TODO: Use PW enum?
        Logger.debug(logger, "Verify User PIN with invalid attempts until blocked");
        for (int i = status.getAttemptsUser(); i > 0; i--) {
            try {
                protocol.sendAndReceive(new Apdu(0, INS_VERIFY, 0, 0x81, INVALID_PIN));
            } catch (ApduException e) {
                // Ignore
            }
        }
        Logger.debug(logger, "Verify Admin PIN with invalid attempts until blocked");
        for (int i = status.getAttemptsAdmin(); i > 0; i--) {
            try {
                protocol.sendAndReceive(new Apdu(0, INS_VERIFY, 0, 0x83, INVALID_PIN));
            } catch (ApduException e) {
                // Ignore
            }
        }

        // Reset the application
        Logger.debug(logger, "Sending TERMINATE, then ACTIVATE");
        protocol.sendAndReceive(new Apdu(0, INS_TERMINATE, 0, 0, null));
        protocol.sendAndReceive(new Apdu(0, INS_ACTIVATE, 0, 0, null));
        Logger.info(logger, "OpenPGP application data reset performed");
    }

    public void setPinAttempts(int userAttempts, int resetAttempts, int adminAttempts) throws ApduException, IOException {
        require(FEATURE_PIN_ATTEMPTS);

        Logger.debug(logger, "Setting PIN attempts to ({}, {}, {})", userAttempts, resetAttempts, adminAttempts);
        protocol.sendAndReceive(new Apdu(
                0,
                INS_SET_PIN_RETRIES,
                0,
                0,
                new byte[]{(byte) userAttempts, (byte) resetAttempts, (byte) adminAttempts}
        ));
        Logger.info(logger, "Number of PIN attempts has been changed");
    }

    public Uif getUif(KeyRef keyRef) throws ApduException, IOException {
        try {
            return Uif.fromValue(getData(keyRef.getUif())[0]);
        } catch (ApduException e) {
            if (e.getSw() == SW.WRONG_PARAMETERS_P1P2) {
                // Not supported
                return Uif.OFF;
            }
            throw e;
        }
    }

    public void setUif(KeyRef keyRef, Uif uif) throws ApduException, IOException {
        require(FEATURE_UIF);
        if (keyRef == KeyRef.ATT) {
            require(FEATURE_ATTESTATION);
        }
        if (uif.isCached()) {
            require(FEATURE_CACHED);
        }

        Logger.debug(logger, "Setting UIF for {} to {}", keyRef, uif);

        if (getUif(keyRef).isFixed()) {
            throw new IllegalStateException("Cannot change UIF when set to FIXED");
        }

        putData(keyRef.getUif(), uif.getBytes());
        Logger.info(logger, "UIF changed for {}", keyRef);
    }

    public Map<KeyRef, List<AlgorithmAttributes>> getAlgorithmInformation() throws ApduException, IOException, BadResponseException {
        if (!getExtendedCapabilities().getFlags().contains(ExtendedCapabilityFlag.ALGORITHM_ATTRIBUTES_CHANGEABLE)) {
            throw new UnsupportedOperationException("Writing Algorithm Attributes is not supported");
        }

        Map<KeyRef, List<AlgorithmAttributes>> data = new HashMap<>();
        if (version.isLessThan(5, 2, 0)) {
            AlgorithmAttributes.Rsa.ImportFormat fmt;
            List<Integer> sizes = new ArrayList<>();
            sizes.add(2048);
            if (version.isLessThan(4, 0, 0)) {
                // Needed by Neo
                fmt = AlgorithmAttributes.Rsa.ImportFormat.CRT_W_MOD;
            } else {
                fmt = AlgorithmAttributes.Rsa.ImportFormat.STANDARD;
                if (!(version.major == 4 && version.minor == 4)) {
                    // Non-FIPS
                    sizes.add(3072);
                    sizes.add(4096);
                }
            }
            List<AlgorithmAttributes> attributes = new ArrayList<>();
            for (int size : sizes) {
                attributes.add(AlgorithmAttributes.Rsa.create(size, fmt));
            }
            data.put(KeyRef.SIG, Collections.unmodifiableList(attributes));
            data.put(KeyRef.DEC, Collections.unmodifiableList(attributes));
            data.put(KeyRef.AUT, Collections.unmodifiableList(attributes));
        } else {
            Logger.debug(logger, "Getting supported Algorithm Information");
            byte[] buf = getData(Do.ALGORITHM_INFORMATION);
            try {
                buf = Tlvs.unpackValue(Do.ALGORITHM_INFORMATION, buf);
            } catch (BufferUnderflowException e) {
                buf = Arrays.copyOf(buf, buf.length + 2);
                buf = Tlvs.unpackValue(Do.ALGORITHM_INFORMATION, buf);
                buf = Arrays.copyOf(buf, buf.length - 2);
            }
            Map<Integer, KeyRef> refs = new HashMap<>();
            for (KeyRef ref : KeyRef.values()) {
                refs.put(ref.getAlgorithmAttributes(), ref);
            }
            for (Tlv tlv : Tlvs.decodeList(buf)) {
                KeyRef ref = refs.get(tlv.getTag());
                if (!data.containsKey(ref)) {
                    data.put(ref, new ArrayList<>());
                }
                data.get(ref).add(AlgorithmAttributes.parse(tlv.getValue()));
            }

            if (version.isLessThan(5, 6, 1)) {
                // Fix for invalid Curve25519 entries:
                // Remove X25519 with EdDSA from all keys
                AlgorithmAttributes invalidX25519 = new AlgorithmAttributes.Ec(
                        (byte) 0x16,
                        OpenPgpCurve.X25519,
                        AlgorithmAttributes.Ec.ImportFormat.STANDARD
                );
                for (List<AlgorithmAttributes> values : data.values()) {
                    values.remove(invalidX25519);
                }

                AlgorithmAttributes x25519 = new AlgorithmAttributes.Ec(
                        (byte) 0x12,
                        OpenPgpCurve.X25519,
                        AlgorithmAttributes.Ec.ImportFormat.STANDARD
                );

                // Add X25519 ECDH for DEC
                if (!data.get(KeyRef.DEC).contains(x25519)) {
                    data.get(KeyRef.DEC).add(x25519);
                }

                // Remove EdDSA from DEC, ATT
                AlgorithmAttributes ed25519 = new AlgorithmAttributes.Ec(
                        (byte) 0x16,
                        OpenPgpCurve.Ed25519,
                        AlgorithmAttributes.Ec.ImportFormat.STANDARD
                );
                data.get(KeyRef.DEC).remove(ed25519);
                data.get(KeyRef.ATT).remove(ed25519);
            }
        }

        return data;
    }

    public void setAlgorithmAttributes(KeyRef keyRef, AlgorithmAttributes attributes) throws BadResponseException, ApduException, IOException {
        Logger.debug(logger, "Setting Algorithm Attributes for {}", keyRef);

        Map<KeyRef, List<AlgorithmAttributes>> supported = getAlgorithmInformation();
        if (!supported.containsKey(keyRef)) {
            throw new UnsupportedOperationException("Key slot not supported");
        }
        if (!supported.get(keyRef).contains(attributes)) {
            throw new UnsupportedOperationException("Algorithm attributes not supported");
        }

        putData(keyRef.getAlgorithmAttributes(), attributes.getBytes());
        Logger.info(logger, "Algorithm Attributes have been changed");
    }

    public void setGenerationTime(KeyRef keyRef, int timestamp) throws ApduException, IOException {
        Logger.debug(logger, "Setting key generation timestamp for {}", keyRef);
        putData(keyRef.getGenerationTime(), ByteBuffer.allocate(4).putInt(timestamp).array());
        Logger.info(logger, "Key generation timestamp set for {}", keyRef);
    }

    public void setFingerprint(KeyRef keyRef, byte[] fingerprint) throws ApduException, IOException {
        Logger.debug(logger, "Setting key fingerprint for {}", keyRef);
        putData(keyRef.getFingerprint(), fingerprint);
        Logger.info(logger, "Key fingerprint set for {}", keyRef);
    }

    static AlgorithmAttributes getKeyAttributes(PrivateKeyValues values, KeyRef keyRef, Version version) {
        if (values instanceof PrivateKeyValues.Rsa) {
            return AlgorithmAttributes.Rsa.create(
                    values.getBitLength(),
                    version.isLessThan(4, 0, 0) ? AlgorithmAttributes.Rsa.ImportFormat.CRT_W_MOD : AlgorithmAttributes.Rsa.ImportFormat.STANDARD
            );
        } else if (values instanceof PrivateKeyValues.Ec) {
            return AlgorithmAttributes.Ec.create(
                    keyRef,
                    OpenPgpCurve.valueOf(((PrivateKeyValues.Ec) values).getCurveParams().name())
            );
        } else {
            throw new IllegalArgumentException("Unsupported private key type");
        }
    }

    static PrivateKeyTemplate getKeyTemplate(PrivateKeyValues values, KeyRef keyRef, boolean useCrt) {
        if (values instanceof PrivateKeyValues.Rsa) {
            int byteLength = values.getBitLength() / 8 / 2;
            PrivateKeyValues.Rsa rsaValues = (PrivateKeyValues.Rsa) values;
            if (useCrt) {
                return new PrivateKeyTemplate.RsaCrt(
                        keyRef.getCrt(),
                        rsaValues.getPublicExponent().toByteArray(),
                        bytesToLength(rsaValues.getPrimeP(), byteLength),
                        bytesToLength(rsaValues.getPrimeQ(), byteLength),
                        bytesToLength(Objects.requireNonNull(rsaValues.getPrimeExponentP()), byteLength),
                        bytesToLength(Objects.requireNonNull(rsaValues.getPrimeExponentQ()), byteLength),
                        bytesToLength(Objects.requireNonNull(rsaValues.getCrtCoefficient()), byteLength),
                        bytesToLength(rsaValues.getModulus(), byteLength)
                );
            } else {
                return new PrivateKeyTemplate.Rsa(
                        keyRef.getCrt(),
                        rsaValues.getPublicExponent().toByteArray(),
                        bytesToLength(rsaValues.getPrimeP(), byteLength),
                        bytesToLength(rsaValues.getPrimeQ(), byteLength)
                );
            }
        } else if (values instanceof PrivateKeyValues.Ec) {
            return new PrivateKeyTemplate.Ec(
                    keyRef.getCrt(),
                    ((PrivateKeyValues.Ec) values).getSecret(),
                    null
            );
        }
        throw new UnsupportedOperationException("Unsupported private key type");
    }

    public PublicKeyValues generateRsaKey(KeyRef keyRef, int keySize) throws BadResponseException, ApduException, IOException {
        require(FEATURE_RSA_GENERATION);
        Logger.debug(logger, "Generating RSA private key for {}", keyRef);

        if (getExtendedCapabilities().getFlags().contains(ExtendedCapabilityFlag.ALGORITHM_ATTRIBUTES_CHANGEABLE)) {
            setAlgorithmAttributes(keyRef, AlgorithmAttributes.Rsa.create(
                    keySize,
                    AlgorithmAttributes.Rsa.ImportFormat.STANDARD
            ));
        } else if (keySize != 2048) {
            throw new UnsupportedOperationException("Algorithm attributes not supported");
        }

        byte[] resp = protocol.sendAndReceive(new Apdu(0, INS_GENERATE_ASYM, 0x80, 0x00, keyRef.getCrt()));
        Map<Integer, byte[]> data = Tlvs.decodeMap(Tlvs.unpackValue(TAG_PUBLIC_KEY, resp));
        Logger.info(logger, "RSA key generated for {}", keyRef);
        return new PublicKeyValues.Rsa(
                new BigInteger(1, data.get(0x81)),
                new BigInteger(1, data.get(0x82))
        );
    }

    public PublicKeyValues generateEcKey(KeyRef keyRef, OpenPgpCurve curve) throws BadResponseException, ApduException, IOException {
        require(FEATURE_EC_KEYS);
        Logger.debug(logger, "Generating EC private key for {}", keyRef);

        setAlgorithmAttributes(keyRef, AlgorithmAttributes.Ec.create(keyRef, curve));

        byte[] resp = protocol.sendAndReceive(new Apdu(0, INS_GENERATE_ASYM, 0x80, 0x00, keyRef.getCrt()));
        Map<Integer, byte[]> data = Tlvs.decodeMap(Tlvs.unpackValue(TAG_PUBLIC_KEY, resp));
        Logger.info(logger, "EC key generated for {}", keyRef);
        byte[] encoded = data.get(0x86);
        if (curve == OpenPgpCurve.Ed25519 || curve == OpenPgpCurve.X25519) {
            return new PublicKeyValues.Cv25519(curve.getValues(), encoded);
        }
        return PublicKeyValues.Ec.fromEncodedPoint(curve.getValues(), encoded);
    }

    public void putKey(KeyRef keyRef, PrivateKeyValues privateKey) throws BadResponseException, ApduException, IOException {
        Logger.debug(logger, "Importing a private key for {}", keyRef);
        AlgorithmAttributes attributes = getKeyAttributes(privateKey, keyRef, version);

        if (getExtendedCapabilities().getFlags().contains(ExtendedCapabilityFlag.ALGORITHM_ATTRIBUTES_CHANGEABLE)) {
            setAlgorithmAttributes(keyRef, attributes);
        } else {
            if (!(attributes instanceof AlgorithmAttributes.Rsa && ((AlgorithmAttributes.Rsa) attributes).getNLen() != 2048)) {
                throw new UnsupportedOperationException("This YubiKey only supports RSA 2048 keys");
            }
        }
        PrivateKeyTemplate template = getKeyTemplate(privateKey, keyRef, version.isLessThan(4, 0, 0));
        protocol.sendAndReceive(new Apdu(0, INS_PUT_DATA_ODD, 0x3f, 0xff, template.getBytes()));
        Logger.info(logger, "Private key imported for {}", keyRef);
    }

    public PublicKeyValues getPublicKey(KeyRef keyRef) throws ApduException, IOException, BadResponseException {
        Logger.debug(logger, "Getting public key for {}", keyRef);
        byte[] resp = protocol.sendAndReceive(new Apdu(0, INS_GENERATE_ASYM, 0x81, 0x00, keyRef.getCrt()));
        Map<Integer, byte[]> data = Tlvs.decodeMap(Tlvs.unpackValue(TAG_PUBLIC_KEY, resp));
        AlgorithmAttributes attributes = getApplicationRelatedData().getDiscretionary().getAlgorithmAttributes(keyRef);
        if (attributes instanceof AlgorithmAttributes.Ec) {
            byte[] encoded = data.get(0x86);
            OpenPgpCurve curve = ((AlgorithmAttributes.Ec) attributes).getCurve();
            if (curve == OpenPgpCurve.Ed25519 || curve == OpenPgpCurve.X25519) {
                return new PublicKeyValues.Cv25519(curve.getValues(), encoded);
            }
            return PublicKeyValues.Ec.fromEncodedPoint(curve.getValues(), encoded);
        } else {
            return new PublicKeyValues.Rsa(
                    new BigInteger(1, data.get(0x81)),
                    new BigInteger(1, data.get(0x82))
            );
        }
    }

    public void deleteKey(KeyRef keyRef) throws BadResponseException, ApduException, IOException {
        Logger.debug(logger, "Deleting private key for {}", keyRef);
        if (version.isLessThan(4, 0, 0)) {
            Logger.debug(logger, "Overwriting with dummy key");
            // Import over the key, using a dummy
            try {
                KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
                rsaGen.initialize(2048);
                putKey(keyRef, PrivateKeyValues.fromPrivateKey(rsaGen.generateKeyPair().getPrivate()));
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        } else {
            Logger.debug(logger, "Changing algorithm attributes");
            // Delete key by changing the key attributes twice.
            // Use putData to avoid checking for RSA 4096 support
            putData(
                    keyRef.getAlgorithmAttributes(),
                    AlgorithmAttributes.Rsa.create(4096, AlgorithmAttributes.Rsa.ImportFormat.STANDARD).getBytes()
            );
            setAlgorithmAttributes(
                    keyRef,
                    AlgorithmAttributes.Rsa.create(2048, AlgorithmAttributes.Rsa.ImportFormat.STANDARD)
            );
        }
        Logger.info(logger, "Private key deleted for {}", keyRef);
    }

    static byte[] formatDssSignature(byte[] response) {
        int split = response.length / 2;
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(response, 0, split));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(response, split, response.length));
        return new Tlv(0x30, Tlvs.encodeList(Arrays.asList(
                new Tlv(0x02, r.toByteArray()),
                new Tlv(0x02, s.toByteArray())
        ))).getBytes();
    }

    public byte[] sign(byte[] payload) throws ApduException, IOException {
        AlgorithmAttributes attributes = getApplicationRelatedData().getDiscretionary().getAlgorithmAttributes(KeyRef.SIG);
        // TODO: Null check
        Logger.debug(logger, "Signing a message with {}", attributes);
        byte[] response = protocol.sendAndReceive(new Apdu(0, INS_PSO, 0x9e, 0x9a, payload));
        Logger.info(logger, "Message signed");
        if (attributes.getAlgorithmId() == 0x13) {
            return formatDssSignature(response);
        }
        return response;
    }

    public byte[] decrypt(byte[] payload) throws ApduException, IOException {
        Logger.debug(logger, "Decrypting a value");
        byte[] response = protocol.sendAndReceive(new Apdu(0, INS_PSO, 0x80, 0x86, ByteBuffer.allocate(payload.length + 1).put((byte) 0).put(payload).array()));
        Logger.info(logger, "Value decrypted");
        return response;
    }

    public byte[] decrypt(PublicKeyValues peerPublicKey) throws ApduException, IOException {
        byte[] encodedPoint;
        if (peerPublicKey instanceof PublicKeyValues.Ec) {
            encodedPoint = ((PublicKeyValues.Ec) peerPublicKey).getEncodedPoint();
        } else if (peerPublicKey instanceof PublicKeyValues.Cv25519) {
            encodedPoint = ((PublicKeyValues.Cv25519) peerPublicKey).getBytes();
        } else {
            throw new IllegalArgumentException("peerPublicKey must be an Elliptic Curve key");
        }

        byte[] response = protocol.sendAndReceive(
                new Apdu(0, INS_PSO, 0x80, 0x86,
                        new Tlv(0xA6,
                                new Tlv(0x7F49,
                                        new Tlv(0x86, encodedPoint).getBytes()
                                ).getBytes()
                        ).getBytes()));
        Logger.info(logger, "ECDH key agreement performed");
        return response;
    }

    public byte[] authenticate(byte[] payload) throws ApduException, IOException {
        AlgorithmAttributes attributes = getApplicationRelatedData().getDiscretionary().getAlgorithmAttributes(KeyRef.AUT);
        // TODO: Null check
        Logger.debug(logger, "Authenticating a message with {}", attributes);
        byte[] response = protocol.sendAndReceive(
                new Apdu(0, INS_INTERNAL_AUTHENTICATE, 0x0, 0x0, payload)
        );
        Logger.info(logger, "Message authenticated");
        if (attributes.getAlgorithmId() == 0x13) {
            return formatDssSignature(response);
        }
        return response;
    }
}