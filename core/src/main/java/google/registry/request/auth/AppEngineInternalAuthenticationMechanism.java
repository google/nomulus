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

import static google.registry.request.auth.AuthLevel.APP;
import static google.registry.request.auth.AuthLevel.NONE;

import com.google.appengine.api.users.UserService;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

/**
 * Authentication mechanism which uses the X-AppEngine-QueueName header set by App Engine for
 * internal requests.
 *
 * <p>
 * Task queue push task requests set this header value to the actual queue name. Cron requests set
 * this header value to __cron, since that's actually the name of the hidden queue used for cron
 * requests. Cron also sets the header X-AppEngine-Cron, which we could check, but it's simpler just
 * to check the one.
 *
 * <p>
 * App Engine allows app admins to set these headers for testing purposes. Because of this, we also
 * need to verify that the current user is null, indicating that there is no user, to prevent
 * access by someone with "admin" privileges.  If the user is an admin, UserService presumably must
 * return a User object.
 *
 * <p>See <a href=
 * "https://cloud.google.com/appengine/docs/java/taskqueue/push/creating-handlers#reading_request_headers">task
 * handler request header documentation</a>
 */
public class AppEngineInternalAuthenticationMechanism implements AuthenticationMechanism {

  // As defined in the App Engine request header documentation.
  private static final String QUEUE_NAME_HEADER = "X-AppEngine-QueueName";

  private UserService userService;

  @Inject
  public AppEngineInternalAuthenticationMechanism(UserService userService) {
    this.userService = userService;
  }

  @Override
  public AuthResult authenticate(HttpServletRequest request) {
    if (request.getHeader(QUEUE_NAME_HEADER) == null || userService.getCurrentUser() != null) {
      return AuthResult.create(NONE);
    } else {
      return AuthResult.create(APP);
    }
  }
}
