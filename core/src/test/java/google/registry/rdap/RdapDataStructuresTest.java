// Copyright 2019 The Nomulus Authors. All Rights Reserved.
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

package google.registry.rdap;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.rdap.RdapTestHelper.createJson;

import com.google.common.collect.ImmutableSet;
import google.registry.rdap.RdapDataStructures.Event;
import google.registry.rdap.RdapDataStructures.EventAction;
import google.registry.rdap.RdapDataStructures.EventWithoutActor;
import google.registry.rdap.RdapDataStructures.LanguageIdentifier;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapDataStructures.ObjectClassName;
import google.registry.rdap.RdapDataStructures.Port43WhoisServer;
import google.registry.rdap.RdapDataStructures.PublicId;
import google.registry.rdap.RdapDataStructures.RdapConformance;
import google.registry.rdap.RdapDataStructures.RdapStatus;
import google.registry.rdap.RdapDataStructures.Remark;
import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RdapDataStructures}. */
final class RdapDataStructuresTest {

  private void assertRestrictedNames(Object object, String... names) {
    assertThat(AbstractJsonableObject.getNameRestriction(object.getClass()).get())
        .containsExactlyElementsIn(ImmutableSet.copyOf(names));
  }

  @Test
  void testRdapConformance() {
    assertThat(RdapConformance.INSTANCE.toJson())
        .isEqualTo(createJson(
            "[",
            "  'rdap_level_0',",
            "  'icann_rdap_response_profile_0',",
            "  'icann_rdap_technical_implementation_guide_0'",
            "]"));
  }

  @Test
  void testLink() {
    Link link =
        Link.builder()
            .setHref("myHref")
            .setRel("myRel")
            .setTitle("myTitle")
            .setType("myType")
            .build();
    assertThat(link.toJson())
        .isEqualTo(createJson("{'href':'myHref','rel':'myRel','title':'myTitle','type':'myType'}"));
    assertRestrictedNames(link, "links[]");
  }

  @Test
  void testNotice() {
    Notice notice = Notice.builder()
        .setDescription("AAA", "BBB")
        .setTitle("myTitle")
        .addLink(Link.builder().setHref("myHref").setTitle("myLink").build())
        .setType(Notice.Type.RESULT_TRUNCATED_AUTHORIZATION)
        .build();
    assertThat(notice.toJson())
        .isEqualTo(
            createJson(
                "{",
                "  'title':'myTitle',",
                "  'type':'result set truncated due to authorization',",
                "  'description':['AAA','BBB'],",
                "  'links':[{'href':'myHref','title':'myLink'}]",
                "}"));
    assertRestrictedNames(notice, "notices[]");
  }

  @Test
  void testRemark() {
    Remark remark = Remark.builder()
        .setDescription("AAA", "BBB")
        .setTitle("myTitle")
        .addLink(Link.builder().setHref("myHref").setTitle("myLink").build())
        .setType(Remark.Type.OBJECT_TRUNCATED_AUTHORIZATION)
        .build();
    assertThat(remark.toJson())
        .isEqualTo(
            createJson(
                "{",
                "  'title':'myTitle',",
                "  'type':'object truncated due to authorization',",
                "  'description':['AAA','BBB'],",
                "  'links':[{'href':'myHref','title':'myLink'}]",
                "}"));
    assertRestrictedNames(remark, "remarks[]");
  }

  @Test
  void testLanguage() {
    assertThat(LanguageIdentifier.EN.toJson()).isEqualTo(createJson("'en'"));
    assertRestrictedNames(LanguageIdentifier.EN, "lang");
  }

  @Test
  void testEvent() {
    Event event =
        Event.builder()
            .setEventAction(EventAction.REGISTRATION)
            .setEventActor("Event Actor")
            .setEventDate(DateTime.parse("2012-04-03T14:54Z"))
            .addLink(Link.builder().setHref("myHref").build())
            .build();
    assertThat(event.toJson())
        .isEqualTo(
            createJson(
                "{",
                "  'eventAction':'registration',",
                "  'eventActor':'Event Actor',",
                "  'eventDate':'2012-04-03T14:54:00.000Z',",
                "  'links':[{'href':'myHref'}]",
                "}"));
    assertRestrictedNames(event, "events[]");
  }

  @Test
  void testEventWithoutActor() {
    EventWithoutActor event =
        EventWithoutActor.builder()
            .setEventAction(EventAction.REGISTRATION)
            .setEventDate(DateTime.parse("2012-04-03T14:54Z"))
            .addLink(Link.builder().setHref("myHref").build())
            .build();
    assertThat(event.toJson())
        .isEqualTo(
            createJson(
                "{",
                "  'eventAction':'registration',",
                "  'eventDate':'2012-04-03T14:54:00.000Z',",
                "  'links':[{'href':'myHref'}]",
                "}"));
    assertRestrictedNames(event, "asEventActor[]");
  }

  @Test
  void testRdapStatus() {
    assertThat(RdapStatus.ACTIVE.toJson()).isEqualTo(createJson("'active'"));
    assertRestrictedNames(RdapStatus.ACTIVE, "status[]");
  }

  @Test
  void testPort43() {
    Port43WhoisServer port43 = Port43WhoisServer.create("myServer");
    assertThat(port43.toJson()).isEqualTo(createJson("'myServer'"));
    assertRestrictedNames(port43, "port43");
  }

  @Test
  void testPublicId() {
    PublicId publicId = PublicId.create(PublicId.Type.IANA_REGISTRAR_ID, "myId");
    assertThat(publicId.toJson())
        .isEqualTo(createJson("{'identifier':'myId','type':'IANA Registrar ID'}"));
    assertRestrictedNames(publicId, "publicIds[]");
  }

  @Test
  void testObjectClassName() {
    assertThat(ObjectClassName.DOMAIN.toJson()).isEqualTo(createJson("'domain'"));
    assertRestrictedNames(ObjectClassName.DOMAIN, "objectClassName");
  }
}
