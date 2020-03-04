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

package google.registry.rde;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.rde.RegistrarToXjcConverter.convertRegistrar;
import static google.registry.testing.DatastoreHelper.cloneAndSetAutoTimestamps;
import static google.registry.xjc.XjcXmlTransformer.marshalStrict;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import google.registry.model.ofy.Ofy;
import google.registry.model.registrar.Registrar;
import google.registry.model.registrar.Registrar.State;
import google.registry.model.registrar.RegistrarAddress;
import google.registry.testing.AppEngineRule;
import google.registry.testing.FakeClock;
import google.registry.testing.InjectRule;
import google.registry.testing.ShardableTestCase;
import google.registry.xjc.rderegistrar.XjcRdeRegistrar;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarAddrType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarPostalInfoEnumType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarPostalInfoType;
import google.registry.xjc.rderegistrar.XjcRdeRegistrarStatusType;
import java.io.ByteArrayOutputStream;
import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Unit tests for {@link RegistrarToXjcConverter}.
 *
 * <p>This tests the mapping between {@link Registrar} and {@link XjcRdeRegistrar} as well as
 * some exceptional conditions.
 */
@RunWith(JUnit4.class)
public class RegistrarToXjcConverterTest extends ShardableTestCase {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder().withDatastoreAndCloudSql().build();

  @Rule
  public final InjectRule inject = new InjectRule();

  Registrar registrar;

  @Before
  public void init() {
    registrar = new Registrar.Builder()
        .setClientId("GoblinMarket")
        .setRegistrarName("Maids heard the goblins cry: Come buy, come buy:")
        .setType(Registrar.Type.REAL)
        .setIanaIdentifier(8L)
        .setState(Registrar.State.ACTIVE)
        .setInternationalizedAddress(new RegistrarAddress.Builder()
            .setStreet(ImmutableList.of("123 Detonation Boulevard"))
            .setCity("Williamsburg")
            .setState("NY")
            .setZip("11211")
            .setCountryCode("US")
            .build())
        .setLocalizedAddress(new RegistrarAddress.Builder()
            .setStreet(ImmutableList.of("123 Example Boulevard."))
            .setCity("Hipsterville")
            .setState("NY")
            .setZip("11211")
            .setCountryCode("US")
            .build())
        .setPhoneNumber("+1.2125551212")
        .setFaxNumber("+1.2125551213")
        .setEmailAddress("contact-us@goblinmen.example")
        .setWhoisServer("whois.goblinmen.example")
        .setUrl("http://www.goblinmen.example")
        .build();
    FakeClock clock = new FakeClock(DateTime.parse("2013-01-01T00:00:00Z"));
    inject.setStaticField(Ofy.class, "clock", clock);
    registrar = cloneAndSetAutoTimestamps(registrar);  // Set the creation time in 2013.
    clock.setTo(DateTime.parse("2014-01-01T00:00:00Z"));
    registrar = cloneAndSetAutoTimestamps(registrar);  // Set the update time in 2014.
  }

  @Test
  public void test_convertRegistrar() {
    XjcRdeRegistrar bean = convertRegistrar(registrar);

    assertThat(bean.getId()).isEqualTo("GoblinMarket");
    assertThat(bean.getName()).isEqualTo("Maids heard the goblins cry: Come buy, come buy:");

    assertThat(bean.getPostalInfos()).hasSize(2);
    // I hard-coded the localized unicode happy address to come first just cuz.
    XjcRdeRegistrarPostalInfoType postalInfo0 = bean.getPostalInfos().get(0);
    assertThat(postalInfo0.getType()).isEqualTo(XjcRdeRegistrarPostalInfoEnumType.LOC);
    XjcRdeRegistrarAddrType address0 = postalInfo0.getAddr();
    assertThat(address0.getStreets()).containsExactly("123 Example Boulevard.");
    assertThat(address0.getCity()).isEqualTo("Hipsterville");
    assertThat(address0.getSp()).isEqualTo("NY");
    assertThat(address0.getPc()).isEqualTo("11211");
    assertThat(address0.getCc()).isEqualTo("US");
    // Now for the non-unicode form.
    XjcRdeRegistrarPostalInfoType postalInfo1 = bean.getPostalInfos().get(1);
    assertThat(postalInfo1.getType()).isEqualTo(XjcRdeRegistrarPostalInfoEnumType.INT);
    XjcRdeRegistrarAddrType address1 = postalInfo1.getAddr();
    assertThat(address1.getStreets()).containsExactly("123 Detonation Boulevard");
    assertThat(address1.getCity()).isEqualTo("Williamsburg");
    assertThat(address1.getSp()).isEqualTo("NY");
    assertThat(address1.getPc()).isEqualTo("11211");
    assertThat(address1.getCc()).isEqualTo("US");

    assertThat(bean.getVoice().getValue()).isEqualTo("+1.2125551212");
    assertThat(bean.getVoice().getX()).isNull();

    assertThat(bean.getFax().getValue()).isEqualTo("+1.2125551213");
    assertThat(bean.getFax().getX()).isNull();

    assertThat(bean.getEmail()).isEqualTo("contact-us@goblinmen.example");

    assertThat(bean.getUrl()).isEqualTo("http://www.goblinmen.example");

    assertThat(bean.getStatus()).isEqualTo(XjcRdeRegistrarStatusType.OK);

    assertThat(bean.getCrDate()).isEqualTo(DateTime.parse("2013-01-01T00:00:00Z"));

    assertThat(bean.getUpDate()).isEqualTo(DateTime.parse("2014-01-01T00:00:00Z"));

    assertThat(bean.getWhoisInfo().getName()).isEqualTo("whois.goblinmen.example");
  }

  @Test
  public void test_convertRegistrar_disabledStateMeansTerminated() {
    XjcRdeRegistrar bean = convertRegistrar(registrar.asBuilder().setState(State.DISABLED).build());
    assertThat(bean.getStatus()).isEqualTo(XjcRdeRegistrarStatusType.TERMINATED);
  }

  @Test
  public void test_convertRegistrar_handlesAllRegistrarStates() {
    for (State state : Registrar.State.values()) {
      // This will throw an exception if it can't handle the chosen state.
      convertRegistrar(registrar.asBuilder().setState(state).build());
    }
  }

  @Test
  public void testMarshal() throws Exception {
    marshalStrict(RegistrarToXjcConverter.convert(registrar), new ByteArrayOutputStream(), UTF_8);
  }
}
