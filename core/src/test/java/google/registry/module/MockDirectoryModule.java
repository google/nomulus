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

package google.registry.module;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.api.services.directory.Directory;
import com.google.api.services.directory.model.User;
import dagger.Module;
import dagger.Provides;
import java.io.IOException;

@Module
public class MockDirectoryModule {
  @Provides
  static Directory provideDirectory() {
    Directory directory = mock(Directory.class);
    Directory.Users users = mock(Directory.Users.class);
    Directory.Users.Insert insert = mock(Directory.Users.Insert.class);
    Directory.Users.Delete delete = mock(Directory.Users.Delete.class);
    when(directory.users()).thenReturn(users);
    try {
      when(users.insert(any(User.class))).thenReturn(insert);
      when(users.delete(anyString())).thenReturn(delete);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return directory;
  }
}
