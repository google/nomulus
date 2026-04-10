// Copyright 2026 The Nomulus Authors. All Rights Reserved.
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

package google.registry.cache;

import com.google.auth.oauth2.GoogleCredentials;
import google.registry.util.GoogleCredentialsBundle;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;

/** Redisson {@link CredentialsResolver} that uses a GCP service account for IAM auth. */
public class MemorystoreIamCredentialsResolver implements CredentialsResolver {

  private static final String MEMORYSTORE_SCOPE_URI =
      "https://www.googleapis.com/auth/cloud-platform";

  private final GoogleCredentials credentials;

  public MemorystoreIamCredentialsResolver(GoogleCredentialsBundle credentialsBundle) {
    this.credentials = credentialsBundle.getGoogleCredentials().createScoped(MEMORYSTORE_SCOPE_URI);
  }

  @Override
  public CompletionStage<Credentials> resolve(InetSocketAddress inetSocketAddress) {
    try {
      credentials.refreshIfExpired();
    } catch (IOException e) {
      throw new RuntimeException("Failed to fetch IAM token for Memorystore", e);
    }
    String token = credentials.getAccessToken().getTokenValue();
    return CompletableFuture.completedFuture(new Credentials(null, token));
  }
}
