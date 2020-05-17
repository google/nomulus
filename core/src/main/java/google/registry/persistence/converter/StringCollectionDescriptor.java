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

import static google.registry.persistence.converter.StringCollectionDescriptor.StringCollection;

import com.google.common.collect.ImmutableList;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
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
 * The {@link JavaTypeDescriptor} and {@link SqlTypeDescriptor} for {@link StringCollection}.
 *
 * <p>A {@link StringCollection} object is a simple wrapper for a {@code Collection<String>} which
 * can be stored as a string array in the database. The {@link JavaTypeDescriptor} and {@link
 * SqlTypeDescriptor} is used by JPA/Hibernate to map between the collection and {@link Array} which
 * is the actual type that JDBC uses to read from and write to the database.
 *
 * @see <a
 *     href="https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#basic-jpa-convert">JPA
 *     2.1 AttributeConverters</a>
 */
public class StringCollectionDescriptor extends AbstractTypeDescriptor<StringCollection>
    implements SqlTypeDescriptor {
  public static final int COLUMN_TYPE = Types.ARRAY;
  public static final String COLUMN_NAME = "text";
  public static final String COLUMN_DDL_NAME = COLUMN_NAME + "[]";
  private static final StringCollectionDescriptor INSTANCE = new StringCollectionDescriptor();

  protected StringCollectionDescriptor() {
    super(StringCollection.class);
  }

  public static StringCollectionDescriptor getInstance() {
    return INSTANCE;
  }

  @Override
  public StringCollection fromString(String string) {
    throw new UnsupportedOperationException(
        "Constructing StringCollectionDescriptor from string is not allowed");
  }

  @Override
  public <X> X unwrap(StringCollection value, Class<X> type, WrapperOptions options) {
    if (value == null) {
      return null;
    }
    if (Collection.class.isAssignableFrom(type)) {
      return (X) value.getCollection();
    }
    throw unknownUnwrap(type);
  }

  @Override
  public <X> StringCollection wrap(X value, WrapperOptions options) {
    if (value == null) {
      return null;
    }
    if (value instanceof Array) {
      try {
        String[] arr = (String[]) ((Array) value).getArray();
        ImmutableList.Builder<String> builder = new ImmutableList.Builder<>();
        for (String str : arr) {
          builder.add(str);
        }
        return StringCollection.create(builder.build());
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
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
        if (value == null) {
          st.setArray(index, null);
          return;
        }
        if (value instanceof StringCollection) {
          StringCollection stringCollection = (StringCollection) value;
          if (stringCollection.getCollection() == null) {
            st.setArray(index, null);
          } else {
            st.setArray(
                index,
                st.getConnection()
                    .createArrayOf(COLUMN_NAME, stringCollection.getCollection().toArray()));
          }
        } else {
          throw new UnsupportedOperationException(
              String.format(
                  "Binding type %s is not supported by StringCollectionDescriptor",
                  value.getClass().getName()));
        }
      }

      @Override
      protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
          throws SQLException {
        // CallableStatement.setArray() doesn't have an overload version for setting array by its
        // column name
        throw new UnsupportedOperationException(
            "Binding array by its column name is not supported");
      }
    };
  }

  @Override
  public <X> ValueExtractor<X> getExtractor(JavaTypeDescriptor<X> javaTypeDescriptor) {
    return new BasicExtractor<X>(javaTypeDescriptor, this) {
      @Override
      protected X doExtract(ResultSet rs, String name, WrapperOptions options) throws SQLException {
        return javaTypeDescriptor.wrap(rs.getArray(name), options);
      }

      @Override
      protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
          throws SQLException {
        return javaTypeDescriptor.wrap(statement.getArray(index), options);
      }

      @Override
      protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
          throws SQLException {
        return javaTypeDescriptor.wrap(statement.getArray(name), options);
      }
    };
  }

  public static class StringCollection {
    private Collection<String> collection;

    private StringCollection(Collection<String> collection) {
      this.collection = collection;
    }

    public static StringCollection create(Collection<String> collection) {
      return new StringCollection(collection);
    }

    public Collection<String> getCollection() {
      return collection;
    }
  }
}
