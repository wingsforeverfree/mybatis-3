/*
 *    Copyright 2009-2025 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.type;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.chrono.JapaneseDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public final class TypeHandlerRegistry {

  /**
   * JDBC类型 -> 类型处理器 的映射表
   *
   * 结构设计原因：
   * 1. 使用 EnumMap：因为JdbcType是枚举类型，EnumMap在性能和内存占用上都优于HashMap
   * 2. 一层Map结构：只需要通过JdbcType就能直接找到对应的Handler
   * 3. 使用场景：当Java类型未知时，或者作为找不到匹配Handler时的最后备选方案
   *
   * 为什么使用final：
   * - 引用不可变：Map对象的引用一旦初始化就不能改变，保证线程安全性和防止意外替换
   * - 内容可变：Map内部的内容可以put/remove，只是引用本身不能指向其他Map对象
   * - 设计原则：这是注册表的核心数据结构，不应该在运行时被替换为其他Map实例
   */
  private final Map<JdbcType, TypeHandler<?>> jdbcTypeHandlerMap = new EnumMap<>(JdbcType.class);

  /**
   * Java类型 -> (JDBC类型 -> 类型处理器) 的双层映射表
   *
   * 结构设计原因：
   * 1. 双层Map结构：外层Map的key是Java类型，value是内层Map；内层Map的key是JdbcType，value是TypeHandler
   * 2. 为什么需要双层：同一个Java类型可以根据不同的JdbcType使用不同的Handler
   *    例如：String类型在JDBC中可以是VARCHAR、CLOB、NCLOB等，需要不同的Handler处理
   * 3. 内层Map的value可能为null的key：表示该Java类型的默认Handler（不指定JdbcType时使用）
   *    例如：register(String.class, null, StringTypeHandler.INSTANCE) 注册了String的默认Handler
   *
   * 使用示例：
   * - typeHandlerMap.get(String.class).get(JdbcType.VARCHAR) -> 获取String对应VARCHAR类型的Handler
   * - typeHandlerMap.get(String.class).get(null) -> 获取String的默认Handler
   *
   * 为什么使用ConcurrentHashMap：
   * - 支持并发读写：多线程环境下可以安全地注册和查询类型处理器
   * - 高性能：使用分段锁机制，比同步Map性能更好
   *
   * 为什么使用final：
   * - 引用不可变：保证这个核心注册表在对象生命周期内始终使用同一个Map实例
   * - 线程安全：配合ConcurrentHashMap使用，确保在多线程环境下Map引用不会被篡改
   * - 初始化安全：final字段必须在构造函数中初始化，保证了对象的正确构造
   */
  private final Map<Type, Map<JdbcType, TypeHandler<?>>> typeHandlerMap = new ConcurrentHashMap<>();

  /**
   * 智能类型处理器的构造器缓存表
   *
   * 结构设计原因：
   * 1. 缓存构造器：存储能够接受Type或Class参数的"智能"Handler的构造函数
   * 2. 什么是智能Handler：有些TypeHandler需要根据具体的Java类型动态创建（如泛型集合Handler）
   *    例如：ListTypeHandler需要知道List中元素的类型才能正确处理
   * 3. 延迟实例化：当需要使用时，通过缓存的构造器动态创建Handler实例
   *
   * 为什么使用ConcurrentHashMap：
   * - 并发访问：多线程环境下可能同时注册和使用智能Handler
   * - 原子操作：computeIfAbsent等方法提供了原子性的缓存操作
   *
   * 为什么使用final：
   * - 缓存一致性：保证缓存在对象生命周期内使用同一个Map实例
   * - 防止清空：避免缓存被意外清空或替换
   */
  private final ConcurrentHashMap<Type, Constructor<?>> smartHandlers = new ConcurrentHashMap<>();

  /**
   * 所有已注册类型处理器的索引表（按Handler的Class类型索引）
   *
   * 结构设计原因：
   * 1. 反向索引：不是按Java类型找Handler，而是按Handler的Class找实例
   * 2. 单例管理：确保每个Handler类只注册一个实例（大多Handler是无状态的无状态对象）
   * 3. 快速查找：可以通过Handler类快速判断是否已注册，或获取已注册的实例
   *
   * 使用场景：
   * - getTypeHandlers()方法返回所有已注册的Handler（用于mybatis-guice等集成）
   * - getMappingTypeHandler()方法通过Handler类查找已注册的实例
   * - register()方法末尾注册Handler实例到索引表（第552行）
   *
   * 为什么使用HashMap而不是ConcurrentHashMap：
   *
   * 【关键点1：访问时机差异】
   * - allTypeHandlersMap：只在初始化阶段写入（Configuration构造时），运行时只读
   * - typeHandlerMap：运行时可能动态写入（如getSmartHandler方法中延迟注册）
   *
   * 【关键点2：并发需求不同】
   * - allTypeHandlersMap：
   *   ① put操作：仅在register()方法末尾执行（第552行），发生在Configuration初始化阶段
   *   ② get操作：仅getMappingTypeHandler()和getTypeHandlers()使用，都是读操作
   *   ③ 结论：没有并发写入的风险，使用HashMap足够
   *
   * - typeHandlerMap：
   *   ① put/compute操作：不仅在初始化时注册，还在getSmartHandler()中动态注册（第444行）
   *   ② get操作：getTypeHandler()频繁查询，发生在SQL执行的整个生命周期
   *   ③ 并发场景：多个线程可能同时执行SQL，触发getSmartHandler的延迟注册
   *   ④ 结论：存在并发读写，必须使用ConcurrentHashMap
   *
   * 【关键点3：性能考虑】
   * - HashMap：无锁，单线程性能最优（比ConcurrentHashMap快20-30%）
   * - ConcurrentHashMap：有锁，并发安全但有性能开销
   * - 设计原则：在安全的前提下，优先选择性能更好的方案
   *
   * 【实际使用对比】
   * allTypeHandlersMap.put(handler.getClass(), handler);  // 只在初始化时
   * typeHandlerMap.compute(javaType, ...);                 // 运行时可能并发
   * typeHandlerMap.get(type);                              // 多线程并发查询
   *
   * 为什么使用final：
   * - 引用稳定：保证索引表在对象生命周期内不变
   * - 初始化保证：final字段必须在构造时初始化，避免空指针
   */
  private final Map<Class<?>, TypeHandler<?>> allTypeHandlersMap = new HashMap<>();

  /**
   * 空类型处理器的不可变空Map标记
   *
   * 设计原因：
   * 1. 标记作用：用于标记某个Java类型"已查询但无Handler"的状态
   * 2. 性能优化：避免重复查询不存在的Handler，直接返回这个空Map标记
   * 3. 内存优化：使用Collections.emptyMap()返回一个共享的不可变空Map，不占用额外内存
   *
   * 为什么使用static final：
   * - 全局唯一：所有TypeHandlerRegistry实例共享同一个空Map标记
   * - 不可变：防止空Map被修改，确保作为标记的安全性
   * - 常量：编译时常量，JVM会进行优化
   *
   * 使用方式：
   * - typeHandlerMap.put(type, NULL_TYPE_HANDLER_MAP) 表示该type没有Handler
   * - 后续查询时，如果得到NULL_TYPE_HANDLER_MAP，就知道无需再查找
   */
  private static final Map<JdbcType, TypeHandler<?>> NULL_TYPE_HANDLER_MAP = Collections.emptyMap();

  @SuppressWarnings("rawtypes")
  private Class<? extends TypeHandler> defaultEnumTypeHandler = EnumTypeHandler.class;

  /**
   * The default constructor.
   */
  public TypeHandlerRegistry() {
    this(new Configuration());
  }

  /**
   * The constructor that pass the MyBatis configuration.
   *
   * @param configuration
   *          a MyBatis configuration
   *
   * @since 3.5.4
   */
  public TypeHandlerRegistry(Configuration configuration) {
    // If a handler is registered against null JDBC type, it is the default handler for the Java type. Users can
    // override the default handler (e.g. `register(boolean.class, null, new YNBooleanTypeHandler())` or register a
    // custom handler for a specific Java-JDBC type combination (e.g. `register(boolean.class, JdbcType.CHAR, new
    // YNBooleanTypeHandler())`).
    register(new Type[] { Boolean.class, boolean.class }, new JdbcType[] { null }, BooleanTypeHandler.INSTANCE);
    register(new Type[] { Byte.class, byte.class }, new JdbcType[] { null }, ByteTypeHandler.INSTANCE);
    register(new Type[] { Short.class, short.class }, new JdbcType[] { null }, ShortTypeHandler.INSTANCE);
    register(new Type[] { Integer.class, int.class }, new JdbcType[] { null }, IntegerTypeHandler.INSTANCE);
    register(new Type[] { Long.class, long.class }, new JdbcType[] { null }, LongTypeHandler.INSTANCE);
    register(new Type[] { Float.class, float.class }, new JdbcType[] { null }, FloatTypeHandler.INSTANCE);
    register(new Type[] { Double.class, double.class }, new JdbcType[] { null }, DoubleTypeHandler.INSTANCE);
    register(new Type[] { Character.class, char.class }, new JdbcType[] { null }, new CharacterTypeHandler());
    // 设置java String类型默认的处理器
    register(String.class, null, StringTypeHandler.INSTANCE);
    register(Reader.class, null, new ClobReaderTypeHandler());
    register(BigInteger.class, null, new BigIntegerTypeHandler());
    register(BigDecimal.class, null, BigDecimalTypeHandler.INSTANCE);
    register(InputStream.class, null, new BlobInputStreamTypeHandler());
    register(Byte[].class, null, new ByteObjectArrayTypeHandler());
    register(byte[].class, null, ByteArrayTypeHandler.INSTANCE);
    register(Date.class, null, DateTypeHandler.INSTANCE);
    register(java.sql.Date.class, null, new SqlDateTypeHandler());
    register(Time.class, null, new SqlTimeTypeHandler());
    register(Timestamp.class, null, new SqlTimestampTypeHandler());
    register(Instant.class, null, new InstantTypeHandler());
    register(LocalDateTime.class, null, new LocalDateTimeTypeHandler());
    register(LocalDate.class, null, new LocalDateTypeHandler());
    register(LocalTime.class, null, new LocalTimeTypeHandler());
    register(OffsetDateTime.class, null, new OffsetDateTimeTypeHandler());
    register(OffsetTime.class, null, new OffsetTimeTypeHandler());
    register(ZonedDateTime.class, null, new ZonedDateTimeTypeHandler());
    register(Month.class, null, new MonthTypeHandler());
    register(Year.class, null, new YearTypeHandler());
    register(YearMonth.class, null, new YearMonthTypeHandler());
    register(JapaneseDate.class, null, new JapaneseDateTypeHandler());

    // These type handlers are used only for specific combinations of Java type and JDBC type.
    // 设置java String类型，jdbc类型为CLOB的处理器
    register(String.class, JdbcType.CLOB, ClobTypeHandler.INSTANCE);
    // 设置java String类型，jdbc类型为NCLOB的处理器
    register(String.class, JdbcType.NCLOB, NClobTypeHandler.INSTANCE);
    register(new Type[] { String.class }, new JdbcType[] { JdbcType.NCHAR, JdbcType.NVARCHAR, JdbcType.LONGNVARCHAR },
        NStringTypeHandler.INSTANCE);
    register(new Type[] { Byte[].class }, new JdbcType[] { JdbcType.BLOB, JdbcType.LONGVARBINARY },
        new BlobByteObjectArrayTypeHandler());
    register(new Type[] { byte[].class }, new JdbcType[] { JdbcType.BLOB, JdbcType.LONGVARBINARY },
        BlobTypeHandler.INSTANCE);
    register(Date.class, JdbcType.DATE, DateOnlyTypeHandler.INSTANCE);
    register(Date.class, JdbcType.TIME, TimeOnlyTypeHandler.INSTANCE);
    register(String.class, JdbcType.SQLXML, new SqlxmlTypeHandler());

    // Type handlers in the `jdbcTypeHandlerMap` are used when Java type is unknown or
    // as a last resort when no matching handler is found for the target Java type.
    // It is also used in some internal purposes like creating cache keys.
    // Although it is possible for users to override these mappings via register(JdbcType, TypeHandler),
    // it might have unexpected side-effect.
    // To configure type handlers for mapping to Map, for example, it is recommended to call the 3-args
    // version of register method. e.g. register(Object.class, JdbcType.DATE, new DateTypeHandler())
    jdbcTypeHandlerMap.put(JdbcType.BOOLEAN, BooleanTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.BIT, BooleanTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.TINYINT, ByteTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.SMALLINT, ShortTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.INTEGER, IntegerTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.BIGINT, LongTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.REAL, FloatTypeHandler.INSTANCE); // As per JDBC spec
    jdbcTypeHandlerMap.put(JdbcType.FLOAT, DoubleTypeHandler.INSTANCE); // As per JDBC spec
    jdbcTypeHandlerMap.put(JdbcType.DOUBLE, DoubleTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.DECIMAL, BigDecimalTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.NUMERIC, BigDecimalTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.CHAR, StringTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.VARCHAR, StringTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.LONGVARCHAR, StringTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.CLOB, ClobTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.NVARCHAR, NStringTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.NCHAR, NStringTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.LONGNVARCHAR, NStringTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.NCLOB, NClobTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.ARRAY, new ArrayTypeHandler());
    jdbcTypeHandlerMap.put(JdbcType.BINARY, ByteArrayTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.VARBINARY, ByteArrayTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.LONGVARBINARY, ByteArrayTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.BLOB, BlobTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.TIMESTAMP, DateTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.DATE, DateOnlyTypeHandler.INSTANCE);
    jdbcTypeHandlerMap.put(JdbcType.TIME, TimeOnlyTypeHandler.INSTANCE);
  }

  /**
   * Set a default {@link TypeHandler} class for {@link Enum}. A default {@link TypeHandler} is
   * {@link org.apache.ibatis.type.EnumTypeHandler}.
   *
   * @param typeHandler
   *          a type handler class for {@link Enum}
   *
   * @since 3.4.5
   */
  public void setDefaultEnumTypeHandler(@SuppressWarnings("rawtypes") Class<? extends TypeHandler> typeHandler) {
    this.defaultEnumTypeHandler = typeHandler;
  }

  public boolean hasTypeHandler(Type javaType) {
    return hasTypeHandler(javaType, null);
  }

  @Deprecated(since = "3.6.0", forRemoval = true)
  public boolean hasTypeHandler(TypeReference<?> javaTypeReference) {
    return hasTypeHandler(javaTypeReference, null);
  }

  public boolean hasTypeHandler(Type javaType, JdbcType jdbcType) {
    return javaType != null && getTypeHandler(javaType, jdbcType) != null;
  }

  @Deprecated(since = "3.6.0", forRemoval = true)
  public boolean hasTypeHandler(TypeReference<?> javaTypeReference, JdbcType jdbcType) {
    return javaTypeReference != null && getTypeHandler(javaTypeReference, jdbcType) != null;
  }

  @Deprecated(since = "3.6.0", forRemoval = true)
  public TypeHandler<?> getMappingTypeHandler(Class<? extends TypeHandler<?>> handlerType) {
    return allTypeHandlersMap.get(handlerType);
  }

  public TypeHandler<?> getTypeHandler(Type type) {
    return getTypeHandler(type, null);
  }

  @Deprecated(since = "3.6.0", forRemoval = true)
  public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference) {
    return getTypeHandler(javaTypeReference, null);
  }

  public TypeHandler<?> getTypeHandler(JdbcType jdbcType) {
    return jdbcTypeHandlerMap.get(jdbcType);
  }

  @SuppressWarnings("unchecked")
  @Deprecated(since = "3.6.0", forRemoval = true)
  public <T> TypeHandler<T> getTypeHandler(TypeReference<T> javaTypeReference, JdbcType jdbcType) {
    return (TypeHandler<T>) getTypeHandler(javaTypeReference.getRawType(), jdbcType);
  }

  public TypeHandler<?> getTypeHandler(Type type, JdbcType jdbcType, Class<? extends TypeHandler<?>> typeHandlerClass) {
    TypeHandler<?> typeHandler = getTypeHandler(type, jdbcType);
    if (typeHandler != null && (typeHandlerClass == null || typeHandler.getClass().equals(typeHandlerClass))) {
      return typeHandler;
    }
    if (typeHandlerClass == null) {
      typeHandler = getSmartHandler(type, jdbcType);
    } else {
      typeHandler = getMappingTypeHandler(typeHandlerClass);
      if (typeHandler == null) {
        typeHandler = getInstance(type, typeHandlerClass);
      }
    }
    return typeHandler;
  }

  public TypeHandler<?> getTypeHandler(Type type, JdbcType jdbcType) {
    if (ParamMap.class.equals(type)) {
      return null;
    } else if (type == null) {
      return getTypeHandler(jdbcType);
    }

    TypeHandler<?> handler = null;
    Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = getJdbcHandlerMap(type);

    if (Object.class.equals(type)) {
      if (jdbcHandlerMap != null) {
        handler = jdbcHandlerMap.get(jdbcType);
      }
      return handler;
    }

    if (jdbcHandlerMap != null) {
      handler = jdbcHandlerMap.get(jdbcType);
      if (handler == null) {
        handler = jdbcHandlerMap.get(null);
      }
      if (handler == null) {
        // #591
        handler = pickSoleHandler(jdbcHandlerMap);
      }
    }
    if (handler == null) {
      handler = getSmartHandler(type, jdbcType);
    }
    if (handler == null && type instanceof ParameterizedType) {
      handler = getTypeHandler((Class<?>) ((ParameterizedType) type).getRawType(), jdbcType);
    }
    return handler;
  }

  private TypeHandler<?> getSmartHandler(Type type, JdbcType jdbcType) {
    Constructor<?> candidate = null;

    for (Entry<Type, Constructor<?>> entry : smartHandlers.entrySet()) {
      Type registeredType = entry.getKey();
      if (registeredType.equals(type)) {
        candidate = entry.getValue();
        break;
      }
      if (registeredType instanceof Class) {
        if (type instanceof Class && ((Class<?>) registeredType).isAssignableFrom((Class<?>) type)) {
          candidate = entry.getValue();
        }
      } else if (registeredType instanceof ParameterizedType) {
        Class<?> registeredClass = (Class<?>) ((ParameterizedType) registeredType).getRawType();
        if (type instanceof ParameterizedType) {
          Class<?> clazz = (Class<?>) ((ParameterizedType) type).getRawType();
          if (registeredClass.isAssignableFrom(clazz)) {
            candidate = entry.getValue();
          }
        }
      }
    }

    if (candidate == null) {
      if (type instanceof Class) {
        Class<?> clazz = (Class<?>) type;
        if (Enum.class.isAssignableFrom(clazz)) {
          Class<?> enumClass = clazz.isAnonymousClass() ? clazz.getSuperclass() : clazz;
          TypeHandler<?> enumHandler = getInstance(enumClass, defaultEnumTypeHandler);
          register(new Type[] { enumClass }, new JdbcType[] { jdbcType }, enumHandler);
          return enumHandler;
        }
      }
      return null;
    }

    try {
      TypeHandler<?> typeHandler = (TypeHandler<?>) candidate.newInstance(type);
      register(type, jdbcType, typeHandler);
      return typeHandler;
    } catch (ReflectiveOperationException e) {
      throw new TypeException("Failed to invoke constructor " + candidate.toString(), e);
    }
  }

  private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMap(Type type) {
    Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(type);
    if (jdbcHandlerMap != null) {
      return NULL_TYPE_HANDLER_MAP.equals(jdbcHandlerMap) ? null : jdbcHandlerMap;
    }
    if (type instanceof Class) {
      Class<?> clazz = (Class<?>) type;
      if (!Enum.class.isAssignableFrom(clazz)) {
        jdbcHandlerMap = getJdbcHandlerMapForSuperclass(clazz);
      }
    }
    typeHandlerMap.put(type, jdbcHandlerMap == null ? NULL_TYPE_HANDLER_MAP : jdbcHandlerMap);
    return jdbcHandlerMap;
  }

  private Map<JdbcType, TypeHandler<?>> getJdbcHandlerMapForSuperclass(Class<?> clazz) {
    Class<?> superclass = clazz.getSuperclass();
    if (superclass == null || Object.class.equals(superclass)) {
      return null;
    }
    Map<JdbcType, TypeHandler<?>> jdbcHandlerMap = typeHandlerMap.get(superclass);
    if (jdbcHandlerMap != null) {
      return jdbcHandlerMap;
    }
    return getJdbcHandlerMapForSuperclass(superclass);
  }

  private TypeHandler<?> pickSoleHandler(Map<JdbcType, TypeHandler<?>> jdbcHandlerMap) {
    TypeHandler<?> soleHandler = null;
    for (TypeHandler<?> handler : jdbcHandlerMap.values()) {
      if (soleHandler == null) {
        soleHandler = handler;
      } else if (!handler.getClass().equals(soleHandler.getClass())) {
        // More than one type handlers registered.
        return null;
      }
    }
    return soleHandler;
  }

  public void register(JdbcType mappedJdbcType, TypeHandler<?> handler) {
    jdbcTypeHandlerMap.put(mappedJdbcType, handler);
  }

  //
  // REGISTER INSTANCE
  //

  // Only handler

  public <T> void register(TypeHandler<T> handler) {
    register(mappedJavaTypes(handler.getClass()), mappedJdbcTypes(handler.getClass()), handler);
  }

  // java type + handler

  public void register(Class<?> mappedJavaType, TypeHandler<?> handler) {
    register((Type) mappedJavaType, handler);
  }

  private void register(Type mappedJavaType, TypeHandler<?> handler) {
    register(new Type[] { mappedJavaType }, mappedJdbcTypes(handler.getClass()), handler);
  }

  @Deprecated(since = "3.6.0", forRemoval = true)
  public <T> void register(TypeReference<T> javaTypeReference, TypeHandler<? extends T> handler) {
    register(javaTypeReference.getRawType(), handler);
  }

  // java type + jdbc type + handler

  public void register(Type mappedJavaType, JdbcType mappedJdbcType, TypeHandler<?> handler) {
    register(new Type[] { mappedJavaType }, new JdbcType[] { mappedJdbcType }, handler);
  }

  /**
   * 批量注册类型处理器到多个Java类型和多个JDBC类型的映射关系中
   *
   * @param mappedJavaTypes  要映射的Java类型数组（可以是带有泛型参数的参数化类型）
   * @param mappedJdbcTypes  要映射的JDBC类型数组
   * @param handler          类型处理器实例
   *
   * 该方法会：
   * 1. 遍历所有Java类型，为每个Java类型建立与所有JDBC类型的映射关系
   * 2. 如果Java类型是参数化类型（带泛型），还会额外为原始类型建立映射，并处理冲突
   * 3. 将处理器实例注册到全局类型处理器映射表中
   */
  private void register(Type[] mappedJavaTypes, JdbcType[] mappedJdbcTypes, TypeHandler<?> handler) {
    // 遍历所有要映射的Java类型
    for (Type javaType : mappedJavaTypes) {
      // 跳过null的Java类型
      if (javaType == null) {
        continue;
      }

      // 使用compute方法原子性地更新typeHandlerMap
      // 如果该Java类型还没有映射表，创建一个新的HashMap；否则使用已有的映射表
      typeHandlerMap.compute(javaType, (k, v) -> {
        // 如果当前的map是null或空映射标记，则创建新的HashMap；否则复用现有的map
        Map<JdbcType, TypeHandler<?>> map = (v == null || v == NULL_TYPE_HANDLER_MAP ? new HashMap<>() : v);
        // 遍历所有要映射的JDBC类型，将handler注册到每个JDBC类型下
        for (JdbcType jdbcType : mappedJdbcTypes) {
          map.put(jdbcType, handler);
        }
        return map;
      });

      // TODO 如果Java类型是参数化类型（例如 List<String>、Map<Integer, String> 等）
      if (javaType instanceof ParameterizedType) {
        // 获取参数化类型的原始类型（例如 List<String> 的原始类型是 List）
        Type rawType = ((ParameterizedType) javaType).getRawType();
        // 为原始类型也建立映射关系，这样可以支持更灵活的类型匹配
        typeHandlerMap.compute(rawType, (k, v) -> {
          // 如果当前的map是null或空映射标记，则创建新的HashMap；否则复用现有的map
          Map<JdbcType, TypeHandler<?>> map = (v == null || v == NULL_TYPE_HANDLER_MAP ? new HashMap<>() : v);
          // 遍历所有要映射的JDBC类型
          for (JdbcType jdbcType : mappedJdbcTypes) {
            // 使用merge方法处理可能的冲突：
            // - 如果该JDBC类型下还没有handler，直接放入当前handler
            // - 如果已有handler且与新handler相同，保持原handler
            // - 如果已有handler但与新handler不同，创建一个ConflictedTypeHandler来包装两个冲突的handler
            map.merge(jdbcType, handler, (handler1, handler2) -> handler1.equals(handler2) ? handler1
                : new ConflictedTypeHandler((Class<?>) rawType, jdbcType, handler1, handler2));
          }
          return map;
        });
      }
    }

    // 将handler实例注册到全局的type处理器映射表中（按handler的Class类型索引）
    // 这个映射用于后续通过handler的Class来查找已注册的handler实例
    allTypeHandlersMap.put(handler.getClass(), handler);
  }

  //
  // REGISTER CLASS
  //

  // Only handler type

  public void register(Class<?> handlerClass) {
    register(mappedJavaTypes(handlerClass), mappedJdbcTypes(handlerClass), handlerClass);
  }

  // java type + handler type

  @Deprecated(since = "3.6.0", forRemoval = true)
  public void register(String javaTypeClassName, String typeHandlerClassName) throws ClassNotFoundException {
    register(Resources.classForName(javaTypeClassName), Resources.classForName(typeHandlerClassName));
  }

  public void register(Type mappedJavaType, Class<?> handlerClass) {
    register(new Type[] { mappedJavaType }, mappedJdbcTypes(handlerClass), handlerClass);
  }

  // java type + jdbc type + handler type

  public void register(Type mappedJavaType, JdbcType mappedJdbcType, Class<?> handlerClass) {
    register(new Type[] { mappedJavaType }, new JdbcType[] { mappedJdbcType }, handlerClass);
  }

  private void register(Type[] mappedJavaTypes, JdbcType[] mappedJdbcTypes, Class<?> handlerClass) {
    if (!TypeHandler.class.isAssignableFrom(handlerClass)) {
      throw new IllegalArgumentException(String.format("'%s' does not implement TypeHandler.", handlerClass.getName()));
    }
    for (Constructor<?> constructor : handlerClass.getConstructors()) {
      if (constructor.getParameterCount() != 1) {
        continue;
      }
      Class<?> argType = constructor.getParameterTypes()[0];
      if (Type.class.equals(argType) || Class.class.equals(argType)) {
        for (Type javaType : mappedJavaTypes) {
          smartHandlers.computeIfAbsent(javaType, k -> constructor);
        }
        return;
      }
    }
    // It is not a smart handler
    register(mappedJavaTypes, mappedJdbcTypes, getInstance(null, handlerClass));
  }

  private Type[] mappedJavaTypes(Class<?> clazz) {
    MappedTypes mappedTypesAnno = clazz.getAnnotation(MappedTypes.class);
    if (mappedTypesAnno != null) {
      return mappedTypesAnno.value();
    }
    return TypeParameterResolver.resolveClassTypeParams(TypeHandler.class, clazz);
  }

  private JdbcType[] mappedJdbcTypes(Class<?> clazz) {
    MappedJdbcTypes mappedJdbcTypesAnno = clazz.getAnnotation(MappedJdbcTypes.class);
    if (mappedJdbcTypesAnno != null) {
      JdbcType[] jdbcTypes = mappedJdbcTypesAnno.value();
      if (mappedJdbcTypesAnno.includeNullJdbcType()) {
        int newLength = jdbcTypes.length + 1;
        jdbcTypes = Arrays.copyOf(jdbcTypes, newLength);
        jdbcTypes[newLength - 1] = null;
      }
      return jdbcTypes;
    }
    return new JdbcType[] { null };
  }

  // Construct a handler (used also from Builders)

  @SuppressWarnings("unchecked")
  public <T> TypeHandler<T> getInstance(Type javaType, Class<?> handlerClass) {
    Constructor<?> c;
    try {
      if (javaType != null) {
        try {
          c = handlerClass.getConstructor(Type.class);
          return (TypeHandler<T>) c.newInstance(javaType);
        } catch (NoSuchMethodException ignored) {
        }
        if (javaType instanceof Class) {
          try {
            c = handlerClass.getConstructor(Class.class);
            return (TypeHandler<T>) c.newInstance(javaType);
          } catch (NoSuchMethodException ignored) {
          }
        }
      }
      try {
        c = handlerClass.getConstructor();
        return (TypeHandler<T>) c.newInstance();
      } catch (NoSuchMethodException e) {
        throw new TypeException("Unable to find a usable constructor for " + handlerClass, e);
      }
    } catch (ReflectiveOperationException e) {
      throw new TypeException("Failed to invoke constructor for handler " + handlerClass, e);
    }
  }

  // scan

  public void register(String packageName) {
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    resolverUtil.find(new ResolverUtil.IsA(TypeHandler.class), packageName);
    Set<Class<? extends Class<?>>> handlerSet = resolverUtil.getClasses();
    for (Class<?> type : handlerSet) {
      // Ignore inner classes and interfaces (including package-info.java) and abstract classes
      if (!type.isAnonymousClass() && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
        register(type);
      }
    }
  }

  // get information

  /**
   * Gets the type handlers. Used by mybatis-guice.
   *
   * @return the type handlers
   *
   * @since 3.2.2
   */
  public Collection<TypeHandler<?>> getTypeHandlers() {
    return Collections.unmodifiableCollection(allTypeHandlersMap.values());
  }

}
