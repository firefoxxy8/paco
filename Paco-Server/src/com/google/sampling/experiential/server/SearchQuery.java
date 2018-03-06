package com.google.sampling.experiential.server;

import java.sql.SQLException;
import java.text.ParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.json.JSONException;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.sampling.experiential.cloudsql.columns.ChoiceCollectionColumns;
import com.google.sampling.experiential.cloudsql.columns.EventServerColumns;
import com.google.sampling.experiential.cloudsql.columns.ExperimentDetailColumns;
import com.google.sampling.experiential.cloudsql.columns.ExperimentGroupVersionMappingColumns;
import com.google.sampling.experiential.cloudsql.columns.ExternStringInputColumns;
import com.google.sampling.experiential.cloudsql.columns.ExternStringListLabelColumns;
import com.google.sampling.experiential.cloudsql.columns.GroupDetailColumns;
import com.google.sampling.experiential.cloudsql.columns.InputCollectionColumns;
import com.google.sampling.experiential.cloudsql.columns.InputColumns;
import com.google.sampling.experiential.cloudsql.columns.OutputServerColumns;
import com.pacoapp.paco.shared.model2.EventBaseColumns;
import com.pacoapp.paco.shared.model2.OutputBaseColumns;
import com.pacoapp.paco.shared.model2.SQLQuery;
import com.pacoapp.paco.shared.util.Constants;
import com.pacoapp.paco.shared.util.ErrorMessages;
import com.pacoapp.paco.shared.util.QueryPreprocessor;
import com.pacoapp.paco.shared.util.SearchUtil;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

public abstract class SearchQuery {
  Float pacoProtocol;
  SQLQuery sqlQueryObj;
  boolean isClientTzNeedsAdded = false;
  Select jsqlStatement = null;
  QueryPreprocessor qPreProcessor = null;
  private static Map<String, Class> validColumnNamesDataTypeInDb = Maps.newHashMap();
  static List<String> localDateColumns = Lists.newArrayList();
  private static List<String> utcDateColumns = Lists.newArrayList();
  private static List<String> allDateColumns = Lists.newArrayList();
 
  public static final Logger log = Logger.getLogger(SearchQuery.class.getName());
  static{
    validColumnNamesDataTypeInDb.put(Constants.UNDERSCORE_ID, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.EXPERIMENT_ID, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.EXPERIMENT_NAME, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.EXPERIMENT_VERSION, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.SCHEDULE_TIME, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.RESPONSE_TIME, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.SCHEDULE_TIME_UTC, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.RESPONSE_TIME_UTC, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.GROUP_NAME, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.ACTION_TRIGGER_ID, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.ACTION_TRIGGER_SPEC_ID, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.ACTION_ID, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.WHO, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.WHEN, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.WHEN_FRAC_SEC, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.PACO_VERSION, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.APP_ID, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.JOINED, LongValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.SORT_DATE, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.SORT_DATE_UTC, StringValue.class);
    validColumnNamesDataTypeInDb.put(EventServerColumns.CLIENT_TIME_ZONE, StringValue.class);
    validColumnNamesDataTypeInDb.put(OutputBaseColumns.NAME, StringValue.class);
    validColumnNamesDataTypeInDb.put(OutputBaseColumns.ANSWER, StringValue.class);
    localDateColumns.add(EventServerColumns.RESPONSE_TIME);
    localDateColumns.add(EventServerColumns.SCHEDULE_TIME);
    localDateColumns.add(EventServerColumns.SORT_DATE);
    utcDateColumns.add(EventServerColumns.RESPONSE_TIME_UTC);
    utcDateColumns.add(EventServerColumns.SCHEDULE_TIME_UTC);
    utcDateColumns.add(EventServerColumns.SORT_DATE_UTC);
    utcDateColumns.add(EventServerColumns.WHEN);
    allDateColumns.addAll(localDateColumns);
    allDateColumns.addAll(utcDateColumns);
  }
  
  public abstract PacoResponse executeAcledQuery(String query) throws SQLException, ParseException, JSONException;
  
  public PacoResponse process(String loggedInUser) throws JSQLParserException, Exception {
    EventQueryStatus evStatus = null;
    PacoResponse pacoResp = null;
    // 1.client tz needed
    if (isClientTzNeedsAdded) { 
      List<String> projList = Lists.newArrayList(sqlQueryObj.getProjection());
      if (sqlQueryObj.getGroupBy() == null && (projList.contains(EventBaseColumns.RESPONSE_TIME) || (projList.contains(EventBaseColumns.SCHEDULE_TIME)))) {
        sqlQueryObj.addClientTzToProjection();
      }
    }
    // 2. do query preprocessing and validation
    evStatus = queryPreprocessingAndValidation();
    if (Constants.SUCCESS.equals(evStatus.getStatus())) {
      // 3. add join
      addJoinClauses();
      // 5. Add acl-ing to query
      String acledQuery = addAclToQuery(loggedInUser);
      // 6. execute acl-ed query
      pacoResp = executeAcledQuery(acledQuery);
    } else {
      return evStatus;
    }
    return pacoResp;
  }
 
  public EventQueryStatus queryPreprocessingAndValidation() throws JSQLParserException, Exception { 
    EventQueryStatus evQueryStatus = new EventQueryStatus();
    String plainSql = SearchUtil.getPlainSql(sqlQueryObj);
    Select clientJsqlStatement = SearchUtil.getJsqlSelectStatement(plainSql);
    boolean webRequest = true;
    evQueryStatus.setStatus(Constants.SUCCESS);
    QueryPreprocessor qProcessor = new QueryPreprocessor(clientJsqlStatement, validColumnNamesDataTypeInDb, webRequest, allDateColumns);
    if (qProcessor.probableSqlInjection() != null) {
      evQueryStatus.setErrorMessage(ErrorMessages.PROBABLE_SQL_INJECTION + qProcessor.probableSqlInjection());
      return evQueryStatus;
    }
    if (qProcessor.getInvalidDataType() != null) {
      evQueryStatus.setErrorMessage(ErrorMessages.INVALID_DATA_TYPE.getDescription() + qProcessor.getInvalidDataType());
      return evQueryStatus;
    }
    if (qProcessor.getInvalidColumnName() != null) {
      evQueryStatus.setErrorMessage(ErrorMessages.INVALID_COLUMN_NAME.getDescription()+ qProcessor.getInvalidColumnName());
      return evQueryStatus;
    }
    if (sqlQueryObj.getGroupBy() != null && !isValidGroupBy(sqlQueryObj.getProjection(), Arrays.asList(sqlQueryObj.getGroupBy().split(",")))) {
      evQueryStatus.setErrorMessage(ErrorMessages.INVALID_GROUPBY.getDescription() + Arrays.toString(sqlQueryObj.getProjection()));
      return evQueryStatus;
    }
    jsqlStatement = clientJsqlStatement;
    qPreProcessor = qProcessor;
    return evQueryStatus;
  }
  
  public void addJoinClauses() throws JSQLParserException { 
    addExperimentBundleJoinClause(jsqlStatement);
  }
  
  public String addAclToQuery(String loggedInUser) throws Exception { 
    List<Long> adminExperimentsinDB = ExperimentAccessManager.getExistingExperimentIdsForAdmin(loggedInUser, 0,
                                                                                               null)
                                                             .getExperiments();
    String aclQuery = ACLHelper.getModifiedQueryBasedOnACL(jsqlStatement, loggedInUser, adminExperimentsinDB, qPreProcessor);
    log.info("opt performance query with acl:" + aclQuery);
    return aclQuery;
  }
     
  private boolean isValidGroupBy(String[] selectColumnsArr, List<String> groupByCols) {
    // all (plain) columns in projection (except aggregate functions on columns) must be in group by list
    // select experiment_id, count(experiment_version) from events group by who INVALID
    // plain columns->experiment_id; aggregate columns->experiment_version); group by column->who
    // select who, count(experiment_version) from events group by who VALID
    boolean isValidGroupBy = true;
    List<String> selectColumns = Lists.newArrayList();
    for(String s: selectColumnsArr) {
      //aggregate function on columns will have open brackets
      if (!(s.contains("("))) {
        selectColumns.add(s.trim());
      }
    }
    if((selectColumns != null && groupByCols.size() > 0 && !groupByCols.containsAll(selectColumns))) {
      isValidGroupBy = false;
    }
   return isValidGroupBy;
  }
  
  private static void addExperimentBundleJoinClause(Select selStatement) throws JSQLParserException {
    PlainSelect ps = null;
    Expression joinExp1 = null, joinExp2 = null, joinExp3 = null;
    List<Join> jList = Lists.newArrayList();
    Join joinObj1 = new Join();
    Join joinObj2 = new Join();
    Join joinObj3 = new Join();
    
    try {
      joinExp1 = CCJSqlParserUtil.parseCondExpression(EventServerColumns.TABLE_NAME + "." + EventServerColumns.EXPERIMENT_GROUP_VERSION_MAPPING_ID + " = " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.EXPERIMENT_GROUP_VERSION_MAPPING_ID + " AND " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.EVENTS_POSTED + "=1");
      joinExp2 = CCJSqlParserUtil.parseCondExpression(ExperimentDetailColumns.TABLE_NAME + "." + ExperimentDetailColumns.EXPERIMENT_DETAIL_ID + " = " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.EXPERIMENT_DETAIL_ID);
      joinExp3 = CCJSqlParserUtil.parseCondExpression(GroupDetailColumns.TABLE_NAME + "." + GroupDetailColumns.GROUP_DETAIL_ID + " = " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.GROUP_DETAIL_ID);
    } catch (JSQLParserException e) {
      e.printStackTrace();
    }
    joinObj1.setOnExpression(joinExp1);
    joinObj1.setInner(true);
    FromItem fi = new Table(ExperimentGroupVersionMappingColumns.TABLE_NAME);
    joinObj1.setRightItem(fi);
    
    joinObj2.setOnExpression(joinExp2);
    joinObj2.setInner(true);
    joinObj2.setRightItem(new Table(ExperimentDetailColumns.TABLE_NAME));
    
    joinObj3.setOnExpression(joinExp3);
    joinObj3.setInner(true);
    joinObj3.setRightItem(new Table(GroupDetailColumns.TABLE_NAME));
    
    jList.add(joinObj1);
    jList.add(joinObj2);
    jList.add(joinObj3);
    
    ps = ((PlainSelect) selStatement.getSelectBody());
    ps.setJoins(jList);
  }
  
  public static void addInputCollectionBundleJoinClause(Select selStatement) throws JSQLParserException {
    PlainSelect ps = null;
    List<Join> jList = null;
    ps = ((PlainSelect) selStatement.getSelectBody());
    Expression joinExp1 = null, joinExp2 = null, joinExp3 = null, joinExp4 = null, joinExp5 = null, joinExp6 = null;
    if (ps.getJoins() == null) {
      jList = Lists.newArrayList();
    } else {
      jList = ps.getJoins();
    }
    Join joinObj1 = new Join();
    Join joinObj2 = new Join();
    Join joinObj3 = new Join();
    Join joinObj4 = new Join();
    Join joinObj5 = new Join();
    Join joinObj6 = new Join();
    
    try {
      joinExp1 = CCJSqlParserUtil.parseCondExpression(InputCollectionColumns.TABLE_NAME + "." + InputCollectionColumns.INPUT_COLLECTION_ID + " = " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.INPUT_COLLECTION_ID + " and " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.EXPERIMENT_ID + "=" + InputCollectionColumns.TABLE_NAME + "."+ InputCollectionColumns.EXPERIMENT_ID);
      joinExp2 = CCJSqlParserUtil.parseCondExpression(InputColumns.TABLE_NAME + "." + InputColumns.INPUT_ID + " = " + InputCollectionColumns.TABLE_NAME + "." + InputCollectionColumns.INPUT_ID + " AND " + OutputServerColumns.TABLE_NAME + "." + OutputServerColumns.INPUT_ID + " = " + InputColumns.TABLE_NAME + "." + InputColumns.INPUT_ID );
      joinExp3 = CCJSqlParserUtil.parseCondExpression( "esi1." + ExternStringInputColumns.EXTERN_STRING_INPUT_ID + " = " + InputColumns.TABLE_NAME + "." + InputColumns.NAME_ID );
      joinExp4 = CCJSqlParserUtil.parseCondExpression( "esi2." + ExternStringInputColumns.EXTERN_STRING_INPUT_ID + " = " + InputColumns.TABLE_NAME + "." + InputColumns.TEXT_ID);
      joinExp5 = CCJSqlParserUtil.parseCondExpression(ChoiceCollectionColumns.TABLE_NAME + "." + ChoiceCollectionColumns.CHOICE_COLLECTION_ID + " = " + InputCollectionColumns.TABLE_NAME + "." + InputCollectionColumns.CHOICE_COLLECTION_ID + " and " + ExperimentGroupVersionMappingColumns.TABLE_NAME + "." + ExperimentGroupVersionMappingColumns.EXPERIMENT_ID + "=" + ChoiceCollectionColumns.TABLE_NAME + "."+ ChoiceCollectionColumns.EXPERIMENT_ID + " and " + OutputServerColumns.TABLE_NAME + "." + OutputServerColumns.ANSWER + " = " + ChoiceCollectionColumns.TABLE_NAME + "." + ChoiceCollectionColumns.CHOICE_ORDER);
      joinExp6 = CCJSqlParserUtil.parseCondExpression(ExternStringListLabelColumns.TABLE_NAME + "." + ExternStringListLabelColumns.EXTERN_STRING_LIST_LABEL_ID + " = " + ChoiceCollectionColumns.TABLE_NAME + "." + ChoiceCollectionColumns.CHOICE_ID);
      
      
    } catch (JSQLParserException e) {
      e.printStackTrace();
    }
    joinObj1.setOnExpression(joinExp1);
    joinObj1.setLeft(true);
    joinObj1.setRightItem(new Table(InputCollectionColumns.TABLE_NAME));
    
    joinObj2.setOnExpression(joinExp2);
    joinObj2.setInner(true);
    joinObj2.setRightItem(new Table(InputColumns.TABLE_NAME));
    
    joinObj3.setOnExpression(joinExp3);
    joinObj3.setInner(true);
    FromItem fi1 = new Table(ExternStringInputColumns.TABLE_NAME);
    fi1.setAlias(new Alias("esi1"));
    joinObj3.setRightItem(fi1);
    
    joinObj4.setOnExpression(joinExp4);
    joinObj4.setInner(true);
    FromItem fi2 = new Table(ExternStringInputColumns.TABLE_NAME);
    fi2.setAlias(new Alias("esi2"));
    joinObj4.setRightItem(fi2);
    
    joinObj5.setOnExpression(joinExp5);
    joinObj5.setLeft(true);
    joinObj5.setRightItem(new Table(ChoiceCollectionColumns.TABLE_NAME));
    
    joinObj6.setOnExpression(joinExp6);
    joinObj6.setLeft(true);
    joinObj6.setRightItem(new Table(ExternStringListLabelColumns.TABLE_NAME));
    
    jList.add(joinObj1);
    jList.add(joinObj2);
    jList.add(joinObj3);
    jList.add(joinObj4);
    jList.add(joinObj5);
    jList.add(joinObj6);

    ps.setJoins(jList);
  }
}
