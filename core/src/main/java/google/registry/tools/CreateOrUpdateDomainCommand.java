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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static google.registry.util.CollectionUtils.findDuplicates;

import com.beust.jcommander.IStringConverter;
import com.beust.jcommander.Parameter;
import com.google.auto.value.AutoValue;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;
import com.google.template.soy.data.SoyListData;
import com.google.template.soy.data.SoyMapData;
import google.registry.tools.params.NameserversParameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared base class for commands to create or update a Domain via EPP. */
abstract class CreateOrUpdateDomainCommand extends MutatingEppToolCommand {

  @Parameter(
    names = {"-c", "--client"},
    description = "Client identifier of the registrar to execute the command as",
    required = true
  )
  String clientId;

  @Parameter(description = "Names of the domains", required = true)
  private List<String> mainParameters;

  @Parameter(
      names = {"-n", "--nameservers"},
      description = "Comma-delimited list of nameservers, up to 13.",
      converter = NameserversParameter.class,
      validateWith = NameserversParameter.class)
  Set<String> nameservers = new HashSet<>();

  @Parameter(
    names = {"-r", "--registrant"},
    description = "Domain registrant."
  )
  String registrant;

  @Parameter(
    names = {"-a", "--admins"},
    description = "Comma-separated list of admin contacts."
  )
  List<String> admins = new ArrayList<>();

  @Parameter(
    names = {"-t", "--techs"},
    description = "Comma-separated list of technical contacts."
  )
  List<String> techs = new ArrayList<>();

  @Parameter(
    names = {"-p", "--password"},
    description = "Password."
  )
  String password;

  @Parameter(
    names = "--ds_records",
    description =
        "Comma-separated list of DS records. Each DS record is given as "
        + "<keyTag> <alg> <digestType> <digest>, in order, as it appears in the Zonefile.",
    converter = DsRecordConverter.class
  )
  List<DsRecord> dsRecords = new ArrayList<>();

  Set<String> domains;

  @AutoValue
  abstract static class DsRecord {
    private static final Splitter SPLITTER =
        Splitter.on(CharMatcher.whitespace()).omitEmptyStrings();

    public abstract int keyTag();
    public abstract int alg();
    public abstract int digestType();
    public abstract String digest();

    private static DsRecord create(int keyTag, int alg, int digestType, String digest) {
      digest = Ascii.toUpperCase(digest);
      checkArgument(
          BaseEncoding.base16().canDecode(digest),
          "digest should be even-lengthed hex, but is %s (length %s)",
          digest,
          digest.length());
      return new AutoValue_CreateOrUpdateDomainCommand_DsRecord(keyTag, alg, digestType, digest);
    }

    /**
     * Parses a string representation of the DS record.
     *
     * <p>The string format accepted is "[keyTag] [alg] [digestType] [digest]" (i.e., the 4
     * arguments separated by any number of spaces, as it appears in the Zone file)
     */
    public static DsRecord parse(String dsRecord) {
      List<String> elements = SPLITTER.splitToList(dsRecord);
      checkArgument(
          elements.size() == 4,
          "dsRecord %s should have 4 parts, but has %s",
          dsRecord,
          elements.size());
      return DsRecord.create(
          Integer.parseUnsignedInt(elements.get(0)),
          Integer.parseUnsignedInt(elements.get(1)),
          Integer.parseUnsignedInt(elements.get(2)),
          elements.get(3));
    }

    public SoyMapData toSoyData() {
      return new SoyMapData(
          "keyTag", keyTag(),
          "alg", alg(),
          "digestType", digestType(),
          "digest", digest());
    }

    public static SoyListData convertToSoy(List<DsRecord> dsRecords) {
      return new SoyListData(
          dsRecords.stream().map(DsRecord::toSoyData).collect(toImmutableList()));
    }
  }

  public static class DsRecordConverter implements IStringConverter<DsRecord> {
    @Override
    public DsRecord convert(String dsRecord) {
      return DsRecord.parse(dsRecord);
    }
  }

  @Override
  protected void initEppToolCommand() throws Exception {
    checkArgument(nameservers.size() <= 13, "There can be at most 13 nameservers.");

    String duplicates = Joiner.on(", ").join(findDuplicates(mainParameters));
    checkArgument(duplicates.isEmpty(), "Duplicate arguments found: '%s'", duplicates);
    domains = ImmutableSet.copyOf(mainParameters);

    initMutatingEppToolCommand();
  }
}
