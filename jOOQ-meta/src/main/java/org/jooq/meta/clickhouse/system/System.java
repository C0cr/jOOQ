/*
 * This file is generated by jOOQ.
 */
package org.jooq.meta.clickhouse.system;


import java.util.Arrays;
import java.util.List;

import org.jooq.Table;
import org.jooq.impl.SchemaImpl;
import org.jooq.meta.clickhouse.system.tables.Columns;
import org.jooq.meta.clickhouse.system.tables.DataSkippingIndices;
import org.jooq.meta.clickhouse.system.tables.Tables;


/**
 * This class is generated by jOOQ.
 */
@SuppressWarnings({ "all", "unchecked", "rawtypes", "this-escape" })
public class System extends SchemaImpl {

    private static final long serialVersionUID = 1L;

    /**
     * The reference instance of <code>system</code>
     */
    public static final System SYSTEM = new System();

    /**
     * Lists all columns from all tables of the current server.
     */
    public final Columns COLUMNS = Columns.COLUMNS;

    /**
     * Contains all the information about all the data skipping indices in
     * tables, similar to system.columns.
     */
    public final DataSkippingIndices DATA_SKIPPING_INDICES = DataSkippingIndices.DATA_SKIPPING_INDICES;

    /**
     * Lists all tables of the current server.
     */
    public final Tables TABLES = Tables.TABLES;

    /**
     * No further instances allowed
     */
    private System() {
        super("system", null);
    }

    @Override
    public final List<Table<?>> getTables() {
        return Arrays.asList(
            Columns.COLUMNS,
            DataSkippingIndices.DATA_SKIPPING_INDICES,
            Tables.TABLES
        );
    }
}