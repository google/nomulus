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
import static org.junit.Assert.assertThrows;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import google.registry.rdap.AbstractJsonableObject.JsonableException;
import google.registry.rdap.AbstractJsonableObject.RestrictJsonNames;
import java.util.Optional;
import org.joda.time.DateTime;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public final class AbstractJsonableObjectTest {

  private final Gson gson = new Gson();

  private JsonElement createJson(String... lines) {
    return gson.fromJson(Joiner.on("\n").join(lines), JsonElement.class);
  }

  @Test
  public void testPrimitives() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement String myString = "Hello, world!";
      @JsonableElement int myInt = 42;
      @JsonableElement double myDouble = 3.14;
      @JsonableElement boolean myBoolean = false;
    };
    assertThat(jsonable.toJson())
        .isEqualTo(
            createJson(
                "{'myBoolean':false,'myDouble':3.14,'myInt':42,'myString':'Hello, world!'}"));
  }


  @Test
  public void testDateTime() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement DateTime dateTime = DateTime.parse("2019-01-02T13:53Z");
    };
    assertThat(jsonable.toJson()).isEqualTo(createJson("{'dateTime':'2019-01-02T13:53:00.000Z'}"));
  }

  @Test
  public void testRenaming() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement("newName") String myString = "Hello, world!";
    };
    assertThat(jsonable.toJson()).isEqualTo(createJson("{'newName':'Hello, world!'}"));
  }

  @Test
  public void testDuplicateNames_fails() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement String myString = "A";
      @JsonableElement("myString") String anotherString = "B";
    };
    assertThat(assertThrows(JsonableException.class, () -> jsonable.toJson()))
        .hasMessageThat().contains("Encountered the same field name 'myString' multiple times");
  }

  @Test
  public void testMethod() {
    Jsonable jsonable =
        new AbstractJsonableObject() {
          @JsonableElement String myString() {
            return "Hello, world!";
          }
        };
    assertThat(jsonable.toJson()).isEqualTo(createJson("{'myString':'Hello, world!'}"));
  }

  @Test
  public void testMethodWithArguments_fails() {
    Jsonable jsonable =
        new AbstractJsonableObject() {
          @JsonableElement String myString(String in) {
            return in;
          }
        };
    assertThat(assertThrows(JsonableException.class, () -> jsonable.toJson()))
        .hasMessageThat().contains("must have no arguments");
  }


  @Test
  public void testRecursive() {
    Jsonable myJsonableObject = new AbstractJsonableObject() {
      @JsonableElement double myDouble = 3.14;
      @JsonableElement boolean myBoolean = false;
    };
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement Jsonable internalJsonableObject = myJsonableObject;
      @JsonableElement int myInt = 42;
    };
    assertThat(jsonable.toJson())
        .isEqualTo(
            createJson(
                "{",
                "  'internalJsonableObject':{'myBoolean':false,'myDouble':3.14},",
                "  'myInt':42",
                "}"));
  }

  @Test
  public void testList() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      ImmutableList<String> myList = ImmutableList.of("my", "immutable", "list");
    };
    assertThat(jsonable.toJson()).isEqualTo(createJson("{'myList':['my','immutable','list']}"));
  }

  @Test
  public void testMultipleLists() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      ImmutableList<String> myList = ImmutableList.of("my", "immutable", "list");
      @JsonableElement("myList")
      ImmutableList<Number> myList2 = ImmutableList.of(42, 3.14);
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'myList':['my','immutable','list',42,3.14]}"));
  }

  @Test
  public void testListElements() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      ImmutableList<String> myList = ImmutableList.of("my", "immutable", "list");
      @JsonableElement("myList[]")
      Integer myListMeaningOfLife = 42;
      @JsonableElement("myList[]")
      Double myListPi = 3.14;
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'myList':['my','immutable','list',42,3.14]}"));
  }

  @Test
  public void testListElementsWithoutList() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement("myList[]")
      Integer myListMeaningOfLife = 42;
      @JsonableElement("myList[]")
      Double myListPi = 3.14;
    };
    assertThat(jsonable.toJson()).isEqualTo(createJson("{'myList':[42,3.14]}"));
  }

  @Test
  public void testListOptionalElements() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      ImmutableList<String> myList = ImmutableList.of("my", "immutable", "list");
      @JsonableElement("myList[]")
      Optional<Integer> myListMeaningOfLife = Optional.of(42);
      @JsonableElement("myList[]")
      Optional<Double> myListPi = Optional.empty();
    };
    assertThat(jsonable.toJson()).isEqualTo(createJson("{'myList':['my','immutable','list',42]}"));
  }

  @Test
  public void testList_sameNameAsElement_failes() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement("myList")
      String myString = "A";
      @JsonableElement("myList[]")
      Optional<Integer> myListMeaningOfLife = Optional.of(42);
    };
    assertThat(assertThrows(JsonableException.class, () -> jsonable.toJson()))
        .hasMessageThat().contains("Encountered the same field name 'myList' multiple times");
  }

  @RestrictJsonNames({"allowed", "allowedList[]"})
  private static final class JsonableWithNameRestrictions implements Jsonable {
    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive("someValue");
    }
  }

  @RestrictJsonNames({})
  private static final class JsonableWithNoAllowedNames implements Jsonable {
    @Override
    public JsonPrimitive toJson() {
      return new JsonPrimitive("someValue");
    }
  }

  @Test
  public void testRestricted_withAllowedNames() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      JsonableWithNameRestrictions allowed = new JsonableWithNameRestrictions();

      @JsonableElement
      ImmutableList<JsonableWithNameRestrictions> allowedList =
          ImmutableList.of(new JsonableWithNameRestrictions());
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'allowed':'someValue','allowedList':['someValue']}"));
  }

  @Test
  public void testRestricted_withWrongName_failes() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      JsonableWithNameRestrictions wrong = new JsonableWithNameRestrictions();
    };
    assertThat(assertThrows(JsonableException.class, () -> jsonable.toJson()))
        .hasMessageThat()
        .contains("must be named one of ['allowed', 'allowedList[]'], but is named 'wrong'");
  }

  @Test
  public void testRestricted_withNoNamesAllowed_fails() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement
      JsonableWithNoAllowedNames wrong = new JsonableWithNoAllowedNames();
    };
    assertThat(assertThrows(JsonableException.class, () -> jsonable.toJson()))
        .hasMessageThat()
        .contains("is annotated with an empty RestrictJsonNames");
  }

  @RestrictJsonNames({})
  private static final class JsonableObjectWithNoAllowedNames extends AbstractJsonableObject {
    @JsonableElement final String key = "value";
  }

  @Test
  public void testRestricted_withNoNamesAllowed_canBeOutermost() {
    Jsonable jsonable = new JsonableObjectWithNoAllowedNames();
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'key':'value'}"));
  }

  @Test
  public void testRestricted_withNoNamesAllowed_canBeMerged() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement("*") final Jsonable inner = new JsonableObjectWithNoAllowedNames();
      @JsonableElement final String otherKey = "otherValue";
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'key':'value','otherKey':'otherValue'}"));
  }

  private abstract static class BaseWithStatic extends AbstractJsonableObject {
    @JsonableElement("messages[]")
    static final String MESSAGE_1 = "message 1";
    @JsonableElement("messages[]")
    static String getMessage2() {
      return "message 2";
    }
  }

  private static final class InheritedWithStatic extends BaseWithStatic {
    @JsonableElement("messages")
    final ImmutableList<String> moreMessages = ImmutableList.of("more messages");
  }

  @Test
  public void testInheritingStaticMembers_works() {
    Jsonable jsonable = new InheritedWithStatic();
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'messages':['message 1','more messages','message 2']}"));
  }

  private abstract static class BaseToOverride extends AbstractJsonableObject {
    @JsonableElement String annotationOnlyOnBase() {
      return "old value";
    }
    @JsonableElement String annotationOnBoth() {
      return "old value";
    }
    String annotationOnlyOnInherited() {
      return "old value";
    }
  }

  private static final class InheritedOverriding extends BaseToOverride {
    @Override
    String annotationOnlyOnBase() {
      return "new value";
    }
    @Override
    @JsonableElement String annotationOnBoth() {
      return "new value";
    }
    @Override
    @JsonableElement String annotationOnlyOnInherited() {
      return "new value";
    }
  }

  @Test
  public void testOverriding_works() {
    Jsonable jsonable = new InheritedOverriding();
    assertThat(jsonable.toJson())
        .isEqualTo(
            createJson(
                "{",
                "  'annotationOnlyOnBase':'new value',",
                "  'annotationOnBoth':'new value',",
                "  'annotationOnlyOnInherited':'new value'",
                "}"));
  }

  @Test
  public void testMerge_works() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement String key = "value";
      @JsonableElement("*") Object subObject = new AbstractJsonableObject() {
        @JsonableElement String innerKey = "innerValue";
      };
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'key':'value','innerKey':'innerValue'}"));
  }

  @Test
  public void testMerge_joinsArrays_works() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement("lst[]") String a = "value";
      @JsonableElement("*") Object subObject = new AbstractJsonableObject() {
        @JsonableElement("lst[]") String b = "innerValue";
      };
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'lst':['value','innerValue']}"));
  }

  @Test
  public void testMerge_multiLayer_works() {
    Jsonable jsonable = new AbstractJsonableObject() {
      @JsonableElement String key = "value";

      @JsonableElement("*") Object middleObject = new AbstractJsonableObject() {
        @JsonableElement String middleKey = "middleValue";

        @JsonableElement("*") Object subObject = new AbstractJsonableObject() {
          @JsonableElement String innerKey = "innerValue";
        };
      };
    };
    assertThat(jsonable.toJson())
        .isEqualTo(createJson("{'key':'value','middleKey':'middleValue','innerKey':'innerValue'}"));
  }
}
