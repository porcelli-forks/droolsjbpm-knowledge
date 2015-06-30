package org.kie.internal.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This class is a utility class for dynamically creating JPA queries.
 * </p>
 * See the jbpm-human-task-core and jbpm-audit *query() method logic.
 * </p>
 * This class is <em>not</em> thread-safe and should only be used locally in a method.
 */
public class QueryAndParameterAppender {

    private boolean startWithWhere = false;
    private boolean noClauseAddedYet = true;
    private boolean alreadyUsed = false;
    private final StringBuilder queryBuilder;
    private final Map<String, Object> queryParams;

    private int queryParamId = 0;

    public QueryAndParameterAppender(StringBuilder queryBuilder, Map<String, Object> params) {
        this.queryBuilder = queryBuilder;
        this.queryParams = params;
    }

    public QueryAndParameterAppender(StringBuilder queryBuilder, Map<String, Object> params, boolean useWhere) {
        this.queryBuilder = queryBuilder;
        this.queryParams = params;
        this.startWithWhere = useWhere;
    }

    public boolean hasBeenUsed() {
        return ! this.noClauseAddedYet;
    }

    public void markAsUsed() {
        this.noClauseAddedYet = false;
    }

    public void addNamedQueryParam(String name, Object value) { 
        queryParams.put(name, value);
    }
    
    // "Normal" query parameters --------------------------------------------------------------------------------------------------
    
    public <T> void addQueryParameters( List<? extends Object> inputParams, String listId, Class<T> type, String fieldName, boolean union ) {
        List<T> listIdParams;
        if( inputParams != null && inputParams.size() > 0 ) {
            Object inputObject = inputParams.get(0);
            listIdParams = checkAndConvertListToType(inputParams, inputObject, listId, type);
        } else {
            return;
        }
        String paramName = generateParamName();
        StringBuilder queryClause = new StringBuilder(fieldName + " IN (:" + paramName + ")");
        addToQueryBuilder(queryClause.toString(), union, listIdParams, paramName);
    }

    public <T> void addQueryListParameters( Map<String, List<? extends Object>> inputParamsMap, String listId, Class<T> type, String fieldName, boolean union ) {
        List<? extends Object> inputParams = inputParamsMap.get(listId);
        addQueryParameters(inputParams, listId, type, fieldName, union );
    }

    // Range query parameters -----------------------------------------------------------------------------------------------------

    public <T> void addRangeQueryParameters(List<? extends Object> paramList, String listId, Class<T> type, String fieldName, String joinClause, boolean union ) {
        List<T> listIdParams;
        if( paramList != null && paramList.size() > 0 ) { 
            Object inputObject = paramList.get(0);
            if( inputObject == null ) { 
                inputObject = paramList.get(1);
                if( inputObject == null ) { 
                    return;
                }
            }  
            listIdParams = checkAndConvertListToType(paramList, inputObject, listId, type);
        } else { 
            return;
        }
        
        T min = listIdParams.get(0);
        T max = listIdParams.get(1);
        Map<String, T> paramNameMinMaxMap = new HashMap<String, T>(2);
        StringBuilder queryClause = new StringBuilder("( " );
        if( joinClause != null ) { 
           queryClause.append("( "); 
        } 
        queryClause.append(fieldName);
        if( min == null ) { 
          if( max == null ) { 
              return;
          } else { 
              // only max
              String maxParamName = generateParamName();
              queryClause.append(" <= :" + maxParamName + " " );
              paramNameMinMaxMap.put(maxParamName, max);
          }
        } else if( max == null ) { 
            // only min
            String minParamName = generateParamName();
            queryClause.append(" >= :" + minParamName + " ");
            paramNameMinMaxMap.put(minParamName, min);
        } else { 
            // both min and max
            String minParamName = generateParamName();
            String maxParamName = generateParamName();
            if( union ) { 
                queryClause.append(" >= :" + minParamName + " OR " + fieldName + " <= :" + maxParamName + " " );
            } else { 
                queryClause.append(" BETWEEN :" + minParamName + " AND :" + maxParamName + " " );
            }
            paramNameMinMaxMap.put(minParamName, min);
            paramNameMinMaxMap.put(maxParamName, max);
        } 
        if( joinClause != null ) { 
            queryClause.append(") and " + joinClause.trim() + " ");
        }
        queryClause.append(")");
        
        // add query string to query builder and fill params map
        internalAddToQueryBuilder(queryClause.toString(), union);
        for( Entry<String, T> nameMinMaxEntry : paramNameMinMaxMap.entrySet() ) { 
            addNamedQueryParam(nameMinMaxEntry.getKey(), nameMinMaxEntry.getValue());
        }
        queryBuilderModificationCleanup();
    }

    public <T> void addRangeQueryParameters( Map<String, List<? extends Object>> inputParamsMap, String listId, Class<T> type,
            String fieldName, boolean union, String joinClause ) {
        List<? extends Object> inputParams = inputParamsMap.get(listId);
        addRangeQueryParameters(inputParams, listId, type, fieldName, joinClause, union );
    }

    public <T> void addRangeQueryParameters( List<? extends Object> inputParams, String listId, Class<T> type, String fieldName,
            boolean union ) {
        addRangeQueryParameters(inputParams, listId, type, fieldName, null, union);
    }

    public <T> void addRangeQueryParameters( Map<String, List<? extends Object>> inputParamsMap, String listId, Class<T> type,
            String fieldName, boolean union ) {
        List<? extends Object> inputParams = inputParamsMap.get(listId);
        addRangeQueryParameters(inputParams, listId, type, fieldName, null, union);
    }

    // Regex query parameters -----------------------------------------------------------------------------------------------------

    public void addRegexQueryParameters( List<String> inputParams, String listId, String fieldName, boolean union ) {
        addRegexQueryParameters(inputParams, listId, fieldName, null, union);
    }

    public void addRegexQueryParameters( List<String> paramValList, String listId, String fieldName, String joinClause, 
            boolean union) {
        // setup
        if( paramValList == null || paramValList.isEmpty() ) {
            return;
        }
        List<String> regexList = new ArrayList<String>(paramValList.size());
        for( String input : paramValList ) {
            if( input == null || input.isEmpty() ) {
                continue;
            }
            String regex = input.replace('*', '%').replace('.', '_');
            regexList.add(regex);
        }

        // build query string
        Map<String, String> paramNameRegexMap = new HashMap<String, String>();
        StringBuilder queryClause = new StringBuilder("( ");
        if( joinClause != null ) {
            queryClause.append("( ");
        }
        for( int i = 0; i < regexList.size(); ++i ) {
            String paramName = generateParamName();
            queryClause.append(fieldName + " LIKE :" + paramName + " " );
            paramNameRegexMap.put(paramName, regexList.get(i));
            if( i + 1 < regexList.size() ) {
                queryClause.append(union ? "OR" : "AND").append(" ");
            }
        }
        if( joinClause != null ) {
            queryClause.append(") AND " + joinClause.trim() + " ");
        }
        queryClause.append(")");

        // add query string to query builder and fill params map
        internalAddToQueryBuilder(queryClause.toString(), union);
        for( Entry<String, String> nameRegexEntry : paramNameRegexMap.entrySet() ) {
            addNamedQueryParam(nameRegexEntry.getKey(), nameRegexEntry.getValue());
        }
        queryBuilderModificationCleanup();
    }

    private <T> void addToQueryBuilder( String query, boolean union, List<T> paramValList, String paramName ) {
        // modify query builder
        internalAddToQueryBuilder(query, union);
        // add query parameters
        Set<T> paramVals = new HashSet<T>(paramValList);
        addNamedQueryParam(paramName, paramVals);
        // cleanup
        queryBuilderModificationCleanup();
    }

    private void internalAddToQueryBuilder( String query, boolean union ) {
        if( this.noClauseAddedYet ) {
            if( startWithWhere ) {
                queryBuilder.append(" WHERE");
            } else {
                queryBuilder.append(" AND");
            }
            queryBuilder.append(" (");
            this.noClauseAddedYet = false;
        } else if( this.alreadyUsed ) {
            queryBuilder.append(union ? "\nOR " : "\nAND ");
        }
        queryBuilder.append(query);
    }

    public void queryBuilderModificationCleanup() {
        this.alreadyUsed = true;
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> checkAndConvertListToType( List<?> inputList, Object inputObject, String listId, Class<T> type ) {
        assert type != null : listId + ": type is null!";
        assert inputObject != null : listId + ": input object is null!";
        if( type.equals(inputObject.getClass()) ) {
            return (List<T>) inputList;
        } else {
            throw new IllegalArgumentException(listId + " parameter is an instance of " + "List<"
                    + inputObject.getClass().getSimpleName() + "> instead of " + "List<" + type.getSimpleName() + ">");
        }
    }

    public String generateParamName() {
        int id = queryParamId++ % 26;
        char first = (char) ('A' + id);
        return new String(first + String.valueOf(((id + 1) / 26) + 1));
    }
}