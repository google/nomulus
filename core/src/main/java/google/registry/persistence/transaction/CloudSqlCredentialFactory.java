// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.transaction;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.cloud.sql.CredentialFactory;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Factory class to provide {@link Credential} for Cloud SQL library. */
public class CloudSqlCredentialFactory implements CredentialFactory {
  private static Credential credential;

  /** Initialize the factory with given credential json and scopes. */
  public static void setupCredentialFactory(
      String credentialJson, ImmutableList<String> credentialScopes) {
    System.setProperty(
        CredentialFactory.CREDENTIAL_FACTORY_PROPERTY, CloudSqlCredentialFactory.class.getName());
    try {
      GoogleCredential credential =
          GoogleCredential.fromStream(new ByteArrayInputStream(credentialJson.getBytes(UTF_8)));
      if (credential.createScopedRequired()) {
        credential = credential.createScoped(credentialScopes);
      }
      CloudSqlCredentialFactory.credential = credential;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Credential create() {
    return credential;
  }
}
