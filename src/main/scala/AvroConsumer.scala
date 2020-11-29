import AvroConsumer.sparkSchemaTransactions
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.serializers.AbstractKafkaAvroDeserializer
import org.apache.avro.Schema
import org.apache.avro.generic.GenericRecord
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.avro.SchemaConverters
import org.apache.spark.sql.functions.{col, from_json}

object AvroConsumer {
  private val schemaRegistryUrl = "http://localhost:8081"
  private val schemaRegistryClient = new CachedSchemaRegistryClient(schemaRegistryUrl, 128)
  private val kafkaAvroDeserializer = new AvroDeserializer(schemaRegistryClient)

  private val kafkaUrl = "localhost:9092"

  private val transactions = "transactions-avro"
  private val avroTransactionsSchema = schemaRegistryClient.getLatestSchemaMetadata(transactions + "-value").getSchema
  private val sparkSchemaTransactions = SchemaConverters.toSqlType(new Schema.Parser().parse(avroTransactionsSchema))

  def main(args: Array[String]): Unit = {
    val spark = SparkSession
      .builder
      .appName("ConfluentConsumer")
      .master("local")
      .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")

    spark.udf.register("deserialize", (bytes: Array[Byte]) =>
      DeserializerWrapper.deserializer.deserialize(bytes)
    )

    val kafkaDataFrameTransactions = spark
      .readStream
      .format("kafka")
      .option("kafka.bootstrap.servers", kafkaUrl)
      .option("subscribe", transactions)
      .load()
      .selectExpr("""deserialize(value) AS message""")
      .select(
        from_json(col("message"), sparkSchemaTransactions.dataType).alias("transaction")
      )
      .select("transaction.*")

    import org.apache.spark.sql.functions._

    kafkaDataFrameTransactions
      .writeStream
      .format("console")
      .option("truncate", value = false)
      .start()
      .awaitTermination()
  }

  object DeserializerWrapper {
    val deserializer = kafkaAvroDeserializer
  }

  class AvroDeserializer extends AbstractKafkaAvroDeserializer {
    def this(client: SchemaRegistryClient) {
      this()
      this.schemaRegistry = client
    }

    override def deserialize(bytes: Array[Byte]): String = {
      val genericRecord = super.deserialize(bytes).asInstanceOf[GenericRecord]
      genericRecord.toString
    }
  }

}
