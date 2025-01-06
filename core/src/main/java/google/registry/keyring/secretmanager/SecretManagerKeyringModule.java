// Copyright 2025 The Nomulus Authors. All Rights Reserved.
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

package google.registry.keyring.secretmanager;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;
import google.registry.keyring.api.Keyring;

/** Dagger module for {@link Keyring} backed by the Cloud SecretManager. */
@Module
public abstract class SecretManagerKeyringModule {

  public static final String NAME = "CSM";
  // TODO(b/257276342): Remove after configs in nomulus-internal are updated.
  public static final String DEPRECATED_NAME = "KMS";

  @Binds
  @IntoMap
  @StringKey(DEPRECATED_NAME)
  abstract Keyring provideDeprecatedKeyring(SecretManagerKeyring keyring);

  @Binds
  @IntoMap
  @StringKey(NAME)
  abstract Keyring provideKeyring(SecretManagerKeyring keyring);
}
