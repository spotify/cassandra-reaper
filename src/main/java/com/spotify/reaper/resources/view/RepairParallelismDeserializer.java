package com.spotify.reaper.resources.view;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import org.apache.cassandra.repair.RepairParallelism;

import java.io.IOException;

public class RepairParallelismDeserializer extends StdDeserializer<RepairParallelism> {
  protected RepairParallelismDeserializer() {
    super(RepairParallelism.class);
  }

  protected RepairParallelismDeserializer(Class<RepairParallelismDeserializer> t) {
    super(t);
  }

  @Override
  public RepairParallelism deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JsonProcessingException {
    return RepairParallelism.fromName(jsonParser.getValueAsString());
  }
}
