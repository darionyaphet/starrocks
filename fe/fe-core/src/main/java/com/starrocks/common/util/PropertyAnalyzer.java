// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/common/util/PropertyAnalyzer.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.common.util;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Sets;
import com.starrocks.analysis.DateLiteral;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.DataProperty;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.thrift.TStorageFormat;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TStorageType;
import com.starrocks.thrift.TTabletType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class PropertyAnalyzer {
    private static final Logger LOG = LogManager.getLogger(PropertyAnalyzer.class);
    private static final String COMMA_SEPARATOR = ",";

    public static final String PROPERTIES_SHORT_KEY = "short_key";
    public static final String PROPERTIES_REPLICATION_NUM = "replication_num";
    public static final String PROPERTIES_STORAGE_TYPE = "storage_type";
    public static final String PROPERTIES_STORAGE_MEDIUM = "storage_medium";
    public static final String PROPERTIES_STORAGE_COLDOWN_TIME = "storage_cooldown_time";
    // for 1.x -> 2.x migration
    public static final String PROPERTIES_VERSION_INFO = "version_info";
    // for restore
    public static final String PROPERTIES_SCHEMA_VERSION = "schema_version";

    public static final String PROPERTIES_BF_COLUMNS = "bloom_filter_columns";
    public static final String PROPERTIES_BF_FPP = "bloom_filter_fpp";
    private static final double MAX_FPP = 0.05;
    private static final double MIN_FPP = 0.0001;

    public static final String PROPERTIES_COLUMN_SEPARATOR = "column_separator";
    public static final String PROPERTIES_LINE_DELIMITER = "line_delimiter";

    public static final String PROPERTIES_COLOCATE_WITH = "colocate_with";

    public static final String PROPERTIES_TIMEOUT = "timeout";

    public static final String PROPERTIES_DISTRIBUTION_TYPE = "distribution_type";
    public static final String PROPERTIES_SEND_CLEAR_ALTER_TASK = "send_clear_alter_tasks";
    /*
     * for upgrade alpha rowset to beta rowset, valid value: v1, v2
     * v1: alpha rowset
     * v2: beta rowset
     */
    public static final String PROPERTIES_STORAGE_FORMAT = "storage_format";

    public static final String PROPERTIES_INMEMORY = "in_memory";

    public static final String PROPERTIES_TABLET_TYPE = "tablet_type";

    public static final String PROPERTIES_STRICT_RANGE = "strict_range";
    public static final String PROPERTIES_USE_TEMP_PARTITION_NAME = "use_temp_partition_name";

    public static final String PROPERTIES_TYPE = "type";

    public static final String ENABLE_LOW_CARD_DICT_TYPE = "enable_low_card_dict";
    public static final String ABLE_LOW_CARD_DICT = "1";
    public static final String DISABLE_LOW_CARD_DICT = "0";

    public static DataProperty analyzeDataProperty(Map<String, String> properties, DataProperty oldDataProperty)
            throws AnalysisException {
        if (properties == null) {
            return oldDataProperty;
        }

        TStorageMedium storageMedium = null;
        long coolDownTimeStamp = DataProperty.MAX_COOLDOWN_TIME_MS;

        boolean hasMedium = false;
        boolean hasCooldown = false;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!hasMedium && key.equalsIgnoreCase(PROPERTIES_STORAGE_MEDIUM)) {
                hasMedium = true;
                if (value.equalsIgnoreCase(TStorageMedium.SSD.name())) {
                    storageMedium = TStorageMedium.SSD;
                } else if (value.equalsIgnoreCase(TStorageMedium.HDD.name())) {
                    storageMedium = TStorageMedium.HDD;
                } else if (value.equalsIgnoreCase(TStorageMedium.S3.name()) && Config.use_staros) {
                    storageMedium = TStorageMedium.S3;
                } else {
                    throw new AnalysisException("Invalid storage medium: " + value);
                }
            } else if (!hasCooldown && key.equalsIgnoreCase(PROPERTIES_STORAGE_COLDOWN_TIME)) {
                hasCooldown = true;
                DateLiteral dateLiteral = new DateLiteral(value, Type.DATETIME);
                coolDownTimeStamp = dateLiteral.unixTimestamp(TimeUtils.getTimeZone());
            }
        } // end for properties

        if (!hasCooldown && !hasMedium) {
            return oldDataProperty;
        }

        properties.remove(PROPERTIES_STORAGE_MEDIUM);
        properties.remove(PROPERTIES_STORAGE_COLDOWN_TIME);

        if (hasCooldown && !hasMedium) {
            throw new AnalysisException("Invalid data property. storage medium property is not found");
        }

        if (storageMedium == TStorageMedium.HDD && hasCooldown) {
            throw new AnalysisException("Can not assign cooldown timestamp to HDD storage medium");
        }

        long currentTimeMs = System.currentTimeMillis();
        if (storageMedium == TStorageMedium.SSD && hasCooldown) {
            if (coolDownTimeStamp <= currentTimeMs) {
                throw new AnalysisException("Cooldown time should later than now");
            }
        }

        if (storageMedium == TStorageMedium.SSD && !hasCooldown) {
            // set default cooldown time
            coolDownTimeStamp = currentTimeMs + Config.storage_cooldown_second * 1000L;
        }

        Preconditions.checkNotNull(storageMedium);
        return new DataProperty(storageMedium, coolDownTimeStamp);
    }

    public static short analyzeShortKeyColumnCount(Map<String, String> properties) throws AnalysisException {
        short shortKeyColumnCount = (short) -1;
        if (properties != null && properties.containsKey(PROPERTIES_SHORT_KEY)) {
            // check and use specified short key
            try {
                shortKeyColumnCount = Short.parseShort(properties.get(PROPERTIES_SHORT_KEY));
            } catch (NumberFormatException e) {
                throw new AnalysisException("Short key: " + e.getMessage());
            }

            if (shortKeyColumnCount <= 0) {
                throw new AnalysisException("Short key column count should larger than 0.");
            }

            properties.remove(PROPERTIES_SHORT_KEY);
        }

        return shortKeyColumnCount;
    }

    public static Short analyzeReplicationNum(Map<String, String> properties, short oldReplicationNum)
            throws AnalysisException {
        short replicationNum = oldReplicationNum;
        if (properties != null && properties.containsKey(PROPERTIES_REPLICATION_NUM)) {
            try {
                replicationNum = Short.parseShort(properties.get(PROPERTIES_REPLICATION_NUM));
            } catch (Exception e) {
                throw new AnalysisException(e.getMessage());
            }

            if (replicationNum <= 0) {
                throw new AnalysisException("Replication num should larger than 0. (suggested 3)");
            }

            properties.remove(PROPERTIES_REPLICATION_NUM);
        }
        return replicationNum;
    }

    public static Short analyzeReplicationNum(Map<String, String> properties, boolean isDefault)
            throws AnalysisException {
        String key = "default.";
        if (isDefault) {
            key += PropertyAnalyzer.PROPERTIES_REPLICATION_NUM;
        } else {
            key = PropertyAnalyzer.PROPERTIES_REPLICATION_NUM;
        }
        short replicationNum = Short.parseShort(properties.get(key));
        if (replicationNum <= 0) {
            throw new AnalysisException("Replication num should larger than 0. (suggested 3)");
        }
        return replicationNum;
    }

    public static String analyzeColumnSeparator(Map<String, String> properties, String oldColumnSeparator) {
        String columnSeparator = oldColumnSeparator;
        if (properties != null && properties.containsKey(PROPERTIES_COLUMN_SEPARATOR)) {
            columnSeparator = properties.get(PROPERTIES_COLUMN_SEPARATOR);
            properties.remove(PROPERTIES_COLUMN_SEPARATOR);
        }
        return columnSeparator;
    }

    public static String analyzeRowDelimiter(Map<String, String> properties, String oldRowDelimiter) {
        String rowDelimiter = oldRowDelimiter;
        if (properties != null && properties.containsKey(PROPERTIES_LINE_DELIMITER)) {
            rowDelimiter = properties.get(PROPERTIES_LINE_DELIMITER);
            properties.remove(PROPERTIES_LINE_DELIMITER);
        }
        return rowDelimiter;
    }

    public static TStorageType analyzeStorageType(Map<String, String> properties) throws AnalysisException {
        // default is COLUMN
        TStorageType tStorageType = TStorageType.COLUMN;
        if (properties != null && properties.containsKey(PROPERTIES_STORAGE_TYPE)) {
            String storageType = properties.get(PROPERTIES_STORAGE_TYPE);
            if (storageType.equalsIgnoreCase(TStorageType.COLUMN.name())) {
                tStorageType = TStorageType.COLUMN;
            } else {
                throw new AnalysisException("Invalid storage type: " + storageType);
            }

            properties.remove(PROPERTIES_STORAGE_TYPE);
        }

        return tStorageType;
    }

    public static TTabletType analyzeTabletType(Map<String, String> properties) throws AnalysisException {
        // default is TABLET_TYPE_DISK
        TTabletType tTabletType = TTabletType.TABLET_TYPE_DISK;
        if (properties != null && properties.containsKey(PROPERTIES_TABLET_TYPE)) {
            String tabletType = properties.get(PROPERTIES_TABLET_TYPE);
            if (tabletType.equalsIgnoreCase("memory")) {
                tTabletType = TTabletType.TABLET_TYPE_MEMORY;
            } else if (tabletType.equalsIgnoreCase("disk")) {
                tTabletType = TTabletType.TABLET_TYPE_DISK;
            } else {
                throw new AnalysisException(("Invalid tablet type"));
            }
            properties.remove(PROPERTIES_TABLET_TYPE);
        }
        return tTabletType;
    }

    public static Long analyzeVersionInfo(Map<String, String> properties) throws AnalysisException {
        Long versionInfo = Partition.PARTITION_INIT_VERSION;
        if (properties != null && properties.containsKey(PROPERTIES_VERSION_INFO)) {
            String versionInfoStr = properties.get(PROPERTIES_VERSION_INFO);
            try {
                versionInfo = Long.parseLong(versionInfoStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("version info format error.");
            }

            properties.remove(PROPERTIES_VERSION_INFO);
        }

        return versionInfo;
    }

    public static int analyzeSchemaVersion(Map<String, String> properties) throws AnalysisException {
        int schemaVersion = 0;
        if (properties != null && properties.containsKey(PROPERTIES_SCHEMA_VERSION)) {
            String schemaVersionStr = properties.get(PROPERTIES_SCHEMA_VERSION);
            try {
                schemaVersion = Integer.parseInt(schemaVersionStr);
            } catch (Exception e) {
                throw new AnalysisException("schema version format error");
            }

            properties.remove(PROPERTIES_SCHEMA_VERSION);
        }

        return schemaVersion;
    }

    public static Set<String> analyzeBloomFilterColumns(Map<String, String> properties, List<Column> columns,
            boolean isPrimaryKey) throws AnalysisException {
        Set<String> bfColumns = null;
        if (properties != null && properties.containsKey(PROPERTIES_BF_COLUMNS)) {
            bfColumns = Sets.newHashSet();
            String bfColumnsStr = properties.get(PROPERTIES_BF_COLUMNS);
            if (Strings.isNullOrEmpty(bfColumnsStr)) {
                return bfColumns;
            }

            String[] bfColumnArr = bfColumnsStr.split(COMMA_SEPARATOR);
            Set<String> bfColumnSet = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
            for (String bfColumn : bfColumnArr) {
                bfColumn = bfColumn.trim();
                String finalBfColumn = bfColumn;
                Column column = columns.stream().filter(col -> col.getName().equalsIgnoreCase(finalBfColumn))
                        .findFirst()
                        .orElse(null);
                if (column == null) {
                    throw new AnalysisException(
                            String.format("Invalid bloom filter column '%s': not exists", bfColumn));
                }

                Type type = column.getType();

                // tinyint/float/double columns don't support
                if (!type.supportBloomFilter()) {
                    throw new AnalysisException(String.format("Invalid bloom filter column '%s': unsupported type %s",
                            bfColumn, type));
                }

                // Only support create bloom filter on DUPLICATE/PRIMARY table or key columns of UNIQUE/AGGREGATE table.
                if (!(column.isKey() || isPrimaryKey || column.getAggregationType() == AggregateType.NONE)) {
                    // Although the implementation supports bloom filter for replace non-key column,
                    // for simplicity and unity, we don't expose that to user.
                    throw new AnalysisException("Bloom filter index only used in columns of DUP_KEYS/PRIMARY table or "
                            + "key columns of UNIQUE_KEYS/AGG_KEYS table. invalid column: " + bfColumn);
                }

                if (bfColumnSet.contains(bfColumn)) {
                    throw new AnalysisException(String.format("Duplicate bloom filter column '%s'", bfColumn));
                }

                bfColumnSet.add(bfColumn);
                bfColumns.add(column.getName());
            }

            properties.remove(PROPERTIES_BF_COLUMNS);
        }

        return bfColumns;
    }

    public static double analyzeBloomFilterFpp(Map<String, String> properties) throws AnalysisException {
        double bfFpp = 0;
        if (properties != null && properties.containsKey(PROPERTIES_BF_FPP)) {
            String bfFppStr = properties.get(PROPERTIES_BF_FPP);
            try {
                bfFpp = Double.parseDouble(bfFppStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("Bloom filter fpp is not Double");
            }

            // check range
            if (bfFpp < MIN_FPP || bfFpp > MAX_FPP) {
                throw new AnalysisException("Bloom filter fpp should in [" + MIN_FPP + ", " + MAX_FPP + "]");
            }

            properties.remove(PROPERTIES_BF_FPP);
        }

        return bfFpp;
    }

    public static String analyzeColocate(Map<String, String> properties) throws AnalysisException {
        String colocateGroup = null;
        if (properties != null && properties.containsKey(PROPERTIES_COLOCATE_WITH)) {
            colocateGroup = properties.get(PROPERTIES_COLOCATE_WITH);
            properties.remove(PROPERTIES_COLOCATE_WITH);
        }
        return colocateGroup;
    }

    public static long analyzeTimeout(Map<String, String> properties, long defaultTimeout) throws AnalysisException {
        long timeout = defaultTimeout;
        if (properties != null && properties.containsKey(PROPERTIES_TIMEOUT)) {
            String timeoutStr = properties.get(PROPERTIES_TIMEOUT);
            try {
                timeout = Long.parseLong(timeoutStr);
            } catch (NumberFormatException e) {
                throw new AnalysisException("Invalid timeout format: " + timeoutStr);
            }
            properties.remove(PROPERTIES_TIMEOUT);
        }
        return timeout;
    }

    // analyzeStorageFormat will parse the storage format from properties
    // sql: alter table tablet_name set ("storage_format" = "v2")
    // Use this sql to convert all tablets(base and rollup index) to a new format segment
    public static TStorageFormat analyzeStorageFormat(Map<String, String> properties) throws AnalysisException {
        String storageFormat;
        if (properties != null && properties.containsKey(PROPERTIES_STORAGE_FORMAT)) {
            storageFormat = properties.get(PROPERTIES_STORAGE_FORMAT);
            properties.remove(PROPERTIES_STORAGE_FORMAT);
        } else {
            return TStorageFormat.DEFAULT;
        }

        if (storageFormat.equalsIgnoreCase("v1")) {
            return TStorageFormat.V1;
        } else if (storageFormat.equalsIgnoreCase("v2")) {
            return TStorageFormat.V2;
        } else if (storageFormat.equalsIgnoreCase("default")) {
            return TStorageFormat.DEFAULT;
        } else {
            throw new AnalysisException("unknown storage format: " + storageFormat);
        }
    }

    // analyze common boolean properties, such as "in_memory" = "false"
    public static boolean analyzeBooleanProp(Map<String, String> properties, String propKey, boolean defaultVal) {
        if (properties != null && properties.containsKey(propKey)) {
            String val = properties.get(propKey);
            properties.remove(propKey);
            return Boolean.parseBoolean(val);
        }
        return defaultVal;
    }

    // analyze property like : "type" = "xxx";
    public static String analyzeType(Map<String, String> properties) {
        String type = null;
        if (properties != null && properties.containsKey(PROPERTIES_TYPE)) {
            type = properties.get(PROPERTIES_TYPE);
            properties.remove(PROPERTIES_TYPE);
        }
        return type;
    }
}
