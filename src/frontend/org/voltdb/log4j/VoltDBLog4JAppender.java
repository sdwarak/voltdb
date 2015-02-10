/* This file is part of VoltDB.
 * Copyright (C) 2008-2015 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.log4j;

import org.apache.log4j.Appender;
import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;
import org.voltdb.VoltTable;
import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.NullCallback;
import org.voltdb.client.VoltBulkLoader.BulkLoaderFailureCallBack;
import org.voltdb.client.VoltBulkLoader.VoltBulkLoader;

/**
 * A Log4j appender that writes to a VoltDB instance.
 * The appender works automatically with minimal setup; a blank, running VoltDB instance
 * is all that is required.
 */
public class VoltDBLog4JAppender extends AppenderSkeleton implements Appender {
    String server = "localhost";
    int port = 21212;
    String user = null;
    String password = null;
    String table = "log4j";
    String insertMethod = "bulkloader";
    long current_index = 0;

    ClientConfig config = null;
    Client client = null;
    AppenderInsert insertDevice = null;

    /**
     * Failure callback for insertions to VoltDB
     */
    static class VoltDBLog4JAppenderCallback implements BulkLoaderFailureCallBack {

        @Override
        public void failureCallback(Object rowHandle, Object[] fieldList,
                ClientResponse response) {
            System.err.println("Log insertion into VoltDB failed:");
            System.err.println(response.getStatusString());
        }

    }

    /**
     * Interface that defines one method: insert log information into VoltDB
     */
    interface AppenderInsert {
        public void insert(long id, long timestamp, String level, String message) throws Exception;
        public void close();
    }

    /**
     * Insert class that uses a bulkloader
     */
    class BulkLoaderAppenderInsert implements AppenderInsert {
        VoltBulkLoader bulkLoader = null;
        public BulkLoaderAppenderInsert(Client client) {
            try{
                bulkLoader = client.getNewBulkLoader(table, 1, new VoltDBLog4JAppenderCallback());
            } catch (Exception e) {
                System.err.println("Coundn't get bulkloader for client");
                e.printStackTrace();
            }
        }
        @Override
        public void insert(long id, long timestamp, String level, String message) throws Exception{
            Object rowHandle = null;
            bulkLoader.insertRow(rowHandle, id, timestamp, level, message);
        }
        @Override
        public void close() {
            try {
                bulkLoader.drain();
                bulkLoader.close();
            } catch (Exception e) {
                System.err.println("Couldn't close bulkloader");
                e.printStackTrace();
            }
        }
    }

    /**
     * Insert class that uses a stored procedure
     */
    class ProcedureAppenderInsert implements AppenderInsert {
        Client client;
        public ProcedureAppenderInsert(Client client) { this.client = client; }
        @Override
        public void insert(long id, long timestamp, String level, String message) throws Exception {
            client.callProcedure(new NullCallback(), "LogInsert", id, timestamp, level, message);
        }
        @Override
        public void close() {
            // Nothing to close
            return;
        }
    }

    // Log4j configuration loaders
    public void setCluster(String cluster) { this.server = cluster; }
    public String getCluster() { return this.server; }

    public void setPort(int port) { this.port = port; }
    public int getPort() { return this.port; }

    public void setUser(String user) { this.user = user; }
    public String getUser() { return this.user; }

    public void setPassword(String password) { this.password = password; }
    public String getPassword () { return this.password; }

    public void setTable(String table) { this.table = table; }
    public String getTable() { return this.table; }

    public void setInsert(String insertMethod) { this.insertMethod = insertMethod.toLowerCase(); }
    public String getInsert() { return this.table; }

    /**
     * Initializes a new Log4j appender.
     * Connects to VoltDB and verifies a table is ready for insertion.
     */
    public VoltDBLog4JAppender() {
        try {
            // Create a connection to VoltDB
            if ((user != null && !user.trim().isEmpty()) && (password != null && !password.trim().isEmpty())) {
                config = new ClientConfig(user, password);
            } else {
                config = new ClientConfig();
            }
            config.setReconnectOnConnectionLoss(true);
            client = ClientFactory.createClient(config);
            client.createConnection(server, port);

            // Make sure we have a table set up.
            setupTable(client);

            // Create the insert device
            if (insertMethod.equals("bulkloader"))
                insertDevice = new BulkLoaderAppenderInsert(client);
            else if (insertMethod.equals("procedure"))
                insertDevice = new ProcedureAppenderInsert(client);
            else
                System.err.println("Unrecognized insert method: '" + insertMethod + "'");
        } catch (Exception e) {
            System.err.println("Unable to create VoltDB client");
            e.printStackTrace();
        }
    }

    /**
     * Checks the running VoltDB instance for a table.
     * If no table exists, create & partition one.
     * @param client       The VoltDB client
     * @throws Exception
     */
    private void setupTable(Client client) throws Exception {
        // See if we have a table
        VoltTable allTables = client.callProcedure("@SystemCatalog", "TABLES").getResults()[0];
        while (allTables.advanceRow()) {
            String name = allTables.getString("TABLE_NAME");
            if (name.toLowerCase().equals(table)){
                // We have the table, don't need to add it
                System.out.println("Using existing table '" + table + "' in VoltDB");
                current_index = findCurrentLogIndex();
                return;
            }
        }
       // No table, so we need to add one
       String sqlStmt = "CREATE TABLE " + table + " ( id INT UNIQUE NOT NULL, timestamp BIGINT, level VARCHAR(10), message VARCHAR(255))";
       String sqlStmt2 = "PARTITION TABLE " + table + " ON COLUMN id;";
       String sqlStmt3 = "CREATE PROCEDURE LogInsert AS "
                         + "INSERT INTO " + table + " (id, timestamp, level, message) "
                         + "VALUES (?,?,?,?);";
       client.callProcedure("@AdHoc", sqlStmt);
       client.callProcedure("@AdHoc", sqlStmt2);
       client.callProcedure("@AdHoc", sqlStmt3);
    }

    /**
     * When starting the appender on an existing VoltDB table, find the current index
     * @return The current index
     * @throws Exception
     */
    private long findCurrentLogIndex() throws Exception {
        String sqlStmt = "SELECT MAX(id) from " + table + ";";
        VoltTable result = client.callProcedure("@AdHoc", sqlStmt).getResults()[0];
        result.advanceRow();
        return result.getLong(0);
    }

    @Override
    public void close() {
        // Close the VoltDB connection
        try {
            insertDevice.close();
            client.drain();
            client.close();
        } catch (Exception e) {
            System.err.println("Unable to close connection to VoltDB");
            e.printStackTrace();
        }
    }

    @Override
    public boolean requiresLayout() {
        return true;
    }

    @Override
    protected void append(LoggingEvent arg0) {
        // Extract the message information we need
        long timestamp = arg0.getTimeStamp();
        String level = arg0.getLevel().toString();
        String message = arg0.getMessage().toString();

        // Insert the log message into VoltDB
        try{
            insertDevice.insert(current_index, timestamp, level, message);
            current_index++;
        } catch (Exception e) {
            System.err.println("Failed to insert into VoltDB");
            e.printStackTrace();
        }
    }


}