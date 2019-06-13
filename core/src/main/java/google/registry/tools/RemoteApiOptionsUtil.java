// Copyright 2018 The Nomulus Authors. All Rights Reserved.
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

package google.registry.tools;

import static com.google.common.base.Preconditions.checkState;

import com.google.appengine.tools.remoteapi.RemoteApiOptions;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Provides a method to access {@link RemoteApiOptions#useGoogleCredentialStream(InputStream)},
 * which is a package private method.
 *
 * <p>This is obviously a hack, but until that method is exposed, we have to do this to set up the
 * {@link RemoteApiOptions} with a JSON representing a user credential.
 */
public class RemoteApiOptionsUtil {
  static RemoteApiOptions useGoogleCredentialStream(RemoteApiOptions options, InputStream stream)
      throws Exception {
    Method method =
        options.getClass().getDeclaredMethod("useGoogleCredentialStream", InputStream.class);
    checkState(
        !method.isAccessible(),
        "RemoteApiOptoins#useGoogleCredentialStream(InputStream) is accessible."
            + " Stop using RemoteApiOptionsUtil.");
    method.setAccessible(true);
    method.invoke(options, stream);
    method.setAccessible(false);
    return options;
  }
}
