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

package google.registry.request;

import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import javax.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link ResponseImpl}. */
class ResponseImplTest {

  private final HttpServletResponse rsp = mock(HttpServletResponse.class);

  @Test
  void testSetStatus() {
    new ResponseImpl(rsp).setStatus(666);
    verify(rsp).setStatus(666);
    verifyNoMoreInteractions(rsp);
  }

  @Test
  void testSetContentType() {
    new ResponseImpl(rsp).setContentType(PLAIN_TEXT_UTF_8);
    verify(rsp).setContentType("text/plain; charset=utf-8");
    verifyNoMoreInteractions(rsp);
  }

  @Test
  void testSetPayload() throws Exception {
    StringWriter httpOutput = new StringWriter();
    when(rsp.getWriter()).thenReturn(new PrintWriter(httpOutput));
    new ResponseImpl(rsp).setPayload("hello world");
    assertThat(httpOutput.toString()).isEqualTo("hello world");
  }
}
