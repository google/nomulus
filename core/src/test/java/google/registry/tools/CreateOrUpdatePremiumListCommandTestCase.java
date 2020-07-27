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

package google.registry.tools;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.common.net.MediaType;
import google.registry.testing.UriParameters;
import java.io.File;
import java.nio.charset.StandardCharsets;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

/** Base class for common testing setup for create and update commands for Premium Lists. */
abstract class CreateOrUpdatePremiumListCommandTestCase<T extends CreateOrUpdatePremiumListCommand>
    extends CommandTestCase<T> {

  @Captor
  ArgumentCaptor<ImmutableMap<String, String>> urlParamCaptor;

  @Captor
  ArgumentCaptor<byte[]> requestBodyCaptor;

  static String generateInputData(String premiumTermsPath) throws Exception {
    return Files.asCharSource(new File(premiumTermsPath), StandardCharsets.UTF_8).read();
  }

  void verifySentParams(
      AppEngineConnection connection, String path, ImmutableMap<String, String> parameterMap)
      throws Exception {
    verify(connection)
        .sendPostRequest(
            eq(path),
            urlParamCaptor.capture(),
            eq(MediaType.FORM_DATA),
            requestBodyCaptor.capture());
    assertThat(new ImmutableMap.Builder<String, String>()
        .putAll(urlParamCaptor.getValue())
        .putAll(UriParameters.parse(new String(requestBodyCaptor.getValue(), UTF_8)).entries())
        .build())
            .containsExactlyEntriesIn(parameterMap);
  }
}
