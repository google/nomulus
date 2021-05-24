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

package google.registry.model.poll;

import static com.google.common.collect.ImmutableMap.toImmutableMap;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.googlecode.objectify.Key;
import google.registry.model.EppResource;
import google.registry.model.contact.ContactResource;
import google.registry.model.domain.DomainBase;
import google.registry.model.host.HostResource;
import google.registry.model.reporting.HistoryEntry;
import google.registry.persistence.VKey;
import java.util.List;
import java.util.Map;

/**
 * A converter between external key strings for {@link PollMessage}s (i.e. what registrars use to
 * identify and ACK them) and Datastore keys to the resource.
 *
 * <p>The format of the key string is A-B-C-D-E-F as follows:
 *
 * <pre>
 *   A = EppResource.typeId (decimal)
 *   B = EppResource.repoId prefix (STRING)
 *   C = EppResource.repoId suffix (STRING)
 *   D = HistoryEntry.id (decimal)
 *   E = PollMessage.id (decimal)
 *   F = PollMessage.eventTime (decimal, year only)
 * </pre>
 *
 * <p>A typical poll message ID might thus look like: 1-FE0F22-TLD-10071463070-10072612074-2018
 */
public class PollMessageExternalKeyConverter {

  /** An exception thrown when an external key cannot be parsed. */
  public static class PollMessageExternalKeyParseException extends RuntimeException {}

  /** Maps that detail the correspondence between EppResource classes and external IDs. */
  private static final ImmutableMap<Long, Class<? extends EppResource>> ID_TO_CLASS_MAP =
      ImmutableMap.of(
          1L, DomainBase.class,
          2L, ContactResource.class,
          3L, HostResource.class);

  private static final ImmutableMap<String, Long> KEY_KIND_TO_ID_MAP =
      ID_TO_CLASS_MAP.entrySet().stream()
          .collect(toImmutableMap(entry -> entry.getValue().getSimpleName(), Map.Entry::getKey));

  /** Returns an external poll message ID for the given poll message. */
  public static String makePollMessageExternalId(PollMessage pollMessage) {
    @SuppressWarnings("unchecked")
    Key<EppResource> ancestorResource =
        (Key<EppResource>) (Key<?>) pollMessage.getParentKey().getParent();
    long externalKeyClassId = KEY_KIND_TO_ID_MAP.get(ancestorResource.getKind());
    return String.format(
        "%d-%s-%d-%d-%d",
        externalKeyClassId,
        ancestorResource.getName(),
        pollMessage.getParentKey().getId(),
        pollMessage.getId(),
        pollMessage.getEventTime().getYear());
  }

  /**
   * Returns an Objectify Key to a PollMessage corresponding with the external ID.
   *
   * <p>Note that the year field that is included at the end of the poll message isn't actually used
   * for anything; it exists solely to create unique externally visible IDs for autorenews. We thus
   * ignore it (for now) for backwards compatibility reasons, so that registrars can still ACK
   * existing poll message IDs they may have lying around.
   *
   * @throws PollMessageExternalKeyParseException if the external key has an invalid format.
   */
  public static VKey<PollMessage> parsePollMessageExternalId(String externalKey) {
    List<String> idComponents = Splitter.on('-').splitToList(externalKey);
    if (idComponents.size() != 6) {
      throw new PollMessageExternalKeyParseException();
    }
    try {
      Class<?> resourceClazz = ID_TO_CLASS_MAP.get(Long.parseLong(idComponents.get(0)));
      if (resourceClazz == null) {
        throw new PollMessageExternalKeyParseException();
      }
      return VKey.from(
          Key.create(
              Key.create(
                  Key.create(
                      null,
                      resourceClazz,
                      String.format("%s-%s", idComponents.get(1), idComponents.get(2))),
                  HistoryEntry.class,
                  Long.parseLong(idComponents.get(3))),
              PollMessage.class,
              Long.parseLong(idComponents.get(4))));
      // Note that idComponents.get(5) is entirely ignored; we never use the year field internally.
    } catch (NumberFormatException e) {
      throw new PollMessageExternalKeyParseException();
    }
  }

  private PollMessageExternalKeyConverter() {}
}

