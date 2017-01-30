/**
 * Copyright 2017 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.pipeline.stage.origin.jdbc.table;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.lib.jdbc.JdbcUtil;
import com.streamsets.pipeline.sdk.RecordCreator;
import com.streamsets.pipeline.sdk.SourceRunner;
import com.streamsets.pipeline.sdk.StageRunner;
import com.streamsets.pipeline.stage.origin.jdbc.table.util.OffsetQueryUtil;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@RunWith(Parameterized.class)
public class DifferentTypesAsOffsetIT extends BaseTableJdbcSourceIT {
  private static final Logger LOGGER = LoggerFactory.getLogger(DifferentTypesAsOffsetIT.class);
  private static final String TABLE_NAME = "CHECK_OFFSET";
  private static final String FIELD_NAME_TEMPLATE = "field_%s";
  private static final String EXTRA_STRING_COLUMN = "extra_string_column";
  private static final String INSERT_PREPARED_STATEMENT = "INSERT INTO %s.%s values (?, ?)";
  private static final int NUMBER_OF_RECORDS = 2000;
  private static final Random RANDOM = new Random();

  private final int offsetSqlType;
  private final Field.Type offsetFieldType;
  private String offsetFieldName;
  private List<Record> expectedRecords;

  public DifferentTypesAsOffsetIT(int offsetSqlType, Field.Type offsetFieldType) {
    this.offsetSqlType = offsetSqlType;
    this.offsetFieldType = offsetFieldType;
    expectedRecords = new ArrayList<>();
  }

  @Parameterized.Parameters(name = "Offset Field Type: ({1}), SQL Type: ({0})")
  public static Collection<Object[]> data() throws Exception {
    Set<Integer> supportedSqlTypesForOffset =
        Sets.newHashSet(
            Sets.difference(
                OffsetQueryUtil.SQL_TYPE_TO_FIELD_TYPE.keySet(),
                OffsetQueryUtil.UNSUPPORTED_OFFSET_SQL_TYPES
            )
        );
    //These cause a bit of friction of because of cast to different types in different DBS
    supportedSqlTypesForOffset.removeAll(Arrays.asList(Types.BIT, Types.BOOLEAN, Types.CHAR));
    List<Object[]> data = new ArrayList<>();
    for (int supportedSqlType : supportedSqlTypesForOffset) {
      Object[] dataDimension = new Object[2];
      dataDimension[0] = supportedSqlType;
      dataDimension[1] = OffsetQueryUtil.SQL_TYPE_TO_FIELD_TYPE.get(supportedSqlType);
      data.add(dataDimension);
    }
    return data;
  }

  private class OffsetFieldComparator implements Comparator<Field> {
    @Override
    public int compare(Field f1, Field f2) {
      switch (offsetFieldType) {
        case BYTE:
          return Byte.compare(f1.getValueAsByte(), f2.getValueAsByte());
        case SHORT:
          return Short.valueOf(f1.getValueAsShort()).compareTo(f2.getValueAsShort());
        case INTEGER:
          return Integer.compare(f1.getValueAsInteger(),f2.getValueAsInteger());
        case LONG:
          return Long.compare(f1.getValueAsLong(), f2.getValueAsLong());
        case FLOAT:
          return Float.compare(f1.getValueAsFloat(), f2.getValueAsFloat());
        case DOUBLE:
          return Double.compare(f1.getValueAsDouble(), f2.getValueAsDouble());
        case DECIMAL:
          return f1.getValueAsDecimal().compareTo(f2.getValueAsDecimal());
        case STRING:
          return f1.getValueAsString().compareTo(f2.getValueAsString());
        case DATE:
        case TIME:
        case DATETIME:
          return f1.getValueAsDatetime().compareTo(f2.getValueAsDatetime());
        default:
          throw new IllegalArgumentException("Unsupported Field Type for offset: " + offsetFieldType);
      }
    }
  }

  private Date getRandomDateTime(Field.Type type) {
    Calendar calendar = Calendar.getInstance();
    //1990-2020
    calendar.set(Calendar.YEAR, RANDOM.nextInt(30) + 1990);
    calendar.set(Calendar.MONTH, RANDOM.nextInt(11) + 1);
    calendar.set(Calendar.DAY_OF_MONTH, RANDOM.nextInt(25));
    calendar.set(Calendar.HOUR_OF_DAY, RANDOM.nextInt(24));
    calendar.set(Calendar.MINUTE, RANDOM.nextInt(60));
    calendar.set(Calendar.SECOND, RANDOM.nextInt(60));
    calendar.set(Calendar.MILLISECOND, RANDOM.nextInt(1000));
    if (type == Field.Type.DATE) {
      //zero out time part
      calendar.set(Calendar.HOUR_OF_DAY, 0);
      calendar.set(Calendar.MINUTE, 0);
      calendar.set(Calendar.SECOND, 0);
      calendar.set(Calendar.MILLISECOND, 0);
    } else if (type == Field.Type.TIME) {
      //unset
      calendar.set(Calendar.YEAR, 1970);
      calendar.set(Calendar.MONTH, 0);
      calendar.set(Calendar.DAY_OF_MONTH, 1);
    }
    return calendar.getTime();
  }

  private Object generateRandomData() {
    switch(offsetFieldType) {
      case DATE:
      case DATETIME:
      case TIME:
        return getRandomDateTime(offsetFieldType);
      case DOUBLE:
        return RANDOM.nextDouble();
      case FLOAT:
        return RANDOM.nextFloat();
      case SHORT:
        return (short) RANDOM.nextInt(Short.MAX_VALUE + 1);
      case INTEGER:
        return RANDOM.nextInt();
      case LONG:
        return RANDOM.nextLong();
      case CHAR:
        return UUID.randomUUID().toString().charAt(0);
      case STRING:
        return UUID.randomUUID().toString();
      case DECIMAL:
        return new BigDecimal(BigInteger.valueOf(RANDOM.nextLong() % (long)Math.pow(10, 20)), 10);
    }
    return null;
  }

  @Before
  public void setupTable() throws Exception {
    offsetFieldName = String.format(FIELD_NAME_TEMPLATE, offsetFieldType.name().toLowerCase());
    try (Statement st = connection.createStatement()) {
      st.execute(
          getCreateStatement(
              database,
              TABLE_NAME,
              Collections.singletonMap(offsetFieldName, FIELD_TYPE_TO_SQL_TYPE_AND_STRING.get(offsetFieldType)),
              Collections.singletonMap(EXTRA_STRING_COLUMN, FIELD_TYPE_TO_SQL_TYPE_AND_STRING.get(Field.Type.STRING)
              )
          )
      );
    }

    Set<Field> alreadySeenOffsetFieldValues = new TreeSet<>(new OffsetFieldComparator());
    for (int i = 0; i < NUMBER_OF_RECORDS; i++) {
      Field offsetField;
      //Make offset unique
      do {
        offsetField = Field.create(offsetFieldType, generateRandomData());
      } while (!alreadySeenOffsetFieldValues.add(offsetField));

      String extraColumnValue = UUID.randomUUID().toString();
      Record record = RecordCreator.create();
      LinkedHashMap<String, Field> rootField = new LinkedHashMap<>();
      rootField.put(offsetFieldName, offsetField);
      rootField.put(EXTRA_STRING_COLUMN, Field.create(Field.Type.STRING, extraColumnValue));
      record.set(Field.createListMap(rootField));
      expectedRecords.add(record);
    }

    //Sort the records based on the offset field value.
    Collections.sort(expectedRecords, new Comparator<Record>() {
      @Override
      public int compare(Record r1, Record r2) {
        Field f1 = r1.get("/" + offsetFieldName);
        Field f2 = r2.get("/" + offsetFieldName);
        return new OffsetFieldComparator().compare(f1, f2);
      }
    });

    String insertStatement = String.format(INSERT_PREPARED_STATEMENT, database, TABLE_NAME);
    try (PreparedStatement ps = connection.prepareStatement(insertStatement)) {
      for (Record record : expectedRecords) {
        setParamsToPreparedStatement(ps, 1, offsetSqlType, record.get("/"+ offsetFieldName).getValue());
        setParamsToPreparedStatement(ps, 2, Types.VARCHAR, record.get("/"+ EXTRA_STRING_COLUMN).getValueAsString());
        ps.execute();
      }
    }
  }

  @After
  public void deleteTable() throws Exception {
    try (Statement st = connection.createStatement()) {
      st.execute(String.format(DROP_STATEMENT_TEMPLATE, database, TABLE_NAME));
    }
  }

  private Pair<String, Integer> runSourceAndReturnOffsetAndRecordsRead(
      String lastOffset,
      int totalNoOfRecordsRead
  ) throws Exception {
    int batchSize = (RANDOM.nextInt(5) + 1) * 10;
    TableConfigBean tableConfigBean = new TableJdbcSourceTestBuilder.TableConfigBeanTestBuilder()
        .tablePattern(TABLE_NAME)
        .schema(database)
        .build();
    TableJdbcSource tableJdbcSource = new TableJdbcSourceTestBuilder(JDBC_URL, true, USER_NAME, PASSWORD)
        .tableConfigBeans(ImmutableList.of(tableConfigBean))
        .maxBatchSize(batchSize)
        .build();
    SourceRunner runner = new SourceRunner.Builder(TableJdbcDSource.class, tableJdbcSource)
        .addOutputLane("a").build();
    runner.runInit();
    try {
      StageRunner.Output op = runner.runProduce(lastOffset, batchSize);
      List<Record> actualRecords = op.getRecords().get("a");
      LOGGER.info("Read {} records", actualRecords.size());
      if (totalNoOfRecordsRead >= expectedRecords.size()) {
        Assert.assertEquals(0, actualRecords.size());
      } else {
        checkRecords(
            expectedRecords.subList(totalNoOfRecordsRead, totalNoOfRecordsRead + actualRecords.size()),
            actualRecords
        );
        totalNoOfRecordsRead += actualRecords.size();
      }
      lastOffset = op.getNewOffset();
    } finally {
      runner.runDestroy();
    }
    return Pair.of(lastOffset, totalNoOfRecordsRead);
  }

  @Test
  public void testCorrectOffsetRead() throws Exception {
    String lastOffset = "";
    int totalNoOfRecordsRead = 0;
    while (totalNoOfRecordsRead < NUMBER_OF_RECORDS) {
      Pair<String, Integer> offsetAndRecordsRead =
          runSourceAndReturnOffsetAndRecordsRead(lastOffset, totalNoOfRecordsRead);
      lastOffset = offsetAndRecordsRead.getLeft();
      totalNoOfRecordsRead = offsetAndRecordsRead.getRight();
    }
    Assert.assertEquals(totalNoOfRecordsRead, expectedRecords.size());
  }

  @Test
  public void testInitialOffset() throws Exception {
    int batchSize = NUMBER_OF_RECORDS/2;
    String initialOffset;
    if (JdbcUtil.isSqlTypeOneOf(offsetSqlType, Types.DATE, Types.TIME, Types.TIMESTAMP)) {
      initialOffset = String.valueOf(expectedRecords.get(batchSize-1).get("/" + offsetFieldName).getValueAsLong());
    } else {
      initialOffset = expectedRecords.get(batchSize-1).get("/" + offsetFieldName).getValueAsString();
    }
    TableConfigBean tableConfigBean = new TableJdbcSourceTestBuilder.TableConfigBeanTestBuilder()
        .tablePattern(TABLE_NAME)
        .offsetColumnToInitialOffsetValue(ImmutableMap.of(offsetFieldName.toUpperCase(), initialOffset))
        .schema(database)
        .build();
    TableJdbcSource tableJdbcSource = new TableJdbcSourceTestBuilder(JDBC_URL, true, USER_NAME, PASSWORD)
        .tableConfigBeans(ImmutableList.of(tableConfigBean))
        .maxBatchSize(batchSize)
        .build();
    SourceRunner runner = new SourceRunner.Builder(TableJdbcDSource.class, tableJdbcSource)
        .addOutputLane("a").build();
    runner.runInit();
    try {
      StageRunner.Output op = runner.runProduce("", batchSize);
      List<Record> actualRecords = op.getRecords().get("a");
      checkRecords(expectedRecords.subList(batchSize, expectedRecords.size()), actualRecords);
    } finally {
      runner.runDestroy();
    }
  }
}