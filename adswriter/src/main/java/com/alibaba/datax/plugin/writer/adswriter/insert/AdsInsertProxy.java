package com.alibaba.datax.plugin.writer.adswriter.insert;

import com.alibaba.datax.common.element.Column;
import com.alibaba.datax.common.element.Record;
import com.alibaba.datax.common.exception.DataXException;
import com.alibaba.datax.common.plugin.RecordReceiver;
import com.alibaba.datax.common.plugin.TaskPluginCollector;
import com.alibaba.datax.common.util.Configuration;
import com.alibaba.datax.common.util.RetryUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtil;
import com.alibaba.datax.plugin.rdbms.util.DBUtilErrorCode;
import com.alibaba.datax.plugin.writer.adswriter.util.Constant;
import com.alibaba.datax.plugin.writer.adswriter.util.Key;
import com.mysql.jdbc.JDBC4PreparedStatement;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Triple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;


public class AdsInsertProxy {

    private static final Logger LOG = LoggerFactory
            .getLogger(AdsInsertProxy.class);

    private String table;
    private List<String> columns;
    private TaskPluginCollector taskPluginCollector;
    private Configuration configuration;
    private Boolean emptyAsNull;
    private String sqlPrefix;
    private int retryTimeUpperLimit;
    private Connection currentConnection;

    private Triple<List<String>, List<Integer>, List<String>> resultSetMetaData;

    public AdsInsertProxy(String table, List<String> columns, Configuration configuration, TaskPluginCollector taskPluginCollector) {
        this.table = table;
        this.columns = columns;
        this.configuration = configuration;
        this.taskPluginCollector = taskPluginCollector;
        this.emptyAsNull = configuration.getBool(Key.EMPTY_AS_NULL, false);
        this.sqlPrefix = "insert into " + this.table + "("
                + StringUtils.join(columns, ",") + ") values";
        this.retryTimeUpperLimit = configuration.getInt(
                Key.RETRY_CONNECTION_TIME, Constant.DEFAULT_RETRY_TIMES);
    }

    public void startWriteWithConnection(RecordReceiver recordReceiver,
                                                Connection connection,
                                                int columnNumber) {
        //目前 ads 新建的表 如果未插入数据  不能通过select colums from table where 1=2，获取列信息。
//        this.resultSetMetaData = DBUtil.getColumnMetaData(connection,
//                this.table, StringUtils.join(this.columns, ","));
        this.currentConnection = connection;
        //no retry here(fetch meta data)
        this.resultSetMetaData = AdsInsertUtil.getColumnMetaData(configuration, columns);

        int batchSize = this.configuration.getInt(Key.BATCH_SIZE, Constant.DEFAULT_BATCH_SIZE);
        List<Record> writeBuffer = new ArrayList<Record>(batchSize);
        try {
            Record record;
            while ((record = recordReceiver.getFromReader()) != null) {
                if (record.getColumnNumber() != columnNumber) {
                    // 源头读取字段列数与目的表字段写入列数不相等，直接报错
                    throw DataXException
                            .asDataXException(
                                    DBUtilErrorCode.CONF_ERROR,
                                    String.format(
                                            "列配置信息有错误. 因为您配置的任务中，源头读取字段数:%s 与 目的表要写入的字段数:%s 不相等. 请检查您的配置并作出修改.",
                                            record.getColumnNumber(),
                                            columnNumber));
                }

                writeBuffer.add(record);

                if (writeBuffer.size() >= batchSize) {
                    //doOneInsert(connection, writeBuffer);
                    doBatchInsertWithOneStatement(writeBuffer);
                    writeBuffer.clear();
                }
            }
            if (!writeBuffer.isEmpty()) {
                doOneInsert(writeBuffer);
                writeBuffer.clear();
            }
        } catch (Exception e) {
            throw DataXException.asDataXException(
                    DBUtilErrorCode.WRITE_DATA_ERROR, e);
        } finally {
            writeBuffer.clear();
            DBUtil.closeDBResources(null, null, connection);
        }
    }

    //warn: ADS 无法支持事物，这里面的roll back都是不管用的吧, not used
    @Deprecated
    protected void doBatchInsert(Connection connection, List<Record> buffer) throws SQLException {
        Statement statement = null;
        try {
            connection.setAutoCommit(false);
            statement = connection.createStatement();

            for (Record record : buffer) {
                String sql = generateInsertSql(connection, record);
                statement.addBatch(sql);
            }
            statement.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            LOG.warn("回滚此次写入, 采用每次写入一行方式提交. 因为:" + e.getMessage(), e);
            connection.rollback();
            doOneInsert(buffer);
        } catch (Exception e) {
            throw DataXException.asDataXException(
                    DBUtilErrorCode.WRITE_DATA_ERROR, e);
        } finally {
            DBUtil.closeDBResources(statement, null);
        }
    }
    
    private void doBatchInsertWithOneStatement(List<Record> buffer) throws SQLException {
        Statement statement = null;
        String sql = null;
        try {
            int bufferSize = buffer.size();
            if (buffer.isEmpty()) {
                return;
            }
            StringBuilder sqlSb = new StringBuilder();
            // connection.setAutoCommit(true);
            //mysql impl warn: if a database access error occurs or this method is called on a closed connection throw SQLException
            statement = this.currentConnection.createStatement();
            sqlSb.append(this.generateInsertSql(this.currentConnection, buffer.get(0)));
            for (int i = 1; i < bufferSize; i++) {
                Record record = buffer.get(i);
                this.appendInsertSqlValues(this.currentConnection, record, sqlSb);
            }
            sql = sqlSb.toString();
            LOG.debug(sql);
            @SuppressWarnings("unused")
            int status = statement.executeUpdate(sql);
            sql = null;
        } catch (SQLException e) {
            LOG.warn("doBatchInsertWithOneStatement meet a exception: " + sql, e);
            LOG.info("try to re insert each record...");
            doOneInsert(buffer);
            // below is the old way
            // for (Record eachRecord : buffer) {
            // this.taskPluginCollector.collectDirtyRecord(eachRecord, e);
            // }
        } catch (Exception e) {
            LOG.error("插入异常, sql: " + sql);
            throw DataXException.asDataXException(
                    DBUtilErrorCode.WRITE_DATA_ERROR, e);
        } finally {
            DBUtil.closeDBResources(statement, null);
        }
    }
    
    protected void doOneInsert(List<Record> buffer) {
        List<Class<?>> retryExceptionClasss = new ArrayList<Class<?>>();
        retryExceptionClasss.add(com.mysql.jdbc.exceptions.jdbc4.CommunicationsException.class);
        retryExceptionClasss.add(java.net.SocketException.class);
        for (final Record record : buffer) {
            try {
                RetryUtil.executeWithRetry(new Callable<Boolean>() {
                    @Override
                    public Boolean call() throws Exception {
                        doOneRecordInsert(record);
                        return true;
                    }
                }, this.retryTimeUpperLimit, 2000L, true, retryExceptionClasss);
            } catch (Exception e) {
                // 不能重试的一行，记录脏数据
                this.taskPluginCollector.collectDirtyRecord(record, e);
            }
        }
    }
    
    @SuppressWarnings("resource")
    protected void doOneRecordInsert(Record record) throws Exception {
        Statement statement = null;
        String sql = null;
        try {
            // connection.setAutoCommit(true);
            statement = this.currentConnection.createStatement();
            sql = generateInsertSql(this.currentConnection, record);
            LOG.debug(sql);
            @SuppressWarnings("unused")
            int status = statement.executeUpdate(sql);
            sql = null;
        } catch (SQLException e) {
            LOG.error("doOneInsert meet a exception: " + sql, e);
            //need retry before record dirty data
            //this.taskPluginCollector.collectDirtyRecord(record, e);
            // 更新当前可用连接
            Exception eachException = e;
            int maxIter = 0;// 避免死循环
            while (null != eachException && maxIter < 100) {
                if (this.isRetryable(eachException)) {
                    LOG.warn("doOneInsert meet a retry exception: " + e.getMessage());
                    this.currentConnection = AdsInsertUtil
                            .getAdsConnect(this.configuration);
                    throw eachException;
                } else {
                    try {
                        eachException = (Exception) eachException.getCause();
                    } catch (Exception castException) {
                        LOG.warn("doOneInsert meet a no! retry exception: " + e.getMessage());
                        throw e;
                    }
                }
                maxIter++;
            }
            throw e;
        } catch (Exception e) {
            LOG.error("插入异常, sql: " + sql);
            throw DataXException.asDataXException(
                    DBUtilErrorCode.WRITE_DATA_ERROR, e);
        } finally {
            DBUtil.closeDBResources(statement, null);
        }
    }
    
    private boolean isRetryable(Throwable e) {
        Class<?> meetExceptionClass = e.getClass();
        if (meetExceptionClass == com.mysql.jdbc.exceptions.jdbc4.CommunicationsException.class) {
            return true;
        }
        if (meetExceptionClass == java.net.SocketException.class) {
            return true;
        }
        return false;
    }
    
    private String generateInsertSql(Connection connection, Record record) throws SQLException {
        StringBuilder sqlSb = new StringBuilder(this.sqlPrefix + "(");
        for (int i = 0; i < columns.size(); i++) {
            if((i+1) != columns.size()) {
                sqlSb.append("?,");
            } else {
                sqlSb.append("?");
            }
        }
        sqlSb.append(")");
        //mysql impl warn: if a database access error occurs or this method is called on a closed connection
        PreparedStatement statement = connection.prepareStatement(sqlSb.toString());
        for (int i = 0; i < columns.size(); i++) {
            int columnSqltype = this.resultSetMetaData.getMiddle().get(i);
            checkColumnType(statement, columnSqltype, record.getColumn(i), i);
        }
        String sql = ((JDBC4PreparedStatement) statement).asSql();
        DBUtil.closeDBResources(statement, null);
        return sql;
    }
    
    private void appendInsertSqlValues(Connection connection, Record record, StringBuilder sqlSb) throws SQLException { 
        String sqlResult = this.generateInsertSql(connection, record);
        sqlSb.append(",");
        sqlSb.append(sqlResult.substring(this.sqlPrefix.length()));
    }

    @SuppressWarnings({ "null" })
    private void checkColumnType(PreparedStatement statement, int columnSqltype, Column column, int columnIndex) throws SQLException {
        java.util.Date utilDate;
        switch (columnSqltype) {
            case Types.CHAR:
            case Types.NCHAR:
            case Types.CLOB:
            case Types.NCLOB:
            case Types.VARCHAR:
            case Types.LONGVARCHAR:
            case Types.NVARCHAR:
            case Types.LONGNVARCHAR:
                String strValue = column.asString();
                statement.setString(columnIndex + 1, strValue);
                break;

            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT:
            case Types.NUMERIC:
            case Types.DECIMAL:
            case Types.REAL:
                String numValue = column.asString();
                if(emptyAsNull && "".equals(numValue) || numValue == null){
                    statement.setLong(columnIndex + 1, (Long) null);
                } else{
                    statement.setLong(columnIndex + 1, column.asLong());
                }
                break;
                
            case Types.FLOAT:
            case Types.DOUBLE:
                String floatValue = column.asString();
                if(emptyAsNull && "".equals(floatValue) || floatValue == null){
                    statement.setDouble(columnIndex + 1, (Double) null);
                } else{
                    statement.setDouble(columnIndex + 1, column.asDouble());
                }
                break;

            //tinyint is a little special in some database like mysql {boolean->tinyint(1)}
            case Types.TINYINT:
                Long longValue = column.asLong();
                statement.setLong(columnIndex + 1, longValue);
                break;

            case Types.DATE:
                java.sql.Date sqlDate = null;
                try {
                    if("".equals(column.getRawData())) {
                        utilDate = null;
                    } else {
                        utilDate = column.asDate();
                    }
                } catch (DataXException e) {
                    throw new SQLException(String.format(
                            "Date 类型转换错误：[%s]", column));
                }
                
                if (null != utilDate) {
                    sqlDate = new java.sql.Date(utilDate.getTime());
                } 
                statement.setDate(columnIndex + 1, sqlDate);
                break;

            case Types.TIME:
                java.sql.Time sqlTime = null;
                try {
                    if("".equals(column.getRawData())) {
                        utilDate = null;
                    } else {
                        utilDate = column.asDate();
                    }
                } catch (DataXException e) {
                    throw new SQLException(String.format(
                            "TIME 类型转换错误：[%s]", column));
                }

                if (null != utilDate) {
                    sqlTime = new java.sql.Time(utilDate.getTime());
                }
                statement.setTime(columnIndex + 1, sqlTime);
                break;

            case Types.TIMESTAMP:
                java.sql.Timestamp sqlTimestamp = null;
                try {
                    if("".equals(column.getRawData())) {
                        utilDate = null;
                    } else {
                        utilDate = column.asDate();
                    }
                } catch (DataXException e) {
                    throw new SQLException(String.format(
                            "TIMESTAMP 类型转换错误：[%s]", column));
                }

                if (null != utilDate) {
                    sqlTimestamp = new java.sql.Timestamp(
                            utilDate.getTime());
                }
                statement.setTimestamp(columnIndex + 1, sqlTimestamp);
                break;

            case Types.BOOLEAN:
            case Types.BIT:
                statement.setBoolean(columnIndex + 1, column.asBoolean());
                break;
            default:
                throw DataXException
                        .asDataXException(
                                DBUtilErrorCode.UNSUPPORTED_TYPE,
                                String.format(
                                        "您的配置文件中的列配置信息有误. 因为DataX 不支持数据库写入这种字段类型. 字段名:[%s], 字段类型:[%d], 字段Java类型:[%s]. 请修改表中该字段的类型或者不同步该字段.",
                                        this.resultSetMetaData.getLeft()
                                                .get(columnIndex),
                                        this.resultSetMetaData.getMiddle()
                                                .get(columnIndex),
                                        this.resultSetMetaData.getRight()
                                                .get(columnIndex)));
        }
    }
}
