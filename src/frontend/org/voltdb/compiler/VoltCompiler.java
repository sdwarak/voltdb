/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB Inc.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.compiler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.hsqldb_voltpatches.HSQLInterface;
import org.voltdb.ProcInfo;
import org.voltdb.ProcInfoData;
import org.voltdb.RealVoltDB;
import org.voltdb.TransactionIdManager;
import org.voltdb.catalog.Catalog;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Column;
import org.voltdb.catalog.Database;
import org.voltdb.catalog.Group;
import org.voltdb.catalog.GroupRef;
import org.voltdb.catalog.MaterializedViewInfo;
import org.voltdb.catalog.Procedure;
import org.voltdb.catalog.SnapshotSchedule;
import org.voltdb.catalog.Statement;
import org.voltdb.catalog.Table;
import org.voltdb.compiler.projectfile.ClassdependenciesType.Classdependency;
import org.voltdb.compiler.projectfile.DatabaseType;
import org.voltdb.compiler.projectfile.ExportsType.Connector;
import org.voltdb.compiler.projectfile.ExportsType.Connector.Tables;
import org.voltdb.compiler.projectfile.GroupsType;
import org.voltdb.compiler.projectfile.ProceduresType;
import org.voltdb.compiler.projectfile.ProjectType;
import org.voltdb.compiler.projectfile.SchemasType;
import org.voltdb.compiler.projectfile.SecurityType;
import org.voltdb.compiler.projectfile.SnapshotType;
import org.voltdb.logging.Level;
import org.voltdb.logging.VoltLogger;
import org.voltdb.utils.CatalogUtil;
import org.voltdb.utils.Encoder;
import org.voltdb.utils.InMemoryJarfile;
import org.voltdb.utils.LogKeys;
import org.voltdb.utils.StringInputStream;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Compiles a project XML file and some metadata into a Jarfile
 * containing stored procedure code and a serialzied catalog.
 *
 */
public class VoltCompiler {
    /** Represents the level of severity for a Feedback message generated during compiling. */
    public static enum Severity { INFORMATIONAL, WARNING, ERROR, UNEXPECTED };
    public static final int NO_LINE_NUMBER = -1;

    // feedback by filename
    ArrayList<Feedback> m_infos = new ArrayList<Feedback>();
    ArrayList<Feedback> m_warnings = new ArrayList<Feedback>();
    ArrayList<Feedback> m_errors = new ArrayList<Feedback>();

    // set of annotations by procedure name
    Map<String, ProcInfoData> m_procInfoOverrides;

    String m_projectFileURL = null;
    String m_jarOutputPath = null;
    PrintStream m_outputStream = null;
    String m_currentFilename = null;
    Map<String, String> m_ddlFilePaths = new HashMap<String, String>();

    InMemoryJarfile m_jarOutput = null;
    Catalog m_catalog = null;
    //Cluster m_cluster = null;
    HSQLInterface m_hsql = null;

    DatabaseEstimates m_estimates = new DatabaseEstimates();

    private static final VoltLogger compilerLog = new VoltLogger("COMPILER");
    @SuppressWarnings("unused")
    private static final VoltLogger Log = new VoltLogger("org.voltdb.compiler.VoltCompiler");

    /**
     * Represents output from a compile. This works similarly to Log4j; there
     * are different levels of feedback including info, warning, error, and
     * unexpected error. Feedback can be output to a printstream (like stdout)
     * or can be examined programatically.
     *
     */
    public static class Feedback {
        Severity severityLevel;
        String fileName;
        int lineNo;
        String message;

        Feedback(final Severity severityLevel, final String message, final String fileName, final int lineNo) {
            this.severityLevel = severityLevel;
            this.message = message;
            this.fileName = fileName;
            this.lineNo = lineNo;
        }

        public String getStandardFeedbackLine() {
            String retval = "";
            if (severityLevel == Severity.INFORMATIONAL)
                retval = "INFO";
            if (severityLevel == Severity.WARNING)
                retval = "WARNING";
            if (severityLevel == Severity.ERROR)
                retval = "ERROR";
            if (severityLevel == Severity.UNEXPECTED)
                retval = "UNEXPECTED ERROR";

            return retval + " " + getLogString();
        }

        public String getLogString() {
            String retval = new String();
            if (fileName != null) {
                retval += "[" + fileName;
                if (lineNo != NO_LINE_NUMBER)
                    retval += ":" + lineNo;
                retval += "]";
            }

            retval += ": " + message;
            return retval;
        }

        public Severity getSeverityLevel() {
            return severityLevel;
        }

        public String getFileName() {
            return fileName;
        }

        public int getLineNumber() {
            return lineNo;
        }

        public String getMessage() {
            return message;
        }
    }

    class VoltCompilerException extends Exception {
        private static final long serialVersionUID = -2267780579911448600L;
        private String message = null;

        VoltCompilerException(final Exception e) {
            super(e);
        }

        VoltCompilerException(final String message, final int lineNo) {
            addErr(message, lineNo);
            this.message = message;
        }

        VoltCompilerException(final String message) {
            addErr(message);
            this.message = message;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }

    class VoltXMLErrorHandler implements ErrorHandler {
        public void error(final SAXParseException exception) throws SAXException {
            addErr(exception.getMessage(), exception.getLineNumber());
        }

        public void fatalError(final SAXParseException exception) throws SAXException {
            //addErr(exception.getMessage(), exception.getLineNumber());
        }

        public void warning(final SAXParseException exception) throws SAXException {
            addWarn(exception.getMessage(), exception.getLineNumber());
        }
    }

    class ProcedureDescriptor {
        final ArrayList<String> m_authGroups;
        final String m_className;
        // for single-stmt procs
        final String m_singleStmt;
        final String m_joinOrder;
        final String m_partitionString;

        ProcedureDescriptor (final ArrayList<String> authGroups, final String className) {
            assert(className != null);

            m_authGroups = authGroups;
            m_className = className;
            m_singleStmt = null;
            m_joinOrder = null;
            m_partitionString = null;
        }

        ProcedureDescriptor (final ArrayList<String> authGroups, final String className, final String singleStmt, final String joinOrder, final String partitionString) {
            assert(className != null);
            assert(singleStmt != null);

            m_authGroups = authGroups;
            m_className = className;
            m_singleStmt = singleStmt;
            m_joinOrder = joinOrder;
            m_partitionString = partitionString;
        }
    }

    public boolean hasErrors() {
        return m_errors.size() > 0;
    }

    public boolean hasErrorsOrWarnings() {
        return (m_warnings.size() > 0) || hasErrors();
    }

    void addInfo(final String msg) {
        addInfo(msg, NO_LINE_NUMBER);
    }

    void addWarn(final String msg) {
        addWarn(msg, NO_LINE_NUMBER);
    }

    void addErr(final String msg) {
        addErr(msg, NO_LINE_NUMBER);
    }

    void addInfo(final String msg, final int lineNo) {
        final Feedback fb = new Feedback(Severity.INFORMATIONAL, msg, m_currentFilename, lineNo);
        m_infos.add(fb);
        compilerLog.info(fb.getLogString());
    }

    void addWarn(final String msg, final int lineNo) {
        final Feedback fb = new Feedback(Severity.WARNING, msg, m_currentFilename, lineNo);
        m_warnings.add(fb);
        compilerLog.warn(fb.getLogString());
    }

    void addErr(final String msg, final int lineNo) {
        final Feedback fb = new Feedback(Severity.ERROR, msg, m_currentFilename, lineNo);
        m_errors.add(fb);
        compilerLog.error(fb.getLogString());
    }

    /**
     * Compile with this method for general use.
     *
     * @param projectFileURL URL of the project file.
     * @param jarOutputPath The location to put the finished JAR to.
     * @param output Where to print status/errors to, usually stdout.
     * @param procInfoOverrides Optional overridden values for procedure annotations.
     */
    public boolean compile(final String projectFileURL, final String jarOutputPath, final PrintStream output,
                           final Map<String, ProcInfoData> procInfoOverrides) {
        m_hsql = null;
        m_projectFileURL = projectFileURL;
        m_jarOutputPath = jarOutputPath;
        m_outputStream = output;
        // use this map as default annotation values
        m_procInfoOverrides = procInfoOverrides;

        // do all the work to get the catalog
        final Catalog catalog = compileCatalog(projectFileURL);
        if (catalog == null) {
            compilerLog.error("Catalog compilation failed.");
            return false;
        }

        HashMap<String, byte[]> explainPlans = getExplainPlans(catalog);

        // WRITE CATALOG TO JAR HERE
        final String catalogCommands = catalog.serialize();

        byte[] catalogBytes = null;
        try {
            catalogBytes =  catalogCommands.getBytes("UTF-8");
        } catch (final UnsupportedEncodingException e1) {
            addErr("Can't encode the compiled catalog file correctly");
            return false;
        }

        StringBuffer buildinfo = new StringBuffer();
        String info[] = RealVoltDB.extractBuildInfo();
        buildinfo.append(info[0]).append('\n');
        buildinfo.append(info[1]).append('\n');
        buildinfo.append(System.getProperty("user.name")).append('\n');
        buildinfo.append(System.getProperty("user.dir")).append('\n');
        buildinfo.append(Long.toString(System.currentTimeMillis())).append('\n');

        try {
            byte buildinfoBytes[] = buildinfo.toString().getBytes("UTF-8");
            m_jarOutput.put("buildinfo.txt", buildinfoBytes);
            m_jarOutput.put("catalog.txt", catalogBytes);
            m_jarOutput.put("project.xml", new File(projectFileURL));
            for (final Entry<String, String> e : m_ddlFilePaths.entrySet())
                m_jarOutput.put(e.getKey(), new File(e.getValue()));
            // write all the plans to a folder in the jarfile
            for (final Entry<String, byte[]> e : explainPlans.entrySet())
                m_jarOutput.put("plans/" + e.getKey(), e.getValue());
            m_jarOutput.writeToFile(new File(jarOutputPath));
        } catch (final Exception e) {
            return false;
        }

        assert(!hasErrors());

        if (hasErrors()) {
            return false;
        }

        return true;
    }

    /**
     * Get textual explain plan info for each plan from the
     * catalog to be shoved into the catalog jarfile.
     */
    HashMap<String, byte[]> getExplainPlans(Catalog catalog) {
        HashMap<String, byte[]> retval = new HashMap<String, byte[]>();
        Database db = catalog.getClusters().get("cluster").getDatabases().get("database");
        assert(db != null);
        for (Procedure proc : db.getProcedures()) {
            for (Statement stmt : proc.getStatements()) {
                String s = "SQL: " + stmt.getSqltext() + "\n";
                s += "COST: " + Integer.toString(stmt.getCost()) + "\n";
                s += "PLAN:\n\n";
                s += Encoder.hexDecodeToString(stmt.getExplainplan()) + "\n";
                byte[] b = null;
                try {
                    b = s.getBytes("UTF-8");
                } catch (UnsupportedEncodingException e) {
                    assert(false);
                }
                retval.put(proc.getTypeName() + "_" + stmt.getTypeName() + ".txt", b);
            }
        }
        return retval;
    }

    @SuppressWarnings("unchecked")
    public Catalog compileCatalog(final String projectFileURL)
    {
        // Compiler instance is reusable. Clear the cache.
        cachedAddedClasses.clear();
        m_currentFilename = new File(projectFileURL).getName();
        m_jarOutput = new InMemoryJarfile();
        ProjectType project = null;

        try {
            JAXBContext jc = JAXBContext.newInstance("org.voltdb.compiler.projectfile");
            // This schema shot the sheriff.
            SchemaFactory sf = SchemaFactory.newInstance(
              javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = sf.newSchema(this.getClass().getResource("ProjectFileSchema.xsd"));
            Unmarshaller unmarshaller = jc.createUnmarshaller();
            // But did not shoot unmarshaller!
            unmarshaller.setSchema(schema);
            JAXBElement<ProjectType> result = (JAXBElement<ProjectType>) unmarshaller.unmarshal(new File(projectFileURL));
            project = result.getValue();
        }
        catch (JAXBException e) {
            // Convert some linked exceptions to more friendly errors.
            if (e.getLinkedException() instanceof java.io.FileNotFoundException) {
                addErr(e.getLinkedException().getMessage());
                compilerLog.error(e.getLinkedException().getMessage());
                return null;
            }
            if (e.getLinkedException() instanceof org.xml.sax.SAXParseException) {
                addErr("Error schema validating project.xml file. " + e.getLinkedException().getMessage());
                compilerLog.error("Error schema validating project.xml file: " + e.getLinkedException().getMessage());
                compilerLog.error(e.getMessage());
                compilerLog.error(projectFileURL);
                return null;
            }
            throw new RuntimeException(e);
        }
        catch (SAXException e) {
            addErr("Error schema validating project.xml file. " + e.getMessage());
            compilerLog.error("Error schema validating project.xml file. " + e.getMessage());
            return null;
        }

        try {
            compileXMLRootNode(project);
        } catch (final VoltCompilerException e) {
            compilerLog.l7dlog( Level.ERROR, LogKeys.compiler_VoltCompiler_FailedToCompileXML.name(), null);
            compilerLog.error(e.getMessage());
            // e.printStackTrace();
            return null;
        }
        assert(m_catalog != null);

        // add epoch info to catalog
        final int epoch = (int)(TransactionIdManager.getEpoch() / 1000);
        m_catalog.getClusters().get("cluster").setLocalepoch(epoch);

        // done handling files
        m_currentFilename = null;
        return m_catalog;
    }

    ProcInfoData getProcInfoOverride(final String procName) {
        if (m_procInfoOverrides == null)
            return null;
        return m_procInfoOverrides.get(procName);
    }

    public Catalog getCatalog() {
        return m_catalog;
    }

    void compileXMLRootNode(ProjectType project) throws VoltCompilerException {
        m_catalog = new Catalog();
        temporaryCatalogInit();

        SecurityType security = project.getSecurity();
        if (security != null) {
            m_catalog.getClusters().get("cluster").
                setSecurityenabled(security.isEnabled());

        }

        DatabaseType database = project.getDatabase();
        if (database != null) {
            compileDatabaseNode(database);
        }
    }

    /**
     * Initialize the catalog for one cluster
     */
    void temporaryCatalogInit() {
        m_catalog.execute("add / clusters cluster");
        m_catalog.getClusters().get("cluster").setSecurityenabled(false);
    }

    void compileDatabaseNode(DatabaseType database) throws VoltCompilerException {
        final ArrayList<String> programs = new ArrayList<String>();
        final ArrayList<String> schemas = new ArrayList<String>();
        final ArrayList<ProcedureDescriptor> procedures = new ArrayList<ProcedureDescriptor>();
        final ArrayList<Class<?>> classDependencies = new ArrayList<Class<?>>();
        final ArrayList<String[]> partitions = new ArrayList<String[]>();

        final String databaseName = database.getName();

        // schema does not verify that the database is named "database"
        if (databaseName.equals("database") == false) {
            final String msg = "VoltDB currently requires all database elements to be named "+
                         "\"database\" (found: \"" + databaseName + "\")";
            throw new VoltCompilerException(msg);
        }

        // create the database in the catalog
        m_catalog.execute("add /clusters[cluster] databases " + databaseName);
        Database db = m_catalog.getClusters().get("cluster").getDatabases().get(databaseName);

        SnapshotType snapshotSettings = database.getSnapshot();
        if (snapshotSettings != null) {
            SnapshotSchedule schedule = db.getSnapshotschedule().add("default");
            String frequency = snapshotSettings.getFrequency();
            if (!frequency.endsWith("s") &&
                    !frequency.endsWith("m") &&
                    !frequency.endsWith("h")) {
                throw new VoltCompilerException(
                        "Snapshot frequency " + frequency +
                        " needs to end with time unit specified" +
                        " that is one of [s, m, h] (seconds, minutes, hours)");
            }

            int frequencyInt = 0;
            String frequencySubstring = frequency.substring(0, frequency.length() - 1);
            try {
                frequencyInt = Integer.parseInt(frequencySubstring);
            } catch (Exception e) {
                throw new VoltCompilerException("Frequency " + frequencySubstring +
                        " is not an integer ");
            }

            String prefix = snapshotSettings.getPrefix();
            if (prefix == null || prefix.isEmpty()) {
                throw new VoltCompilerException("Snapshot prefix " + prefix +
                " is not a valid prefix ");
            }

            if (prefix.contains("-") || prefix.contains(",")) {
                throw new VoltCompilerException("Snapshot prefix " + prefix +
                " cannot include , or - ");
            }

            String path = snapshotSettings.getPath();
            if (path == null || path.isEmpty()) {
                throw new VoltCompilerException("Snapshot path " + path +
                " is not a valid path ");
            }

            if (snapshotSettings.getRetain() == null) {
                throw new VoltCompilerException("Snapshot retain value not provided");
            }

            int retain = snapshotSettings.getRetain().intValue();
            if (retain < 1) {
                throw new VoltCompilerException("Snapshot retain value " + retain +
                        " is not a valid value. Must be 1 or greater.");
            }

            schedule.setFrequencyunit(
                    frequency.substring(frequency.length() - 1, frequency.length()));
            schedule.setFrequencyvalue(frequencyInt);
            schedule.setPath(path);
            schedule.setPrefix(prefix);
            schedule.setRetain(retain);
        }

        // schemas/schema
        for (SchemasType.Schema schema : database.getSchemas().getSchema()) {
            compilerLog.l7dlog( Level.INFO, LogKeys.compiler_VoltCompiler_CatalogPath.name(),
                                new Object[] {schema.getPath()}, null);
            schemas.add(schema.getPath());
        }

        // groups/group.
        if (database.getGroups() != null) {
            for (GroupsType.Group group : database.getGroups().getGroup()) {
                org.voltdb.catalog.Group catGroup = db.getGroups().add(group.getName());
                catGroup.setAdhoc(group.isAdhoc());
                catGroup.setSysproc(group.isSysproc());
            }
        }

        // procedures/procedure
        for (ProceduresType.Procedure proc : database.getProcedures().getProcedure()) {
            procedures.add(getProcedure(proc));
        }

        // classdependencies/classdependency
        if (database.getClassdependencies() != null) {
            for (Classdependency dep : database.getClassdependencies().getClassdependency()) {
                classDependencies.add(getClassDependency(dep));
            }
        }

        // partitions/table
        if (database.getPartitions() != null) {
            for (org.voltdb.compiler.projectfile.PartitionsType.Partition table : database.getPartitions().getPartition()) {
                partitions.add(getPartition(table));
            }
        }

        String msg = "Database \"" + databaseName + "\" ";
        // TODO: schema allows 0 procedures. Testbase relies on this.
        if (procedures.size() == 0) {
            msg += "needs at least one \"procedure\" element " +
                    "(currently has " + String.valueOf(procedures.size()) + ")";
            throw new VoltCompilerException(msg);
        }
        if (procedures.size() < 1) {
            msg += "is missing the \"procedures\" element";
            throw new VoltCompilerException(msg);
        }

        // shutdown and make a new hsqldb
        m_hsql = HSQLInterface.loadHsqldb();

        // Actually parse and handle all the programs
        for (final String programName : programs) {
            m_catalog.execute("add " + db.getPath() + " programs " + programName);
        }

        // Actually parse and handle all the DDL
        final DDLCompiler ddlcompiler = new DDLCompiler(this, m_hsql);

        for (final String schemaPath : schemas) {
            File schemaFile = null;

            if (schemaPath.contains(".jar!")) {
                String ddlText = null;
                try {
                    ddlText = readFileFromJarfile(schemaPath);
                } catch (final Exception e) {
                    throw new VoltCompilerException(e);
                }
                schemaFile = VoltProjectBuilder.writeStringToTempFile(ddlText);
            }
            else {
                schemaFile = new File(schemaPath);
            }

            if (!schemaFile.isAbsolute()) {
                // Resolve schemaPath relative to the database definition xml file
                schemaFile = new File(new File(m_projectFileURL).getParent(), schemaPath);
            }

            // add the file object's path to the list of files for the jar
            m_ddlFilePaths.put(schemaFile.getName(), schemaFile.getPath());

            ddlcompiler.loadSchema(schemaFile.getAbsolutePath());
        }
        ddlcompiler.compileToCatalog(m_catalog, db);

        // Actually parse and handle all the partitions
        // this needs to happen before procedures are compiled
        msg = "In database \"" + databaseName + "\", ";
        final CatalogMap<Table> tables = db.getTables();
        for (final String[] partition : partitions) {
            final String tableName = partition[0];
            final String colName = partition[1];
            final Table t = tables.getIgnoreCase(tableName);
            if (t == null) {
                msg += "\"partition\" element has unknown \"table\" attribute '" + tableName + "'";
                throw new VoltCompilerException(msg);
            }
            final Column c = t.getColumns().getIgnoreCase(colName);
            // make sure the column exists
            if (c == null) {
                msg += "\"partition\" element has unknown \"column\" attribute '" + colName + "'";
                throw new VoltCompilerException(msg);
            }
            // make sure the column is marked not-nullable
            if (c.getNullable() == true) {
                msg += "Partition column '" + tableName + "." + colName + "' is nullable. " +
                    "Partition columns must be constrained \"NOT NULL\".";
                throw new VoltCompilerException(msg);
            }
            t.setPartitioncolumn(c);
            t.setIsreplicated(false);

            // Set the destination tables of associated views non-replicated.
            // If a view's source table is replicated, then a full scan of the
            // associated view is singled-sited. If the source is partitioned,
            // a full scan of the view must be distributed.
            final CatalogMap<MaterializedViewInfo> views = t.getViews();
            for (final MaterializedViewInfo mvi : views) {
                mvi.getDest().setIsreplicated(false);
            }
        }

        // this should reorder the tables and partitions all alphabetically
        String catData = m_catalog.serialize();
        m_catalog = new Catalog();
        m_catalog.execute(catData);
        db = m_catalog.getClusters().get("cluster").getDatabases().get(databaseName);

        // add database estimates info
        addDatabaseEstimatesInfo(m_estimates, db);
        try {
            addSystemProcsToCatalog(m_catalog, db);
        } catch (final VoltCompilerException ex) {
            throw new RuntimeException(ex);
        }

        // Process and add exports and connectors to the catalog
        // Must do this before compiling procedures to deny updates
        // on append-only tables.
        if (database.getExports() != null) {
            // currently, only a single connector is allowed
            Connector conn = database.getExports().getConnector();
            compileConnector(conn, db);
        }

        // Actually parse and handle all the Procedures
        for (final ProcedureDescriptor procedureDescriptor : procedures) {
            final String procedureName = procedureDescriptor.m_className;
            m_currentFilename = procedureName.substring(procedureName.lastIndexOf('.') + 1);
            m_currentFilename += ".class";
            ProcedureCompiler.compile(this, m_hsql, m_estimates,
                    m_catalog, db, procedureDescriptor);
        }

        // Add all the class dependencies to the output jar
        for (final Class<?> classDependency : classDependencies) {
            addClassToJar( classDependency, this );
        }

        m_hsql.close();
    }


    static void addDatabaseEstimatesInfo(final DatabaseEstimates estimates, final Database db) {
        /*for (Table table : db.getTables()) {
            DatabaseEstimates.TableEstimates tableEst = new DatabaseEstimates.TableEstimates();
            tableEst.maxTuples = 1000000;
            tableEst.minTuples = 100000;
            estimates.tables.put(table, tableEst);
        }*/
    }

    ProcedureDescriptor getProcedure(
        org.voltdb.compiler.projectfile.ProceduresType.Procedure xmlproc)
        throws VoltCompilerException
    {
        final ArrayList<String> groups = new ArrayList<String>();

        // @groups
        if (xmlproc.getGroups() != null) {
            for (String group : xmlproc.getGroups().split(",")) {
                groups.add(group);
            }
        }

        // @class
        String classattr = xmlproc.getClazz();

        // If procedure/sql is present, this is a "statement procedure"
        if (xmlproc.getSql() != null) {
            String partattr = xmlproc.getPartitioninfo();
            // null partattr means multi-partition
            // set empty attributes to multi-partition
            if (partattr != null && partattr.length() == 0)
                partattr = null;
            return new ProcedureDescriptor(groups, classattr,
                                           xmlproc.getSql().getValue(), xmlproc.getSql().getJoinOrder(), partattr);
        }
        else {
            String partattr = xmlproc.getPartitioninfo();
            if (partattr != null) {
                String msg = "Java procedures must specify partition info using " +
                "@ProcInfo annotation in the Java class implementation " +
                "and may not use the @partitioninfo project file procedure attribute.";
                throw new VoltCompilerException(msg);
            }
            return new ProcedureDescriptor(groups, classattr);
        }
    }


    Class<?> getClassDependency(Classdependency xmlclassdep)
    throws VoltCompilerException
    {
        String msg = "";
        String className = xmlclassdep.getClazz();

        // schema doesn't currently enforce this.. but could I guess.
        if (className.length() == 0) {
            msg += "\"classDependency\" element has empty \"class\" attribute.";
            throw new VoltCompilerException(msg);
        }

        Class<?> cls = null;
        try {
            cls = Class.forName(className);
        } catch (final ClassNotFoundException e) {
            msg += "\"classDependency\" can not find class " + className + " in classpath";
            throw new VoltCompilerException(msg);
        }

        return cls;
    }

    String[] getPartition(org.voltdb.compiler.projectfile.PartitionsType.Partition xmltable)
    throws VoltCompilerException
    {
        String msg = "";
        final String tableName = xmltable.getTable();
        final String columnName = xmltable.getColumn();

        // where is table and column validity checked?
        if (tableName.length() == 0) {
            msg += "\"partition\" element has empty \"table\" attribute";
            throw new VoltCompilerException(msg);
        }

        if (columnName.length() == 0) {
            msg += "\"partition\" element has empty \"column\" attribute";
            throw new VoltCompilerException(msg);
        }

        final String[] retval = { tableName, columnName };
        return retval;
    }

    void compileConnector(final Connector conn, final Database catdb)
        throws VoltCompilerException
    {
        // Test the error paths before touching the catalog
        if (conn == null) {
            return;
        }

        // Figure out if the connector is enabled or disabled
        // Export will be disabled if there is no destination.
        boolean adminstate = conn.isEnabled();

        if (!conn.isEnabled()) {
            compilerLog.info("Export configuration is present and is " +
                             "configured to be disabled. Export will be disabled.");
        }

        // Catalog Connector
        // Relying on schema's enforcement of at most 1 connector
        org.voltdb.catalog.Connector catconn = catdb.getConnectors().add("0");
        catconn.setEnabled(adminstate);
        catconn.setLoaderclass(conn.getClazz());

        // add authorized users and groups
        final ArrayList<String> groupslist = new ArrayList<String>();

        // @groups
        if (conn.getGroups() != null) {
            for (String group : conn.getGroups().split(",")) {
                groupslist.add(group);
            }
        }

        for (String groupName : groupslist) {
            final Group group = catdb.getGroups().get(groupName);
            if (group == null) {
                throw new VoltCompilerException("Export connector " + conn.getClazz() + " has a group " + groupName + " that does not exist");
            }
            final GroupRef groupRef = catconn.getAuthgroups().add(groupName);
            groupRef.setGroup(group);
        }


        // Catalog Connector.ConnectorTableInfo
        Integer i = 0;
        if (conn.getTables() != null) {
            for (Tables.Table xmltable : conn.getTables().getTable()) {
                // verify that the table exists in the catalog
                String tablename = xmltable.getName();
                org.voltdb.catalog.Table tableref = catdb.getTables().getIgnoreCase(tablename);
                if (tableref == null) {
                    throw new VoltCompilerException("While configuring export, table " + tablename + " was not present in " +
                    "the catalog.");
                }
                if (xmltable.isExportonly() &&
                    CatalogUtil.isTableMaterializeViewSource(catdb, tableref)) {
                    compilerLog.error("While configuring export, table " + tablename + " is a source table " +
                            "for a materialized view. Export only tables do not support views.");
                    throw new VoltCompilerException("Export-only table configured with materialized view.");
                }
                if (xmltable.isExportonly() &&
                    tableref.getMaterializer() != null)
                {
                    compilerLog.error("While configuring export, table " + tablename + " is a " +
                                                "materialized view.  A view cannot be an export-only table.");
                    throw new VoltCompilerException("View configured as an export-only table");
                }

                org.voltdb.catalog.ConnectorTableInfo connTableInfo = catconn.getTableinfo().add(tablename);
                connTableInfo.setAppendonly(xmltable.isExportonly());
                connTableInfo.setTable(tableref);
                ++i;
            }
        }
    }

    /**
     * Add the system procedures to the catalog.
     */
    void addSystemProcsToCatalog(final Catalog catalog, final Database database) throws VoltCompilerException {
        assert (catalog != null);
        assert(database != null);

        // Table of sysproc metadata.
        final String[][] procedures =
        {
         // package.classname                                readonly    everysite
         {"org.voltdb.sysprocs.AdHoc",                        "false",    "false"},
         {"org.voltdb.sysprocs.LoadMultipartitionTable",      "false",    "false"},
         {"org.voltdb.sysprocs.Quiesce",                      "false",    "false"},
         {"org.voltdb.sysprocs.Rejoin",                       "false",    "false"},
         {"org.voltdb.sysprocs.SnapshotSave",                 "false",    "false"},
         {"org.voltdb.sysprocs.SnapshotRestore",              "false",    "false"},
         {"org.voltdb.sysprocs.SnapshotStatus",               "false",    "false"},
         {"org.voltdb.sysprocs.SnapshotScan",                 "false",    "false"},
         {"org.voltdb.sysprocs.SnapshotDelete",               "false",    "false"},
         {"org.voltdb.sysprocs.Shutdown",                     "false",    "false"},
         {"org.voltdb.sysprocs.ProfCtl",                      "false",    "false"},
         {"org.voltdb.sysprocs.Statistics",                   "true",     "false"},
         {"org.voltdb.sysprocs.SystemInformation",            "true",     "false"},
         {"org.voltdb.sysprocs.UpdateApplicationCatalog",     "false",    "true"},
         {"org.voltdb.sysprocs.UpdateLogging",                "false",    "true"}

        };

        for (int ii=0; ii < procedures.length; ++ii) {
            String classname = procedures[ii][0];
            boolean readonly = Boolean.parseBoolean(procedures[ii][1]);
            boolean everysite = Boolean.parseBoolean(procedures[ii][2]);

            Class<?> procClass = null;
            try {
                procClass = Class.forName(classname);
            }
            catch (final ClassNotFoundException e) {
                final String msg = "Cannot load sysproc " + classname;
                throw new VoltCompilerException(msg);
            }

            // short name is "@ClassName" without package
            final String[] parts = classname.split("\\.");
            final String shortName = "@" + parts[parts.length - 1];

            // read annotations
            final ProcInfo info = procClass.getAnnotation(ProcInfo.class);
            if (info == null) {
                throw new VoltCompilerException("Sysproc " + shortName + " is missing annotation.");
            }

            // add an entry to the catalog
            final Procedure procedure = database.getProcedures().add(shortName);
            procedure.setClassname(classname);
            procedure.setReadonly(readonly);
            procedure.setSystemproc(true);
            procedure.setHasjava(true);
            procedure.setSinglepartition(info.singlePartition());
            procedure.setEverysite(everysite);

            // Stored procedure sysproc classes are present in VoltDB.jar
            // and not duplicated in the catalog. This was decided
            // arbitrarily - no one had a strong opinion.
            //
            // VoltCompiler.addClassToJar(procClass, compiler);
        }
    }

    public static void main(final String[] args) {
        // Parse arguments
        if (args.length != 2) {
            System.err.println("VoltCompiler [project file] [output JAR]");
            System.exit(1);
        }
        final String projectPath = args[0];
        final String outputJar = args[1];

        // Compile and exit with error code if we failed
        final VoltCompiler compiler = new VoltCompiler();
        final boolean success = compiler.compile(projectPath, outputJar, System.out, null);
        if (!success) {
            compiler.summarizeErrors();
            System.exit(-1);
        }
        else {
            compiler.summarizeSuccess();
        }
    }

    private void summarizeSuccess() {
        if (m_outputStream != null) {

            Database database = m_catalog.getClusters().get("cluster").
            getDatabases().get("database");

            m_outputStream.println("------------------------------------------");
            m_outputStream.println("Successfully created " + m_jarOutputPath);

            for (String ddl : m_ddlFilePaths.keySet()) {
                m_outputStream.println("Includes schema: " + m_ddlFilePaths.get(ddl));
            }

            m_outputStream.println();

            for (Procedure p : database.getProcedures()) {
                if (p.getSystemproc()) {
                    continue;
                }
                m_outputStream.printf("[%s][%s] %s\n",
                                      p.getSinglepartition() ? "SP" : "MP",
                                      p.getReadonly() ? "RO" : "RW",
                                      p.getTypeName());
                for (Statement s : p.getStatements()) {
                    if (s.getSqltext().length() > 80) {
                        m_outputStream.println("  " + s.getSqltext().substring(0, 80) + "...");
                    }
                    else {
                        m_outputStream.println("  " + s.getSqltext());
                    }
                }
                m_outputStream.println();
            }
            m_outputStream.println("------------------------------------------");
        }
    }

    private void summarizeErrors() {
        if (m_outputStream != null) {
            m_outputStream.println("------------------------------------------");
            m_outputStream.println("Project compilation failed. See log for errors.");
            m_outputStream.println("------------------------------------------");
        }
    }

    // this needs to be reset in the main compile func
    private static final HashSet<Class<?>> cachedAddedClasses = new HashSet<Class<?>>();

    public static final void addClassToJar(final Class<?> cls, final VoltCompiler compiler)
    throws VoltCompiler.VoltCompilerException {

        if (cachedAddedClasses.contains(cls)) {
            return;
        } else {
            cachedAddedClasses.add(cls);
        }

        for (final Class<?> nested : cls.getDeclaredClasses()) {
            addClassToJar(nested, compiler);
        }

        String packagePath = cls.getName();
        packagePath = packagePath.replace('.', '/');
        packagePath += ".class";

        String realName = cls.getName();
        realName = realName.substring(realName.lastIndexOf('.') + 1);
        realName += ".class";

        final URL absolutePath = cls.getResource(realName);
        File file = null;

        InputStream fis = null;
        int fileSize = 0;
        try {
            file =
                new File(URLDecoder.decode(absolutePath.getFile(), "UTF-8"));
            fis = new FileInputStream(file);
            assert(file.canRead());
            assert(file.isFile());
            fileSize = (int) file.length();
        } catch (final FileNotFoundException e) {
            try {
                final String contents = readFileFromJarfile(absolutePath.getPath());
                fis = new StringInputStream(contents);
                fileSize = contents.length();
            }
            catch (final Exception e2) {
                final String msg = "Unable to locate classfile for " + realName;
                throw compiler.new VoltCompilerException(msg);
            }
        } catch (final UnsupportedEncodingException e) {
            e.printStackTrace();
            System.exit(-1);
        }

        assert(fileSize > 0);
        int readSize = 0;

        final byte[] fileBytes = new byte[fileSize];

        try {
            while (readSize < fileSize) {
                readSize = fis.read(fileBytes, readSize, fileSize - readSize);
            }
        } catch (final IOException e) {
            final String msg = "Unable to read (or completely read) classfile for " + realName;
            throw compiler.new VoltCompilerException(msg);
        }

        compiler.m_jarOutput.put(packagePath, fileBytes);
    }

    /**
     * Read a file from a jar in the form path/to/jar.jar!/path/to/file.ext
     */
    static String readFileFromJarfile(String fulljarpath) throws IOException {
        assert (fulljarpath.contains(".jar!"));

        String[] paths = fulljarpath.split("!");
        if (paths[0].startsWith("file:"))
            paths[0] = paths[0].substring("file:".length());
        paths[1] = paths[1].substring(1);

        return readFileFromJarfile(paths[0], paths[1]);
    }

    static String readFileFromJarfile(String jarfilePath, String entryPath) throws IOException {
        InputStream fin = null;
        try {
            URL jar_url = new URL(jarfilePath);
            fin = jar_url.openStream();
        } catch (MalformedURLException ex) {
            // Invalid URL. Try as a file.
            fin = new FileInputStream(jarfilePath);
        }
        JarInputStream jarIn = new JarInputStream(fin);

        JarEntry catEntry = jarIn.getNextJarEntry();
        while ((catEntry != null) && (catEntry.getName().equals(entryPath) == false)) {
            catEntry = jarIn.getNextJarEntry();
        }
        if (catEntry == null) {
            return null;
        }

        byte[] bytes = InMemoryJarfile.readFromJarEntry(jarIn, catEntry);

        return new String(bytes, "UTF-8");
    }
}
