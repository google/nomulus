// Copyright 2017 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.keyring.kms;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.services.cloudkms.v1.CloudKMS;
import com.google.api.services.cloudkms.v1.model.CryptoKey;
import com.google.api.services.cloudkms.v1.model.CryptoKeyVersion;
import com.google.api.services.cloudkms.v1.model.DecryptRequest;
import com.google.api.services.cloudkms.v1.model.DecryptResponse;
import com.google.api.services.cloudkms.v1.model.EncryptRequest;
import com.google.api.services.cloudkms.v1.model.EncryptResponse;
import com.google.api.services.cloudkms.v1.model.KeyRing;
import com.google.api.services.cloudkms.v1.model.UpdateCryptoKeyPrimaryVersionRequest;
import google.registry.testing.FakeClock;
import google.registry.testing.FakeSleeper;
import google.registry.util.Retrier;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Unit tests for {@link KmsConnectionImpl}. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KmsConnectionImplTest {

  @Mock private CloudKMS kms;
  @Mock private CloudKMS.Projects kmsProjects;
  @Mock private CloudKMS.Projects.Locations kmsLocations;
  @Mock private CloudKMS.Projects.Locations.KeyRings kmsKeyRings;
  @Mock private CloudKMS.Projects.Locations.KeyRings.Get kmsKeyRingsGet;
  @Mock private CloudKMS.Projects.Locations.KeyRings.Create kmsKeyRingsCreate;
  @Mock private CloudKMS.Projects.Locations.KeyRings.CryptoKeys kmsCryptoKeys;
  @Mock private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.Get kmsCryptoKeysGet;
  @Mock private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.Create kmsCryptoKeysCreate;

  @Mock
  private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.UpdatePrimaryVersion updatePrimaryVersion;

  @Mock
  private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.CryptoKeyVersions kmsCryptoKeyVersions;

  @Mock
  private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.CryptoKeyVersions.Create
      kmsCryptoKeyVersionsCreate;

  @Mock private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.Encrypt kmsCryptoKeysEncrypt;
  @Mock private CloudKMS.Projects.Locations.KeyRings.CryptoKeys.Decrypt kmsCryptoKeysDecrypt;

  @Captor private ArgumentCaptor<KeyRing> keyRing;
  @Captor private ArgumentCaptor<CryptoKey> cryptoKey;
  @Captor private ArgumentCaptor<CryptoKeyVersion> cryptoKeyVersion;
  @Captor private ArgumentCaptor<String> locationName;
  @Captor private ArgumentCaptor<String> keyRingName;
  @Captor private ArgumentCaptor<String> cryptoKeyName;
  @Captor private ArgumentCaptor<EncryptRequest> encryptRequest;
  @Captor private ArgumentCaptor<DecryptRequest> decryptRequest;

  @Captor
  private ArgumentCaptor<UpdateCryptoKeyPrimaryVersionRequest> updateCryptoKeyPrimaryVersionRequest;

  private final Retrier retrier = new Retrier(new FakeSleeper(new FakeClock()), 3);

  @BeforeEach
  void beforeEach() throws Exception {
    when(kms.projects()).thenReturn(kmsProjects);
    when(kmsProjects.locations()).thenReturn(kmsLocations);
    when(kmsLocations.keyRings()).thenReturn(kmsKeyRings);
    when(kmsKeyRings.get(anyString())).thenReturn(kmsKeyRingsGet);
    when(kmsKeyRings.create(anyString(), any(KeyRing.class))).thenReturn(kmsKeyRingsCreate);
    when(kmsKeyRingsCreate.setKeyRingId(anyString())).thenReturn(kmsKeyRingsCreate);
    when(kmsKeyRings.cryptoKeys()).thenReturn(kmsCryptoKeys);
    when(kmsCryptoKeys.get(anyString())).thenReturn(kmsCryptoKeysGet);
    when(kmsCryptoKeys.create(anyString(), any(CryptoKey.class))).thenReturn(kmsCryptoKeysCreate);
    when(kmsCryptoKeysCreate.setCryptoKeyId(anyString())).thenReturn(kmsCryptoKeysCreate);
    when(kmsCryptoKeys.cryptoKeyVersions()).thenReturn(kmsCryptoKeyVersions);
    when(kmsCryptoKeyVersions.create(anyString(), any(CryptoKeyVersion.class)))
        .thenReturn(kmsCryptoKeyVersionsCreate);
    when(kmsCryptoKeyVersionsCreate.execute())
        .thenReturn(new CryptoKeyVersion().setName(KmsTestHelper.DUMMY_CRYPTO_KEY_VERSION));
    when(kmsCryptoKeys.encrypt(anyString(), any(EncryptRequest.class)))
        .thenReturn(kmsCryptoKeysEncrypt);
    when(kmsCryptoKeysEncrypt.execute())
        .thenReturn(
            new EncryptResponse()
                .setName(KmsTestHelper.DUMMY_CRYPTO_KEY_VERSION)
                .setCiphertext(KmsTestHelper.DUMMY_ENCRYPTED_VALUE));
    when(kmsCryptoKeys.decrypt(anyString(), any(DecryptRequest.class)))
        .thenReturn(kmsCryptoKeysDecrypt);
    when(kmsCryptoKeys.updatePrimaryVersion(
            anyString(), any(UpdateCryptoKeyPrimaryVersionRequest.class)))
        .thenReturn(updatePrimaryVersion);
  }

  @Test
  void test_encrypt_createsKeyRingIfNotFound() throws Exception {
    when(kmsKeyRingsGet.execute()).thenThrow(createNotFoundException());

    new KmsConnectionImpl("foo", "bar", retrier, kms).encrypt("key", "moo".getBytes(UTF_8));

    verify(kmsKeyRings).create(locationName.capture(), keyRing.capture());
    assertThat(locationName.getValue()).isEqualTo("projects/foo/locations/global");
    assertThat(keyRing.getValue()).isEqualTo(new KeyRing());
    verify(kmsKeyRingsCreate).setKeyRingId(keyRingName.capture());
    assertThat(keyRingName.getValue()).isEqualTo("bar");

    verify(kmsKeyRingsCreate).execute();
    verifyEncryptKmsApiCalls(
        "moo",
        "projects/foo/locations/global/keyRings/bar",
        "projects/foo/locations/global/keyRings/bar/cryptoKeys/key");
  }

  @Test
  void test_encrypt_newCryptoKey() throws Exception {
    when(kmsCryptoKeysGet.execute()).thenThrow(createNotFoundException());

    new KmsConnectionImpl("foo", "bar", retrier, kms).encrypt("key", "moo".getBytes(UTF_8));

    verify(kmsCryptoKeys).create(keyRingName.capture(), cryptoKey.capture());
    assertThat(keyRingName.getValue()).isEqualTo("projects/foo/locations/global/keyRings/bar");
    assertThat(cryptoKey.getValue()).isEqualTo(new CryptoKey().setPurpose("ENCRYPT_DECRYPT"));
    verify(kmsCryptoKeysCreate).setCryptoKeyId(cryptoKeyName.capture());
    assertThat(cryptoKeyName.getValue()).isEqualTo("key");
    verify(kmsCryptoKeysCreate).execute();
    verify(kmsCryptoKeyVersionsCreate, never()).execute();
    verify(updatePrimaryVersion, never()).execute();

    verifyEncryptKmsApiCalls(
        "moo",
        "projects/foo/locations/global/keyRings/bar",
        "projects/foo/locations/global/keyRings/bar/cryptoKeys/key");
  }

  @Test
  void test_encrypt() throws Exception {
    new KmsConnectionImpl("foo", "bar", retrier, kms).encrypt("key", "moo".getBytes(UTF_8));

    verify(kmsCryptoKeyVersions).create(cryptoKeyName.capture(), cryptoKeyVersion.capture());
    assertThat(cryptoKeyName.getValue())
        .isEqualTo("projects/foo/locations/global/keyRings/bar/cryptoKeys/key");

    verify(kmsCryptoKeys)
        .updatePrimaryVersion(
            cryptoKeyName.capture(), updateCryptoKeyPrimaryVersionRequest.capture());
    assertThat(cryptoKeyName.getValue())
        .isEqualTo("projects/foo/locations/global/keyRings/bar/cryptoKeys/key");
    assertThat(updateCryptoKeyPrimaryVersionRequest.getValue())
        .isEqualTo(
            new UpdateCryptoKeyPrimaryVersionRequest()
                .setCryptoKeyVersionId(KmsTestHelper.DUMMY_CRYPTO_KEY_VERSION));

    verifyEncryptKmsApiCalls(
        "moo",
        "projects/foo/locations/global/keyRings/bar",
        "projects/foo/locations/global/keyRings/bar/cryptoKeys/key");
  }

  @Test
  void test_decrypt() throws Exception {
    when(kmsCryptoKeysDecrypt.execute())
        .thenReturn(new DecryptResponse().encodePlaintext("moo".getBytes(UTF_8)));

    byte[] plaintext = new KmsConnectionImpl("foo", "bar", retrier, kms).decrypt("key", "blah");

    verify(kmsCryptoKeys).decrypt(cryptoKeyName.capture(), decryptRequest.capture());
    assertThat(cryptoKeyName.getValue())
        .isEqualTo("projects/foo/locations/global/keyRings/bar/cryptoKeys/key");
    assertThat(decryptRequest.getValue()).isEqualTo(new DecryptRequest().setCiphertext("blah"));
    assertThat(plaintext).isEqualTo("moo".getBytes(UTF_8));
  }

  private void verifyEncryptKmsApiCalls(
      String goldenValue, String goldenCryptoKeyRingName, String goldenCryptoKeyName)
      throws Exception {
    verify(kmsKeyRings).get(keyRingName.capture());
    assertThat(keyRingName.getValue()).isEqualTo(goldenCryptoKeyRingName);

    verify(kmsCryptoKeys).get(cryptoKeyName.capture());
    assertThat(cryptoKeyName.getValue()).isEqualTo(goldenCryptoKeyName);

    verify(kmsCryptoKeys).encrypt(cryptoKeyName.capture(), encryptRequest.capture());
    assertThat(cryptoKeyName.getValue()).isEqualTo(goldenCryptoKeyName);
    assertThat(encryptRequest.getValue())
        .isEqualTo(new EncryptRequest().encodePlaintext(goldenValue.getBytes(UTF_8)));
  }

  private static GoogleJsonResponseException createNotFoundException() throws Exception {
    ByteArrayInputStream inputStream = new ByteArrayInputStream("".getBytes(UTF_8));
    HttpResponse response = GoogleJsonResponseExceptionHelper.createHttpResponse(404, inputStream);
    HttpResponseException.Builder httpResponseExceptionBuilder =
        new HttpResponseException.Builder(response);
    httpResponseExceptionBuilder.setStatusCode(404);
    httpResponseExceptionBuilder.setStatusMessage("NOT_FOUND");
    return new GoogleJsonResponseException(httpResponseExceptionBuilder, null);
  }
}
