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

package google.registry.xml;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertWithMessage;
import static google.registry.util.DiffUtils.prettyPrintXmlDeepDiff;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.net.InetAddresses;
import com.google.common.net.InternetDomainName;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.joda.time.format.ISODateTimeFormat;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.XML;

/** Helper class for unit tests that need XML. */
public class XmlTestUtils {

  /**
   * Asserts that the two XML strings match.
   *
   * <p>Note that the actual XML must start with a UTF-8 standalone XML header, but the expected XML
   * has no such restriction (and typically lacks the header entirely).
   */
  public static void assertXmlEquals(String expected, String actual, String... ignoredPaths)
      throws Exception {
    assertXmlEqualsWithMessage(expected, actual, "", ignoredPaths);
  }

  /**
   * Asserts that the two XML strings match, but ignoring the XML header.
   *
   * <p>Do NOT use this for assertions about results of EPP flows, as the XML header is required per
   * the EPP spec for those. Rather, use this for raw operations on EPP XMLs, in situations where
   * the header may be absent or incorrect (e.g. because you did operations on raw EPP XML directly
   * loaded from a file without passing it through an EPP flow).
   */
  public static void assertXmlEqualsIgnoreHeader(
      String expected, String actual, String... ignoredPaths) throws Exception {
    assertXmlEqualsWithMessageHelper(expected, actual, "", ignoredPaths);
  }

  public static void assertXmlEqualsWithMessage(
      String expected, String actual, String message, String... ignoredPaths) throws Exception {
    if (!actual.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")) {
      assertWithMessage("XML declaration not found at beginning:\n%s", actual).fail();
    }
    assertXmlEqualsWithMessageHelper(expected, actual, message, ignoredPaths);
  }

  private static void assertXmlEqualsWithMessageHelper(
      String expected, String actual, String message, String... ignoredPaths) throws Exception {
    Map<String, Object> expectedMap = toComparableJson(expected, ignoredPaths);
    Map<String, Object> actualMap = toComparableJson(actual, ignoredPaths);
    if (!expectedMap.equals(actualMap)) {
      assertWithMessage(
              "%s: Expected:\n%s\n\nActual:\n%s\n\nDiff:\n%s\n\n",
              message, expected, actual, prettyPrintXmlDeepDiff(expectedMap, actualMap, null))
          .fail();
    }
  }

  /**
   * Map an element or attribute name using a namespace map to replace the namespace identifier
   * with the complete URI as given in the map. If the name has no namespace identifier, the default
   * namespace mapping is used. If the namespace identifier does not exist in the map, the name is
   * left unchanged.
   */
  private static String mapName(
      @Nullable String name, Map<String, String> nsMap, boolean mapDefaultNamespace) {
    if (name == null) {
      return null;
    }
    String ns;
    String simpleKey;
    List<String> components = Splitter.on(':').splitToList(name);
    // Handle names without identifiers, meaning they are in the default namespace.
    if (components.size() < 2) {
      if (!mapDefaultNamespace) {
        return name;
      }
      ns = "";
      simpleKey = name;
    // Handle names with identifiers.
    } else {
      ns = components.get(0);
      simpleKey = components.get(1);
    }
    // If the map does not contain the specified identifier (or "" for the default), don't do
    // anything.
    if (nsMap.containsKey(ns)) {
      ns = nsMap.get(ns);
    }
    return ns.isEmpty() ? simpleKey : (ns + ':' + simpleKey);
  }

  /**
   * Deeply explore the object and normalize values so that things we consider equal compare so.
   * The return value consists of two parts: the updated key and the value. The value is
   * straightforward enough: it is the rendering of the subtree to be attached at the current point.
   * The key is more complicated, because of namespaces. When an XML element specifies namespaces
   * using xmlns attributes, those namespaces apply to the element as well as all of its
   * descendants. That means that, when prefixing the element name with the full namespace path,
   * as required to do proper comparison, the element name depends on its children. When looping
   * through a JSONObject map, we can't just recursively generate the value and store it using the
   * key. We may have to update the key as well, to get the namespaces correct. A returned key of
   * null indicates that we should use the existing key. A non-null key indicates that we should
   * replace the existing key.
   *
   * @param elementName the name under which the current subtree was found, or null if the current
   *     subtree's name is nonexistent or irrelevant
   * @param obj the current subtree
   * @param path the (non-namespaced) element path used for ignoredPaths purposes
   * @param ignoredPaths the set of paths whose values should be set to IGNORED
   * @param nsMap the inherited namespace identifier-to-URI map
   * @return the key under which the rendered subtree should be stored (or null), and the rendered
   *     subtree
   */
  private static Map.Entry<String, Object> normalize(
      @Nullable String elementName,
      Object obj,
      @Nullable String path,
      Set<String> ignoredPaths,
      Map<String, String> nsMap) throws Exception {
    if (obj instanceof JSONObject) {
      JSONObject jsonObject = (JSONObject) obj;
      Map<String, Object> map = new HashMap<>();
      String[] names = JSONObject.getNames(jsonObject);
      if (names != null) {
        // Separate all elements and keys into namespace specifications, which we must process
        // first, and everything else.
        ImmutableList.Builder<String> namespacesBuilder = new ImmutableList.Builder<>();
        ImmutableList.Builder<String> othersBuilder = new ImmutableList.Builder<>();
        for (String key : names) {
          (key.startsWith("xmlns") ? namespacesBuilder : othersBuilder).add(key);
        }
        // First, handle all namespace specifications, updating our ns-to-URI map. Use a HashMap
        // rather than an ImmutableMap.Builder so that we can override existing map entries.
        HashMap<String, String> newNsMap = new HashMap<>(nsMap);
        for (String key : namespacesBuilder.build()) {
          // Parse the attribute name, of the form xmlns:nsid, and extract the namespace identifier.
          // If there's no colon, we are setting the default namespace.
          List<String> components = Splitter.on(':').splitToList(key);
          String ns = (components.size() >= 2) ? components.get(1) : "";
          newNsMap.put(ns, jsonObject.get(key).toString());
        }
        nsMap = ImmutableMap.copyOf(newNsMap);
        // Now, handle the non-namespace items, recursively transforming the map and mapping all
        // namespaces to the full URI for proper comparison.
        for (String key : othersBuilder.build()) {
          String simpleKey = Iterables.getLast(Splitter.on(':').split(key));
          String newPath = (path == null) ? simpleKey : (path + "." + simpleKey);
          String mappedKey;
          Object value;
          if (ignoredPaths.contains(newPath)) {
            mappedKey = null;
            // Set ignored fields to a value that will compare equal.
            value = "IGNORED";
          } else {
            Map.Entry<String, Object> simpleEntry =
                normalize(key, jsonObject.get(key), newPath, ignoredPaths, nsMap);
            mappedKey = simpleEntry.getKey();
            value = simpleEntry.getValue();
          }
          if (mappedKey == null) {
            // Note that this does not follow the XML extensions exactly. I read somewhere that attribute
            // names, unlike element names, never use the default namespace. But after
            // JSONification, we cannot distinguish between attributes and child elements, so we
            // apply the default namespace to everything. Hopefully that will not cause a problem.
            mappedKey = key.equals("content") ? key : mapName(key, nsMap, true);
          }
          map.put(mappedKey, value);
        }
      }
      // Map the namespace of the element name of the map we are normalizing.
      elementName = mapName(elementName, nsMap, true);
      // If a node has both text content and attributes, the text content will end up under a key
      // called "content". If that's the only thing left (which will only happen if there was an
      // "xmlns:*" key that we removed), treat the node as just text and recurse.
      if (map.size() == 1 && map.containsKey("content")) {
        return new AbstractMap.SimpleEntry<>(
            elementName,
            normalize(null, jsonObject.get("content"), path, ignoredPaths, nsMap).getValue());
      }
      // The conversion to JSON converts <a/> into "" and the semantically equivalent <a></a> into
      // an empty map, so normalize that here.
      return new AbstractMap.SimpleEntry<>(elementName, map.isEmpty() ? "" : map);
    }
    if (obj instanceof JSONArray) {
      // Another problem resulting from JSONification: If the array contains elements whose names
      // are the same before URI expansion, but different after URI expansion, because they use
      // xmlns attribute that define the namespaces differently, we will screw up. Again, hopefully
      // that doesn't happen much. The reverse is also true: If the array contains names that are
      // different before URI expansion, but the same after, we may have a problem, because the
      // elements will wind up in different JSONArrays as a result of JSONification. We wave our
      // hands and just assume that the URI expansion of the first element holds for all others.
      Set<Object> set = new HashSet<>();
      String mappedKey = null;
      for (int i = 0; i < ((JSONArray) obj).length(); ++i) {
        Map.Entry<String, Object> simpleEntry =
            normalize(null, ((JSONArray) obj).get(i), path, ignoredPaths, nsMap);
        if (i == 0) {
          mappedKey = simpleEntry.getKey();
        }
        set.add(simpleEntry.getValue());
      }
      return new AbstractMap.SimpleEntry<>(mappedKey, set);
    }
    if (obj instanceof Number) {
      return new AbstractMap.SimpleEntry<>(null, obj.toString());
    }
    if (obj instanceof Boolean) {
      return new AbstractMap.SimpleEntry<>(null, ((Boolean) obj) ? "1" : "0");
    }
    if (obj instanceof String) {
      // Turn stringified booleans into integers. Both are acceptable as xml boolean values, but
      // we use "true" and "false" whereas the samples use "1" and "0".
      if (obj.equals("true")) {
        return new AbstractMap.SimpleEntry<>(null, "1");
      }
      if (obj.equals("false")) {
        return new AbstractMap.SimpleEntry<>(null, "0");
      }
      String string = obj.toString();
      // We use a slightly different datetime format (both legal) than the samples, so normalize
      // both into Datetime objects.
      try {
        return new AbstractMap.SimpleEntry<>(
            null, ISODateTimeFormat.dateTime().parseDateTime(string).toDateTime(UTC));
      } catch (IllegalArgumentException e) {
        // It wasn't a DateTime.
      }
      try {
        return new AbstractMap.SimpleEntry<>(
            null, ISODateTimeFormat.dateTimeNoMillis().parseDateTime(string).toDateTime(UTC));
      } catch (IllegalArgumentException e) {
        // It wasn't a DateTime.
      }
      try {
        if (!InternetDomainName.isValid(string)) {
          // It's not a domain name, but it is an InetAddress. Ergo, it's an ip address.
          return new AbstractMap.SimpleEntry<>(null, InetAddresses.forString(string));
        }
      } catch (IllegalArgumentException e) {
        // Not an ip address.
      }
      return new AbstractMap.SimpleEntry<>(null, string);
    }
    return new AbstractMap.SimpleEntry<>(null, checkNotNull(obj));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> toComparableJson(
      String xml, String... ignoredPaths) throws Exception {
    return (Map<String, Object>) normalize(
        null,
        XML.toJSONObject(xml),
        null,
        ImmutableSet.copyOf(ignoredPaths),
        ImmutableMap.of()).getValue();
  }
}

