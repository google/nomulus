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
import java.util.function.Supplier;
import redis.clients.jedis.DefaultRedisCredentials;
import redis.clients.jedis.RedisCredentials;

public class ValkeyCredentialsProvider implements Supplier<RedisCredentials> {

  private static final String MEMORYSTORE_AUTH_SCOPE =
      "https://www.googleapis.com/auth/cloud-platform";

  private final GoogleCredentials credentials;

  public ValkeyCredentialsProvider(GoogleCredentialsBundle credentialsBundle) {
    this.credentials =
        credentialsBundle.getGoogleCredentials().createScoped(MEMORYSTORE_AUTH_SCOPE);
  }

  @Override
  public RedisCredentials get() {
    try {
      credentials.refreshIfExpired();
    } catch (IOException e) {
      throw new RuntimeException("Unable to refresh IAM token for Memorystore", e);
    }
    String token = credentials.getAccessToken().getTokenValue();
    return new DefaultRedisCredentials(null, token);
  }
}
