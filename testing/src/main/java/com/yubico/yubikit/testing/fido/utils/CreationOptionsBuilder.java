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

package com.yubico.yubikit.testing.fido.utils;

import com.yubico.yubikit.fido.webauthn.AuthenticatorSelectionCriteria;
import com.yubico.yubikit.fido.webauthn.Extensions;
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialCreationOptions;
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialRpEntity;
import com.yubico.yubikit.fido.webauthn.PublicKeyCredentialUserEntity;
import com.yubico.yubikit.fido.webauthn.ResidentKeyRequirement;

import java.util.Collections;
import java.util.Map;

import javax.annotation.Nullable;

public class CreationOptionsBuilder {
    boolean residentKey = false;
    @Nullable
    Extensions extensions = null;
    @Nullable
    PublicKeyCredentialUserEntity userEntity = null;

    public CreationOptionsBuilder residentKey(boolean residentKey) {
        this.residentKey = residentKey;
        return this;
    }

    public CreationOptionsBuilder extensions(@Nullable Map<String, ?> extensions) {
        this.extensions = extensions == null
                ? null
                : Extensions.fromMap(extensions);
        return this;
    }

    public CreationOptionsBuilder userEntity(String name, byte[] id) {
        this.userEntity = new PublicKeyCredentialUserEntity(name, id, name);
        return this;
    }

    public CreationOptionsBuilder userEntity(PublicKeyCredentialUserEntity userEntity) {
        this.userEntity = userEntity;
        return this;
    }

    public PublicKeyCredentialCreationOptions build() {
        PublicKeyCredentialRpEntity rp = TestData.RP;
        AuthenticatorSelectionCriteria criteria = new AuthenticatorSelectionCriteria(
                null,
                residentKey ? ResidentKeyRequirement.REQUIRED : ResidentKeyRequirement.DISCOURAGED,
                null
        );
        return new PublicKeyCredentialCreationOptions(
                rp,
                userEntity != null ? userEntity : TestData.USER,
                TestData.CHALLENGE,
                Collections.singletonList(TestData.PUB_KEY_CRED_PARAMS_ES256),
                (long) 90000,
                null,
                criteria,
                null,
                extensions
        );
    }

}