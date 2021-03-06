package wandou.avro

import java.io.ByteArrayInputStream
import java.io.IOException
import org.apache.avro.Schema
import org.apache.avro.Schema.Type
import org.apache.avro.generic.GenericData
import org.apache.avro.io.DecoderFactory
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificRecord
import org.codehaus.jackson.JsonFactory
import org.codehaus.jackson.JsonNode
import org.codehaus.jackson.JsonParser
import org.codehaus.jackson.JsonParser.Feature
import org.codehaus.jackson.map.ObjectMapper
import org.codehaus.jackson.node.ObjectNode
import scala.collection.JavaConversions._

/**
 * Decode a JSON string into an Avro value.
 *
 * TODO choose SpecificRecord or GenericRecord
 */
object FromJson {

  /**
   * Decodes a JSON node as an Avro value.
   *
   * Comply with specified default values when decoding records with missing fields.
   *
   * @param json JSON node to decode.
   * @param schema Avro schema of the value to decode.
   * @return the decoded value.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  def fromJsonNode(json: JsonNode, schema: Schema, specified: Boolean = false): Any = {
    schema.getType match {
      case Type.INT =>
        if (!json.isInt) {
          throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
        }
        json.getIntValue

      case Type.LONG =>
        if (!json.isLong && !json.isInt) {
          throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
        }
        json.getLongValue

      case Type.FLOAT =>
        if (json.isDouble || json.isInt || json.isLong) {
          return json.getDoubleValue.toFloat
        }
        throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))

      case Type.DOUBLE =>
        if (json.isDouble || json.isInt || json.isLong) {
          return json.getDoubleValue
        }
        throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))

      case Type.STRING =>
        if (!json.isTextual) {
          throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
        }
        json.getTextValue

      case Type.BOOLEAN =>
        if (!json.isBoolean) {
          throw new IOException(String.format(
            "Avro schema specifies '%s' but got JSON value: '%s'.",
            schema, json))
        }
        json.getBooleanValue()

      case Type.ARRAY =>
        if (!json.isNull) {
          if (!json.isArray) {
            throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
          }
          val list = new java.util.ArrayList[Any]()
          val it = json.getElements
          while (it.hasNext) {
            val element = it.next()
            list.add(fromJsonNode(element, schema.getElementType, specified))
          }
          list
        } else {
          null
        }

      case Type.MAP =>
        if (!json.isNull) {
          if (!json.isObject) {
            throw new IOException(String.format(
              "Avro schema specifies '%s' but got JSON value: '%s'.",
              schema, json))
          }
          //assert json instanceof ObjectNode; // Help findbugs out.
          val map = new java.util.HashMap[String, Any]()
          val it = json.asInstanceOf[ObjectNode].getFields
          while (it.hasNext) {
            val entry = it.next()
            map.put(entry.getKey, fromJsonNode(entry.getValue, schema.getValueType, specified))
          }
          map
        } else {
          null
        }

      case Type.RECORD =>
        if (!json.isNull) {
          if (!json.isObject) {
            throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
          }
          json.getFieldNames.toSet
          var fields = json.getFieldNames.toSet
          val record = if (specified) newSpecificRecord(schema.getFullName) else newGenericRecord(schema)
          for (field <- schema.getFields) {
            val fieldName = field.name
            val fieldElement = json.get(fieldName)
            if (fieldElement != null) {
              val fieldValue = fromJsonNode(fieldElement, field.schema, specified)
              record.put(field.pos, fieldValue)
            } else if (field.defaultValue != null) {
              record.put(field.pos, fromJsonNode(field.defaultValue, field.schema, specified))
            } else {
              throw new IOException("Error parsing Avro record '%s' with missing field '%s'.".format(schema.getFullName, field.name))
            }
            fields -= fieldName
          }
          if (!fields.isEmpty) {
            throw new IOException("Error parsing Avro record '%s' with unexpected fields: %s.".format(schema.getFullName, fields.mkString(",")))
          }
          record
        } else {
          null
        }

      case Type.UNION =>
        fromUnionJsonNode(json, schema, specified)

      case Type.NULL =>
        if (!json.isNull) {
          throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
        }
        null

      case Type.BYTES | Type.FIXED =>
        if (!json.isTextual) {
          throw new IOException("Avro schema specifies '%s' but got non-string JSON value: '%s'.".format(schema, json))
        }
        // TODO: parse string into byte array.
        throw new RuntimeException("Parsing byte arrays is not implemented yet")

      case Type.ENUM =>
        if (!json.isTextual) {
          throw new IOException("Avro schema specifies enum '%s' but got non-string JSON value: '%s'.".format(schema, json))
        }
        val enumValStr = json.getTextValue
        enumValue(schema.getFullName, enumValStr)

      case _ =>
        throw new RuntimeException("Unexpected schema type: " + schema)
    }
  }

  /**
   * Decodes a union from a JSON node.
   *
   * @param json JSON node to decode.
   * @param schema Avro schema of the union value to decode.
   * @return the decoded value.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  private def fromUnionJsonNode(json: JsonNode, schema: Schema, specified: Boolean): Any = {
    if (schema.getType != Type.UNION) {
      throw new IOException("Avro schema specifies '%s' but got JSON value: '%s'.".format(schema, json))
    }

    try {
      val optionalType = getFirstNoNullTypeOfUnion(schema)
      if (optionalType != null) {
        return if (json.isNull) null else fromJsonNode(json, optionalType, specified)
      }
    } catch {
      case ex: IOException => // Union value may be wrapped, ignore.
    }

    /** Map from Avro schema type to list of schemas of this type in the union. */
    val typeMap = new java.util.HashMap[Type, java.util.List[Schema]]()
    for (tpe <- schema.getTypes) {
      var types = typeMap.get(tpe.getType)
      if (null == types) {
        types = new java.util.ArrayList[Schema]()
        typeMap.put(tpe.getType, types)
      }
      types.add(tpe)
    }

    if (json.isObject && (json.size == 1)) {
      val entry = json.getFields.next()
      val typeName = entry.getKey
      val actualNode = entry.getValue

      for (tpe <- schema.getTypes) {
        if (tpe.getFullName == typeName) {
          return fromJsonNode(actualNode, tpe, specified)
        }
      }
    }

    for (tpe <- schema.getTypes) {
      try {
        return fromJsonNode(json, tpe, specified)
      } catch {
        case ex: IOException => // Wrong union type case.
      }
    }

    throw new IOException("Unable to decode JSON '%s' for union '%s'.".format(json, schema))
  }

  /**
   * Decodes a JSON encoded record.
   *
   * @param json JSON tree to decode, encoded as a string.
   * @param schema Avro schema of the value to decode.
   * @return the decoded value.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  def fromJsonString(json: String, schema: Schema, specified: Boolean = false): Any = {
    val mapper = new ObjectMapper()
    val parser = new JsonFactory().createJsonParser(json)
      .enable(Feature.ALLOW_COMMENTS)
      .enable(Feature.ALLOW_SINGLE_QUOTES)
      .enable(Feature.ALLOW_UNQUOTED_FIELD_NAMES)
    val root = mapper.readTree(parser)
    fromJsonNode(root, schema, specified)
  }

  /**
   * Instantiates a specific record by name.
   *
   * @param fullName Fully qualified record name to instantiate.
   * @return a brand-new specific record instance of the given class.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  private def newGenericRecord(schema: Schema): GenericData.Record = {
    new GenericData.Record(schema)
  }

  /**
   * Instantiates a specific record by name.
   *
   * @param fullName Fully qualified record name to instantiate.
   * @return a brand-new specific record instance of the given class.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  private def newSpecificRecord(fullName: String): SpecificRecord = {
    try {
      val klass = Class.forName(fullName)
      klass.newInstance().asInstanceOf[SpecificRecord]
    } catch {
      case ex: ClassNotFoundException => throw new IOException("Error while deserializing JSON: '%s' class not found.".format(fullName))
      case ex: IllegalAccessException => throw new IOException("Error while deserializing JSON: cannot access '%s'.".format(fullName))
      case ex: InstantiationException => throw new IOException("Error while deserializing JSON: cannot instantiate '%s'.".format(fullName))
    }
  }

  /**
   * Looks up an Avro enum by name and string value.
   *
   * @param fullName Fully qualified enum name to look-up.
   * @param value Enum value as a string.
   * @return the Java enum value.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  private def enumValue(fullName: String, value: String): Any = {
    try {
      Class.forName(fullName) match {
        case enumClass if classOf[Enum[_]].isAssignableFrom(enumClass) => enumValue(enumClass, value)
        case _ => throw new IOException("Error while deserializing JSON: '%s' enum class not found.".format(fullName))
      }
    } catch {
      case ex: ClassNotFoundException => throw new IOException("Error while deserializing JSON: '%s' enum class not found.".format(fullName))
    }
  }

  /**
   * We need the compiler to believe that the Class[_] we have is actually a Class[T <: Enum[T]]
   * (so of course, a preliminary test that this is indeed a Java enum — as done
   * in your code — is needed). So we cast cls to Class[T], where T was inferred
   * by the compiler to be <: Enum[T]. But the compiler still has to find a suitable T,
   * and defaults to Nothing here. So, as far as the compiler is concerned, cls.asInstanceOf[Class[T]]
   * is a Class[Nothing]. This is temporarily OK since it can be used to call Enum.valueOf —
   * the problem is that the inferred return type of valueOf is then, naturally,
   * Nothing as well. And here we have a problem, because the compiler will insert
   * an exception when we try to actually use an instance of type Nothing. So, we
   * finally cast the return value of valueOf to an Enum[_].
   *
   * The trick is then to always let the compiler infer the type argument to enumValueOf
   * and never try to specify it ourselves (since we're not supposed to know it anyway) —
   * and thus to extract the call to Enum.valueOf in another method, giving the
   * compiler a chance to bind a T <: Enum[T].
   */
  private def enumValue[T <: Enum[T]](cls: Class[_], stringValue: String): Enum[_] =
    Enum.valueOf(cls.asInstanceOf[Class[T]], stringValue).asInstanceOf[Enum[_]]

  /**
   * Standard Avro JSON decoder.
   *
   * @param json JSON string to decode.
   * @param schema Schema of the value to decode.
   * @return the decoded value.
   * @throws IOException on error.
   */
  @throws(classOf[IOException])
  def fromAvroJsonString(json: String, schema: Schema): Any = {
    val jsonInput = new ByteArrayInputStream(json.getBytes("UTF-8"))
    val decoder = DecoderFactory.get.jsonDecoder(schema, jsonInput)
    val reader = new SpecificDatumReader[Any](schema)
    reader.read(null.asInstanceOf[Any], decoder)
  }

  def getFirstNoNullTypeOfUnion(schema: Schema) = {
    val tpes = schema.getTypes.iterator
    var firstNonNullType: Schema = null
    while (tpes.hasNext && firstNonNullType == null) {
      val tpe = tpes.next
      if (tpe.getType != Type.NULL) {
        firstNonNullType = tpe
      }
    }
    if (firstNonNullType != null) firstNonNullType else schema.getTypes.get(0)
  }
}