package parquet.scrooge;

import com.twitter.scrooge.ThriftStructCodec;
import com.twitter.scrooge.ThriftStructField;
import parquet.thrift.struct.ThriftField;
import parquet.thrift.struct.ThriftType;
import parquet.thrift.struct.ThriftTypeID;
import scala.collection.Iterator;
import scala.collection.JavaConversions;
import scala.reflect.Manifest;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ScroogeSchemaConverter {

  public ThriftType.StructType convertStructFromClassName(String className) throws Exception {
    Class<?> companionClass = Class.forName(className + "$");
    ThriftStructCodec cObject = (ThriftStructCodec<?>) companionClass.getField("MODULE$").get(null);

    List<ThriftField> children = new ArrayList<ThriftField>();
    Iterable<ThriftStructField> scroogeFields = JavaConversions.asIterable(cObject.metaData().fields());
    for (ThriftStructField field : scroogeFields) {
      children.add(toThriftField(field));//TODO should I check requirement here?
    }  //StructType could it self be wrapped by StructField, so no worry
    return new ThriftType.StructType(children);
  }

  public ThriftField toThriftField(ThriftStructField f) throws Exception {
    ThriftField.Requirement requirement = ThriftField.Requirement.REQUIRED;
    if (isOptional(f)) {
      requirement = ThriftField.Requirement.OPTIONAL;
    }
    //TODO: default to optional or required???? Better solution: ask CSL to add Requirement for field

    String fieldName = f.tfield().name;
    short fieldId = f.tfield().id;
    byte thriftTypeByte = f.tfield().type;
    ThriftTypeID typeId = ThriftTypeID.fromByte(thriftTypeByte);
    System.out.println(fieldName);

    ThriftType resultType;
    switch (typeId) {
        // Primitive type can be inspected from type of TField, it should be accurate
      case BOOL:
        resultType = new ThriftType.BoolType();
        break;
      case BYTE:
        resultType = new ThriftType.ByteType();
        break;
      case DOUBLE:
        resultType = new ThriftType.DoubleType();
        break;
      case I16:
        resultType = new ThriftType.I16Type();
        break;
      case I32:
        resultType = new ThriftType.I32Type();
        break;
      case I64:
        resultType = new ThriftType.I64Type();
        break;
      case STRING:
        resultType = new ThriftType.StringType();
        break;
      case STRUCT:
        resultType = convertStructTypeField(f);
        break;
      case MAP:
        resultType = convertMapTypeField(f);
        break;
      case SET:
        resultType = convertSetTypeField(f);
        break;
      case LIST:
        resultType = convertListTypeField(f);
        break;
      case ENUM:
        resultType = convertEnumTypeField(f);
        break;
      case STOP:
      case VOID:
      default:
        throw new UnsupportedOperationException("can't convert type");
    }

    return new ThriftField(fieldName, fieldId, requirement, resultType);
  }

  private ThriftType convertEnumTypeField(ThriftStructField f) {
    return new EnumConverter().convertEnumTypeField(f);
  }

  private ThriftType convertSetTypeField(ThriftStructField f) throws Exception {
    List<Class> typeArguments = getTypeArguments(f);
    ThriftType elementType = convertBasedOnClass(typeArguments.get(0));
    ThriftField elementField = new ThriftField(f.name(), (short) 1, ThriftField.Requirement.REQUIRED, elementType);
    return new ThriftType.SetType(elementField);
  }

  private List<Class> getTypeArguments(ThriftStructField f) {
    Iterator<Manifest> it = ((Manifest) f.manifest().get()).typeArguments().iterator();
    List<Class> types = new ArrayList<Class>();
    while (it.hasNext()) {
      types.add(it.next().erasure());
    }
    return types;
  }

  private ThriftType convertListTypeField(ThriftStructField f) throws Exception {
    List<Class> typeArguments = getTypeArguments(f);
    ThriftType elementType = convertBasedOnClass(typeArguments.get(0));
    ThriftField elementField = new ThriftField(f.name(), (short) 1, ThriftField.Requirement.REQUIRED, elementType);
    return new ThriftType.ListType(elementField);
  }

  private ThriftType convertMapTypeField(ThriftStructField f) throws Exception {
    List<Class> typeArguments = getTypeArguments(f);
    Class keyClass = typeArguments.get(0);
    //TODO requirement should be the requirement of the map
    ThriftType keyType = convertBasedOnClass(keyClass);
    Class valueClass = typeArguments.get(1);
    //id of a key:default to 1, this is the behavior in elephant bird
    //requirementType will be required TODO: check compatible with elephantbird
    ThriftField keyField = new ThriftField(f.name() + "_map_key", (short) 1, ThriftField.Requirement.REQUIRED, keyType);
    ThriftType valueType = convertBasedOnClass(valueClass);
    ThriftField valueField = new ThriftField(f.name() + "_map_value", (short) 1, ThriftField.Requirement.REQUIRED, valueType);
    return new ThriftType.MapType(keyField, valueField);
  }

  private ThriftType convertBasedOnClass(Class keyClass) throws Exception {
    //This will be used by generic type containers, like map, list, set
    if (keyClass == boolean.class) {
      return new ThriftType.BoolType();
    } else if (keyClass == byte.class) {
      return new ThriftType.ByteType();
    } else if (keyClass == double.class) {
      return new ThriftType.DoubleType();
    } else if (keyClass == short.class) {
      return new ThriftType.I16Type();
    } else if (keyClass == int.class) {
      return new ThriftType.I32Type();
    } else if (keyClass == long.class) {
      return new ThriftType.I64Type();
    } else if (keyClass == String.class) {
      return new ThriftType.StringType();
    } else {
      return convertStructFromClassName(keyClass.getName());
    }
  }

  private ThriftType convertStructTypeField(ThriftStructField f) throws Exception {
    Type structClassType = f.method().getReturnType();
    if (isOptional(f)) {
      structClassType = extractClassFromOption(f.method().getGenericReturnType());
    }
    return convertStructFromClassName(((Class) structClassType).getName());
  }

  private Type extractClassFromOption(Type genericReturnType) {
    return ((ParameterizedType) genericReturnType).getActualTypeArguments()[0];
  }

  private boolean isOptional(ThriftStructField f) {
    return f.method().getReturnType() == scala.Option.class;
  }

  public ThriftType.StructType convert(Class scroogeClass) throws Exception {
    return convertStructFromClassName(scroogeClass.getName());
  }
}