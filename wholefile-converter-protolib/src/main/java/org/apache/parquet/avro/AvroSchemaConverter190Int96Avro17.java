/*
 * Copyright 2019 StreamSets Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.parquet.avro;

import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.lib.util.AvroTypeUtil;
import org.apache.avro.Schema;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.schema.ConversionPatterns;
import org.apache.parquet.schema.DecimalMetadata;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.OriginalType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.apache.avro.JsonProperties.NULL_VALUE;
import static org.apache.parquet.avro.AvroWriteSupport.WRITE_OLD_LIST_STRUCTURE;
import static org.apache.parquet.avro.AvroWriteSupport.WRITE_OLD_LIST_STRUCTURE_DEFAULT;
import static org.apache.parquet.schema.OriginalType.DECIMAL;
import static org.apache.parquet.schema.OriginalType.ENUM;
import static org.apache.parquet.schema.OriginalType.UTF8;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BOOLEAN;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.DOUBLE;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FLOAT;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT64;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT96;
import static org.apache.parquet.schema.Type.Repetition.REPEATED;

public class AvroSchemaConverter190Int96Avro17 {

  private static final Logger LOG = LoggerFactory.getLogger(AvroSchemaConverter190Int96Avro17.class);

  public static final String ADD_LIST_ELEMENT_RECORDS =
      "parquet.avro.add-list-element-records";
  private static final boolean ADD_LIST_ELEMENT_RECORDS_DEFAULT = true;

  private final boolean assumeRepeatedIsListElement;
  private final boolean writeOldListStructure;

  public AvroSchemaConverter190Int96Avro17(Configuration conf) {
    this.assumeRepeatedIsListElement = conf.getBoolean(
        ADD_LIST_ELEMENT_RECORDS, ADD_LIST_ELEMENT_RECORDS_DEFAULT);
    this.writeOldListStructure = conf.getBoolean(
        WRITE_OLD_LIST_STRUCTURE, WRITE_OLD_LIST_STRUCTURE_DEFAULT);
  }

  /**
   * Given a schema, check to see if it is a union of a null type and a regular schema,
   * and then return the non-null sub-schema. Otherwise, return the given schema.
   *
   * @param schema The schema to check
   * @return The non-null portion of a union schema, or the given schema
   */
  public static Schema getNonNull(Schema schema) {
    if (schema.getType().equals(Schema.Type.UNION)) {
      List<Schema> schemas = schema.getTypes();
      if (schemas.size() == 2) {
        if (schemas.get(0).getType().equals(Schema.Type.NULL)) {
          return schemas.get(1);
        } else if (schemas.get(1).getType().equals(Schema.Type.NULL)) {
          return schemas.get(0);
        } else {
          return schema;
        }
      } else {
        return schema;
      }
    } else {
      return schema;
    }
  }

  public MessageType convert(Schema avroSchema) {
    if (!avroSchema.getType().equals(Schema.Type.RECORD)) {
      throw new IllegalArgumentException("Avro schema must be a record.");
    }
    LOG.debug("Converting avro schema to parquet");
    return new MessageType(avroSchema.getFullName(), convertFields(avroSchema.getFields()));
  }

  private List<Type> convertFields(List<Schema.Field> fields) {
    List<Type> types = new ArrayList<Type>();
    for (Schema.Field field : fields) {
      if (field.schema().getType().equals(Schema.Type.NULL)) {
        continue; // Avro nulls are not encoded, unless they are null unions
      }
      types.add(convertField(field));
    }
    return types;
  }

  private Type convertField(String fieldName, Schema schema) {
    return convertField(fieldName, schema, Type.Repetition.REQUIRED);
  }

  @SuppressWarnings("deprecation")
  private Type convertField(String fieldName, Schema schema, Type.Repetition repetition) {
    LOG.debug("Converting field: {}", fieldName);

    return convertFieldWithoutUsingLogicalType(fieldName, schema, repetition);
  }

  private Type convertFieldWithoutUsingLogicalType(String fieldName, Schema schema, Type.Repetition repetition) {
    LOG.debug("Converting field: {} without using LogicalType", fieldName);
    Types.PrimitiveBuilder<PrimitiveType> builder;
    Schema.Type type = schema.getType();

    String logicalType = schema.getProp(AvroTypeUtil.LOGICAL_TYPE);

    if (type.equals(Schema.Type.BOOLEAN)) {
      builder = Types.primitive(BOOLEAN, repetition);
    } else if (type.equals(Schema.Type.INT)) {
      builder = Types.primitive(INT32, repetition);
    } else if (type.equals(Schema.Type.LONG)) {
      // Special case handling timestamp until int96 fully supported or logical types correctly supported
      if (AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MILLIS.equals(logicalType) ||
          AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MICROS.equals(logicalType)) {
        LOG.debug("Logical type is a timestamp millis or micros");
        builder = Types.primitive(INT96, repetition);
      } else {
        builder = Types.primitive(INT64, repetition);
      }
    } else if (type.equals(Schema.Type.FLOAT)) {
      builder = Types.primitive(FLOAT, repetition);
    } else if (type.equals(Schema.Type.DOUBLE)) {
      builder = Types.primitive(DOUBLE, repetition);
    } else if (type.equals(Schema.Type.BYTES)) {
      builder = Types.primitive(BINARY, repetition);
    } else if (type.equals(Schema.Type.STRING)) {
      builder = Types.primitive(BINARY, repetition).as(UTF8);
    } else if (type.equals(Schema.Type.RECORD)) {
      return new GroupType(repetition, fieldName, convertFields(schema.getFields()));
    } else if (type.equals(Schema.Type.ENUM)) {
      builder = Types.primitive(BINARY, repetition).as(ENUM);
    } else if (type.equals(Schema.Type.ARRAY)) {
      if (writeOldListStructure) {
        return ConversionPatterns.listType(repetition, fieldName,
            convertField("array", schema.getElementType(), REPEATED));
      } else {
        return ConversionPatterns.listOfElements(repetition, fieldName,
            convertField(AvroWriteSupport.LIST_ELEMENT_NAME, schema.getElementType()));
      }
    } else if (type.equals(Schema.Type.MAP)) {
      Type valType = convertField("value", schema.getValueType());
      // avro map key type is always string
      return ConversionPatterns.stringKeyMapType(repetition, fieldName, valType);
    } else if (type.equals(Schema.Type.FIXED)) {
      builder = Types.primitive(FIXED_LEN_BYTE_ARRAY, repetition)
                     .length(schema.getFixedSize());
    } else if (type.equals(Schema.Type.UNION)) {
      return convertUnion(fieldName, schema, repetition);
    } else {
      throw new UnsupportedOperationException("Cannot convert Avro type " + type);
    }

    // schema translation can only be done for known logical types because this
    // creates an equivalence

    if (logicalType != null &&
        !(AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MILLIS.equals(logicalType) || AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MICROS.equals(logicalType))) {
      if (AvroTypeUtil.LOGICAL_TYPE_DECIMAL.equals(logicalType)) {
        builder = (Types.PrimitiveBuilder<PrimitiveType>) builder.as(DECIMAL)
           .precision(schema.getJsonProp(AvroTypeUtil.LOGICAL_TYPE_ATTR_PRECISION).getIntValue())
           .scale(schema.getJsonProp(AvroTypeUtil.LOGICAL_TYPE_ATTR_SCALE).getIntValue());

      } else {
        OriginalType annotation = convertLogicalTypeStr(logicalType);
        if (annotation != null) {
          builder.as(annotation);
        }
      }
    }

    return builder.named(fieldName);
  }

  private Type convertUnion(String fieldName, Schema schema, Type.Repetition repetition) {
    List<Schema> nonNullSchemas = new ArrayList<Schema>(schema.getTypes().size());
    for (Schema childSchema : schema.getTypes()) {
      if (childSchema.getType().equals(Schema.Type.NULL)) {
        if (Type.Repetition.REQUIRED == repetition) {
          repetition = Type.Repetition.OPTIONAL;
        }
      } else {
        nonNullSchemas.add(childSchema);
      }
    }
    // If we only get a null and one other type then its a simple optional field
    // otherwise construct a union container
    switch (nonNullSchemas.size()) {
      case 0:
        throw new UnsupportedOperationException("Cannot convert Avro union of only nulls");

      case 1:
        return convertField(fieldName, nonNullSchemas.get(0), repetition);

      default: // complex union type
        List<Type> unionTypes = new ArrayList<Type>(nonNullSchemas.size());
        int index = 0;
        for (Schema childSchema : nonNullSchemas) {
          unionTypes.add( convertField("member" + index++, childSchema, Type.Repetition.OPTIONAL));
        }
        return new GroupType(repetition, fieldName, unionTypes);
    }
  }

  private Type convertField(Schema.Field field) {
    return convertField(field.name(), field.schema());
  }

  public Schema convert(MessageType parquetSchema) {
    return convertFields(parquetSchema.getName(), parquetSchema.getFields());
  }

  Schema convert(GroupType parquetSchema) {
    return convertFields(parquetSchema.getName(), parquetSchema.getFields());
  }

  private Schema convertFields(String name, List<Type> parquetFields) {
    List<Schema.Field> fields = new ArrayList<Schema.Field>();
    for (Type parquetType : parquetFields) {
      Schema fieldSchema = convertField(parquetType);
      if (parquetType.isRepetition(REPEATED)) {
        throw new UnsupportedOperationException("REPEATED not supported outside LIST or MAP. Type: " + parquetType);
      } else if (parquetType.isRepetition(Type.Repetition.OPTIONAL)) {
        fields.add(new Schema.Field(
            parquetType.getName(), optional(fieldSchema), null, NULL_VALUE));
      } else { // REQUIRED
        fields.add(new Schema.Field(
            parquetType.getName(), fieldSchema, null, (Object) null));
      }
    }
    Schema schema = Schema.createRecord(name, null, null, false);
    schema.setFields(fields);
    return schema;
  }

  private Schema convertField(final Type parquetType) {
    if (parquetType.isPrimitive()) {
      final PrimitiveType asPrimitive = parquetType.asPrimitiveType();
      final PrimitiveType.PrimitiveTypeName parquetPrimitiveTypeName =
          asPrimitive.getPrimitiveTypeName();
      final OriginalType annotation = parquetType.getOriginalType();
      Schema schema = parquetPrimitiveTypeName.convert(
          new PrimitiveType.PrimitiveTypeNameConverter<Schema, RuntimeException>() {
            @Override
            public Schema convertBOOLEAN(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              return Schema.create(Schema.Type.BOOLEAN);
            }
            @Override
            public Schema convertINT32(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              return Schema.create(Schema.Type.INT);
            }
            @Override
            public Schema convertINT64(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              return Schema.create(Schema.Type.LONG);
            }
            @Override
            public Schema convertINT96(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              LOG.debug("INT96 being converted to long though losing precision if value uses more than 64 bits");
              return Schema.create(Schema.Type.LONG);
            }
            @Override
            public Schema convertFLOAT(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              return Schema.create(Schema.Type.FLOAT);
            }
            @Override
            public Schema convertDOUBLE(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              return Schema.create(Schema.Type.DOUBLE);
            }
            @Override
            public Schema convertFIXED_LEN_BYTE_ARRAY(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              int size = parquetType.asPrimitiveType().getTypeLength();
              return Schema.createFixed(parquetType.getName(), null, null, size);
            }
            @Override
            public Schema convertBINARY(PrimitiveType.PrimitiveTypeName primitiveTypeName) {
              if (annotation == OriginalType.UTF8 || annotation == OriginalType.ENUM) {
                return Schema.create(Schema.Type.STRING);
              } else {
                return Schema.create(Schema.Type.BYTES);
              }
            }
          });


      schema = addLogicalTypeStrToSchema(schema, annotation, asPrimitive, parquetPrimitiveTypeName);

      return schema;

    } else {
      GroupType parquetGroupType = parquetType.asGroupType();
      OriginalType originalType = parquetGroupType.getOriginalType();
      if (originalType != null) {
        switch(originalType) {
          case LIST:
            if (parquetGroupType.getFieldCount()!= 1) {
              throw new UnsupportedOperationException("Invalid list type " + parquetGroupType);
            }
            Type repeatedType = parquetGroupType.getType(0);
            if (!repeatedType.isRepetition(REPEATED)) {
              throw new UnsupportedOperationException("Invalid list type " + parquetGroupType);
            }
            if (isElementType(repeatedType, parquetGroupType.getName())) {
              // repeated element types are always required
              return Schema.createArray(convertField(repeatedType));
            } else {
              Type elementType = repeatedType.asGroupType().getType(0);
              if (elementType.isRepetition(Type.Repetition.OPTIONAL)) {
                return Schema.createArray(optional(convertField(elementType)));
              } else {
                return Schema.createArray(convertField(elementType));
              }
            }
          case MAP_KEY_VALUE: // for backward-compatibility
          case MAP:
            if (parquetGroupType.getFieldCount() != 1 || parquetGroupType.getType(0).isPrimitive()) {
              throw new UnsupportedOperationException("Invalid map type " + parquetGroupType);
            }
            GroupType mapKeyValType = parquetGroupType.getType(0).asGroupType();
            if (!mapKeyValType.isRepetition(REPEATED) ||
                mapKeyValType.getFieldCount()!=2) {
              throw new UnsupportedOperationException("Invalid map type " + parquetGroupType);
            }
            Type keyType = mapKeyValType.getType(0);
            if (!keyType.isPrimitive() ||
                !keyType.asPrimitiveType().getPrimitiveTypeName().equals(PrimitiveType.PrimitiveTypeName.BINARY) ||
                !keyType.getOriginalType().equals(OriginalType.UTF8)) {
              throw new IllegalArgumentException("Map key type must be binary (UTF8): "
                  + keyType);
            }
            Type valueType = mapKeyValType.getType(1);
            if (valueType.isRepetition(Type.Repetition.OPTIONAL)) {
              return Schema.createMap(optional(convertField(valueType)));
            } else {
              return Schema.createMap(convertField(valueType));
            }
          case ENUM:
            return Schema.create(Schema.Type.STRING);
          case UTF8:
          default:
            throw new UnsupportedOperationException("Cannot convert Parquet type " +
                parquetType);

        }
      } else {
        // if no original type then it's a record
        return convertFields(parquetGroupType.getName(), parquetGroupType.getFields());
      }
    }
  }

  private Schema addLogicalTypeStrToSchema(
      Schema schema,
      OriginalType annotation,
      PrimitiveType asPrimitive,
      PrimitiveType.PrimitiveTypeName parquetPrimitiveTypeName
  ) {
    Map<String, String> logicalType = convertOriginalTypeToMap(annotation, asPrimitive.getDecimalMetadata());
    if (logicalType != null && (annotation != DECIMAL ||
        parquetPrimitiveTypeName == BINARY ||
        parquetPrimitiveTypeName == FIXED_LEN_BYTE_ARRAY)) {
      for(Map.Entry<String, String> entry : logicalType.entrySet()) {
        schema.addProp(entry.getKey(), entry.getValue());
      }
    }

    return schema;
  }

  private OriginalType convertLogicalTypeStr(String logicalType) {
    if (logicalType == null) {
      return null;
    } else if (AvroTypeUtil.LOGICAL_TYPE_DECIMAL.equals(logicalType)) {
      return OriginalType.DECIMAL;
    } else if (AvroTypeUtil.LOGICAL_TYPE_DATE.equals(logicalType)) {
      return OriginalType.DATE;
    } else if (AvroTypeUtil.LOGICAL_TYPE_TIME_MILLIS.equals(logicalType)) {
      return OriginalType.TIME_MILLIS;
//    } else if (AvroTypeUtil.LOGICAL_TYPE_TIME_MICROS.equals(logicalType)) {
//      return OriginalType.TIME_MICROS;
    } else if (AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MILLIS.equals(logicalType)) {
      return OriginalType.TIMESTAMP_MILLIS;
//    } else if (AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MICROS.equals(logicalType)) {
//      return OriginalType.TIMESTAMP_MICROS;
    }
    return null;
  }

  private Map<String, String> convertOriginalTypeToMap(OriginalType annotation, DecimalMetadata meta) {
    if (annotation == null) {
      return null;
    }
    switch (annotation) {
      case DECIMAL:
        return ImmutableMap.of(
            AvroTypeUtil.LOGICAL_TYPE, AvroTypeUtil.LOGICAL_TYPE_DECIMAL,
            AvroTypeUtil.LOGICAL_TYPE_ATTR_PRECISION, Integer.toString(meta.getPrecision()),
            AvroTypeUtil.LOGICAL_TYPE_ATTR_SCALE, Integer.toString(meta.getScale())
        );
      case DATE:
        return ImmutableMap.of(AvroTypeUtil.LOGICAL_TYPE, AvroTypeUtil.LOGICAL_TYPE_DATE);
      case TIME_MILLIS:
        return ImmutableMap.of(AvroTypeUtil.LOGICAL_TYPE, AvroTypeUtil.LOGICAL_TYPE_TIME_MILLIS);
//      case TIME_MICROS:
//        return ImmutableMap.of(AvroTypeUtil.LOGICAL_TYPE, AvroTypeUtil.LOGICAL_TYPE_TIME_MICROS);
      case TIMESTAMP_MILLIS:
        return ImmutableMap.of(AvroTypeUtil.LOGICAL_TYPE, AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MILLIS);
//      case TIMESTAMP_MICROS:
//        return ImmutableMap.of(AvroTypeUtil.LOGICAL_TYPE, AvroTypeUtil.LOGICAL_TYPE_TIMESTAMP_MICROS);
      default:
        return null;
    }
  }

  /**
   * Implements the rules for interpreting existing data from the logical type
   * spec for the LIST annotation. This is used to produce the expected schema.
   * <p>
   * The AvroArrayConverter will decide whether the repeated type is the array
   * element type by testing whether the element schema and repeated type are
   * the same. This ensures that the LIST rules are followed when there is no
   * schema and that a schema can be provided to override the default behavior.
   */
  private boolean isElementType(Type repeatedType, String parentName) {
    return (
        // can't be a synthetic layer because it would be invalid
        repeatedType.isPrimitive() ||
            repeatedType.asGroupType().getFieldCount() > 1 ||
            repeatedType.asGroupType().getType(0).isRepetition(REPEATED) ||
            // known patterns without the synthetic layer
            repeatedType.getName().equals("array") ||
            repeatedType.getName().equals(parentName + "_tuple") ||
            // default assumption
            assumeRepeatedIsListElement
    );
  }

  private static Schema optional(Schema original) {
    // null is first in the union because Parquet's default is always null
    return Schema.createUnion(Arrays.asList(
        Schema.create(Schema.Type.NULL),
        original)
    );
  }

}
