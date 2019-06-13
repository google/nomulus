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

import com.google.appengine.api.users.User;
import com.google.auto.value.AutoValue;
import java.util.Optional;

/** Extra information provided by the authentication mechanism about the user. */
@AutoValue
public abstract class UserAuthInfo {

  /** User object from the AppEngine Users API. */
  public abstract User user();

  /**
   * Whether the user is an admin.
   *
   * <p>Note that, in App Engine parlance, an admin is any user who is a project owner, editor, OR
   * viewer (as well as the specific role App Engine Admin). So even users with read-only access to
   * the App Engine product qualify as an "admin".
   */
  public abstract boolean isUserAdmin();

  /** Used by the OAuth authentication mechanism (only) to return information about the session. */
  public abstract Optional<OAuthTokenInfo> oauthTokenInfo();

  public static UserAuthInfo create(
      User user, boolean isUserAdmin) {
    return new AutoValue_UserAuthInfo(user, isUserAdmin, Optional.empty());
  }

  public static UserAuthInfo create(
      User user, boolean isUserAdmin, OAuthTokenInfo oauthTokenInfo) {
    return new AutoValue_UserAuthInfo(user, isUserAdmin, Optional.of(oauthTokenInfo));
  }
}
