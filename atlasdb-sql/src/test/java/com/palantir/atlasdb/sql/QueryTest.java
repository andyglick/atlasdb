package com.palantir.atlasdb.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.Namespace;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.protos.generated.TableMetadataPersistence;
import com.palantir.atlasdb.table.description.TableDefinition;
import com.palantir.atlasdb.table.description.ValueType;
import com.palantir.atlasdb.transaction.api.ConflictHandler;
import com.palantir.atlasdb.transaction.api.TransactionManager;
import com.palantir.atlasdb.transaction.api.TransactionTask;

public class QueryTest {

    private static final String ROW_COMP = "row";
    private static final String COL_NAME = "col";
    private static final byte[] COLUMN_NAME_IN_BYTES = COL_NAME.getBytes();
    private static final long DUMMY_TIMESTAMP = 1L;
    private static final TableReference tableRef = TableReference.create(Namespace.DEFAULT_NAMESPACE, "test_table");

    @Before
    public void setup() throws SQLException, ClassNotFoundException {
        try (Connection c = getConnection()) {
            TransactionManager txm = ((AtlasJdbcConnection) c).getTxManager();
            KeyValueService kvs = ((AtlasJdbcConnection) c).getKvs();
            TableDefinition tableDef = new TableDefinition() {{
                rowName();
                rowComponent(ROW_COMP, ValueType.STRING);
                columns();
                column(COL_NAME, COL_NAME, ValueType.BLOB);
                conflictHandler(ConflictHandler.IGNORE_ALL);
                sweepStrategy(TableMetadataPersistence.SweepStrategy.NOTHING);
            }};
            kvs.createTable(tableRef, tableDef.toTableMetadata().persistToBytes());
            kvs.putMetadataForTable(tableRef, tableDef.toTableMetadata().persistToBytes());
            txm.runTaskThrowOnConflict((TransactionTask<Void, RuntimeException>) t -> {
                t.put(tableRef, ImmutableMap.of(Cell.create("key1".getBytes(), COLUMN_NAME_IN_BYTES), "value1".getBytes()));
                t.put(tableRef, ImmutableMap.of(Cell.create("key2".getBytes(), COLUMN_NAME_IN_BYTES), "value2".getBytes()));
                return null;
            });
        }
    }

    @After
    public void teardown() throws SQLException, ClassNotFoundException {
        try (Connection c = getConnection()) {
            KeyValueService kvs = ((AtlasJdbcConnection) c).getKvs();
            kvs.truncateTable(tableRef);
        }
    }

    @Test
    public void testSelect() {
        try (Connection c = getConnection()) {
            Statement stmt = c.createStatement();
            ResultSet results = stmt.executeQuery(String.format("select col from %s", tableRef.getQualifiedName()));
            results.next(); Preconditions.checkArgument(Arrays.equals(results.getBytes(""), "key1".getBytes()));
            results.next(); Preconditions.checkArgument(Arrays.equals(results.getBytes(""), "key2".getBytes()));
            Preconditions.checkArgument(!results.next());
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException("Failure running select.", e);
        };
    }

    private Connection getConnection() throws ClassNotFoundException, SQLException {
        Class.forName(AtlasJdbcDriver.class.getName());
        final java.net.URL resource = ConnectionTest.class.getClassLoader().getResource("memoryTestConfig.yml");
        final String URL = "jdbc:atlas?configFile=" + resource.getFile();
        return DriverManager.getConnection(URL);
    }

}
