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

import static com.google.common.truth.Truth.assertThat;
import static google.registry.testing.DatastoreHelper.createTld;
import static google.registry.xjc.XjcXmlTransformer.marshalStrict;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.common.net.InetAddresses;

import google.registry.model.eppcommon.StatusValue;
import google.registry.model.host.HostResource;
import google.registry.testing.AppEngineRule;
import google.registry.testing.ExceptionRule;
import google.registry.xjc.host.XjcHostStatusValueType;
import google.registry.xjc.rdehost.XjcRdeHost;
import google.registry.xjc.rdehost.XjcRdeHostElement;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;

/**
 * Unit tests for {@link HostResourceToXjcConverter}.
 *
 * <p>This tests the mapping between {@link HostResource} and {@link XjcRdeHost} as well as
 * some exceptional conditions.
 */
@RunWith(JUnit4.class)
public class HostResourceToXjcConverterTest {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .build();

  @Rule
  public final ExceptionRule thrown = new ExceptionRule();

  @Before
  public void init() {
    createTld("foobar");
  }

  @Test
  public void testConvert() throws Exception {
    XjcRdeHost bean = HostResourceToXjcConverter.convertHost(
        new HostResource.Builder()
            .setCreationClientId("LawyerCat")
            .setCreationTime(DateTime.parse("1900-01-01T00:00:00Z"))
            .setCurrentSponsorClientId("BusinessCat")
            .setFullyQualifiedHostName("ns1.love.lol")
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("127.0.0.1")))
            .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("CeilingCat")
            .setLastEppUpdateTime(DateTime.parse("1920-01-01T00:00:00Z"))
            .setRepoId("2-roid")
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_UPDATE))
            .build());

    assertThat(bean.getAddrs()).hasSize(1);
    assertThat(bean.getAddrs().get(0).getIp().value()).isEqualTo("v4");
    assertThat(bean.getAddrs().get(0).getValue()).isEqualTo("127.0.0.1");

    assertThat(bean.getClID()).isEqualTo("BusinessCat");

    assertThat(bean.getCrDate()).isEqualTo(DateTime.parse("1900-01-01T00:00:00Z"));

    // o  A <crRr> element that contains the identifier of the registrar
    //    that created the domain name object.  An OPTIONAL client attribute
    //    is used to specify the client that performed the operation.
    //    This will always be null for us since we track each registrar as a separate client.
    assertThat(bean.getCrRr().getValue()).isEqualTo("LawyerCat");
    assertThat(bean.getCrRr().getClient()).isNull();

    assertThat(bean.getName()).isEqualTo("ns1.love.lol");

    assertThat(bean.getRoid()).isEqualTo("2-roid");

    assertThat(bean.getStatuses()).hasSize(1);
    assertThat(bean.getStatuses().get(0).getS()).isEqualTo(XjcHostStatusValueType.PENDING_UPDATE);
    assertThat(bean.getStatuses().get(0).getS().toString()).isEqualTo("PENDING_UPDATE");
    assertThat(bean.getStatuses().get(0).getValue()).isNull();
    assertThat(bean.getStatuses().get(0).getLang()).isEqualTo("en");

    assertThat(bean.getTrDate()).isEqualTo(DateTime.parse("1910-01-01T00:00:00Z"));

    assertThat(bean.getUpDate()).isEqualTo(DateTime.parse("1920-01-01T00:00:00Z"));

    assertThat(bean.getUpRr().getValue()).isEqualTo("CeilingCat");
    assertThat(bean.getUpRr().getClient()).isNull();
  }

  @Test
  public void testConvertIpv6() throws Exception {
    XjcRdeHost bean = HostResourceToXjcConverter.convertHost(
        new HostResource.Builder()
            .setCreationClientId("LawyerCat")
            .setCreationTime(DateTime.parse("1900-01-01T00:00:00Z"))
            .setCurrentSponsorClientId("BusinessCat")
            .setFullyQualifiedHostName("ns1.love.lol")
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("cafe::abba")))
            .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("CeilingCat")
            .setLastEppUpdateTime(DateTime.parse("1920-01-01T00:00:00Z"))
            .setRepoId("2-LOL")
            .setStatusValues(ImmutableSet.of(StatusValue.PENDING_UPDATE))
            .build());
    assertThat(bean.getAddrs()).hasSize(1);
    assertThat(bean.getAddrs().get(0).getIp().value()).isEqualTo("v6");
    assertThat(bean.getAddrs().get(0).getValue()).isEqualTo("cafe::abba");
  }

  @Test
  public void testHostStatusValueIsInvalid() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    HostResourceToXjcConverter.convertHost(
        new HostResource.Builder()
            .setCreationClientId("LawyerCat")
            .setCreationTime(DateTime.parse("1900-01-01T00:00:00Z"))
            .setCurrentSponsorClientId("BusinessCat")
            .setFullyQualifiedHostName("ns1.love.lol")
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("cafe::abba")))
            .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("CeilingCat")
            .setLastEppUpdateTime(DateTime.parse("1920-01-01T00:00:00Z"))
            .setRepoId("2-LOL")
            .setStatusValues(ImmutableSet.of(StatusValue.SERVER_HOLD))  // <-- OOPS
            .build());
  }

  @Test
  public void testMarshal() throws Exception {
    // Bean! Bean! Bean!
    XjcRdeHostElement bean = HostResourceToXjcConverter.convert(
        new HostResource.Builder()
            .setCreationClientId("LawyerCat")
            .setCreationTime(DateTime.parse("1900-01-01T00:00:00Z"))
            .setCurrentSponsorClientId("BusinessCat")
            .setFullyQualifiedHostName("ns1.love.lol")
            .setInetAddresses(ImmutableSet.of(InetAddresses.forString("cafe::abba")))
            .setLastTransferTime(DateTime.parse("1910-01-01T00:00:00Z"))
            .setLastEppUpdateClientId("CeilingCat")
            .setLastEppUpdateTime(DateTime.parse("1920-01-01T00:00:00Z"))
            .setRepoId("2-LOL")
            .setStatusValues(ImmutableSet.of(StatusValue.OK))
            .build());
    marshalStrict(bean, new ByteArrayOutputStream(), UTF_8);
  }

}
