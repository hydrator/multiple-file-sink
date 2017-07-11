/*
 * Copyright © 2015, 2016 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Macro;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.data.format.StructuredRecord;
import co.cask.cdap.api.data.schema.Schema;
import co.cask.cdap.api.data.schema.UnsupportedTypeException;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.lib.FileSetProperties;
import co.cask.cdap.api.dataset.lib.KeyValue;
import co.cask.cdap.etl.api.Emitter;
import co.cask.cdap.etl.api.batch.BatchRuntimeContext;
import co.cask.cdap.etl.api.batch.BatchSink;
import co.cask.hydrator.common.HiveSchemaConverter;
import co.cask.hydrator.plugin.common.FileBatchSink;
import co.cask.hydrator.plugin.common.FileSetUtil;
import co.cask.hydrator.plugin.common.StructuredToAvroTransformer;
import org.apache.avro.generic.GenericRecord;
import parquet.avro.AvroParquetInputFormat;
import parquet.avro.AvroParquetOutputFormat;

import java.io.IOException;
import javax.annotation.Nullable;


/**
 * {@link FileBatchSink} that stores data in Parquet format.
 */
@Plugin(type = BatchSink.PLUGIN_TYPE)
@Name("SnapshotParquet")
@Description("Sink for a SnapshotFileSet that writes data in Parquet format.")
public class MultipleFilesetSink extends FileBatchSink<Void, GenericRecord> {
  private final SnapshotParquetConfig config;

  private StructuredToAvroTransformer recordTransformer;

  public MultipleFilesetSink(SnapshotParquetConfig config) {
    super(config);
    this.config = config;
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    recordTransformer = new StructuredToAvroTransformer(config.schema, null);
    context.createDataset();
  }

  @Override
  public void transform(StructuredRecord input,
                        Emitter<KeyValue<Void, GenericRecord>> emitter) throws Exception {
    emitter.emit(new KeyValue<Void, GenericRecord>(null, recordTransformer.transform(input)));
  }

  @Override
  protected void addFileProperties(FileSetProperties.Builder propertiesBuilder) {
    String schema = config.schema.toLowerCase();
    // parse to make sure it's valid
    new org.apache.avro.Schema.Parser().parse(schema);
    String hiveSchema;
    try {
      hiveSchema = HiveSchemaConverter.toHiveSchema(Schema.parseJson(schema));
    } catch (UnsupportedTypeException | IOException e) {
      throw new RuntimeException("Error: Schema is not valid ", e);
    }
    propertiesBuilder.addAll(FileSetUtil.getParquetCompressionConfiguration(config.compressionCodec, config.schema,
                                                                            true));

    propertiesBuilder
      .setInputFormat(AvroParquetInputFormat.class)
      .setOutputFormat(AvroParquetOutputFormat.class)
      .setEnableExploreOnCreate(true)
      .setExploreFormat("parquet")
      .setExploreSchema(hiveSchema.substring(1, hiveSchema.length() - 1))
      .add(DatasetProperties.SCHEMA, schema);
  }

  /**
   * Config for SnapshotFileBatchParquetSink.
   */
  public static class SnapshotParquetConfig extends SnapshotFileSetBatchSinkConfig {
    @Description("The Parquet schema of the record being written to the Sink as a JSON Object.")
    @Macro
    private String schema;

    @Nullable
    @Description("Used to specify the compression codec to be used for the final dataset.")
    private String compressionCodec;

    public SnapshotParquetConfig(String name, @Nullable String basePath, String schema,
                                 @Nullable String compressionCodec) {
      super(name, basePath, null);
      this.schema = schema;
      this.compressionCodec = compressionCodec;
    }

    @Override
    public void validate() {
      super.validate();
      try {
        if (schema != null) {
          co.cask.cdap.api.data.schema.Schema.parseJson(schema);
        }
      } catch (IOException e) {
        throw new IllegalArgumentException("Unable to parse schema: " + e.getMessage());
      }
    }
  }
}