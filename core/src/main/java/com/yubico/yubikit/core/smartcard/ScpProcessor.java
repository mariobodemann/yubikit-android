/*
 * Copyright (C) 2024 Yubico.
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

package com.yubico.yubikit.core.smartcard;

import com.yubico.yubikit.core.application.BadResponseException;
import com.yubico.yubikit.core.smartcard.scp.ScpState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.security.auth.DestroyFailedException;

public class ScpProcessor extends ChainedResponseProcessor {
    private final ScpState state;

    ScpProcessor(SmartCardConnection connection, ScpState state, int maxApduSize, byte insSendRemaining) {
        super(connection, true, maxApduSize, insSendRemaining);
        this.state = state;
    }

    @Override
    public ApduResponse sendApdu(Apdu apdu) throws IOException, BadResponseException {
        return sendApdu(apdu, true);
    }

    public ApduResponse sendApdu(Apdu apdu, boolean encrypt) throws IOException, BadResponseException {
        byte[] data = apdu.getData();
        if (encrypt) {
            data = state.encrypt(data);
        }
        byte cla = (byte) (apdu.getCla() | 0x04);

        // Calculate and add MAC to data
        byte[] macedData = new byte[data.length + 8];
        System.arraycopy(data, 0, macedData, 0, data.length);
        byte[] apduData = processor.formatApdu(cla, apdu.getIns(), apdu.getP1(), apdu.getP2(), macedData, 0, macedData.length, apdu.getLe());
        byte[] mac = state.mac(Arrays.copyOf(apduData, apduData.length - 8));
        System.arraycopy(mac, 0, macedData, macedData.length - 8, 8);

        ApduResponse resp = super.sendApdu(new Apdu(cla, apdu.getIns(), apdu.getP1(), apdu.getP2(), macedData, apdu.getLe()));
        byte[] respData = resp.getData();

        // Un-MAC and decrypt, if needed
        if (respData.length > 0) {
            respData = state.unmac(respData, resp.getSw());
        }
        if (respData.length > 0) {
            respData = state.decrypt(respData);
        }

        return new ApduResponse(ByteBuffer.allocate(respData.length + 2).put(respData).putShort(resp.getSw()).array());
    }

    @Override
    public void close() throws IOException {
        try {
            state.destroy();
        } catch (DestroyFailedException e) {
            throw new IOException(e);
        }
        super.close();
    }
}
