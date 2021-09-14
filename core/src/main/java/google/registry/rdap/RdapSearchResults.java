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

package google.registry.rdap;

import static google.registry.rdap.RdapIcannStandardInformation.POSSIBLY_INCOMPLETE_NOTICES;
import static google.registry.rdap.RdapIcannStandardInformation.TRUNCATION_NOTICES;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import google.registry.rdap.RdapDataStructures.Link;
import google.registry.rdap.RdapDataStructures.Notice;
import google.registry.rdap.RdapObjectClasses.BoilerplateType;
import google.registry.rdap.RdapObjectClasses.RdapDomain;
import google.registry.rdap.RdapObjectClasses.RdapEntity;
import google.registry.rdap.RdapObjectClasses.RdapNameserver;
import google.registry.rdap.RdapObjectClasses.ReplyPayloadBase;
import java.net.URI;
import java.util.Optional;

/**
 * Holds domain, nameserver and entity search results.
 *
 * <p>We need to know not only the list of things we found, but also whether the result set was
 * truncated to the limit. If it is, we must add the ICANN-mandated notice to that effect.
 */
@AutoValue
abstract class RdapSearchResults {

  /** Responding To Searches defined in 8 of RFC 9083. */
  abstract static class BaseSearchResponse extends ReplyPayloadBase {
    abstract IncompletenessWarningType incompletenessWarningType();
    abstract ImmutableMap<String, URI> navigationLinks();

    @JsonableElement("notices") ImmutableList<Notice> getIncompletenessWarnings() {
      switch (incompletenessWarningType()) {
        case TRUNCATED:
          return TRUNCATION_NOTICES;
        case MIGHT_BE_INCOMPLETE:
          return POSSIBLY_INCOMPLETE_NOTICES;
        case COMPLETE:
          break;
      }
      return ImmutableList.of();
    }

    /**
     * Creates a JSON object containing a notice with page navigation links.
     *
     * <p>At the moment, only a next page link is supported. Other types of links (e.g. previous
     * page) could be added in the future, but it's not clear how to generate such links, given the
     * way we are querying the database.
     *
     * <p>This isn't part of the spec.
     */
    @JsonableElement("notices[]")
    Optional<Notice> getNavigationNotice() {
      if (navigationLinks().isEmpty()) {
        return Optional.empty();
      }
      Notice.Builder builder =
          Notice.builder().setTitle("Navigation Links").setDescription("Links to related pages.");
      navigationLinks().forEach((name, uri) ->
          builder.linksBuilder()
              .add(Link.builder()
                  .setRel(name)
                  .setHref(uri.toString())
                  .setType("application/rdap+json")
                  .build()));
      return Optional.of(builder.build());
    }

    BaseSearchResponse(BoilerplateType boilerplateType) {
      super(boilerplateType);
    }

    abstract static class Builder<B extends Builder<?>> {
      abstract ImmutableMap.Builder<String, URI> navigationLinksBuilder();
      abstract B setIncompletenessWarningType(IncompletenessWarningType type);

      @SuppressWarnings("unchecked")
      B setNextPageUri(URI uri) {
        navigationLinksBuilder().put("next", uri);
        return (B) this;
      }
    }
  }

  @AutoValue
  abstract static class DomainSearchResponse extends BaseSearchResponse {

    @JsonableElement abstract ImmutableList<RdapDomain> domainSearchResults();

    DomainSearchResponse() {
      super(BoilerplateType.DOMAIN);
    }

    static Builder builder() {
      return new AutoValue_RdapSearchResults_DomainSearchResponse.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends BaseSearchResponse.Builder<Builder> {
      abstract ImmutableList.Builder<RdapDomain> domainSearchResultsBuilder();

      abstract DomainSearchResponse build();
    }
  }

  @AutoValue
  abstract static class EntitySearchResponse extends BaseSearchResponse {

    @JsonableElement public abstract ImmutableList<RdapEntity> entitySearchResults();

    EntitySearchResponse() {
      super(BoilerplateType.ENTITY);
    }

    static Builder builder() {
      return new AutoValue_RdapSearchResults_EntitySearchResponse.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends BaseSearchResponse.Builder<Builder> {
      abstract ImmutableList.Builder<RdapEntity> entitySearchResultsBuilder();

      abstract EntitySearchResponse build();
    }
  }

  @AutoValue
  abstract static class NameserverSearchResponse extends BaseSearchResponse {

    @JsonableElement public abstract ImmutableList<RdapNameserver> nameserverSearchResults();

    NameserverSearchResponse() {
      super(BoilerplateType.NAMESERVER);
    }

    static Builder builder() {
      return new AutoValue_RdapSearchResults_NameserverSearchResponse.Builder();
    }

    @AutoValue.Builder
    abstract static class Builder extends BaseSearchResponse.Builder<Builder> {
      abstract ImmutableList.Builder<RdapNameserver> nameserverSearchResultsBuilder();

      abstract NameserverSearchResponse build();
    }
  }

  enum IncompletenessWarningType {

    /** Result set is complete. */
    COMPLETE,

    /** Result set has been limited to the maximum size. */
    TRUNCATED,

    /**
     * Result set might be missing data because the first step of a two-step query returned a data
     * set that was limited in size.
     */
    MIGHT_BE_INCOMPLETE
  }
}
