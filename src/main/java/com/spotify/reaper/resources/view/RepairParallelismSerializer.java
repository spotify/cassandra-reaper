package com.spotify.reaper.resources.view;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.apache.cassandra.repair.RepairParallelism;

import java.io.IOException;

public class RepairParallelismSerializer extends StdSerializer<RepairParallelism> {
  protected RepairParallelismSerializer() {
    super(RepairParallelism.class);
  }

  protected RepairParallelismSerializer(Class<RepairParallelism> t) {
    super(t);
  }

  @Override
  public void serialize(RepairParallelism repairParallelism, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
    jsonGenerator.writeString(repairParallelism.getName());
  }
}
