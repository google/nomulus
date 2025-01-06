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

package google.registry.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import dagger.Module;
import dagger.Provides;

/** Dagger module for the entity (model) classes. */
@Module
public final class ModelModule {

  /** Returns an {@link ObjectMapper} object that can be used to convert an entity to/from YAML. */
  @Provides
  public static ObjectMapper provideObjectMapper() {
    return EntityYamlUtils.createObjectMapper();
  }
}
