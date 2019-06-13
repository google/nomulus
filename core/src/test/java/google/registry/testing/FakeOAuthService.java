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

package google.registry.testing;

import com.google.appengine.api.oauth.OAuthRequestException;
import com.google.appengine.api.oauth.OAuthService;
import com.google.appengine.api.users.User;
import com.google.common.collect.ImmutableList;
import java.util.List;

/** A fake {@link OAuthService} implementation for testing. */
public class FakeOAuthService implements OAuthService {

  private boolean isOAuthEnabled;
  private User currentUser;
  private boolean isUserAdmin;
  private String clientId;
  private ImmutableList<String> authorizedScopes;

  public FakeOAuthService(
      boolean isOAuthEnabled,
      User currentUser,
      boolean isUserAdmin,
      String clientId,
      List<String> authorizedScopes) {
    this.isOAuthEnabled = isOAuthEnabled;
    this.currentUser = currentUser;
    this.isUserAdmin = isUserAdmin;
    this.clientId = clientId;
    this.authorizedScopes = ImmutableList.copyOf(authorizedScopes);
  }

  public void setOAuthEnabled(boolean isOAuthEnabled) {
    this.isOAuthEnabled = isOAuthEnabled;
  }

  public void setUser(User currentUser) {
    this.currentUser = currentUser;
  }

  public void setUserAdmin(boolean isUserAdmin) {
    this.isUserAdmin = isUserAdmin;
  }

  public void setClientId(String clientId) {
    this.clientId = clientId;
  }

  public void setAuthorizedScopes(String... scopes) {
    this.authorizedScopes = ImmutableList.copyOf(scopes);
  }

  @Override
  public User getCurrentUser() throws OAuthRequestException {
    if (!isOAuthEnabled) {
      throw new OAuthRequestException("invalid OAuth request");
    }
    return currentUser;
  }

  @Override
  public User getCurrentUser(String scope) throws OAuthRequestException {
    return getCurrentUser();
  }

  @Override
  public User getCurrentUser(String... scopes) throws OAuthRequestException {
    return getCurrentUser();
  }

  @Override
  public boolean isUserAdmin() throws OAuthRequestException {
    if (!isOAuthEnabled) {
      throw new OAuthRequestException("invalid OAuth request");
    }
    return isUserAdmin;
  }

  @Override
  public boolean isUserAdmin(String scope) throws OAuthRequestException {
    return isUserAdmin();
  }

  @Override
  public boolean isUserAdmin(String... scopes) throws OAuthRequestException {
    return isUserAdmin();
  }

  @Override
  public String getClientId(String scope) throws OAuthRequestException {
    if (!isOAuthEnabled) {
      throw new OAuthRequestException("invalid OAuth request");
    }
    return clientId;
  }

  @Override
  public String getClientId(String... scopes) throws OAuthRequestException {
    if (!isOAuthEnabled) {
      throw new OAuthRequestException("invalid OAuth request");
    }
    return clientId;
  }

  @Override
  public String[] getAuthorizedScopes(String... scopes) throws OAuthRequestException {
    if (!isOAuthEnabled) {
      throw new OAuthRequestException("invalid OAuth request");
    }
    return authorizedScopes.toArray(new String[0]);
  }

  @Deprecated
  @Override
  public String getOAuthConsumerKey() {
    throw new UnsupportedOperationException();
  }
}
