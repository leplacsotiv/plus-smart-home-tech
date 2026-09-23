package ru.yandex.practicum.kafka.telemetry.serialization;

import org.apache.avro.Schema;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.avro.specific.SpecificRecordBase;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.serialization.Deserializer;
import java.io.IOException;

public abstract class BaseAvroDeserializer<T extends SpecificRecordBase> implements Deserializer<T> {
    private final Schema schema;
    private final DecoderFactory decoders = DecoderFactory.get();

    protected BaseAvroDeserializer(Schema schema) {
        this.schema = schema;
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        if (data == null) {
            return null;
        }
        try {
            return new SpecificDatumReader<T>(schema).read(null, decoders.binaryDecoder(data, null));
        } catch (IOException | RuntimeException e) {
            throw new SerializationException("Cannot deserialize Avro message from " + topic, e);
        }
    }
}
