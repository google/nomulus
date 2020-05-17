// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

package google.registry.persistence.converter;

import static google.registry.persistence.converter.StringMapDescriptor.StringMap;

import com.google.common.collect.ImmutableMap;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.AbstractTypeDescriptor;
import org.hibernate.type.descriptor.java.JavaTypeDescriptor;
import org.hibernate.type.descriptor.spi.JdbcRecommendedSqlTypeMappingContext;
import org.hibernate.type.descriptor.sql.BasicBinder;
import org.hibernate.type.descriptor.sql.BasicExtractor;
import org.hibernate.type.descriptor.sql.SqlTypeDescriptor;

/**
 * The {@link JavaTypeDescriptor} and {@link SqlTypeDescriptor} for {@link StringMap}.
 *
 * <p>A {@link StringMap} object is a simple wrapper for a {@code Map<String, String>} which can be
 * stored in a column with data type of hstore in the database. The {@link JavaTypeDescriptor} and
 * {@link SqlTypeDescriptor} is used by JPA/Hibernate to map between the map and hstore which is the
 * actual type that JDBC uses to read from and write to the database.
 *
 * @see <a
 *     href="https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-jpa-convert">JPA
 *     2.1 AttributeConverters</a>
 * @see <a href="https://www.postgresql.org/docs/current/hstore.html">hstore</a>
 */
public class StringMapDescriptor extends AbstractTypeDescriptor<StringMap>
    implements SqlTypeDescriptor {
  public static final int COLUMN_TYPE = Types.OTHER;
  public static final String COLUMN_NAME = "hstore";
  private static final StringMapDescriptor INSTANCE = new StringMapDescriptor();

  protected StringMapDescriptor() {
    super(StringMap.class);
  }

  public static StringMapDescriptor getInstance() {
    return INSTANCE;
  }

  @Override
  public StringMap fromString(String string) {
    throw new UnsupportedOperationException(
        "Constructing StringMapDescriptor from string is not allowed");
  }

  @Override
  public <X> X unwrap(StringMap value, Class<X> type, WrapperOptions options) {
    if (value == null) {
      return null;
    }
    if (Map.class.isAssignableFrom(type)) {
      return (X) value.getMap();
    }
    throw unknownUnwrap(type);
  }

  @Override
  public <X> StringMap wrap(X value, WrapperOptions options) {
    if (value == null) {
      return null;
    }
    if (value instanceof Map) {
      return StringMap.create((Map<String, String>) value);
    }
    throw unknownWrap(value.getClass());
  }

  @Override
  public SqlTypeDescriptor getJdbcRecommendedSqlType(JdbcRecommendedSqlTypeMappingContext context) {
    return this;
  }

  @Override
  public int getSqlType() {
    return COLUMN_TYPE;
  }

  @Override
  public boolean canBeRemapped() {
    return false;
  }

  @Override
  public <X> ValueBinder<X> getBinder(JavaTypeDescriptor<X> javaTypeDescriptor) {
    return new BasicBinder<X>(javaTypeDescriptor, this) {
      @Override
      protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
          throws SQLException {
        st.setObject(index, getStringMap(value));
      }

      @Override
      protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
          throws SQLException {
        st.setObject(name, getStringMap(value));
      }

      private Map<String, String> getStringMap(X value) {
        if (value == null) {
          return null;
        }
        if (value instanceof StringMap) {
          return ((StringMap) value).getMap();
        } else {
          throw new UnsupportedOperationException(
              String.format(
                  "Binding type %s is not supported by StringMapDescriptor",
                  value.getClass().getName()));
        }
      }
    };
  }

  @Override
  public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
    return new BasicExtractor<X>(javaTypeDescriptor, this) {
      @Override
      protected X doExtract(ResultSet rs, String name, WrapperOptions options) throws SQLException {
        return javaTypeDescriptor.wrap(rs.getObject(name), options);
      }

      @Override
      protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
          throws SQLException {
        return javaTypeDescriptor.wrap(statement.getObject(index), options);
      }

      @Override
      protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
          throws SQLException {
        return javaTypeDescriptor.wrap(statement.getObject(name), options);
      }
    };
  }

  /** A simple wrapper class for {@code Map<String, String>}. */
  public static class StringMap {
    private Map<String, String> map;

    private StringMap(Map<String, String> map) {
      this.map = map;
    }

    /** Constructs an instance of {@link StringMap} from the given map. */
    public static StringMap create(Map<String, String> map) {
      return new StringMap(ImmutableMap.copyOf(map));
    }

    /** Returns the underlying {@code Map<String, String>} object. */
    public Map<String, String> getMap() {
      return map;
    }
  }
}
