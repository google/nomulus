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

package google.registry.request.auth;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableSet;

/** Information provided by the OAuth authentication mechanism (only) about the session. */
@AutoValue
public abstract class OAuthTokenInfo {

  /** Authorized OAuth scopes granted by the access token provided with the request. */
  abstract ImmutableSet<String> authorizedScopes();

  /** OAuth client ID from the access token provided with the request. */
  abstract String oauthClientId();

  /**
   * Raw OAuth access token value provided with the request, for passing along to downstream APIs as
   * appropriate.
   *
   * <p>Note that the request parsing code makes certain assumptions about whether the Authorization
   * header was used as the source of the token. Because OAuthService could theoretically fall back
   * to some other source of authentication, it might be possible for rawAccessToken not to have
   * been the source of OAuth authentication. Looking at the code of OAuthService, that could not
   * currently happen, but if OAuthService were modified in the future so that it tried the bearer
   * token, and then when that failed, fell back to another, successful authentication path, then
   * rawAccessToken might not be valid.
   */
  abstract String rawAccessToken();

  static OAuthTokenInfo create(
      ImmutableSet<String> authorizedScopes, String oauthClientId, String rawAccessToken) {
    return new AutoValue_OAuthTokenInfo(authorizedScopes, oauthClientId, rawAccessToken);
  }
}
