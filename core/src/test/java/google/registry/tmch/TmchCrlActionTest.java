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

package google.registry.tmch;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.TestDataHelper.loadBytes;
import static google.registry.util.ResourceUtils.readResourceBytes;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import google.registry.config.RegistryConfig.ConfigModule.TmchCaMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.SignatureException;
import java.security.cert.CRLException;
import java.security.cert.CertificateNotYetValidException;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TmchCrlAction}. */
class TmchCrlActionTest extends TmchActionTestCase {

  private TmchCrlAction newTmchCrlAction(TmchCaMode tmchCaMode) throws MalformedURLException {
    TmchCrlAction action = new TmchCrlAction();
    action.marksdb = marksdb;
    action.tmchCertificateAuthority = new TmchCertificateAuthority(tmchCaMode, clock);
    action.tmchCrlUrl = new URL("http://sloth.lol/tmch.crl");
    return action;
  }

  @Test
  void testSuccess() throws Exception {
    clock.setTo(DateTime.parse("2013-07-24TZ"));
    when(httpResponse.getContent()).thenReturn(
        readResourceBytes(TmchCertificateAuthority.class, "icann-tmch.crl").read());
    newTmchCrlAction(TmchCaMode.PRODUCTION).run();
    verify(httpResponse).getContent();
    verify(fetchService).fetch(httpRequest.capture());
    assertThat(httpRequest.getValue().getURL().toString()).isEqualTo("http://sloth.lol/tmch.crl");
  }

  @Test
  void testFailure_crlTooOld() throws Exception {
    clock.setTo(DateTime.parse("2020-01-01TZ"));
    when(httpResponse.getContent())
        .thenReturn(loadBytes(TmchCrlActionTest.class, "icann-tmch-pilot-old.crl").read());
    TmchCrlAction action = newTmchCrlAction(TmchCaMode.PILOT);
    Exception e = assertThrows(Exception.class, action::run);
    assertThat(e).hasCauseThat().isInstanceOf(CRLException.class);
    assertThat(e)
        .hasCauseThat()
        .hasMessageThat()
        .contains("New CRL is more out of date than our current CRL.");
  }

  @Test
  void testFailure_crlNotSignedByRoot() throws Exception {
    clock.setTo(DateTime.parse("2013-07-24TZ"));
    when(httpResponse.getContent())
        .thenReturn(readResourceBytes(TmchCertificateAuthority.class, "icann-tmch.crl").read());
    Exception e = assertThrows(Exception.class, newTmchCrlAction(TmchCaMode.PILOT)::run);
    assertThat(e).hasCauseThat().isInstanceOf(SignatureException.class);
    assertThat(e).hasCauseThat().hasMessageThat().isEqualTo("Signature does not match.");
  }

  @Test
  void testFailure_crlNotYetValid() throws Exception {
    clock.setTo(DateTime.parse("1984-01-01TZ"));
    when(httpResponse.getContent()).thenReturn(
        readResourceBytes(TmchCertificateAuthority.class, "icann-tmch-pilot.crl").read());
    Exception e = assertThrows(Exception.class, newTmchCrlAction(TmchCaMode.PILOT)::run);
    assertThat(e).hasCauseThat().isInstanceOf(CertificateNotYetValidException.class);
  }
}
