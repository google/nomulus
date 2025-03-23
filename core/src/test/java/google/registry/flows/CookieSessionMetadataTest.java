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

package google.registry.flows;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.flows.CookieSessionMetadata.COOKIE_NAME;
import static google.registry.flows.CookieSessionMetadata.decode;
import static google.registry.flows.CookieSessionMetadata.encode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import google.registry.testing.FakeResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CookieSessionMetadata}. */
public class CookieSessionMetadataTest {

  private HttpServletRequest request = mock(HttpServletRequest.class);
  private FakeResponse response = new FakeResponse();
  private CookieSessionMetadata cookieSessionMetadata = new CookieSessionMetadata(request);

  @Test
  void testNoCookie() {
    assertThat(cookieSessionMetadata.getRegistrarId()).isNull();
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testCookieWithAllFields() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "THIS_COOKIE=foo; SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=A.B.C}")
                + "; THAT_COOKIE=bar");
    cookieSessionMetadata = new CookieSessionMetadata(request);
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("A", "B", "C");
  }

  @Test
  void testCookieWithNullRegistrar() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=null, failedLoginAttempts=5, "
                        + " serviceExtensionUris=A.B.C}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    assertThat(cookieSessionMetadata.getRegistrarId()).isNull();
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("A", "B", "C");
  }

  @Test
  void testCookieWithEmptyExtension() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testCookieWithSingleExtension() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testIncrementFailedLoginAttempts() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    cookieSessionMetadata.incrementFailedLoginAttempts();
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(6);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testResetFailedLoginAttempts() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    cookieSessionMetadata.resetFailedLoginAttempts();
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testSetRegistrarId() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    cookieSessionMetadata.setRegistrarId("new_registrar");
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("new_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Foo");
  }

  @Test
  void testSetExtensions() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    cookieSessionMetadata.setServiceExtensionUris(ImmutableSet.of("Bar", "Baz"));
    assertThat(cookieSessionMetadata.getRegistrarId()).isEqualTo("test_registrar");
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).containsExactly("Bar", "Baz");
  }

  @Test
  void testInvalidate() {
    when(request.getHeader("Cookie"))
        .thenReturn(
            "SESSION_INFO="
                + encode(
                    "CookieSessionMetadata{clientId=test_registrar, failedLoginAttempts=5, "
                        + " serviceExtensionUris=Foo}"));
    cookieSessionMetadata = new CookieSessionMetadata(request);
    cookieSessionMetadata.invalidate();
    assertThat(cookieSessionMetadata.getRegistrarId()).isNull();
    assertThat(cookieSessionMetadata.getFailedLoginAttempts()).isEqualTo(0);
    assertThat(cookieSessionMetadata.getServiceExtensionUris()).isEmpty();
  }

  @Test
  void testSave() {
    cookieSessionMetadata.save(response);
    String value =
        decode(
            response.getHeaders().get("Set-Cookie").toString().substring(COOKIE_NAME.length() + 1));
    assertThat(value)
        .isEqualTo(
            "CookieSessionMetadata{clientId=null, failedLoginAttempts=0, serviceExtensionUris=}");
    cookieSessionMetadata.setRegistrarId("new_registrar");
    cookieSessionMetadata.setServiceExtensionUris(ImmutableSet.of("Bar", "Baz"));
    cookieSessionMetadata.incrementFailedLoginAttempts();
    cookieSessionMetadata.save(response);
    value =
        decode(
            response.getHeaders().get("Set-Cookie").toString().substring(COOKIE_NAME.length() + 1));
    assertThat(value)
        .isEqualTo(
            "CookieSessionMetadata{clientId=new_registrar, failedLoginAttempts=1,"
                + " serviceExtensionUris=Bar.Baz}");
  }
}
