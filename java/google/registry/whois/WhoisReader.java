// Copyright 2016 The Nomulus Authors. All Rights Reserved.
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

package google.registry.whois;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static google.registry.model.registry.Registries.findTldForName;
import static google.registry.util.DomainNameUtils.canonicalizeDomainName;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.io.CharStreams;
import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import org.joda.time.DateTime;

/**
 * The WhoisReader class understands how to read the WHOIS command from some source, parse it, and
 * produce a new WhoisCommand instance. The command syntax of WHOIS is generally undefined, so we
 * adopt the following rules:
 *
 * <dl>
 * <dt>domain &lt;FQDN&gt;<dd>
 *   Looks up the domain record for the fully qualified domain name.
 * <dt>nameserver &lt;FQDN&gt;<dd>
 *   Looks up the nameserver record for the fully qualified domain name.
 * <dt>nameserver &lt;IP&gt;<dd>
 *   Looks up the nameserver record at the given IP address.
 * <dt>registrar &lt;IANA ID&gt;<dd>
 *   Looks up the registrar record with the given IANA ID.
 * <dt>registrar &lt;NAME&gt;<dd>
 *   Looks up the registrar record with the given name.
 * <dt>&lt;IP&gt;<dd>
 *   Looks up the nameserver record with the given IP address.
 * <dt>&lt;FQDN&gt;<dd>
 *   Looks up the nameserver or domain record for the fully qualified domain name.
 * <dt>&lt;IANA ID&gt;<dd>
 *   Looks up the registrar record with the given IANA ID.
 * </dl>
 *
 * @see <a href="http://tools.ietf.org/html/rfc3912">RFC 3912</a>
 * @see <a href="http://www.iana.org/assignments/registrar-ids">IANA Registrar IDs</a>
 */
class WhoisReader {

  /**
   * These are strings that will always trigger a specific query type when they are sent at
   * the beginning of a command.
   */
  static final String DOMAIN_LOOKUP_COMMAND = "domain";
  static final String NAMESERVER_LOOKUP_COMMAND = "nameserver";
  static final String REGISTRAR_LOOKUP_COMMAND = "registrar";

  private final Reader reader;
  private final DateTime now;
  private final WhoisCommandFactory commandFactory;

  /** Creates a new WhoisReader that extracts its command from the specified Reader. */
  WhoisReader(Reader reader, WhoisCommandFactory commandFactory, DateTime now) {
    this.reader = checkNotNull(reader, "reader");
    this.commandFactory = checkNotNull(commandFactory, "commandFactory");
    this.now = checkNotNull(now, "now");
  }

  /**
   * Read a command from some source to produce a new instance of
   * WhoisCommand.
   *
   * @throws IOException If the command could not be read from the reader.
   * @throws WhoisException If the command could not be parsed as a WhoisCommand.
   */
  WhoisCommand readCommand() throws IOException, WhoisException {
    return parseCommand(CharStreams.toString(reader));
  }

  /**
   * Given a WHOIS command string, parse it into its command type and target string. See class level
   * comments for a full description of the command syntax accepted.
   */
  private WhoisCommand parseCommand(String command) throws WhoisException {
    // Split the string into tokens based on whitespace.
    List<String> tokens = filterEmptyStrings(command.split("\\s"));

    if (tokens.isEmpty()) {
      throw new WhoisException(now, SC_BAD_REQUEST, "No WHOIS command specified.");
    }

    final String arg1 = tokens.get(0);

    // Check if the first token is equal to the domain lookup command.
    if (arg1.equalsIgnoreCase(DOMAIN_LOOKUP_COMMAND)) {
      if (tokens.size() != 2) {
        throw new WhoisException(now, SC_BAD_REQUEST, String.format(
            "Wrong number of arguments to '%s' command.", DOMAIN_LOOKUP_COMMAND));
      }

      // Try to parse the argument as a domain name.
      try {
        return commandFactory.domainLookup(InternetDomainName.from(
            canonicalizeDomainName(tokens.get(1))));
      } catch (IllegalArgumentException iae) {
        // If we can't interpret the argument as a host name, then return an error.
        throw new WhoisException(now, SC_BAD_REQUEST, String.format(
            "Could not parse argument to '%s' command", DOMAIN_LOOKUP_COMMAND));
      }
    }

    // Check if the first token is equal to the nameserver lookup command.
    if (arg1.equalsIgnoreCase(NAMESERVER_LOOKUP_COMMAND)) {
      if (tokens.size() != 2) {
        throw new WhoisException(now, SC_BAD_REQUEST, String.format(
            "Wrong number of arguments to '%s' command.", NAMESERVER_LOOKUP_COMMAND));
      }

      // Try to parse the argument as an IP address.
      try {
        return commandFactory.nameserverLookupByIp(InetAddresses.forString(tokens.get(1)));
      } catch (IllegalArgumentException iae) {
        // Silently ignore this exception.
      }

      // Try to parse the argument as a host name.
      try {
        return commandFactory.nameserverLookupByHost(InternetDomainName.from(
            canonicalizeDomainName(tokens.get(1))));
      } catch (IllegalArgumentException iae) {
        // Silently ignore this exception.
      }

      // If we can't interpret the argument as either a host name or IP address, return an error.
      throw new WhoisException(now, SC_BAD_REQUEST, String.format(
          "Could not parse argument to '%s' command", NAMESERVER_LOOKUP_COMMAND));
    }

    // Check if the first token is equal to the registrar lookup command.
    if (arg1.equalsIgnoreCase(REGISTRAR_LOOKUP_COMMAND)) {
      if (tokens.size() == 1) {
        throw new WhoisException(now, SC_BAD_REQUEST, String.format(
            "Too few arguments to '%s' command.", REGISTRAR_LOOKUP_COMMAND));
      }
      return commandFactory.registrarLookup(Joiner.on(' ').join(tokens.subList(1, tokens.size())));
    }

    // If we have a single token, then try to interpret that in various ways.
    if (tokens.size() == 1) {
      // Try to parse it as an IP address. If successful, then this is a lookup on a nameserver.
      try {
        return commandFactory.nameserverLookupByIp(InetAddresses.forString(arg1));
      } catch (IllegalArgumentException iae) {
        // Silently ignore this exception.
      }

      // Try to parse it as a domain name or host name.
      try {
        final InternetDomainName targetName = InternetDomainName.from(canonicalizeDomainName(arg1));

        // We don't know at this point whether we have a domain name or a host name. We have to
        // search through our configured TLDs to see if there's one that prefixes the name.
        Optional<InternetDomainName> tld = findTldForName(targetName);
        if (!tld.isPresent()) {
          // This target is not under any configured TLD, so just try it as a registrar name.
          return new RegistrarLookupCommand(arg1);
        }

        // If the target is exactly one level above the TLD, then this is an second level domain
        // (SLD) and we should do a domain lookup on it.
        if (targetName.parent().equals(tld.get())) {
          return commandFactory.domainLookup(targetName, tld.get());
        }

        // The target is more than one level above the TLD, so we'll assume it's a nameserver.
        return commandFactory.nameserverLookupByHost(targetName, tld.get());
      } catch (IllegalArgumentException e) {
        // Silently ignore this exception.
      }

      // Purposefully fall through to code below.
    }

    // The only case left is that there are multiple tokens with no particular command given. We'll
    // assume this is a registrar lookup, since there's really nothing else it could be.
    return commandFactory.registrarLookup(Joiner.on(' ').join(tokens));
  }

  /** Returns an ArrayList containing the contents of the String array minus any empty strings. */
  private static List<String> filterEmptyStrings(String[] strings) {
    List<String> list = new ArrayList<>(strings.length);
    for (String str : strings) {
      if (!isNullOrEmpty(str)) {
        list.add(str);
      }
    }
    return list;
  }
}
