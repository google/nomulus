// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.rde;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.appengine.api.ThreadManager;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import dagger.Module;
import dagger.Provides;
import google.registry.keyring.api.KeyModule.Key;

/** Dagger module for {@link JSch} which provides SSH/SFTP connectivity. */
@Module
public final class JSchModule {

  @Provides
  static JSch provideJSch(
      @Key("rdeSshClientPrivateKey") String privateKey,
      @Key("rdeSshClientPublicKey") String publicKey) {
    applyAppEngineKludge();
    JSch jsch = new JSch();
    try {
      jsch.addIdentity(
          "mercury-donuts-test@example.test",
          privateKey.getBytes(UTF_8),
          publicKey.getBytes(UTF_8),
          null);
    } catch (JSchException e) {
      throw new RuntimeException(e);
    }
    // TODO(b/13028224): Implement known hosts checking.
    JSch.setConfig("StrictHostKeyChecking", "no");
    return jsch;
  }

  /**
   * Overrides the threadFactory used in JSch and disable {@link Thread#setName(String)} in order to
   * ensure GAE compatibility. By default it uses the default executor, which fails under GAE. This
   * is currently a Google-specific patch that needs to be sent upstream.
   */
  private static void applyAppEngineKludge() {
    JSch.threadFactory = ThreadManager.currentRequestThreadFactory();
    JSch.useThreadNames = false;
  }
}
