/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.stage.processor.jsonparser;

import com.streamsets.pipeline.api.ConfigDef;
import com.streamsets.pipeline.api.ConfigGroups;
import com.streamsets.pipeline.api.GenerateResourceBundle;
import com.streamsets.pipeline.api.Processor;
import com.streamsets.pipeline.api.StageDef;
import com.streamsets.pipeline.configurablestage.DProcessor;

@GenerateResourceBundle
@StageDef(
    version="1.0.0",
    label="JSON Parser",
    description = "Parses a string field with JSON data",
    icon="jsonparser.svg"
)
@ConfigGroups(Groups.class)
public class JsonParserDProcessor extends DProcessor {

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "Field to Parse",
      description = "String field that contains a JSON object",
      displayPosition = 10,
      group = "JSON"
  )
  public String fieldPathToParse;

  @ConfigDef(
      required = true,
      type = ConfigDef.Type.STRING,
      defaultValue = "",
      label = "New Parsed Field",
      description="Name of the new field to set the parsed JSON data",
      displayPosition = 20,
      group = "JSON"
  )
  public String parsedFieldPath;

  @Override
  protected Processor createProcessor() {
    return new JsonParserProcessor(fieldPathToParse, parsedFieldPath);
  }
}
